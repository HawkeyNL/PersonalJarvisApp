package com.hawkeynl.jarvis.network

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Scheduled jobs (/v1/jobs), as desktop src/jobs.ts: Core's contract, response guards, the
// editor's draft and the fixed owner-facing wording. Pure, so tests load it directly.
//
// Every write names the revision it builds on (?expected_revision=N); Core answers 409
// when the job moved on, and the page then reloads. Run text from Core (summary, reasons)
// is display only; error codes are shown only through the fixed wording below.

const val JOBS_TIME_ZONE = "Europe/Amsterdam"
const val JOBS_NEED_CORE = "Needs Jarvis Core v0.0.80 or later."

enum class JobKind(val wire: String, val label: String, val detail: String) {
    DEPENDENCY_REVIEW(
        "dependency_review", "Dependency review",
        "Reads the open Dependabot alerts and pull requests of your repositories and puts them in order.",
    ),
    PROMPT("prompt", "Research", "Searches the web for your instruction and posts the answer to the job's conversation."),
    REMINDER("reminder", "Reminder", "Posts your text to the job's conversation and sends you a push with the title."),
    DISK_CHECK("disk_check", "Disk check", "Tells you when a Home Node disk is fuller than the threshold. It changes nothing on the host."),
}

/** The kinds made from "New job"; a dependency review keeps its own set-up card. */
val OWNER_KINDS = listOf(JobKind.PROMPT, JobKind.REMINDER, JobKind.DISK_CHECK)
/** Core's room for the owner's jobs of [OWNER_KINDS]. */
const val MAX_OWNER_JOBS = 10

enum class Cadence(val wire: String, val label: String) {
    ONCE("once", "Once"), DAILY("daily", "Daily"), WEEKLY("weekly", "Weekly"), MONTHLY("monthly", "Monthly"),
}

/** A dependency review keeps the schedules it had: daily or weekly, nothing more. */
fun cadencesOf(kind: JobKind): List<Cadence> =
    if (kind == JobKind.DEPENDENCY_REVIEW) listOf(Cadence.WEEKLY, Cadence.DAILY) else Cadence.entries

val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

enum class RunStatus(val wire: String, val label: String) {
    RUNNING("running", "Running"), OK("ok", "OK"), PARTIAL("partial", "Partial"), FAILED("failed", "Failed"),
    MISSED("missed", "Missed"), INTERRUPTED("interrupted", "Interrupted"),
}

/** The kind's parameters; only the kind's own are set. */
data class JobParams(
    val roadmapId: String? = null, val title: String? = null, val instruction: String? = null, val text: String? = null,
    val notify: Boolean? = null, val thresholdPercent: Int? = null,
)

/** A job as Core serves it. Fields an older Core leaves out read as their defaults. */
data class Job(
    val id: String, val kind: JobKind, val cadence: Cadence, val weekday: Int?, val monthDay: Int?, val every: Int,
    val startDate: String?, val maxRuns: Int?, val atMinute: Int, val enabled: Boolean, val revision: Long,
    val params: JobParams, val lastSlotAt: String?, val nextRunAt: String?, val runCount: Int = 0,
    val byJarvis: Boolean = false, val conversationId: String? = null, val expiresAt: String? = null,
    val failureCount: Int = 0, val pausedAfterFailures: Boolean = false,
)

/** [unsupported]: jobs this build cannot read (a newer kind or cadence); listed as unsupported. */
data class JobList(val configured: Boolean, val jobs: List<Job>, val unsupported: Int = 0)
data class Priority(val ref: String, val rank: Long, val reason: String)

data class JobRun(
    val id: String, val jobId: String, val slotAt: String, val byOwner: Boolean, val status: RunStatus,
    val errorCode: String?, val summary: String?, val summarySource: String?, val priorities: List<Priority>,
    val startedAt: String, val finishedAt: String?, val kind: JobKind? = null,
)

/** A fact a priority points at, reduced to plain display strings. */
data class Fact(val ref: String, val title: String, val detail: String)
data class RepoError(val repo: String, val errorCode: String)

/** A run with its facts. A chat-made run names its [conversationId] and message; a research
 *  run also its https [sources]. */
data class RunDetail(
    val run: JobRun, val facts: List<Fact>, val errors: List<RepoError>, val truncated: Boolean,
    val conversationId: String? = null, val messageId: String? = null, val sources: List<String> = emptyList(),
)

// ---- response guards: anything unexpected reads as null (fail closed) -----

