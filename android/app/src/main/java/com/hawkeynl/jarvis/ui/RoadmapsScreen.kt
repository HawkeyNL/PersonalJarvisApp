package com.hawkeynl.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.Author
import com.hawkeynl.jarvis.network.DEFAULT_PHASES
import com.hawkeynl.jarvis.network.DESCRIPTION_RULE
import com.hawkeynl.jarvis.network.HistoryEntry
import com.hawkeynl.jarvis.network.ItemDraft
import com.hawkeynl.jarvis.network.JobFailure
import com.hawkeynl.jarvis.network.LinkKind
import com.hawkeynl.jarvis.network.PHASES_RULE
import com.hawkeynl.jarvis.network.ParsedLink
import com.hawkeynl.jarvis.network.Proposal
import com.hawkeynl.jarvis.network.ROADMAP_TITLE_RULE
import com.hawkeynl.jarvis.network.Roadmap
import com.hawkeynl.jarvis.network.RoadmapItem
import com.hawkeynl.jarvis.network.RoadmapLimits
import com.hawkeynl.jarvis.network.RoadmapSnapshot
import com.hawkeynl.jarvis.network.RoadmapStatus
import com.hawkeynl.jarvis.network.RoadmapWrite
import com.hawkeynl.jarvis.network.Waiting
import com.hawkeynl.jarvis.network.addLink
import com.hawkeynl.jarvis.network.dependencyOptions
import com.hawkeynl.jarvis.network.describeChange
import com.hawkeynl.jarvis.network.describeOp
import com.hawkeynl.jarvis.network.draftIssue
import com.hawkeynl.jarvis.network.groupByPhase
import com.hawkeynl.jarvis.network.itemPatch
import com.hawkeynl.jarvis.network.jobFailureOf
import com.hawkeynl.jarvis.network.linkHref
import com.hawkeynl.jarvis.network.linkLabel
import com.hawkeynl.jarvis.network.newItem
import com.hawkeynl.jarvis.network.newRoadmap
import com.hawkeynl.jarvis.network.parseLink
import com.hawkeynl.jarvis.network.parsePhases
import com.hawkeynl.jarvis.network.proposalFailureText
import com.hawkeynl.jarvis.network.roadmapFailureText
import com.hawkeynl.jarvis.network.roadmapIssue
import com.hawkeynl.jarvis.network.roadmapPatch
import com.hawkeynl.jarvis.network.roadmapWriteFailureText
import com.hawkeynl.jarvis.network.tally
import com.hawkeynl.jarvis.network.toDraft
import com.hawkeynl.jarvis.network.validText
import com.hawkeynl.jarvis.network.waitingOn
import com.hawkeynl.jarvis.network.zonedText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/** Roadmaps (desktop Roadmaps.vue, Roadmap.vue, ItemSheet.vue): the owner's roadmaps, one
 *  roadmap with what it waits on, its phases, Jarvis's pending proposals and its history, and
 *  one item's editor. A roadmap and an item open inside this screen; Back returns to where they
 *  were opened from. Content comes from Core only; notes and proposals are plain text. */
@Composable
fun RoadmapsScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    var roadmapId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = roadmapId
    if (id != null) {
        BackHandler { roadmapId = null }
        key(id) { RoadmapPage(id, actions) { roadmapId = null } }
    } else {
        RoadmapListPage(actions, { onNavigate(backOf(Screen.ROADMAPS)) }) { roadmapId = it }
    }
}

/** Desktop STATUS_TONE; the label always carries the meaning too. */
private fun statusTone(status: RoadmapStatus): Tone = when (status) {
    RoadmapStatus.LIVE, RoadmapStatus.READY -> Tone.OK
    RoadmapStatus.IN_PROGRESS -> Tone.WARN
    RoadmapStatus.PLANNED -> Tone.IDLE
    RoadmapStatus.BLOCKED -> Tone.ERROR
}

private fun plural(n: Int, one: String) = "$n ${if (n == 1) one else "${one}s"}"
private fun firstLine(text: String?) = text?.lines()?.firstOrNull { it.isNotBlank() }.orEmpty()

private data class RoadmapCard(val roadmap: Roadmap, val tally: List<Pair<RoadmapStatus, Int>>?, val items: Int?, val proposals: Int?)

