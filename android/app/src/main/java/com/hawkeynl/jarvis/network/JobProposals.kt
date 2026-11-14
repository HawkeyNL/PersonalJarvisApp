package com.hawkeynl.jarvis.network

import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Jobs a chat answer proposed (`job_proposals` on `assistant.completed`, see "From chat" in
// Core's docs/SCHEDULED_JOBS.md), as desktop src/jobProposals.ts. Each body is a checked
// POST /v1/jobs body; nothing exists on Core until the owner taps Add (the body as is) or
// saves an edited one. Cards live in memory only, for the conversation on screen. Pure, so
// tests load it directly.

const val MAX_PROPOSALS = 3

// The card's wording, in one place (desktop's JobProposalCard.vue and the #175 review).
const val PROPOSAL_ADDED = "Added to Jobs"
const val PROPOSAL_VIEW_JOB = "View job"
/** After a 2xx this build cannot read, or no answer: the job may exist, and chat reloads
 *  nothing (the Jobs page keeps its own NOT_CONFIRMED). */
const val PROPOSAL_NOT_CONFIRMED = "Not confirmed: it may have been added."
const val PROPOSAL_CHECK_JOBS = "Check Jobs"
const val PROPOSAL_INVALID = "Core refused this job as invalid, for example a time that has passed. Edit it and try again."
const val PROPOSAL_NO_PUSH = "No push"
const val PROPOSAL_EDITOR_TITLE = "Proposed job"
fun proposalLabel(title: String) = "Proposed job: $title"

data class JobProposal(val id: String, val body: JsonObject, val draft: JobDraft)

enum class ProposalState { OPEN, ADDING, ADDED, UNCONFIRMED }

data class ProposalCard(
    val id: String, val body: JsonObject, val draft: JobDraft, val conversationId: String, val messageId: String,
    val state: ProposalState = ProposalState.OPEN, val jobId: String? = null, val error: String = "",
)

// What a chat marker can carry (no `enabled`, no roadmap): the card shows all of it, so what
// is added is what was shown.
private val BODY_KEYS = setOf("kind", "cadence", "weekday", "month_day", "every", "start_date", "max_runs", "at_minute", "params")
// The params each kind takes, and the fields only some cadences take (Core refuses the rest,
// so a card never shows a job Core would not make).
private val PARAM_KEYS = mapOf(
    JobKind.PROMPT to setOf("title", "instruction", "notify"), JobKind.REMINDER to setOf("title", "text"),
    JobKind.DISK_CHECK to setOf("threshold_percent"),
)

/** The editor's draft for a body, with Core's defaults for what it leaves out; null when a
 *  field has the wrong type or the job breaks a rule. */
private fun proposalDraft(body: JsonObject, today: LocalDate): JobDraft? = guarded {
    val p = body["params"].takeUnless { it == null || it == JsonNull }?.obj() ?: JsonObject(emptyMap())
    require(BODY_KEYS.containsAll(body.keys))
    val kind = body.optStr("kind").let { k -> OWNER_KINDS.firstOrNull { it.wire == k } } ?: throw IllegalArgumentException()
    require(PARAM_KEYS.getValue(kind).containsAll(p.keys))
    val cadence = body.optStr("cadence")?.let { c -> Cadence.entries.firstOrNull { it.wire == c } ?: throw IllegalArgumentException() } ?: Cadence.WEEKLY
    fun has(key: String) = body[key].let { it != null && it != JsonNull }
    require((!has("weekday") || cadence == Cadence.WEEKLY) && (!has("month_day") || cadence == Cadence.MONTHLY))
    require(!has("max_runs") || cadence != Cadence.ONCE)
    val weekday = (body.optInt("weekday") ?: 1).also { require(it in 1..7) }
    val monthDay = (body.optInt("month_day") ?: 1).also { require(it in 1..31) }
    val at = (body.optInt("at_minute") ?: 420).also { require(it <= 1439) }
    val draft = JobDraft(
        kind, cadence, weekday.toInt(), monthDay.toInt(), "${body.optInt("every") ?: 1}", body.optStr("start_date", 10).orEmpty(),
        body.optInt("max_runs")?.toString().orEmpty(), minuteToTime(at.toInt()),
        title = p.optStr("title", 200).orEmpty(), instruction = p.optStr("instruction", 1000).orEmpty(), text = p.optStr("text", 1000).orEmpty(),
        notify = p.optBool("notify") != false, threshold = "${p.optInt("threshold_percent") ?: 80}",
    )
    draft.takeIf { draftError(it, today) == null }
}

/** The proposals of an `assistant.completed` payload: at most 3, each with a UUID and a body
 *  this build can show and would accept itself. Anything else is skipped, never failing the
 *  message. */
fun readProposals(x: JsonElement?, today: LocalDate = jobsToday()): List<JobProposal> {
    val items = x as? JsonArray ?: return emptyList()
    val out = mutableListOf<JobProposal>()
    for (item in items.take(MAX_PROPOSALS)) {
        val o = item as? JsonObject ?: continue
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString && isJobId(it.content) }?.content ?: continue
        val body = o["body"] as? JsonObject ?: continue
        if (out.any { it.id == id }) continue
        proposalDraft(body, today)?.let { out += JobProposal(id, body, it) }
    }
    return out
}