private val JOB_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
private val CODE = Regex("^[a-z0-9_]{1,64}$")

/** Job and run ids go into URL paths; Core's are UUIDs. */
fun isJobId(id: String): Boolean = JOB_ID.matches(id)

// The parsers below (and Roadmaps.kt) throw IllegalArgumentException (require) on anything unexpected.
internal fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException()
internal fun JsonObject.optStr(key: String, max: Int = 10_000): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.content.takeIf { v.isString && it.length <= max } ?: throw IllegalArgumentException()
    else -> throw IllegalArgumentException()
}
internal fun JsonObject.str(key: String, max: Int = 10_000): String = requireNotNull(optStr(key, max))
internal fun JsonObject.optInt(key: String): Long? = when (val v = this[key]) {
    null, JsonNull -> null
    // A safe, non-negative integer as in JavaScript.
    is JsonPrimitive -> v.content.toLongOrNull()?.takeIf { !v.isString && it in 0..9_007_199_254_740_991 } ?: throw IllegalArgumentException()
    else -> throw IllegalArgumentException()
}
internal fun JsonObject.int(key: String): Long = requireNotNull(optInt(key))
internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: throw IllegalArgumentException()
internal fun JsonObject.optBool(key: String): Boolean? = if (this[key] == null || this[key] == JsonNull) null else bool(key)
internal fun JsonObject.array(key: String, max: Int): JsonArray = (this[key] as? JsonArray)?.takeIf { it.size <= max } ?: throw IllegalArgumentException()
internal fun <T> guarded(parse: () -> T): T? = try { parse() } catch (_: IllegalArgumentException) { null }

private fun parseJob(x: JsonElement): Job {
    val o = x.obj()
    val id = o.str("id").also { require(isJobId(it)) }
    val kind = o.optStr("kind").let { k -> JobKind.entries.firstOrNull { it.wire == k } } ?: throw IllegalArgumentException()
    val cadence = o.optStr("cadence").let { c -> Cadence.entries.firstOrNull { it.wire == c } } ?: throw IllegalArgumentException()
    val weekday = o.optInt("weekday")?.also { require(it in 1..7) }
    val monthDay = o.optInt("month_day")?.also { require(it in 1..31) }
    val every = (o.optInt("every") ?: 1).also { require(it in 1..365) }
    val maxRuns = o.optInt("max_runs")?.also { require(it in 1..100) }
    val at = o.int("at_minute").also { require(it <= 1439) }
    val origin = o.optStr("origin") ?: "owner"
    require(origin == "owner" || origin == "jarvis")
    val p = o["params"].takeUnless { it == null || it == JsonNull }?.obj() ?: JsonObject(emptyMap())
    val params = JobParams(
        p.optStr("roadmap_id", 64), p.optStr("title", 200), p.optStr("instruction", 1000), p.optStr("text", 1000),
        p.optBool("notify"), p.optInt("threshold_percent")?.also { require(it in 1..100) }?.toInt(),
    )
    return Job(
        id, kind, cadence, weekday?.toInt(), monthDay?.toInt(), every.toInt(), o.optStr("start_date", 10), maxRuns?.toInt(),
        at.toInt(), o.bool("enabled"), o.int("revision"), params, o.optStr("last_slot_at", 64), o.optStr("next_run_at", 64),
        runCount = (o.optInt("run_count") ?: 0).coerceAtMost(10_000).toInt(),
        byJarvis = origin == "jarvis",
        conversationId = o.optStr("conversation_id", 64)?.also { require(isJobId(it)) },
        expiresAt = o.optStr("expires_at", 64),
        failureCount = (o.optInt("failure_count") ?: 0).coerceAtMost(10_000).toInt(),
        // A reason this build does not know yet still reads; the job shows as paused.
        pausedAfterFailures = o.optStr("paused_reason", 64) == "paused_after_failures",
    )
}

/** One job that does not read (a newer kind, cadence or field value) never fails the list:
 *  it is counted as unsupported. Unknown fields are ignored. */
fun readJobList(x: JsonElement): JobList? = guarded {
    val o = x.obj()
    val status = o.str("status").also { require(it == "ok" || it == "not_configured") }
    val jobs = o.array("jobs", 50).map { guarded { parseJob(it) } }
    JobList(status == "ok", jobs.filterNotNull(), jobs.count { it == null })
}

