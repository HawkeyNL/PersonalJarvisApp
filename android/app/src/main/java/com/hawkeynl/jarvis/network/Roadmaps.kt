package com.hawkeynl.jarvis.network

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Roadmaps (/v1/roadmaps), as desktop src/roadmaps.ts: Core's contract, response guards,
// input rules, GitHub link parsing and the display helpers the pages share. Pure, so tests
// load it directly.
//
// Every write names the revision it builds on (?expected_revision=N); Core answers 409 when
// the roadmap moved on, and the page then reloads. Accepting or rejecting a proposal takes no
// revision: Core marks a proposal made on an older revision stale (409). Owner edits never set
// an item's auto_status or a link's cached GitHub state; both are display only.

enum class RoadmapStatus(val wire: String, val label: String) {
    LIVE("live", "Live"), READY("ready", "Ready"), IN_PROGRESS("in_progress", "In progress"),
    PLANNED("planned", "Planned"), BLOCKED("blocked", "Blocked");

    /** A dependency is met once it is ready (merged) or live. */
    val done: Boolean get() = this == LIVE || this == READY
}

enum class LinkKind(val wire: String) { PR("pr"), ISSUE("issue"), RELEASE("release"), URL("url") }

/** [state] is GitHub's state as Core last cached it; display only, never sent. */
data class RoadmapLink(
    val kind: LinkKind, val repo: String? = null, val number: Long? = null, val tag: String? = null,
    val url: String? = null, val state: String? = null,
)

data class Roadmap(
    val id: String, val title: String, val description: String?, val phases: List<String>, val revision: Long,
    val createdAt: String, val updatedAt: String, val archivedAt: String?,
)

data class RoadmapItem(
    val id: String, val title: String, val status: RoadmapStatus, val autoStatus: RoadmapStatus?, val phase: String?,
    val position: Long, val notes: String?, val dependsOn: List<String>, val links: List<RoadmapLink>, val updatedAt: String,
)

data class RoadmapSnapshot(val roadmap: Roadmap, val items: List<RoadmapItem>)

enum class Author(val wire: String, val label: String) { OWNER("owner", "You"), JARVIS("jarvis", "Jarvis"), GITHUB("github", "GitHub") }

data class HistoryEntry(
    val id: String, val revision: Long, val at: String, val author: Author, val deviceId: String?, val proposalId: String?,
    val action: String, val itemId: String?, val title: String, val fields: List<String>,
    val fromStatus: RoadmapStatus?, val toStatus: RoadmapStatus?,
)

/** Fields of a proposed item or change. [present] names the fields Core sent; a present
 *  field with a null value clears it. */
data class ItemFields(
    val present: Set<String>, val title: String? = null, val status: RoadmapStatus? = null, val phase: String? = null,
    val position: Long? = null, val notes: String? = null, val dependsOn: List<String>? = null, val links: List<RoadmapLink>? = null,
)

/** A create ([itemId] null) or a change of one item. */
data class ProposalOp(val itemId: String?, val fields: ItemFields)

data class Proposal(
    val id: String, val baseRevision: Long, val ops: List<ProposalOp>, val rationale: String?,
    val createdAt: String, val expiresAt: String,
)

object RoadmapLimits {
    const val ROADMAPS = 20
    const val ITEMS = 300
    const val ROADMAP_TITLE = 120
    const val ITEM_TITLE = 200
    const val DESCRIPTION = 2000
    const val NOTES = 4000
    const val PHASES = 20
    const val PHASE = 60
    const val DEPENDS_ON = 20
    const val LINKS = 10
    const val URL = 500
    const val LINK_NUMBER = 1_000_000_000L
    const val OPS = 20
    const val HISTORY = 100
}

val DEFAULT_PHASES = listOf("Now", "Next", "Later")

private val ROADMAP_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/** Roadmap, item and proposal ids go into URL paths: path-safe characters only. */
fun isRoadmapId(id: String): Boolean = ROADMAP_ID.matches(id)