/** Cards for the proposals not shown before ([seen], which this updates): each shows once. */
fun newCards(seen: MutableSet<String>, proposals: List<JobProposal>, conversationId: String, messageId: String): List<ProposalCard> =
    proposals.filter { seen.add(it.id) }.map { ProposalCard(it.id, it.body, it.draft, conversationId, messageId) }

// ---- wording ---------------------------------------------------------------
// What the owner sees is what Add sends: the full text, the run limit and a muted push.

fun proposalTitle(d: JobDraft): String = d.title.ifEmpty { d.kind.label }

/** The schedule in words, as on the Jobs page, then the run limit and " · No push" when
 *  the body has them: "Daily at 08:00, 2 runs · No push". A one-time job has no run limit (an
 *  edited draft may keep a hidden one that [newJob] drops). */
fun proposalSchedule(d: JobDraft, today: LocalDate = jobsToday()): String = scheduleText(
    Job("", d.kind, d.cadence, d.weekday, d.monthDay, d.every.toIntOrNull() ?: 1, d.startDate.ifEmpty { null }, null,
        timeToMinute(d.time) ?: 0, true, 0, JobParams(), null, null),
    today,
) + d.maxRuns.ifEmpty { null }?.takeIf { d.cadence != Cadence.ONCE }?.let { ", $it run" + if (it == "1") "" else "s" }.orEmpty() + if (d.kind == JobKind.PROMPT && !d.notify) " · $PROPOSAL_NO_PUSH" else ""

/** The whole instruction or text (one line of at most 500 characters), or the threshold;
 *  null when none. */
fun proposalDetail(d: JobDraft): String? = when (d.kind) {
    JobKind.PROMPT -> d.instruction
    JobKind.REMINDER -> d.text.ifEmpty { null }
    else -> "Threshold ${d.threshold}%"
}

// ---- add -------------------------------------------------------------------

data class AddResult(val state: ProposalState, val jobId: String?, val error: String)

/** Sends [body] exactly as given. Only an HTTP refusal (4xx) lets the owner try again;
 *  anything else (a 2xx this build cannot read, no answer, a broken answer, any 5xx, Core's
 *  503 included since it also covers a timed-out write, an answer the lock dropped: [create]
 *  gave null) is not confirmed and Add stays off, since the job may exist. [kind] picks the
 *  wording of a 409. */
suspend fun addJob(body: JsonObject, kind: JobKind, create: suspend (JsonObject) -> ApiResult<Job?>?): AddResult {
    val unconfirmed = AddResult(ProposalState.UNCONFIRMED, null, PROPOSAL_NOT_CONFIRMED)
    val result = create(body) ?: return unconfirmed
    val job = (result as? ApiResult.Success)?.value
    val refused = result == ApiResult.Unauthorized || (result is ApiResult.HttpError && result.status in 400..499)
    return when {
        job != null -> AddResult(ProposalState.ADDED, job.id, "")
        !refused -> unconfirmed
        result is ApiResult.HttpError && result.status == 400 -> AddResult(ProposalState.OPEN, null, PROPOSAL_INVALID)
        else -> AddResult(ProposalState.OPEN, null, writeFailureText(result, update = false, kind))
    }
}

/** [cards] with card [id] set to adding, before anything is sent; null when the tap is
 *  refused: behind the lock or signed out ([allowed] false), or the card is not open (gone,
 *  or a second tap while the first runs). */
fun startAdd(cards: List<ProposalCard>, id: String, allowed: Boolean): List<ProposalCard>? {
    if (!allowed || cards.none { it.id == id && it.state == ProposalState.OPEN }) return null
    return cards.map { if (it.id == id) it.copy(state = ProposalState.ADDING, error = "") else it }
}

/** [cards] with an Add's [result] on card [id]; unchanged when the owner left the sign-in or
 *  conversation it was sent from ([same] false), or dismissed the card. Once an [edited] job
 *  may exist, the card shows it. */
fun finishAdd(cards: List<ProposalCard>, id: String, result: AddResult, same: Boolean, edited: JobDraft? = null): List<ProposalCard> =
    if (!same) cards else cards.map {
        if (it.id != id) it
        else it.copy(state = result.state, jobId = result.jobId, error = result.error,
            draft = if (edited != null && result.state != ProposalState.OPEN) edited else it.draft)
    }
