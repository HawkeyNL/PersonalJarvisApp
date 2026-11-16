package com.hawkeynl.jarvis.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// A roadmap a chat answer proposed (`roadmap_proposals` on `assistant.completed`, see "From
// chat" in Core's docs/ROADMAPS.md), as desktop src/roadmapProposals.ts. The body is the exact
// POST /v1/roadmaps body (a new roadmap) or the POST /v1/roadmaps/{roadmap_id}/items/batch
// body (items for an existing one); nothing exists on Core until the owner taps Add. Cards
// live in memory only, for the conversation on screen. Pure, so tests load it directly.

// The card's wording, in one place (desktop RoadmapProposalCard.vue).
const val ROADMAP_ADDED = "Added to Roadmaps"
const val ROADMAP_VIEW = "View roadmap"
const val ROADMAP_NOT_CONFIRMED = "Not confirmed: it may have been added."
const val ROADMAP_CHECK = "Check Roadmaps"
const val ROADMAP_REFUSED = "Core refused this roadmap, for example because a phase changed or a limit was reached. Ask Jarvis again."
const val ROADMAP_CORE_TOO_OLD = "Update Jarvis Core to add roadmaps from chat."

/** [items] carry their place as id and position, so the Roadmaps grouping reads them.
 *  [roadmapId] is set for items on an existing roadmap ([title] is then its title). */
data class RoadmapProposal(
    val id: String, val roadmapId: String?, val title: String, val phases: List<String>,
    val items: List<RoadmapItem>, val body: JsonObject,
)

data class RoadmapChatCard(
    val proposal: RoadmapProposal, val conversationId: String, val messageId: String,
    val state: ProposalState = ProposalState.OPEN, val roadmapId: String? = null, val error: String = "",
)

private val PROPOSAL_KEYS = setOf("id", "roadmap_id", "title", "body")
private val NEW_KEYS = setOf("title", "description", "phases", "items")
private val ITEM_KEYS = setOf("title", "status", "phase", "notes", "links")
private val LINK_KEYS = setOf("kind", "repo", "number", "tag")

private fun JsonElement?.text(max: Int, multiline: Boolean = false): String =
    (this as? JsonPrimitive)?.takeIf { it.isString && validText(it.content, max, multiline) }?.content ?: throw IllegalArgumentException()

private fun JsonObject.optText(key: String, max: Int, multiline: Boolean = false): String? =
    if (key in this) this[key].text(max, multiline) else null

/** A pr, issue or release link with a valid repo; no url and no cached state. */
private fun readLink(x: JsonElement): RoadmapLink {
    val o = x.obj()
    require(LINK_KEYS.containsAll(o.keys))
    val kind = o.optStr("kind").let { k -> LinkKind.entries.firstOrNull { it.wire == k && it != LinkKind.URL } } ?: throw IllegalArgumentException()
    val repo = o.str("repo", 200).also { require(validRepo(it)) }
    return if (kind == LinkKind.RELEASE) {
        require("number" !in o)
        RoadmapLink(kind, repo, tag = o.str("tag", 100).also { require(TAG.matches(it)) })
    } else {
        require("tag" !in o)
        RoadmapLink(kind, repo, number = o.int("number").also { require(it in 1..RoadmapLimits.LINK_NUMBER) })
    }
}

private fun readItem(x: JsonElement, index: Int): RoadmapItem {
    val o = x.obj()
    require(ITEM_KEYS.containsAll(o.keys))
    // Core always sends the status; it must be one this app knows.
    val status = RoadmapStatus.entries.firstOrNull { it.wire == o.optStr("status") } ?: throw IllegalArgumentException()
    return RoadmapItem(
        "$index", o["title"].text(RoadmapLimits.ITEM_TITLE), status, null, o.optText("phase", RoadmapLimits.PHASE),
        index.toLong(), o.optText("notes", RoadmapLimits.NOTES, multiline = true), emptyList(),
        (if ("links" in o) o.array("links", RoadmapLimits.LINKS) else JsonArray(emptyList())).map(::readLink), "",
    )
}

/** The card of an `assistant.completed` payload's `roadmap_proposals`: only the first entry,
 *  and only when everything in it is what this build can show and Core would take (see the
 *  checks below). Anything else gives no card, never failing the message. */
fun readRoadmapProposal(x: JsonElement?): RoadmapProposal? = guarded {
    val o = (x as? JsonArray)?.firstOrNull().obj()
    require(PROPOSAL_KEYS.containsAll(o.keys))
    val id = o.str("id", 64).also { require(isJobId(it)) }
    val roadmapId = o.optStr("roadmap_id", 64)?.also { require(isRoadmapId(it)) }
    val title = o["title"].text(RoadmapLimits.ROADMAP_TITLE)
    val body = o["body"].obj()
    val items = body.array("items", 100).mapIndexed { i, item -> readItem(item, i) }
    if (roadmapId != null) {
        require(body.keys == setOf("items") && items.isNotEmpty())
        RoadmapProposal(id, roadmapId, title, emptyList(), items, body)
    } else {
        require(NEW_KEYS.containsAll(body.keys) && "roadmap_id" !in o)
        val phases = (if ("phases" in body) body.array("phases", RoadmapLimits.PHASES) else JsonArray(emptyList())).map { it.text(RoadmapLimits.PHASE) }
        require(phases.toSet().size == phases.size && items.all { it.phase == null || it.phase in phases })
        // Checked, not shown (as desktop).
        body.optText("description", RoadmapLimits.DESCRIPTION, multiline = true)
        RoadmapProposal(id, null, body["title"].text(RoadmapLimits.ROADMAP_TITLE), phases, items, body)
    }
}