// ---- input rules (Core enforces the same; these only explain them early) --

// Core's no_hidden_chars denylist: control, invisible and bidi code points.
private val HIDDEN = Regex(
    "[\\p{Cc}\\u00AD\\u034F\\u061C\\u115F\\u1160\\u180E\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u2069\\u3164" +
        "\\uFE00-\\uFE0F\\uFEFF\\uFFF9-\\uFFFB\\x{E0000}-\\x{E007F}]",
)

/** Non-blank, at most [max] characters, no hidden characters; multi-line text may also hold
 *  line breaks and tabs. */
fun validText(value: String, max: Int, multiline: Boolean = false): Boolean {
    if (value.isBlank() || value.codePointCount(0, value.length) > max) return false
    return !HIDDEN.containsMatchIn(if (multiline) value.replace("\n", "").replace("\t", "") else value)
}

/** Phases typed one per line: trimmed, blank lines dropped. Null when they break Core's rule
 *  (at most 20 distinct names of 1 to 60 characters). */
fun parsePhases(text: String): List<String>? {
    val phases = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val ok = phases.size <= RoadmapLimits.PHASES && phases.toSet().size == phases.size &&
        phases.all { validText(it, RoadmapLimits.PHASE) }
    return phases.takeIf { ok }
}

private val REPO = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}$")
private val TAG = Regex("^[A-Za-z0-9._+/-]{1,100}$")
fun validRepo(repo: String): Boolean = REPO.matches(repo) && !Regex("/\\.{1,2}$").containsMatchIn(repo)
private fun validNumber(n: Long?): Boolean = n != null && n in 1..RoadmapLimits.LINK_NUMBER

/** An absolute https URL with a host, no credentials, printable ASCII. */
fun validHttpsUrl(value: String): Boolean {
    if (value.length > RoadmapLimits.URL || !Regex("^https://[!-~]+$").matches(value)) return false
    val url = runCatching { URI(value) }.getOrNull() ?: return false
    return url.scheme == "https" && !url.host.isNullOrEmpty() && url.rawUserInfo == null
}

sealed interface ParsedLink {
    data class Ok(val link: RoadmapLink) : ParsedLink
    data class Bad(val error: String) : ParsedLink
}

private val GITHUB = Regex("^https://github\\.com/([^/?#]+/[^/?#]+)/(pull|issues|releases/tag)/([^?#]+?)/?(?:[?#].*)?$")
private val SHORT = Regex("^([^#@\\s]+)#(\\d+)$")
private val SHORT_RELEASE = Regex("^([^#@\\s]+)@(\\S+)$")

/** One link from what the owner typed or pasted. A GitHub URL names its own kind (pull → pr,
 *  issues → issue, releases/tag → release); `owner/name#12` becomes the [hint] kind (pr or
 *  issue) and `owner/name@v1.2.0` a release. Anything else must be an https URL. */
