package com.hawkeynl.jarvis.network

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
import kotlinx.serialization.json.putJsonObject

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
}

val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

enum class RunStatus(val wire: String, val label: String) {
    RUNNING("running", "Running"), OK("ok", "OK"), PARTIAL("partial", "Partial"), FAILED("failed", "Failed"),
    MISSED("missed", "Missed"), INTERRUPTED("interrupted", "Interrupted"),
}

data class Job(
    val id: String, val kind: JobKind, val daily: Boolean, val weekday: Int?, val atMinute: Int, val enabled: Boolean,
    val revision: Long, val roadmapId: String?, val lastSlotAt: String?, val nextRunAt: String?,
)

data class JobList(val configured: Boolean, val jobs: List<Job>)
data class Priority(val ref: String, val rank: Long, val reason: String)

data class JobRun(
    val id: String, val jobId: String, val slotAt: String, val byOwner: Boolean, val status: RunStatus,
    val errorCode: String?, val summary: String?, val summarySource: String?, val priorities: List<Priority>,
    val startedAt: String, val finishedAt: String?,
)

/** A fact a priority points at, reduced to plain display strings. */
data class Fact(val ref: String, val title: String, val detail: String)
data class RepoError(val repo: String, val errorCode: String)
data class RunDetail(val run: JobRun, val facts: List<Fact>, val errors: List<RepoError>, val truncated: Boolean)

// ---- response guards: anything unexpected reads as null (fail closed) -----

private val JOB_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
private val CODE = Regex("^[a-z0-9_]{1,64}$")

/** Job and run ids go into URL paths; Core's are UUIDs. */
fun isJobId(id: String): Boolean = JOB_ID.matches(id)

// The parsers below throw IllegalArgumentException (require) on anything unexpected.
private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException()
private fun JsonObject.optStr(key: String, max: Int = 10_000): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.content.takeIf { v.isString && it.length <= max } ?: throw IllegalArgumentException()
    else -> throw IllegalArgumentException()
}
private fun JsonObject.str(key: String, max: Int = 10_000): String = requireNotNull(optStr(key, max))
private fun JsonObject.optInt(key: String): Long? = when (val v = this[key]) {
    null, JsonNull -> null
    // A safe, non-negative integer as in JavaScript.
    is JsonPrimitive -> v.content.toLongOrNull()?.takeIf { !v.isString && it in 0..9_007_199_254_740_991 } ?: throw IllegalArgumentException()
    else -> throw IllegalArgumentException()
}
private fun JsonObject.int(key: String): Long = requireNotNull(optInt(key))
private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: throw IllegalArgumentException()
private fun JsonObject.array(key: String, max: Int): JsonArray = (this[key] as? JsonArray)?.takeIf { it.size <= max } ?: throw IllegalArgumentException()
private fun <T> guarded(parse: () -> T): T? = try { parse() } catch (_: IllegalArgumentException) { null }

private fun parseJob(x: JsonElement): Job {
    val o = x.obj()
    val id = o.str("id").also { require(isJobId(it)) }
    val kind = o.optStr("kind").let { k -> JobKind.entries.firstOrNull { it.wire == k } } ?: throw IllegalArgumentException()
    val cadence = o.str("cadence").also { require(it == "daily" || it == "weekly") }
    val weekday = o.optInt("weekday")?.also { require(it in 1..7) }
    val at = o.int("at_minute").also { require(it <= 1439) }
    val params = o["params"].takeUnless { it == null || it == JsonNull }?.obj()
    return Job(
        id, kind, cadence == "daily", weekday?.toInt(), at.toInt(), o.bool("enabled"), o.int("revision"),
        params?.optStr("roadmap_id", 64), o.optStr("last_slot_at", 64), o.optStr("next_run_at", 64),
    )
}

fun readJobList(x: JsonElement): JobList? = guarded {
    val o = x.obj()
    val status = o.str("status").also { require(it == "ok" || it == "not_configured") }
    JobList(status == "ok", o.array("jobs", 50).map(::parseJob))
}

/** Core's answer to a create or change; null when it cannot be read (the page reloads). */
fun readJob(x: JsonElement): Job? = guarded { parseJob(x) }

private fun parseRun(x: JsonElement): JobRun {
    val o = x.obj()
    val id = o.str("id").also { require(isJobId(it)) }
    val jobId = o.str("job_id").also { require(isJobId(it)) }
    val trigger = o.str("trigger").also { require(it == "schedule" || it == "owner") }
    val status = o.str("status").let { s -> RunStatus.entries.firstOrNull { it.wire == s } } ?: throw IllegalArgumentException()
    val errorCode = o.optStr("error_code")?.also { require(CODE.matches(it)) }
    val source = o.optStr("summary_source")?.also { require(it == "claude" || it == "deterministic") }
    val priorities = (if (o["priorities"] == null || o["priorities"] == JsonNull) JsonArray(emptyList()) else o.array("priorities", 20)).map {
        val p = it.obj()
        Priority(p.str("ref", 16), p.int("rank"), p.str("reason", 600))
    }.sortedBy { it.rank }
    return JobRun(
        id, jobId, o.str("slot_at", 64), trigger == "owner", status, errorCode, o.optStr("summary", 4000), source,
        priorities, o.str("started_at", 64), o.optStr("finished_at", 64),
    )
}