@Composable
private fun RoadmapListPage(actions: JarvisViewModel, back: () -> Unit, open: (String) -> Unit) {
    var cards by remember { mutableStateOf<List<RoadmapCard>?>(null) }
    var failure by remember { mutableStateOf<JobFailure?>(null) }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val latest = rememberLatest()

    // A null answer means locked or signed out: this page is about to go.
    suspend fun load() {
        val list = actions.jobs { e, t -> roadmaps(e, t) } ?: return
        if (list !is ApiResult.Success) {
            // 404 on the list means an older Core without roadmaps.
            if (cards == null) failure = jobFailureOf(list, list = true)
            return
        }
        // ponytail: one detail and one proposals read per card (at most 20 roadmaps); a summary route in Core if this gets slow.
        val loaded = coroutineScope {
            list.value.map { r ->
                async {
                    val snap = async { actions.jobs { e, t -> roadmap(e, t, r.id) } }
                    val props = async { actions.jobs { e, t -> roadmapProposals(e, t, r.id) } }
                    Triple(r, snap.await() ?: return@async null, props.await() ?: return@async null)
                }
            }.awaitAll()
        }
        cards = loaded.map { triple ->
            val (r, snap, props) = triple ?: return
            val items = (snap as? ApiResult.Success)?.value?.items
            RoadmapCard(r, items?.let(::tally), items?.size, (props as? ApiResult.Success)?.value?.size)
        }
        failure = null
    }
    // On open and on every return to the app (desktop reloads on focus); never over a write.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!busy) latest.launch { load() } }

    fun create(title: String, description: String, phasesText: String) {
        if (busy) return
        val phases = parsePhases(phasesText)
        roadmapIssue(title, description, phases)?.let { error = it; return }
        val body = newRoadmap(title, description, phases ?: return)
        busy = true
        error = ""
        // Ids on screen before the create: only a new one with this title can be it.
        val before = cards.orEmpty().map { it.roadmap.id }.toSet()
        latest.launch {
            try {
                val result = actions.jobs { e, t -> createRoadmap(e, t, body) } ?: return@launch
                if (result is ApiResult.Success) {
                    creating = false
                    result.value?.let { open(it.roadmap.id) } ?: load()
                } else {
                    error = roadmapWriteFailureText(result, revisionMoved = false)
                    // The roadmap may exist although its answer was unreadable: close the form
                    // once the reloaded list shows a new one with this title, else ask to check
                    // before a second try.
                    if (result is ApiResult.InvalidResponse || result is ApiResult.Unreachable) {
                        load()
                        if (cards.orEmpty().any { it.roadmap.id !in before && it.roadmap.title == title.trim() }) {
                            creating = false
                            error = ""
                        } else {
                            error += " Check the list before trying again."
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    PushedPage(
        screenTitle(Screen.ROADMAPS), "Your plans: what is live, ready, in progress, planned or blocked.", back,
        trailing = { GhostButton("Refresh", { latest.launch { load() } }, enabled = !busy) },
    ) {
        val shown = cards
        val full = shown != null && shown.size >= RoadmapLimits.ROADMAPS
        if (full) MutedText("You have 20 roadmaps, the most Core keeps open. Archive one to start another.")
        if (creating) {
            RoadmapForm("New roadmap", null, emptyList(), busy, error, "Create", onCancel = { creating = false; error = "" }, onSave = ::create)
        } else {
            // Only once the list is known, so the 20-roadmap limit is known too.
            ActionButton("New roadmap", { creating = true; error = "" }, enabled = shown != null && !full)
        }
        val failed = failure
        when {
            shown == null && failed != null ->
                Unavailable(
                    "Roadmaps", JvIcon.LAYERS, kind = UnavailableKind.ERROR, detail = roadmapFailureText(failed),
                    onRetry = if (busy) null else ({ latest.launch { load() } }),
                )
            shown == null -> Skeleton("Loading roadmaps…")
            shown.isEmpty() -> ListSection(null) {
                EmptyState("No roadmaps yet", body = "Start one for a project: its phases, its items and what each is waiting on.", icon = JvIcon.LAYERS)
            }
            else -> shown.forEach { card -> RoadmapCardView(card) { open(card.roadmap.id) } }
        }
    }
}

/** A roadmap on the list: its name opens it; pending proposals, items per status below. */
@Composable
private fun RoadmapCardView(card: RoadmapCard, open: () -> Unit) {
    ListSection(null) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open roadmap", onClick = open).padding(JvSpace.lg),
            verticalArrangement = Arrangement.spacedBy(JvSpace.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Text(card.roadmap.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                card.proposals?.takeIf { it > 0 }?.let { StatusBadge(plural(it, "proposal"), tone = Tone.WARN) }
            }
            card.roadmap.description?.let {
                Text(it, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            card.tally?.takeIf { it.isNotEmpty() }?.let { TallyChips(it) }
            MutedText(
                when (val n = card.items) {
                    null -> "Items could not be loaded"
                    0 -> "No items yet"
                    else -> plural(n, "item")
                },
            )
        }
    }
}

/** Items per status as badges, in status order. */
@Composable
private fun TallyChips(tally: List<Pair<RoadmapStatus, Int>>) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
        tally.forEach { (status, count) -> StatusBadge("$count ${status.label.lowercase()}", tone = statusTone(status)) }
    }
}

/** A text field with its label, help line and error (announced as the field's error). */
@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, error: String? = null, help: String? = null,
    singleLine: Boolean = false, minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().semantics { if (error != null) this.error(error) },
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        isError = error != null,
        singleLine = singleLine,
        minLines = minLines,
        shape = JvShapes.md,
    )
}

/** A labelled choice (desktop's select): the label, then a button with the current choice
 *  that opens the options; the chosen one is marked selected. */
@Composable
internal fun <T> Picker(label: String, options: List<Pair<T, String>>, value: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
        Text(label, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
        Box {
            GhostButton(current, { open = true }, Modifier.semantics { contentDescription = "$label: $current, opens choices" }, enabled = enabled)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (option, text) ->
                    val chosen = option == value
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { onSelect(option); open = false },
                        modifier = Modifier.semantics { selected = chosen },
                        trailingIcon = { if (chosen) JvIconView(JvIcon.CHECK, size = 18.dp) },
                    )
                }
            }
        }
    }
}