fun parseLink(input: String, hint: LinkKind = LinkKind.PR): ParsedLink {
    val text = input.trim()
    GITHUB.matchEntire(text)?.let { gh ->
        val (repo, kind, rest) = gh.destructured
        if (kind == "releases/tag") {
            // decodeURIComponent: a '+' stays a '+'.
            val tag = runCatching { URLDecoder.decode(rest.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull()
            if (tag != null && validRepo(repo) && TAG.matches(tag)) return ParsedLink.Ok(RoadmapLink(LinkKind.RELEASE, repo = repo, tag = tag))
            return ParsedLink.Bad("That release link is not valid.")
        }
        val number = Regex("^(\\d+)(?:/.*)?$").matchEntire(rest)?.groupValues?.get(1)?.toLongOrNull()
        if (validRepo(repo) && validNumber(number)) {
            return ParsedLink.Ok(RoadmapLink(if (kind == "pull") LinkKind.PR else LinkKind.ISSUE, repo = repo, number = number))
        }
        return ParsedLink.Bad("That GitHub link is not valid.")
    }
    SHORT.matchEntire(text)?.let { short ->
        val number = short.groupValues[2].toLongOrNull()
        if (validRepo(short.groupValues[1]) && validNumber(number)) return ParsedLink.Ok(RoadmapLink(hint, repo = short.groupValues[1], number = number))
    }
    SHORT_RELEASE.matchEntire(text)?.let { release ->
        if (validRepo(release.groupValues[1]) && TAG.matches(release.groupValues[2])) {
            return ParsedLink.Ok(RoadmapLink(LinkKind.RELEASE, repo = release.groupValues[1], tag = release.groupValues[2]))
        }
    }
    if (Regex("^http://", RegexOption.IGNORE_CASE).containsMatchIn(text)) return ParsedLink.Bad("Only https links are allowed.")
    if (validHttpsUrl(text)) return ParsedLink.Ok(RoadmapLink(LinkKind.URL, url = text))
    return ParsedLink.Bad("Paste a GitHub link, owner/name#12, owner/name@v1.2.0 or an https URL.")
}

fun linkLabel(link: RoadmapLink): String = when (link.kind) {
    LinkKind.URL -> link.url.orEmpty()
    LinkKind.RELEASE -> "${link.repo}@${link.tag}"
    else -> "${link.repo}#${link.number}"
}

/** encodeURIComponent: UTF-8 bytes, all but A-Z a-z 0-9 - _ . ! ~ * ' ( ) as %XX. */
private fun encodeComponent(text: String): String = text.toByteArray(Charsets.UTF_8).joinToString("") { b ->
    val c = b.toInt().toChar()
    if (b >= 0 && (c.isLetterOrDigit() || c in "-_.!~*'()")) c.toString() else "%%%02X".format(b.toInt() and 0xFF)
}

/** Where a link opens; null when it does not hold an https target. A GitHub link opens only
 *  for a valid owner/name (Core's cached repo is not trusted for a URL). */
fun linkHref(link: RoadmapLink): String? {
    if (link.kind == LinkKind.URL) return link.url?.takeIf(::validHttpsUrl)
    val repo = link.repo?.takeIf(::validRepo) ?: return null
    val base = "https://github.com/$repo"
    return when (link.kind) {
        LinkKind.PR -> link.number?.let { "$base/pull/$it" }
        LinkKind.ISSUE -> link.number?.let { "$base/issues/$it" }
        else -> link.tag?.let { tag -> "$base/releases/tag/" + tag.split("/").joinToString("/", transform = ::encodeComponent) }
    }
}

private fun sameTarget(a: RoadmapLink, b: RoadmapLink) = a.copy(state = null) == b.copy(state = null)

/** A link as Core accepts it: the cached `state` is Core's, never sent. */
fun linkJson(link: RoadmapLink): JsonObject = buildJsonObject {
    put("kind", link.kind.wire)
    link.repo?.let { put("repo", it) }
    link.number?.let { put("number", it) }
    link.tag?.let { put("tag", it) }
    link.url?.let { put("url", it) }
}

/** [links] with [link] added; null when it is already there or the list is full. */
fun addLink(links: List<RoadmapLink>, link: RoadmapLink): List<RoadmapLink>? =
    if (links.size >= RoadmapLimits.LINKS || links.any { sameTarget(it, link) }) null else links + link

// ---- response guards: anything unexpected reads as null (fail closed) -----

private fun JsonObject.status(key: String): RoadmapStatus? =
    optStr(key)?.let { s -> RoadmapStatus.entries.firstOrNull { it.wire == s } ?: throw IllegalArgumentException() }

private fun JsonObject.strList(key: String, max: Int): List<String> = array(key, max).map {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length <= 200 }?.content ?: throw IllegalArgumentException()
}

private fun readLinkJson(x: JsonElement): RoadmapLink {
    val o = x.obj()
    val kind = o.optStr("kind").let { k -> LinkKind.entries.firstOrNull { it.wire == k } } ?: throw IllegalArgumentException()
    return RoadmapLink(kind, o.optStr("repo", 200), o.optInt("number"), o.optStr("tag", 200), o.optStr("url", RoadmapLimits.URL), o.optStr("state", 40))
}

private fun parseRoadmap(x: JsonElement): Roadmap {
    val o = x.obj()
    return Roadmap(
        o.str("id", 64), o.str("title", 1000), o.optStr("description"), o.strList("phases", RoadmapLimits.PHASES), o.int("revision"),
        o.str("created_at", 64), o.str("updated_at", 64), o.optStr("archived_at", 64),
    )
}

private fun parseItem(x: JsonElement): RoadmapItem {
    val o = x.obj()
    return RoadmapItem(
        o.str("id", 64), o.str("title", 1000), requireNotNull(o.status("status")), o.status("auto_status"), o.optStr("phase", 200),
        o.int("position"), o.optStr("notes", 20_000), o.strList("depends_on", RoadmapLimits.DEPENDS_ON),
        o.array("links", RoadmapLimits.LINKS).map(::readLinkJson), o.str("updated_at", 64),
    )
}

fun readRoadmapList(x: JsonElement): List<Roadmap>? = guarded { x.obj().array("roadmaps", 200).map(::parseRoadmap) }

fun readSnapshot(x: JsonElement): RoadmapSnapshot? = guarded {
    val o = x.obj()
    val items = o.array("items", RoadmapLimits.ITEMS)
    RoadmapSnapshot(parseRoadmap(requireNotNull(o["roadmap"])), items.map(::parseItem))
}

fun readHistory(x: JsonElement): List<HistoryEntry>? = guarded {
    x.obj().array("changes", RoadmapLimits.HISTORY).map {
        val c = it.obj()
        val author = c.optStr("author").let { a -> Author.entries.firstOrNull { e -> e.wire == a } } ?: throw IllegalArgumentException()
        HistoryEntry(
            c.str("id", 64), c.int("revision"), c.str("at", 64), author, c.optStr("device_id", 64), c.optStr("proposal_id", 64),
            c.str("action", 40), c.optStr("item_id", 64), c.str("title", 1000), c.strList("fields", 20),
            c.status("from_status"), c.status("to_status"),
        )
    }
}

private fun readFields(o: JsonObject): ItemFields {
    val keys = listOf("title", "status", "phase", "position", "notes", "depends_on", "links")
    // Only phase and notes may be null (cleared); the others, when present, must hold a value.
    return ItemFields(
        keys.filter { it in o }.toSet(),
        title = if ("title" in o) o.str("title", 1000) else null,
        status = if ("status" in o) requireNotNull(o.status("status")) else null,
        phase = o.optStr("phase", 200),
        position = if ("position" in o) o.int("position") else null,
        notes = o.optStr("notes", 20_000),
        dependsOn = if ("depends_on" in o) o.strList("depends_on", RoadmapLimits.DEPENDS_ON) else null,
        links = if ("links" in o) o.array("links", RoadmapLimits.LINKS).map(::readLinkJson) else null,
    )
}

private fun parseOp(x: JsonElement): ProposalOp {
    val o = x.obj()
    return when (o.optStr("op")) {
        "create_item" -> ProposalOp(null, readFields(o["item"].obj()).also { requireNotNull(it.title) })
        "update_item" -> ProposalOp(o.str("item_id", 64), readFields(o["patch"].obj()))
        else -> throw IllegalArgumentException()
    }
}

fun readProposals(x: JsonElement): List<Proposal>? = guarded {
    x.obj().array("proposals", 100).map {
        val p = it.obj()
        Proposal(
            p.str("id", 64), p.int("base_revision"), p.array("ops", RoadmapLimits.OPS).map(::parseOp), p.optStr("rationale", 4000),
            p.str("created_at", 64), p.str("expires_at", 64),
        )
    }
}

// ---- display -----------------------------------------------------------

/** Items per status, in status order, statuses without items left out. */
fun tally(items: List<RoadmapItem>): List<Pair<RoadmapStatus, Int>> =
    RoadmapStatus.entries.map { s -> s to items.count { it.status == s } }.filter { it.second > 0 }

private val BY_POSITION = compareBy<RoadmapItem>({ it.position }, { it.title })

data class PhaseGroup(val phase: String?, val items: List<RoadmapItem>)

/** The rail: one group per roadmap phase in its order, then items without a (known) phase.
 *  Empty phases stay, so the rail shows the whole plan. */
fun groupByPhase(phases: List<String>, items: List<RoadmapItem>): List<PhaseGroup> {
    val groups = phases.map { phase -> PhaseGroup(phase, items.filter { it.phase == phase }.sortedWith(BY_POSITION)) }
    val rest = items.filter { it.phase == null || it.phase !in phases }.sortedWith(BY_POSITION)
    return if (rest.isNotEmpty()) groups + PhaseGroup(null, rest) else groups
}

data class Waiting(val item: RoadmapItem, val unmet: List<RoadmapItem>)

/** What holds the plan up: blocked items, and open items with an unmet dependency. Done items
 *  wait on nothing. */
fun waitingOn(items: List<RoadmapItem>): List<Waiting> {
    val byId = items.associateBy { it.id }
    return items.filter { !it.status.done }
        .map { item -> Waiting(item, item.dependsOn.mapNotNull { byId[it] }.filter { !it.status.done }) }
        .filter { it.item.status == RoadmapStatus.BLOCKED || it.unmet.isNotEmpty() }
        .sortedWith(compareBy(BY_POSITION) { it.item })
}

/** Items [id] may depend on: not itself and none that already depends on it, directly or
 *  through others (Core refuses cycles). */
fun dependencyOptions(items: List<RoadmapItem>, id: String?): List<RoadmapItem> {
    if (id == null) return items.sortedWith(BY_POSITION)
    val dependents = mutableSetOf(id)
    var grew = true
    while (grew) {
        grew = false
        for (i in items) if (i.id !in dependents && i.dependsOn.any { it in dependents }) { dependents += i.id; grew = true }
    }
    return items.filter { it.id !in dependents }.sortedWith(BY_POSITION)
}

/** The item editor's fields. */
data class ItemDraft(
    val title: String, val status: RoadmapStatus, val phase: String?, val notes: String,
    val dependsOn: List<String>, val links: List<RoadmapLink>,
)

fun toDraft(item: RoadmapItem) = ItemDraft(item.title, item.status, item.phase, item.notes.orEmpty(), item.dependsOn, item.links)

/** Only what changed, so an unchanged editor sends nothing (null). Empty notes clear them. */
fun itemPatch(item: RoadmapItem, draft: ItemDraft): JsonObject? {
    val title = draft.title.trim()
    val notes = draft.notes.takeIf { it.isNotBlank() }
    val sameLinks = draft.links.size == item.links.size && draft.links.zip(item.links).all { (a, b) -> sameTarget(a, b) }
    val patch = buildJsonObject {
        if (title != item.title) put("title", title)
        if (draft.status != item.status) put("status", draft.status.wire)
        if (draft.phase != item.phase) put("phase", draft.phase)
        if (notes != item.notes) put("notes", notes)
        if (draft.dependsOn != item.dependsOn) put("depends_on", JsonArray(draft.dependsOn.map(::JsonPrimitive)))
        if (!sameLinks) put("links", JsonArray(draft.links.map(::linkJson)))
    }
    return patch.takeIf { it.isNotEmpty() }
}

/** Why a draft cannot be saved yet; null when Core should accept it. */
fun draftIssue(draft: ItemDraft): String? = when {
    !validText(draft.title.trim(), RoadmapLimits.ITEM_TITLE) -> "The title must be 1 to 200 characters on one line."
    draft.notes.isNotBlank() && !validText(draft.notes, RoadmapLimits.NOTES, multiline = true) ->
        "Notes must be at most 4000 characters, without hidden characters."
    else -> null
}

/** The create body of a new item, in [phase] or none. */
fun newItem(title: String, phase: String?): JsonObject = buildJsonObject {
    put("title", title)
    phase?.let { put("phase", it) }
}

const val ROADMAP_TITLE_RULE = "The title must be 1 to 120 characters on one line."
const val DESCRIPTION_RULE = "The description must be at most 2000 characters."
const val PHASES_RULE = "Phases: at most 20 different names of 1 to 60 characters, one per line."

/** Why a roadmap form cannot be sent yet; null when Core should accept it. A blank
 *  description is none. */
fun roadmapIssue(title: String, description: String, phases: List<String>?): String? = when {
    !validText(title.trim(), RoadmapLimits.ROADMAP_TITLE) -> ROADMAP_TITLE_RULE
    description.isNotBlank() && !validText(description, RoadmapLimits.DESCRIPTION, multiline = true) -> DESCRIPTION_RULE
    phases == null -> PHASES_RULE
    else -> null
}

/** The create body of a new roadmap (checked by [roadmapIssue] first). */
fun newRoadmap(title: String, description: String, phases: List<String>): JsonObject = buildJsonObject {
    put("title", title.trim())
    if (description.isNotBlank()) put("description", description)
    put("phases", JsonArray(phases.map(::JsonPrimitive)))
}

/** Only what changed of a roadmap's title, description and phases; null when nothing did. */
fun roadmapPatch(roadmap: Roadmap, title: String, description: String, phases: List<String>): JsonObject? {
    val desc = description.takeIf { it.isNotBlank() }
    val patch = buildJsonObject {
        if (title.trim() != roadmap.title) put("title", title.trim())
        if (desc != roadmap.description) put("description", desc)
        if (phases != roadmap.phases) put("phases", JsonArray(phases.map(::JsonPrimitive)))
    }
    return patch.takeIf { it.isNotEmpty() }
}

data class DiffRow(val field: String, val before: String, val after: String)
data class OpView(val create: Boolean, val title: String, val rows: List<DiffRow>)

private const val NONE = "—"
private val FIELD_LABEL = linkedMapOf(
    "title" to "Title", "status" to "Status", "phase" to "Phase", "position" to "Position", "notes" to "Notes",
    "depends_on" to "Depends on", "links" to "Links",
)

/** One proposal operation as before → after rows, in the roadmap's words. */
fun describeOp(op: ProposalOp, items: List<RoadmapItem>): OpView {
    val byId = items.associateBy { it.id }
    fun deps(ids: List<String>?) = if (ids.isNullOrEmpty()) NONE else ids.joinToString(", ") { byId[it]?.title ?: "Unknown item" }
    fun links(list: List<RoadmapLink>?) = if (list.isNullOrEmpty()) NONE else list.joinToString(", ", transform = ::linkLabel)
    fun text(v: Any?) = v?.toString()?.takeIf { it.isNotEmpty() } ?: NONE
    fun value(field: String, f: ItemFields): String = when (field) {
        "title" -> text(f.title)
        "status" -> f.status?.label ?: NONE
        "phase" -> text(f.phase)
        "position" -> text(f.position)
        "notes" -> text(f.notes)
        "depends_on" -> deps(f.dependsOn)
        else -> links(f.links)
    }
    fun fieldsOf(item: RoadmapItem) = ItemFields(
        FIELD_LABEL.keys, item.title, item.status, item.phase, item.position, item.notes, item.dependsOn, item.links,
    )
    if (op.itemId == null) {
        // A new item is planned unless the proposal says otherwise.
        val item = if ("status" in op.fields.present) op.fields else op.fields.copy(present = op.fields.present + "status", status = RoadmapStatus.PLANNED)
        val rows = FIELD_LABEL.filterKeys { it != "title" && it != "position" && it in item.present }
            .map { (field, label) -> DiffRow(label, NONE, value(field, item)) }
        return OpView(true, op.fields.title.orEmpty(), rows)
    }
    val current = byId[op.itemId]
    val rows = FIELD_LABEL.filterKeys { it in op.fields.present }
        .map { (field, label) -> DiffRow(label, current?.let { value(field, fieldsOf(it)) } ?: NONE, value(field, op.fields)) }
    return OpView(false, current?.title ?: "An item that no longer exists", rows)
}

private val ACTION_LABEL = mapOf(
    "roadmap_created" to "Created the roadmap", "roadmap_updated" to "Changed the roadmap", "roadmap_archived" to "Archived the roadmap",
    "item_created" to "Added", "item_updated" to "Changed", "item_deleted" to "Deleted",
)

/** One history line, e.g. `Changed "Sync": status Planned → Ready`. */
fun describeChange(c: HistoryEntry): String {
    val action = ACTION_LABEL[c.action] ?: "Changed"
    val subject = if (c.itemId != null) "$action “${c.title}”" else action
    val status = if (c.fromStatus != null && c.toStatus != null) "status ${c.fromStatus.label} → ${c.toStatus.label}" else null
    val others = c.fields.filter { !(status != null && it == "status") }.map { it.replace('_', ' ') }
    val detail = (listOfNotNull(status) + others).joinToString(", ")
    return if (detail.isNotEmpty() && c.action.endsWith("updated")) "$subject: $detail" else subject
}

// ---- failures ------------------------------------------------------------

/** A load failure ([jobFailureOf], where 404 means not found) in the roadmaps' words. */
fun roadmapFailureText(failure: JobFailure): String = when (failure) {
    JobFailure.NO_API -> "This Core has no roadmaps yet. Update Core on your Home Node."
    JobFailure.UNREACHABLE -> "No answer from your Home Node. Check the connection."
    JobFailure.SIGNIN -> "Your session is not valid. Sign this device in again."
    JobFailure.NOT_FOUND -> "This roadmap does not exist on your Home Node (or this Core has no roadmaps yet)."
    JobFailure.UNAVAILABLE -> "Core could not read the roadmaps just now. Try again in a minute."
    JobFailure.FAILED -> "Core returned an answer this app does not understand, or an error. Try again later."
}

/** The notice after a failed write. A 409 is a conflict when the reload shows a newer
 *  revision; otherwise Core refused for a limit or an archived roadmap. A 400 means a rule
 *  this app did not catch. */
fun roadmapWriteFailureText(result: ApiResult<*>, revisionMoved: Boolean): String = when {
    result is ApiResult.HttpError && result.status == 409 ->
        if (revisionMoved) CHANGED_ELSEWHERE else "Core refused this change: the roadmap is archived or a limit is reached."
    result is ApiResult.HttpError && result.status == 400 -> "Core refused this change as invalid. Check the fields and try again."
    result is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check."
    else -> "Not saved. ${roadmapFailureText(jobFailureOf(result))}"
}

/** The notice after a refused accept or reject of a proposal. */
fun proposalFailureText(result: ApiResult<*>): String =
    if (result is ApiResult.HttpError && result.status == 409) "Not applied: the roadmap changed since Jarvis proposed this, or a limit is reached. Reloaded."
    else "Not done. ${roadmapFailureText(jobFailureOf(result))}"

/** One write on a roadmap, sent with the revision it builds on. */
sealed interface RoadmapWrite {
    data class Update(val patch: JsonObject) : RoadmapWrite
    /** Archives; Core deletes nothing. */
    data object Archive : RoadmapWrite
    data class CreateItem(val item: JsonObject) : RoadmapWrite
    data class UpdateItem(val itemId: String, val patch: JsonObject) : RoadmapWrite
    data class DeleteItem(val itemId: String) : RoadmapWrite
}