fun readRuns(x: JsonElement): List<JobRun>? = guarded { x.obj().array("runs", 50).map(::parseRun) }

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
    RunDetail(run, readFacts(facts), errors, (facts["truncated"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true)
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

fun cadenceText(daily: Boolean, weekday: Int?, atMinute: Int): String {
    val at = "${minuteToTime(atMinute)} ($JOBS_TIME_ZONE)"
    return if (daily) "Daily at $at" else "Every ${WEEKDAYS[((weekday ?: 1) - 1).coerceIn(0, 6)]} at $at"
}

fun cadenceText(job: Job): String = cadenceText(job.daily, job.weekday, job.atMinute)

private val ZONED = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK).withZone(ZoneId.of(JOBS_TIME_ZONE))

/** A moment from Core, shown in the schedule's zone. */
fun zonedText(iso: String): String =
    runCatching { ZONED.format(OffsetDateTime.parse(iso)) }.getOrDefault("an unknown time")

data class JobDraft(val daily: Boolean, val weekday: Int, val time: String, val enabled: Boolean, val roadmapId: String?)

fun newDraft() = JobDraft(daily = false, weekday = 1, time = "07:00", enabled = true, roadmapId = null)
fun toDraft(job: Job) = JobDraft(job.daily, job.weekday ?: 1, minuteToTime(job.atMinute), job.enabled, job.roadmapId)

private fun cadence(daily: Boolean) = if (daily) "daily" else "weekly"

/** The create body; null when the time is not valid. A daily job sends no weekday (Core
 *  refuses one). */
fun newJob(kind: JobKind, draft: JobDraft): JsonObject? {
    val at = timeToMinute(draft.time) ?: return null
    return buildJsonObject {
        put("kind", kind.wire)
        put("cadence", cadence(draft.daily))
        if (!draft.daily) put("weekday", draft.weekday)
        put("at_minute", at)
        put("enabled", draft.enabled)
        putJsonObject("params") { draft.roadmapId?.let { put("roadmap_id", it) } }
    }
}

/** Only what changed, so an unchanged editor sends nothing (null). Empty params clear the
 *  roadmap. A weekday is sent only for a weekly job. */
fun jobPatch(job: Job, draft: JobDraft): JsonObject? {
    val at = timeToMinute(draft.time)
    val patch = buildJsonObject {
        if (draft.daily != job.daily) put("cadence", cadence(draft.daily))
        if (!draft.daily && draft.weekday != job.weekday) put("weekday", draft.weekday)
        if (at != null && at != job.atMinute) put("at_minute", at)
        if (draft.enabled != job.enabled) put("enabled", draft.enabled)
        if (draft.roadmapId != job.roadmapId) putJsonObject("params") { draft.roadmapId?.let { put("roadmap_id", it) } }
    }
    return patch.takeIf { it.isNotEmpty() }
}

// ---- fixed wording ---------------------------------------------------------

private val ERROR_TEXT = mapOf(
    "not_configured" to "Not set up on Core: the GitHub token or the repository list (JARVIS_GITHUB_REPOS) is missing on your Home Node.",
    "timed_out" to "The run took longer than 15 minutes and was stopped.",
    "interrupted" to "Core stopped during the run, so it did not finish.",
    "invalid_result" to "Core refused to store the result of this run.",
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

/** The owner-facing text for an error code; never the code's own words. */
fun jobErrorText(code: String): String = ERROR_TEXT[code] ?: "Core reported an error this app does not know yet."

fun summarySourceLabel(source: String): String = if (source == "claude") "Written by Claude" else "Written by Core's template"
fun triggerLabel(byOwner: Boolean): String = if (byOwner) "Run by you" else "Scheduled"

const val JOBS_NOT_CONFIGURED =
    "Core's scheduler is off: it could not read the Europe/Amsterdam time zone on your Home Node. Jobs can be changed, but none runs."

enum class JobFailure { NO_API, UNREACHABLE, SIGNIN, NOT_FOUND, UNAVAILABLE, FAILED }

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
    else -> "Not started. ${jobFailureText(jobFailureOf(result))}"
}

const val CHANGED_ELSEWHERE = "Changed elsewhere, reloaded."

/** The notice after a failed write. A 409 on a change is a revision conflict; on a create,
 *  a second job of the same kind. */
fun writeFailureText(result: ApiResult<*>, update: Boolean): String = when {
    result is ApiResult.HttpError && result.status == 409 -> if (update) CHANGED_ELSEWHERE else "Core refused this: a job of this kind already exists."
    result is ApiResult.HttpError && result.status == 400 ->
        "Core refused this as invalid, for example a roadmap that is archived. Check the fields and try again."
    result is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check."
    else -> "Not saved. ${jobFailureText(jobFailureOf(result))}"
}