/** A new roadmap ([existing] null) or the edit of one: title, description and phases. */
@Composable
private fun RoadmapForm(
    heading: String, existing: Roadmap?, items: List<RoadmapItem>, busy: Boolean, error: String, submit: String,
    onCancel: () -> Unit, onSave: (String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var phases by remember { mutableStateOf((existing?.phases ?: DEFAULT_PHASES).joinToString("\n")) }
    ListSection(heading) {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            Field("Title", title, { title = it.take(RoadmapLimits.ROADMAP_TITLE) }, error = error.takeIf { it == ROADMAP_TITLE_RULE }, singleLine = true)
            Field("Description (optional)", description, { description = it.take(RoadmapLimits.DESCRIPTION) }, error = error.takeIf { it == DESCRIPTION_RULE }, minLines = 2)
            Field(
                "Phases", phases, { phases = it }, error = error.takeIf { it == PHASES_RULE }, minLines = 3,
                help = if (existing == null) "One per line, in order. Items sit in one phase or none." else "One per line, in order.",
            )
            if (existing != null) {
                val next = parsePhases(phases).orEmpty()
                val removed = existing.phases.filter { p -> p !in next && items.any { it.phase == p } }
                if (removed.isNotEmpty()) Warning("Removing ${removed.joinToString(", ")} moves its items to No phase.")
            }
            if (error.isNotEmpty()) ErrorText(error)
            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Cancel", onCancel, enabled = !busy)
                ActionButton(submit, { onSave(title, description, phases) }, Modifier.weight(1f), enabled = !busy)
            }
        }
    }
}

/** The open item editor: per item and revision, so a conflict reopens it on Core's state. */
private data class ItemSheet(val itemId: String, val revision: Long, val key: Int, val notice: String)