/** Core's answer to a create or change; null when it cannot be read (the page reloads). */
fun readJob(x: JsonElement): Job? = guarded { parseJob(x) }

private fun parseRun(x: JsonElement): JobRun {
    val o = x.obj()
    val id = o.str("id").also { require(isJobId(it)) }
    val jobId = o.str("job_id").also { require(isJobId(it)) }
    val trigger = o.str("trigger").also { require(it == "schedule" || it == "owner") }
    val status = o.optStr("status").let { s -> RunStatus.entries.firstOrNull { it.wire == s } } ?: throw IllegalArgumentException()
    val errorCode = o.optStr("error_code")?.also { require(CODE.matches(it)) }
    // A source this build does not know reads as none: the summary still shows, unlabelled.
    val source = o.optStr("summary_source", 64)?.takeIf { it == "claude" || it == "deterministic" || it == "web" }
    val priorities = (if (o["priorities"] == null || o["priorities"] == JsonNull) JsonArray(emptyList()) else o.array("priorities", 20)).map {
        val p = it.obj()
        Priority(p.str("ref", 16), p.int("rank"), p.str("reason", 600))
    }.sortedBy { it.rank }
    val kind = o.optStr("kind", 64).let { k -> JobKind.entries.firstOrNull { it.wire == k } }
    return JobRun(
        id, jobId, o.str("slot_at", 64), trigger == "owner", status, errorCode, o.optStr("summary", 4000), source,
        priorities, o.str("started_at", 64), o.optStr("finished_at", 64), kind,
    )
}

/** The runs that read; one this build cannot read (a newer status) is left out. */
fun readRuns(x: JsonElement): List<JobRun>? = guarded { x.obj().array("runs", 50).mapNotNull { guarded { parseRun(it) } } }

/** Plain display text of a fact field: a short string or a number, else "". */
private fun plain(x: JsonElement?, max: Int = 200): String {
    val p = x as? JsonPrimitive ?: return ""
    return if (p.isString) p.content.takeIf { it.length <= max }.orEmpty()
    else p.content.toLongOrNull()?.toString() ?: p.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toString().orEmpty()
}

/** The facts a priority can name (dependency_review: alerts and pull requests), as plain
 *  strings. Facts that do not fit are skipped; a priority whose ref is not found shows its
 *  ref alone. */