/** The card for [proposal] when its id was not shown before ([seen], which this updates). */
fun newRoadmapCard(seen: MutableSet<String>, proposal: RoadmapProposal?, conversationId: String, messageId: String): RoadmapChatCard? =
    proposal?.takeIf { seen.add(it.id) }?.let { RoadmapChatCard(it, conversationId, messageId) }

// ---- wording ---------------------------------------------------------------

private fun plural(n: Int, one: String) = "$n ${if (n == 1) one else "${one}s"}"

/** "New roadmap: Trip", "Add 3 items to Jarvis", "Add 1 item to Jarvis". */
fun roadmapHeading(p: RoadmapProposal): String =
    if (p.roadmapId == null) "New roadmap: ${p.title}" else "Add ${plural(p.items.size, "item")} to ${p.title}"

/** "3 items in 2 phases"; for an existing roadmap only "3 items". */
fun roadmapSummary(p: RoadmapProposal): String =
    if (p.roadmapId == null) "${plural(p.items.size, "item")} in ${plural(p.phases.size, "phase")}" else plural(p.items.size, "item")

/** The groups the card shows: a new roadmap's phases in order (as the Roadmaps rail), or the
 *  phases the items name, in the order they first appear. */
fun roadmapGroups(p: RoadmapProposal): List<PhaseGroup> =
    groupByPhase(p.phases.ifEmpty { p.items.mapNotNull { it.phase }.distinct() }, p.items)

// ---- add -------------------------------------------------------------------

data class RoadmapAddResult(val state: ProposalState, val roadmapId: String?, val error: String)

private val UNCONFIRMED = RoadmapAddResult(ProposalState.UNCONFIRMED, null, ROADMAP_NOT_CONFIRMED)

private fun opened(error: String) = RoadmapAddResult(ProposalState.OPEN, null, error)

/** A write's answer, as desktop: a readable 2xx is added; 400, 413 and a 409 are refusals;
 *  404 or 405 is an older Core; any other refusal opens the card again with the failure text;
 *  anything else (a 2xx this build cannot read, no answer, any 5xx, an answer the lock
 *  dropped: null) is not confirmed and Add stays off. */
private fun outcome(result: ApiResult<RoadmapSnapshot?>?): RoadmapAddResult = when {
    result is ApiResult.Success -> result.value?.let { RoadmapAddResult(ProposalState.ADDED, it.roadmap.id, "") } ?: UNCONFIRMED
    result == ApiResult.Unauthorized -> opened(roadmapFailureText(JobFailure.SIGNIN))
    result !is ApiResult.HttpError || result.status >= 500 -> UNCONFIRMED
    result.status == 404 || result.status == 405 -> opened(ROADMAP_CORE_TOO_OLD)
    result.status == 400 || result.status == 409 || result.status == 413 -> opened(ROADMAP_REFUSED)
    else -> opened(roadmapFailureText(jobFailureOf(result)))
}

/** Sends the proposal's body exactly as Core gave it: one create for a new roadmap (a 409 is
 *  a refusal); for an existing one, its current revision ([read]) and then [createItems], read
 *  and sent once more after a 409. A failed read sent nothing, so the card opens again; so
 *  does a lock or a sign-in or conversation change between the read and the write
 *  ([allowed] false). */
suspend fun addRoadmap(
    p: RoadmapProposal,
    create: suspend (JsonObject) -> ApiResult<RoadmapSnapshot?>?,
    read: suspend (String) -> ApiResult<RoadmapSnapshot>?,
    createItems: suspend (String, Long, JsonObject) -> ApiResult<RoadmapSnapshot?>?,
    allowed: () -> Boolean,
): RoadmapAddResult {
    val id = p.roadmapId ?: return outcome(create(p.body))
    var result: ApiResult<RoadmapSnapshot?>? = null
    for (attempt in 1..2) {
        val current = read(id) ?: return opened("")
        if (current !is ApiResult.Success) return opened(roadmapFailureText(jobFailureOf(current)))
        if (!allowed()) return opened("")
        result = createItems(id, current.value.roadmap.revision, p.body)
        if (!(result is ApiResult.HttpError && result.status == 409)) break
    }
    return outcome(result)
}

/** One Add under way: the cards with it adding, and the sign-in and conversation it was sent
 *  from. */
data class RoadmapAddStart(
    val id: String, val cards: List<RoadmapChatCard>, val proposal: RoadmapProposal, val identity: Int, val conversationId: String?,
)

/** The Add of card [id], with the card set to adding before anything is sent; null when the
 *  tap is refused: behind the lock or signed out ([allowed] false), or the card is not open
 *  (gone, or a second tap while the first runs). */
fun startRoadmapAdd(cards: List<RoadmapChatCard>, id: String, allowed: Boolean, identity: Int, conversationId: String?): RoadmapAddStart? {
    val card = cards.firstOrNull { it.proposal.id == id && it.state == ProposalState.OPEN }?.takeIf { allowed } ?: return null
    val adding = cards.map { if (it.proposal.id == id) it.copy(state = ProposalState.ADDING, error = "") else it }
    return RoadmapAddStart(id, adding, card.proposal, identity, conversationId)
}

/** [cards] with the Add's [result]; unchanged when the owner has left the sign-in or
 *  conversation it was sent from (now [identity] and [conversationId]), or dismissed the card. */
fun finishRoadmapAdd(
    cards: List<RoadmapChatCard>, start: RoadmapAddStart, result: RoadmapAddResult, identity: Int, conversationId: String?,
): List<RoadmapChatCard> =
    if (identity != start.identity || conversationId != start.conversationId) cards else cards.map {
        if (it.proposal.id != start.id) it else it.copy(state = result.state, roadmapId = result.roadmapId, error = result.error)
    }