@Composable
private fun RoadmapPage(id: String, actions: JarvisViewModel, back: () -> Unit) {
    var snap by remember { mutableStateOf<RoadmapSnapshot?>(null) }
    var failure by remember { mutableStateOf<JobFailure?>(null) }
    var proposals by remember { mutableStateOf<List<Proposal>?>(null) }
    var history by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var historyFailed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("items") }
    var sheet by remember { mutableStateOf<ItemSheet?>(null) }
    var sheetKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf("") }
    var archiving by remember { mutableStateOf(false) }
    val latest = rememberLatest()
    // History reads beside loads and writes, never cancelling them; the newest read wins.
    val historyLatest = rememberLatest()
    val thisDevice = remember { actions.state.value.ownDeviceId }

    suspend fun loadHistory() {
        val result = actions.jobs { e, t -> roadmapHistory(e, t, id) } ?: return
        history = (result as? ApiResult.Success)?.value
        historyFailed = result !is ApiResult.Success
    }

    suspend fun load() {
        val (s, p) = coroutineScope {
            val s = async { actions.jobs { e, t -> roadmap(e, t, id) } }
            val p = async { actions.jobs { e, t -> roadmapProposals(e, t, id) } }
            s.await() to p.await()
        }
        if (s == null || p == null) return
        if (s is ApiResult.Success) {
            snap = s.value
            failure = null
        } else if (snap == null) {
            failure = jobFailureOf(s)
        }
        proposals = (p as? ApiResult.Success)?.value
        if (tab == "history") historyLatest.launch { loadHistory() }
    }
    // On open and on every return to the app (desktop reloads on focus); never over a write.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!busy) latest.launch { load() } }
    LaunchedEffect(tab) { if (tab == "history") historyLatest.launch { loadHistory() } }

    /** One write on [revision] (default: the one on screen); Core answers the whole roadmap.
     *  After any failure the page reloads and explains. [after] gets whether it was saved. */
    fun write(change: RoadmapWrite, revision: Long? = null, after: (Boolean) -> Unit = {}) {
        val current = snap ?: return
        if (busy) return
        val before = revision ?: current.roadmap.revision
        busy = true
        notice = ""
        latest.launch {
            val ok = try {
                val result = actions.jobs { e, t -> writeRoadmap(e, t, id, before, change) } ?: return@launch
                (result as? ApiResult.Success)?.value?.let { snap = it }
                load()
                if (result !is ApiResult.Success) notice = roadmapWriteFailureText(result, (snap?.roadmap?.revision ?: before) != before)
                result is ApiResult.Success
            } finally {
                busy = false
            }
            after(ok)
        }
    }

    fun decide(proposal: Proposal, accept: Boolean) {
        if (busy || snap == null) return
        busy = true
        notice = ""
        latest.launch {
            try {
                val result = actions.jobs { e, t -> decideProposal(e, t, id, proposal.id, accept) } ?: return@launch
                if (result is ApiResult.Success) {
                    if (accept) result.value?.let { snap = it }
                    notice = if (accept) "Proposal applied." else "Proposal rejected."
                } else {
                    notice = proposalFailureText(result)
                }
                load()
            } finally {
                busy = false
            }
        }
    }

    fun openSheet(itemId: String) {
        snap?.let { sheet = ItemSheet(itemId, it.roadmap.revision, ++sheetKey, "") }
    }

    /** Close after a write; after a failure, reopen on Core's state with the notice inside
     *  the editor (or on the page when the item is gone). */
    fun afterSheetWrite(ok: Boolean) {
        val s = sheet ?: return
        val current = snap
        if (ok || current == null || current.items.none { it.id == s.itemId }) {
            sheet = null
            return
        }
        sheet = ItemSheet(s.itemId, current.roadmap.revision, ++sheetKey, notice)
        notice = ""
    }

    val current = snap
    val shownSheet = sheet
    val sheetItem = shownSheet?.let { s -> current?.items?.firstOrNull { it.id == s.itemId } }
    // A reload without the open item (deleted elsewhere) closes the editor and says so. During
    // a write (busy) afterSheetWrite handles the editor, own deletes too.
    LaunchedEffect(shownSheet, current, busy) {
        if (shownSheet != null && sheetItem == null && current != null && !busy) {
            sheet = null
            notice = "This item was deleted elsewhere."
        }
    }

    if (shownSheet != null && sheetItem != null && current != null) {
        BackHandler { sheet = null }
        key(shownSheet.key) {
            ItemEditor(
                sheetItem, current.items, current.roadmap.phases, busy, shownSheet.notice,
                onSave = { patch -> write(RoadmapWrite.UpdateItem(shownSheet.itemId, patch), shownSheet.revision, ::afterSheetWrite) },
                onRemove = { write(RoadmapWrite.DeleteItem(shownSheet.itemId), shownSheet.revision, ::afterSheetWrite) },
                onClose = { sheet = null },
            )
        }
        return
    }

    val archived = current?.roadmap?.archivedAt != null
    PushedPage(
        current?.roadmap?.title ?: "Roadmap", current?.roadmap?.description, back,
        trailing = { GhostButton("Refresh", { latest.launch { load() } }, enabled = !busy) },
    ) {
        if (archived) StatusBadge("Archived")
        // Announced once when it appears or changes, like desktop focusing it.
        if (notice.isNotEmpty()) Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        val failed = failure
        when {
            failed != null -> Unavailable(
                "This roadmap", JvIcon.ALERT, kind = UnavailableKind.ERROR, detail = roadmapFailureText(failed),
                onRetry = { latest.launch { load() } },
            )
            current == null -> Skeleton("Loading the roadmap…")
            else -> {
                if (!archived) {
                    Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                        GhostButton("Edit", { editError = ""; editing = true }, enabled = !busy && !editing)
                        GhostButton("Archive", { archiving = true }, enabled = !busy, danger = true)
                    }
                }
                if (editing) {
                    RoadmapForm("Edit roadmap", current.roadmap, current.items, busy, editError, "Save", onCancel = { editing = false }) { title, description, phasesText ->
                        val phases = parsePhases(phasesText)
                        val issue = roadmapIssue(title, description, phases)
                        val patch = if (issue == null && phases != null) roadmapPatch(current.roadmap, title, description, phases) else null
                        editError = issue.orEmpty()
                        if (issue == null) {
                            if (patch == null) editing = false else write(RoadmapWrite.Update(patch)) { ok -> if (ok) editing = false }
                        }
                    }
                }
                val counts = tally(current.items)
                if (counts.isNotEmpty()) TallyChips(counts)
                val pending = proposals
                Segmented(
                    listOf("items" to "Items", "proposals" to if (pending.isNullOrEmpty()) "Proposals" else "Proposals (${pending.size})", "history" to "History"),
                    tab, { tab = it },
                )
                when (tab) {
                    "items" -> ItemsTab(current, archived, busy, counts, ::openSheet) { title, phase, done ->
                        write(RoadmapWrite.CreateItem(newItem(title, phase))) { ok -> if (ok) done() }
                    }
                    "proposals" -> when {
                        pending == null -> Unavailable(
                            "Proposals", JvIcon.ALERT, kind = UnavailableKind.ERROR, detail = "Could not load proposals.",
                            onRetry = if (busy) null else ({ latest.launch { load() } }),
                        )
                        pending.isEmpty() -> ListSection(null) {
                            EmptyState("No pending proposals. When Jarvis suggests changes, they wait here for you.", compact = true)
                        }
                        else -> pending.forEach { p -> ProposalCard(p, current, archived, busy) { accept -> decide(p, accept) } }
                    }
                    else -> HistoryTab(history, historyFailed, thisDevice) { historyLatest.launch { loadHistory() } }
                }
            }
        }
    }

    if (archiving) {
        AlertDialog(
            onDismissRequest = { if (!busy) archiving = false },
            title = { Text("Archive this roadmap?") },
            text = { Text("It leaves your list and can no longer be changed. Core keeps it and its history; nothing is deleted.") },
            dismissButton = { TextButton(onClick = { archiving = false }, enabled = !busy) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        write(RoadmapWrite.Archive) { ok ->
                            archiving = false
                            if (ok) back()
                        }
                    },
                    enabled = !busy,
                ) { Text("Archive", color = JvTheme.colors.dangerText) }
            },
        )
    }
}