private fun readFacts(facts: JsonObject): List<Fact> {
    fun list(key: String) = (facts[key] as? JsonArray).orEmpty().take(500).filterIsInstance<JsonObject>()
    fun ref(o: JsonObject) = (o["ref"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 16 }?.content
    val alerts = list("alerts").mapNotNull { a ->
        val ref = ref(a) ?: return@mapNotNull null
        val title = listOf(plain(a["package"]), plain(a["severity"]).let { if (it.isEmpty()) "" else "($it)" }).filter { it.isNotEmpty() }.joinToString(" ")
        val fixed = plain(a["patched"]).let { if (it.isEmpty()) "no fixed version yet" else "fixed in $it" }
        val detail = listOf("${plain(a["repo"])}#${plain(a["number"])}", fixed, plain(a["ghsa"])).filter { it.isNotEmpty() }.joinToString(" · ")
        Fact(ref, title.ifEmpty { "Alert" }, detail)
    }
    val pulls = list("pull_requests").mapNotNull { p ->
        val ref = ref(p) ?: return@mapNotNull null
        val bump = when {
            plain(p["package"]).isNotEmpty() -> "${plain(p["package"])} ${plain(p["from"])} → ${plain(p["to"])}"
            plain(p["group"]).isNotEmpty() -> "${plain(p["group"])} group"
            else -> "Pull request"
        }
        Fact(ref, bump, "${plain(p["repo"])}#${plain(p["number"])}")
    }
    return alerts + pulls
}

fun readRunDetail(x: JsonElement): RunDetail? = guarded {
    val run = parseRun(x)
    val facts = x.obj()["facts"] as? JsonObject ?: JsonObject(emptyMap())
    val errors = (facts["errors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { e ->
        val repo = (e["repo"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 200 }?.content
        val code = (e["error_code"] as? JsonPrimitive)?.takeIf { it.isString && CODE.matches(it.content) }?.content
        if (repo != null && code != null) RepoError(repo, code) else null
    }.take(100)
    fun id(key: String) = (facts[key] as? JsonPrimitive)?.takeIf { it.isString && isJobId(it.content) }?.content
    // Validated https links only; they open in the browser.
    val sources = (facts["sources"] as? JsonArray).orEmpty().take(20)
        .mapNotNull { s -> (s as? JsonPrimitive)?.takeIf { it.isString && validHttpsUrl(it.content) }?.content }.distinct()
    RunDetail(
        run, readFacts(facts), errors, (facts["truncated"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
        id("conversation_id"), id("message_id"), sources,
    )
}

// ---- schedule and editor ---------------------------------------------------

fun minuteToTime(m: Int): String = "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)

private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

/** "HH:MM"; null when it is not a time of day. */
fun timeToMinute(text: String): Int? = TIME.matchEntire(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

/** A typed time as "HH:MM": "07:30", "7:30", "0730" and "730" read (a number keypad has no
 *  colon); null when it is not a time of day. */
fun typedTime(text: String): String? {
    val t = text.trim()
    val (hours, minutes) = if (':' in t) t.substringBefore(':') to t.substringAfter(':')
        else if (t.length in 3..4) t.dropLast(2) to t.takeLast(2) else return null
    if (hours.length !in 1..2 || minutes.length != 2 || !(hours + minutes).all { it in '0'..'9' }) return null
    return "${hours.padStart(2, '0')}:$minutes".takeIf { timeToMinute(it) != null }
}

/** "1st", "2nd", "3rd", "11th", "21st", … */
fun ordinal(n: Int): String = "$n" + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

private val ZONE = ZoneId.of(JOBS_TIME_ZONE)
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

/** Today in the schedule's zone, the day Core checks dates against. */
fun jobsToday(): LocalDate = LocalDate.now(ZONE)

/** A YYYY-MM-DD date exactly as written (Core's rule); null otherwise. */
fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()?.takeIf { it.toString() == text }

/** "1 Dec", with the year when it is not this year. */
private fun dayText(date: LocalDate, today: LocalDate): String = (if (date.year == today.year) DAY_MONTH else DAY_MONTH_YEAR).format(date)

/** A moment from Core as a day in the schedule's zone ("13 Dec"), without a year (desktop). */
fun zonedDay(iso: String): String =
    runCatching { DAY_MONTH.format(OffsetDateTime.parse(iso).atZoneSameInstant(ZONE)) }.getOrDefault("an unknown day")

/** The schedule in words: "Every 2 weeks on Friday at 08:00", "Once on 1 Dec at 09:00".
 *  Times are in [JOBS_TIME_ZONE]. */
fun scheduleText(job: Job, today: LocalDate = jobsToday()): String {
    val at = "at ${minuteToTime(job.atMinute)}"
    val n = job.every
    val start = job.startDate?.let(::parseDate)
    val text = when (job.cadence) {
        Cadence.ONCE -> start?.let { "Once on ${dayText(it, today)} $at" } ?: "Once $at"
        Cadence.DAILY -> if (n == 1) "Daily $at" else "Every $n days $at"
        Cadence.WEEKLY -> WEEKDAYS[((job.weekday ?: 1) - 1).coerceIn(0, 6)].let { day -> if (n == 1) "Every $day $at" else "Every $n weeks on $day $at" }
        Cadence.MONTHLY -> "the ${ordinal(job.monthDay ?: 1)}".let { day -> if (n == 1) "Monthly on $day $at" else "Every $n months on $day $at" }
    }
    return if (job.cadence != Cadence.ONCE && start != null && start > today) "$text, from ${dayText(start, today)}" else text
}

/** "2 of 2 runs done" for a job that stops after a number of runs. */
fun runsText(job: Job): String? = job.maxRuns?.let { "${job.runCount.coerceAtMost(it)} of $it runs done" }

/** "Set up by Jarvis · expires 13 Dec" for a job Jarvis made for itself. */
fun originText(job: Job): String? = if (!job.byJarvis) null
    else "Set up by Jarvis" + job.expiresAt?.let { " · expires ${zonedDay(it)}" }.orEmpty()

const val PAUSED_AFTER_FAILURES = "Paused after 3 failed runs"
const val PAUSED_AFTER_FAILURES_NOTE =
    "Core paused this job after 3 failed runs in a row. Check the latest run, change the job if needed, then enable it again."

/** A one-time job after its slot, or a job that reached its run limit: Core disabled it
 *  because it is done, not paused. */
fun isFinished(job: Job): Boolean =
    !job.enabled && ((job.cadence == Cadence.ONCE && job.lastSlotAt != null) || (job.maxRuns != null && job.runCount >= job.maxRuns))

fun stateText(job: Job): String = when {
    job.enabled -> "Enabled"
    job.pausedAfterFailures -> PAUSED_AFTER_FAILURES
    isFinished(job) -> "Finished"
    else -> "Paused"
}

/** When it runs next, for the list. */
fun nextRunText(job: Job): String = if (!job.enabled) stateText(job) else job.nextRunAt?.let(::zonedText) ?: "Not scheduled"

/** The owner's jobs that count towards [MAX_OWNER_JOBS]. */
fun ownerJobCount(jobs: List<Job>): Int = jobs.count { !it.byJarvis && it.kind != JobKind.DEPENDENCY_REVIEW }
/** [unsupported]: jobs this build cannot show; Core may count them too. */
fun jobCountText(jobs: List<Job>, unsupported: Int = 0): String =
    "${ownerJobCount(jobs)} of $MAX_OWNER_JOBS jobs" + if (unsupported > 0) ", not counting any this app cannot show" else ""
const val JOBS_FULL_TEXT = "You have $MAX_OWNER_JOBS jobs, the most Core keeps. Delete one to add another."
fun unsupportedText(n: Int): String =
    "${if (n == 1) "1 job" else "$n jobs"} this app cannot show yet. Update the app to see ${if (n == 1) "it" else "them"}."

fun deleteNote(job: Job): String =
    if (job.conversationId != null) "Its runs are deleted too. Its conversation stays in chat." else "Its runs are deleted too."

/** A job's name on a list: its title, or its kind. */
fun jobTitle(job: Job): String = job.params.title ?: job.kind.label

private val ZONED = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK).withZone(ZONE)

/** A moment from Core, shown in the schedule's zone. */
fun zonedText(iso: String): String =
    runCatching { ZONED.format(OffsetDateTime.parse(iso)) }.getOrDefault("an unknown time")

/** The editor's fields as typed; numbers stay text until [draftError] passes. */
data class JobDraft(
    val kind: JobKind, val cadence: Cadence = Cadence.WEEKLY, val weekday: Int = 1, val monthDay: Int = 1, val every: String = "1",
    val startDate: String = "", val maxRuns: String = "", val time: String = "07:00", val enabled: Boolean = true,
    val roadmapId: String? = null, val title: String = "", val instruction: String = "", val text: String = "",
    val notify: Boolean = true, val threshold: String = "80",
)

fun newDraft(kind: JobKind) = JobDraft(kind)
fun toDraft(job: Job) = JobDraft(
    job.kind, job.cadence, job.weekday ?: 1, job.monthDay ?: 1, "${job.every}", job.startDate.orEmpty(), job.maxRuns?.toString().orEmpty(),
    minuteToTime(job.atMinute), job.enabled, job.params.roadmapId, job.params.title.orEmpty(), job.params.instruction.orEmpty(),
    job.params.text.orEmpty(), job.params.notify ?: true, "${job.params.thresholdPercent ?: 80}",
)

private fun mostEvery(cadence: Cadence): Int = when (cadence) {
    Cadence.ONCE -> 1
    Cadence.DAILY -> 365
    Cadence.WEEKLY -> 104
    Cadence.MONTHLY -> 24
}

/** "days", "weeks" or "months": the unit of `every`. */
fun everyUnit(cadence: Cadence): String = when (cadence) {
    Cadence.WEEKLY -> "weeks"
    Cadence.MONTHLY -> "months"
    else -> "days"
}

/** Whether the editor shows the repeat fields (every, start date, stop after). */
fun hasRecurrence(kind: JobKind): Boolean = kind != JobKind.DEPENDENCY_REVIEW

/** What Core would refuse, in the owner's words (Core's bounds); null when the draft is fine. */
fun draftError(d: JobDraft, today: LocalDate = jobsToday()): String? {
    if ((d.kind == JobKind.PROMPT || d.kind == JobKind.REMINDER) && !validText(d.title, 60)) return "Give it a title of 1 to 60 characters on one line."
    if (d.kind == JobKind.PROMPT && !validText(d.instruction, 500)) return "Say what to research in 1 to 500 characters on one line."
    if (d.kind == JobKind.REMINDER && d.text.isNotBlank() && !validText(d.text, 500)) return "The text can be at most 500 characters on one line."
    if (d.kind == JobKind.DISK_CHECK && d.threshold.trim().toIntOrNull() !in 50..99) return "Pick a threshold from 50 to 99 percent."
    if (d.cadence !in cadencesOf(d.kind)) return "A dependency review runs daily or weekly."
    if (timeToMinute(d.time) == null) return "Pick a time of day."
    if (!hasRecurrence(d.kind)) return null
    val most = mostEvery(d.cadence)
    if (d.cadence != Cadence.ONCE && d.every.trim().toIntOrNull() !in 1..most) return "Repeat every 1 to $most ${everyUnit(d.cadence)}."
    val start = d.startDate.trim()
    if (start.isEmpty()) {
        if (d.cadence == Cadence.ONCE) return "Pick the day it runs."
    } else {
        val date = parseDate(start) ?: return "Write the date as YYYY-MM-DD."
        if (date.year < 2000 || date > today.plusDays(732)) return "Pick a date at most two years ahead."
        if (d.cadence == Cadence.ONCE && d.enabled && date < today) return "Pick a day from today on."
    }
    if (d.cadence != Cadence.ONCE && d.maxRuns.isNotBlank() && d.maxRuns.trim().toIntOrNull() !in 1..100) return "Stop after 1 to 100 runs, or leave it empty."
    return null
}

private fun JobDraft.paramsJson(): JsonObject = buildJsonObject {
    when (kind) {
        JobKind.DEPENDENCY_REVIEW -> roadmapId?.let { put("roadmap_id", it) }
        JobKind.PROMPT -> { put("title", title.trim()); put("instruction", instruction.trim()); put("notify", notify) }
        JobKind.REMINDER -> { put("title", title.trim()); text.trim().takeIf { it.isNotEmpty() }?.let { put("text", it) } }
        JobKind.DISK_CHECK -> put("threshold_percent", threshold.trim().toInt())
    }
}

private fun JobDraft.everyValue(): Int = if (cadence == Cadence.ONCE || !hasRecurrence(kind)) 1 else every.trim().toInt()
private fun JobDraft.startValue(): String? = startDate.trim().takeIf { it.isNotEmpty() && hasRecurrence(kind) }
private fun JobDraft.maxRunsValue(): Int? = maxRuns.trim().takeIf { it.isNotEmpty() && cadence != Cadence.ONCE && hasRecurrence(kind) }?.toInt()

/** The create body; null when [draftError] refuses the draft. Only fields the client may set
 *  are sent (never origin, run count or other fields of Core's), and the newer schedule
 *  fields only when they differ from Core's defaults, so an older Core reads a dependency
 *  review as before. */
fun newJob(draft: JobDraft, today: LocalDate = jobsToday()): JsonObject? {
    if (draftError(draft, today) != null) return null
    return buildJsonObject {
        put("kind", draft.kind.wire)
        put("cadence", draft.cadence.wire)
        if (draft.cadence == Cadence.WEEKLY) put("weekday", draft.weekday)
        if (draft.cadence == Cadence.MONTHLY) put("month_day", draft.monthDay)
        draft.everyValue().takeIf { it != 1 }?.let { put("every", it) }
        draft.startValue()?.let { put("start_date", it) }
        draft.maxRunsValue()?.let { put("max_runs", it) }
        put("at_minute", requireNotNull(timeToMinute(draft.time)))
        put("enabled", draft.enabled)
        put("params", draft.paramsJson())
    }
}

/** Only what changed, so an unchanged editor sends nothing (null; also when [draftError]
 *  refuses it). A weekday goes only with a weekly job, a month day only with a monthly one;
 *  Core sets `every` back to 1 when the cadence changes, so it is compared with that. A
 *  cleared start date or run limit is sent as null. Changed params go whole (Core replaces
 *  them); empty params clear the roadmap. */
fun jobPatch(job: Job, draft: JobDraft, today: LocalDate = jobsToday()): JsonObject? {
    if (draftError(draft, today) != null) return null
    val cadenceChanged = draft.cadence != job.cadence
    val patch = buildJsonObject {
        if (cadenceChanged) put("cadence", draft.cadence.wire)
        if (draft.cadence == Cadence.WEEKLY && (cadenceChanged || draft.weekday != job.weekday)) put("weekday", draft.weekday)
        if (draft.cadence == Cadence.MONTHLY && (cadenceChanged || draft.monthDay != job.monthDay)) put("month_day", draft.monthDay)
        val every = draft.everyValue()
        if (every != (if (cadenceChanged) 1 else job.every)) put("every", every)
        draft.startValue().let { if (it != job.startDate) put("start_date", it) }
        draft.maxRunsValue().let { if (it != job.maxRuns) put("max_runs", it) }
        timeToMinute(draft.time)?.let { if (it != job.atMinute) put("at_minute", it) }
        if (draft.enabled != job.enabled) put("enabled", draft.enabled)
        draft.paramsJson().let { if (it != toDraft(job).paramsJson()) put("params", it) }
    }
    return patch.takeIf { it.isNotEmpty() }
}

// ---- fixed wording ---------------------------------------------------------

private val ERROR_TEXT = mapOf(
    "not_configured" to "Not set up on Core: something this job needs is turned off on your Home Node.",
    "timed_out" to "The run took longer than 15 minutes and was stopped.",
    "interrupted" to "Core stopped during the run, so it did not finish.",
    "invalid_result" to "Core refused to store the result of this run.",
    "internal" to "Something went wrong in Core during the run.",
    "private_instruction" to
        "Not searched: the title or instruction may hold personal or financial details or a symbol you hold. Change the job to leave them out.",
    "holdings_unavailable" to "Not searched: Core could not read your holdings to check the instruction for private details.",
    "research_unavailable" to "The web search did not run or was stopped.",
    "conversation_unavailable" to "The answer could not be stored in the job's conversation.",
    "disk_unavailable" to "No Home Node disk could be read.",
    "token_needs_dependabot_alerts_read" to
        "GitHub refused the Dependabot alerts. Give the GitHub token the \"Dependabot alerts: Read-only\" permission for the repository, and turn on Dependabot alerts in its settings.",
    "github_access_refused" to "GitHub refused the list of pull requests. The repository's alerts were kept.",
    "github_token_refused" to "GitHub refused the token. Check that it is valid and has not expired.",
    "github_rate_limited" to "GitHub's rate limit was reached. The next run tries again.",
    "github_answer_too_large" to "A GitHub answer was larger than 1 MiB and was not read.",
    "github_redirect_refused" to "GitHub redirected to another host, which Core refuses.",
    "github_unavailable" to "GitHub could not be reached or answered with an error.",
    "roadmap_unavailable" to "The roadmap is gone or archived, so no proposal was made.",
    "roadmap_changed" to "The roadmap changed while the run read it. The next run proposes again.",
    "roadmap_limit" to "The roadmap holds its maximum of items or pending proposals, so no proposal was made.",
    "roadmap_proposal_failed" to "The roadmap proposal could not be made.",
)

/** Where a code means something else for one kind of job. */
private val KIND_ERROR_TEXT = mapOf(
    JobKind.DEPENDENCY_REVIEW to mapOf(
        "not_configured" to "Not set up on Core: the GitHub token or the repository list (JARVIS_GITHUB_REPOS) is missing on your Home Node.",
    ),
    JobKind.PROMPT to mapOf(
        "not_configured" to "Not searched: Research is off, or no subscription worker can run it.",
        "timed_out" to "The web search took longer than 5 minutes and was stopped.",
    ),
)

/** The owner-facing text for an error code of a run of [kind] (null: unknown); never the
 *  code's own words. */
fun jobErrorText(code: String, kind: JobKind? = null): String =
    KIND_ERROR_TEXT[kind]?.get(code) ?: ERROR_TEXT[code] ?: "Core reported an error this app does not know yet."

fun summarySourceLabel(source: String): String = when (source) {
    "claude" -> "Written by Claude"
    "web" -> "From a web search"
    else -> "Written by Core's template"
}
fun triggerLabel(byOwner: Boolean): String = if (byOwner) "Run by you" else "Scheduled"

const val JOBS_NOT_CONFIGURED =
    "Core's scheduler is off: it could not read the Europe/Amsterdam time zone on your Home Node. Jobs can be changed, but none runs."

enum class JobFailure { NO_API, UNREACHABLE, SIGNIN, NOT_FOUND, UNAVAILABLE, FAILED }

/** The InvalidResponse message of a call refused before it was sent (a job id that is not a
 *  UUID): nothing reached Core, so it is not "not confirmed". */
const val INVALID_JOB_ID = "Invalid job id"
const val NOT_SENT = "Not sent: this app refused the job's id. Reload and try again."
private fun notSent(result: ApiResult<*>) = result is ApiResult.InvalidResponse && result.message == INVALID_JOB_ID

/** [list]: the failure is on GET /v1/jobs, where 404 means an older Core. */
fun jobFailureOf(result: ApiResult<*>, list: Boolean = false): JobFailure = when (result) {
    is ApiResult.Unreachable -> JobFailure.UNREACHABLE
    ApiResult.Unauthorized -> JobFailure.SIGNIN
    is ApiResult.HttpError -> when (result.status) {
        401 -> JobFailure.SIGNIN
        404 -> if (list) JobFailure.NO_API else JobFailure.NOT_FOUND
        405, 501 -> JobFailure.NO_API
        503 -> JobFailure.UNAVAILABLE
        else -> JobFailure.FAILED
    }
    else -> JobFailure.FAILED
}

fun jobFailureText(failure: JobFailure): String = when (failure) {
    JobFailure.NO_API -> "$JOBS_NEED_CORE Update Core on your Home Node."
    JobFailure.UNREACHABLE -> "No answer from your Home Node. Check the connection."
    JobFailure.SIGNIN -> "Your session is not valid. Sign this device in again."
    JobFailure.NOT_FOUND -> "This does not exist on your Home Node (any more)."
    JobFailure.UNAVAILABLE -> "Core could not read the jobs just now. Try again in a minute."
    JobFailure.FAILED -> "Core returned an answer this app does not understand, or an error. Try again later."
}

const val RUN_NOW_REFUSED = "Not started: a job runs on request at most once an hour, and not while a run is queued or running."

/** The notice after a refused "Run now". */
fun runNowFailureText(result: ApiResult<*>): String = when {
    result is ApiResult.HttpError && result.status == 429 -> RUN_NOW_REFUSED
    result is ApiResult.HttpError && result.status == 409 -> "Not started: Core's scheduler is off, so no job runs."
    result is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the run was queued; reloaded to check."
    notSent(result) -> NOT_SENT
    // A 2xx whose answer is oversized or unreadable: the run may have been queued.
    result is ApiResult.InvalidResponse -> NOT_CONFIRMED
    else -> "Not started. ${jobFailureText(jobFailureOf(result))}"
}

const val CHANGED_ELSEWHERE = "Changed elsewhere, reloaded."

const val INVALID_TEXT =
    "Core refused this as invalid: for example a one-time job that is not in the future, an archived roadmap, or a research title or instruction that may hold personal or financial details. Check the fields and try again."

/** The notice after a failed write. A 409 on a change is a revision conflict; on a create,
 *  no room: a second dependency review, or the owner's 10 jobs. */
fun writeFailureText(result: ApiResult<*>, update: Boolean, kind: JobKind = JobKind.DEPENDENCY_REVIEW): String = when {
    result is ApiResult.HttpError && result.status == 409 -> when {
        update -> CHANGED_ELSEWHERE
        kind == JobKind.DEPENDENCY_REVIEW -> "Core refused this: a dependency review already exists."
        else -> "Core refused this. $JOBS_FULL_TEXT"
    }
    result is ApiResult.HttpError && result.status == 400 -> INVALID_TEXT
    result is ApiResult.HttpError && result.status == 503 ->
        "Not saved: Core could not check this just now (its database or your holdings could not be read). Try again in a minute."
    result is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check."
    notSent(result) -> NOT_SENT
    // A 2xx whose answer is oversized or unreadable: the change may have landed.
    result is ApiResult.InvalidResponse -> NOT_CONFIRMED
    else -> "Not saved. ${jobFailureText(jobFailureOf(result))}"
}

/** The notice after a create or change: [done] when Core's answer reads; NOT_CONFIRMED for a
 *  2xx whose job does not read (it may have landed; the page reloads). */
fun writeNotice(result: ApiResult<Job?>, update: Boolean, kind: JobKind, done: String): String = when {
    result !is ApiResult.Success -> writeFailureText(result, update, kind)
    result.value == null -> NOT_CONFIRMED
    else -> done
}

/** Whether a delete went through: done, or the job was already gone. */
fun jobDeleted(result: ApiResult<*>): Boolean = result is ApiResult.Success || (result is ApiResult.HttpError && result.status == 404)

/** The notice after a failed delete. */
fun deleteFailureText(result: ApiResult<*>): String = when {
    result is ApiResult.HttpError && result.status == 409 -> CHANGED_ELSEWHERE
    result is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the job was deleted; reloaded to check."
    notSent(result) -> NOT_SENT
    result is ApiResult.InvalidResponse -> NOT_CONFIRMED
    else -> "Not deleted. ${jobFailureText(jobFailureOf(result))}"
}