/** What the roadmap waits on, the status filter, the add-item form and the phases with their
 *  items. [add] gets the title, the phase and what to do once it is saved. */
@Composable
private fun ItemsTab(
    snap: RoadmapSnapshot, archived: Boolean, busy: Boolean, counts: List<Pair<RoadmapStatus, Int>>, open: (String) -> Unit,
    add: (String, String?, () -> Unit) -> Unit,
) {
    val items = snap.items
    var filter by rememberSaveable { mutableStateOf<RoadmapStatus?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var newPhase by remember { mutableStateOf<String?>(null) }
    var addError by remember { mutableStateOf("") }
    // A phase Core no longer has falls back to none.
    val phase = newPhase?.takeIf { it in snap.roadmap.phases }

    val waiting = waitingOn(items)
    if (waiting.isNotEmpty()) {
        ListSection("Waiting on") {
            waiting.forEachIndexed { i, w ->
                if (i > 0) ListDivider()
                WaitingRow(w) { open(w.item.id) }
            }
        }
    }

    SectionLabel("Show status", Modifier.padding(horizontal = JvSpace.xs))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${items.size}") })
        RoadmapStatus.entries.forEach { s ->
            val count = counts.firstOrNull { it.first == s }?.second ?: 0
            FilterChip(selected = filter == s, onClick = { filter = s }, label = { Text("${s.label} $count") }, enabled = count > 0 || filter == s)
        }
    }

    if (!archived) {
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                Field("New item title", newTitle, { newTitle = it.take(RoadmapLimits.ITEM_TITLE); addError = "" }, error = addError.ifEmpty { null }, singleLine = true)
                Picker("Phase for the new item", listOf<Pair<String?, String>>(null to "No phase") + snap.roadmap.phases.map { it to it }, phase, { newPhase = it })
                if (addError.isNotEmpty()) ErrorText(addError)
                ActionButton("Add item", {
                    val title = newTitle.trim()
                    addError = when {
                        !validText(title, RoadmapLimits.ITEM_TITLE) -> "The title must be 1 to 200 characters on one line."
                        items.size >= RoadmapLimits.ITEMS -> "A roadmap holds at most 300 items."
                        else -> ""
                    }
                    if (addError.isEmpty()) add(title, phase) { newTitle = "" }
                }, enabled = newTitle.isNotBlank() && !busy)
            }
        }
    }

    if (items.isEmpty()) {
        ListSection(null) { EmptyState("No items yet. Add the first one above.", compact = true) }
        return
    }
    val shown = if (filter == null) items else items.filter { it.status == filter }
    val byId = items.associateBy { it.id }
    // The first phase with open work is "now".
    val now = groupByPhase(snap.roadmap.phases, items).firstOrNull { g -> g.phase != null && g.items.any { !it.status.done } }?.phase
    groupByPhase(snap.roadmap.phases, shown).forEach { g ->
        Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Row(
                Modifier.padding(horizontal = JvSpace.xs).semantics(mergeDescendants = true) { heading() },
                horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(g.phase ?: "No phase")
                if (g.phase != null && g.phase == now) StatusBadge("Now", tone = Tone.OK)
                MutedText(plural(g.items.size, "item"))
            }
            ListSection(null) {
                if (g.items.isEmpty()) EmptyState(if (filter == null) "Nothing here yet." else "Nothing with this status.", compact = true)
                g.items.forEachIndexed { i, item ->
                    if (i > 0) ListDivider()
                    val unmet = item.dependsOn.count { d -> byId[d]?.status?.done == false }
                    ItemRow(item, unmet) { open(item.id) }
                }
            }
        }
    }
}

@Composable
private fun WaitingRow(waiting: Waiting, open: () -> Unit) {
    val item = waiting.item
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit item", onClick = open).padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xxs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (item.status == RoadmapStatus.BLOCKED) StatusBadge("Blocked", tone = Tone.ERROR)
        }
        if (item.status == RoadmapStatus.BLOCKED && firstLine(item.notes).isNotEmpty()) MutedText(firstLine(item.notes))
        if (waiting.unmet.isNotEmpty()) MutedText("waits on ${waiting.unmet.joinToString(", ") { it.title }}")
    }
}

@Composable
private fun ItemRow(item: RoadmapItem, unmet: Int, open: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit item", onClick = open).padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            StatusBadge(item.status.label, tone = statusTone(item.status))
        }
        item.autoStatus?.takeIf { it != item.status }?.let { StatusBadge("auto: ${it.label}") }
        val meta = listOfNotNull(
            "waits on $unmet".takeIf { unmet > 0 },
            *item.links.map { l -> linkLabel(l) + (l.state?.let { " ($it)" } ?: "") }.toTypedArray(),
            firstLine(item.notes).ifEmpty { null },
        )
        if (meta.isNotEmpty()) MutedText(meta.joinToString(" · "))
    }
}

/** One pending Jarvis proposal: each operation as before → after, then Reject and Accept. */
@Composable
private fun ProposalCard(proposal: Proposal, snap: RoadmapSnapshot, archived: Boolean, busy: Boolean, decide: (Boolean) -> Unit) {
    ListSection(null) {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            Text("Proposal from Jarvis", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            MutedText("made ${zonedText(proposal.createdAt)} · expires ${zonedText(proposal.expiresAt)}")
            if (proposal.baseRevision != snap.roadmap.revision) StatusBadge("Made on an older version", tone = Tone.WARN)
            proposal.rationale?.let { Text("Jarvis's reason: $it", style = MaterialTheme.typography.bodyMedium) }
            proposal.ops.map { describeOp(it, snap.items) }.forEach { op ->
                Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(if (op.create) "New item" else "Change", tone = if (op.create) Tone.OK else Tone.IDLE)
                        Text(op.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    }
                    op.rows.forEach { row ->
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                            Text(row.field, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
                            Text("Before: ${row.before}", color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                            Text("After: ${row.after}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (!archived) {
                Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("Reject", { decide(false) }, enabled = !busy)
                    ActionButton("Accept", { decide(true) }, Modifier.weight(1f), enabled = !busy)
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(history: List<HistoryEntry>?, failed: Boolean, thisDevice: String?, retry: () -> Unit) {
    when {
        failed -> Unavailable("History", JvIcon.ALERT, kind = UnavailableKind.ERROR, detail = "Could not load the history.", onRetry = retry)
        history == null -> Skeleton("Loading history…")
        else -> {
            ActivityList(
                "Changes",
                history.map { c ->
                    ActivityItem(
                        c.id,
                        when (c.author) { Author.OWNER -> JvIcon.USER; Author.JARVIS -> JvIcon.SPARK; Author.GITHUB -> JvIcon.BRANCH },
                        describeChange(c), c.deviceId?.let { if (it == thisDevice) "this device" else "device ${it.take(8)}" }, zonedText(c.at),
                        tone = if (c.author == Author.GITHUB) Tone.OK else Tone.IDLE, toneLabel = c.author.label,
                    )
                },
                "No changes yet.",
            )
            if (history.isNotEmpty()) MutedText("The 50 newest changes.", Modifier.padding(horizontal = JvSpace.xs))
        }
    }
}

/** One item (desktop ItemSheet): title, status, phase, notes, what it depends on and its
 *  links. Mounted fresh per item and revision; the draft is compared with the item as opened,
 *  so a change made elsewhere never turns into an edit (saving then meets Core's 409). */
@Composable
private fun ItemEditor(
    item: RoadmapItem, items: List<RoadmapItem>, phases: List<String>, busy: Boolean, notice: String,
    onSave: (JsonObject) -> Unit, onRemove: () -> Unit, onClose: () -> Unit,
) {
    val opened = remember { item }
    var draft by remember { mutableStateOf<ItemDraft>(toDraft(item)) }
    var linkText by remember { mutableStateOf("") }
    var linkHint by remember { mutableStateOf(LinkKind.PR) }
    var linkError by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val patch = itemPatch(opened, draft)
    val issue = draftIssue(draft)
    val options = remember(items) { dependencyOptions(items, item.id) }
    val byId = remember(items) { items.associateBy { it.id } }
    // A phase Core no longer has still shows, so the choice never hides a value.
    val phaseChoices = if (item.phase != null && item.phase !in phases) phases + item.phase else phases
    val uris = LocalUriHandler.current

    fun toggleDep(depId: String, on: Boolean) {
        val deps = draft.dependsOn.filter { it != depId }
        draft = draft.copy(dependsOn = if (on) deps + depId else deps)
    }

    fun addTypedLink() {
        when (val parsed = parseLink(linkText, linkHint)) {
            is ParsedLink.Bad -> linkError = parsed.error
            is ParsedLink.Ok -> {
                val next = addLink(draft.links, parsed.link)
                if (next == null) {
                    linkError = if (draft.links.size >= RoadmapLimits.LINKS) "At most 10 links." else "That link is already there."
                } else {
                    draft = draft.copy(links = next)
                    linkText = ""
                    linkError = ""
                }
            }
        }
    }

    PushedPage(item.title, null, onClose) {
        if (notice.isNotEmpty()) {
            Text(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = JvTheme.colors.negativeText, style = MaterialTheme.typography.bodyMedium)
        }
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                Field("Title", draft.title, { draft = draft.copy(title = it.take(RoadmapLimits.ITEM_TITLE)) }, error = issue?.takeIf { it.startsWith("The title") }, singleLine = true)
                Picker("Status", RoadmapStatus.entries.map { it to it.label }, draft.status, { draft = draft.copy(status = it) })
                Picker("Phase", listOf<Pair<String?, String>>(null to "No phase") + phaseChoices.map { it to it }, draft.phase, { draft = draft.copy(phase = it) })
                item.autoStatus?.let { auto ->
                    Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                        MutedText("GitHub last derived")
                        StatusBadge(auto.label, tone = statusTone(auto))
                        if (auto != draft.status) MutedText("· your status wins")
                    }
                }
                val blocked = draft.status == RoadmapStatus.BLOCKED
                Field(
                    if (blocked) "Notes: what is it waiting on?" else "Notes", draft.notes, { draft = draft.copy(notes = it.take(RoadmapLimits.NOTES)) },
                    error = issue?.takeIf { it.startsWith("Notes") }, minLines = 4,
                    help = if (blocked) "Plain text. The first line shows as the reason under Waiting on." else "Plain text.",
                )
            }
        }

        ListSection("Depends on") {
            if (options.isEmpty() && draft.dependsOn.isEmpty()) EmptyState("No other items to depend on.", compact = true)
            options.forEach { o ->
                val on = o.id in draft.dependsOn
                DependencyRow(o.title, o.status, on, on || draft.dependsOn.size < RoadmapLimits.DEPENDS_ON) { toggleDep(o.id, it) }
            }
            // A dependency Core still has but the picker would no longer offer.
            draft.dependsOn.filter { d -> options.none { it.id == d } }.forEach { d ->
                DependencyRow(byId[d]?.title ?: "An item that no longer exists", null, checked = true, enabled = true) { toggleDep(d, false) }
            }
        }

        ListSection("Links") {
            draft.links.forEachIndexed { i, link ->
                if (i > 0) ListDivider()
                val href = linkHref(link)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = JvSpace.lg, vertical = JvSpace.sm),
                    horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        linkLabel(link),
                        Modifier.weight(1f).then(
                            // Validated https targets only; without a browser openUri throws and the tap does nothing.
                            if (href != null) {
                                Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                                    .clickable(role = Role.Button, onClickLabel = "Open link") { runCatching { uris.openUri(href) } }
                            } else {
                                Modifier
                            },
                        ),
                        color = if (href != null) JvTheme.colors.accentText else JvTheme.colors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    link.state?.let { StatusBadge("GitHub: $it") }
                    val label = linkLabel(link)
                    GhostButton(
                        "Remove", { draft = draft.copy(links = draft.links.filterIndexed { j, _ -> j != i }) },
                        Modifier.semantics { contentDescription = "Remove $label" },
                    )
                }
            }
            if (draft.links.isNotEmpty()) ListDivider()
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                Segmented(listOf("pr" to "PR", "issue" to "Issue"), linkHint.wire, { linkHint = if (it == "issue") LinkKind.ISSUE else LinkKind.PR })
                Field(
                    "Link to add", linkText, { linkText = it; linkError = "" }, error = linkError.ifEmpty { null }, singleLine = true,
                    help = "GitHub link, owner/name#12 (as the kind above), owner/name@v1.2.0 or https URL",
                )
                if (linkError.isNotEmpty()) ErrorText(linkError)
                GhostButton("Add link", ::addTypedLink, enabled = linkText.isNotBlank() && draft.links.size < RoadmapLimits.LINKS)
            }
        }

        if (issue != null) ErrorText(issue)
        ActionButton("Save", { if (patch != null && issue == null && !busy) onSave(patch) }, enabled = patch != null && issue == null && !busy)
        if (confirmDelete) {
            Text("Delete this item? Items that depend on it no longer will.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                GhostButton("Keep", { confirmDelete = false })
                GhostButton("Delete", onRemove, enabled = !busy, danger = true)
            }
        } else {
            GhostButton("Delete item", { confirmDelete = true }, enabled = !busy, danger = true)
        }
    }
}

@Composable
private fun DependencyRow(title: String, status: RoadmapStatus?, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = JvSpace.lg),
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        status?.let { StatusBadge(it.label, tone = statusTone(it)) }
    }
}

