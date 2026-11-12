package com.hawkeynl.jarvis.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hawkeynl.jarvis.chat.WebAnswer
import com.hawkeynl.jarvis.chat.isWebModel
import com.hawkeynl.jarvis.chat.splitSources
import com.hawkeynl.jarvis.network.ConversationMessage
import com.hawkeynl.jarvis.network.PhotoMeta
import kotlinx.coroutines.launch

/** Your messages in an accent bubble with its tail corner bottom-end (D-Home). */
private val OWN_BUBBLE = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)

/** The chat (D-Home): header with the conversation title, the messages, a thinking line
 *  while Jarvis answers and the composer with its photos. */
@Composable
internal fun ChatScreen(state: JarvisUiState, actions: JarvisViewModel) {
    var draft by remember(state.conversationId) { mutableStateOf("") }
    // The full-screen view shows the open conversation's photos only.
    var viewing by remember(state.conversationId) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(state.conversationId) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember(state.conversationId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) actions.attachPhotos(uris)
    }
    // A message Core refused comes back, unless something new was typed since.
    LaunchedEffect(state.returnedDraft) {
        state.returnedDraft?.let { if (draft.isBlank()) draft = it; actions.draftReturned() }
    }
    val c = JvTheme.colors
    Column(Modifier.fillMaxSize().imePadding().testTag("chat")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = JvSpace.lg, vertical = JvSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JvSpace.md),
        ) {
            CircleIconButton(JvIcon.ARROW_LEFT, "Back", { actions.navigate(Screen.HUB) })
            Text(
                state.conversationTitle, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (state.conversationId != null) GhostButton(
                "Delete", { confirmDelete = true },
                Modifier.semantics { contentDescription = "Delete ${state.conversationTitle.ifEmpty { "this conversation" }}" },
            )
            GhostButton("New", actions::newConversation)
        }
        deleteError?.let { ErrorText(it, Modifier.fillMaxWidth().padding(horizontal = JvSpace.lg)) }
        ListDivider()
        if (state.voiceEnabled) {
            Row(Modifier.fillMaxWidth().padding(horizontal = JvSpace.lg), verticalAlignment = Alignment.CenterVertically) {
                Text(state.voiceStatus ?: "Local speech on", Modifier.weight(1f), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                GhostButton("Stop speech", actions::stopSpeaking)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = JvSpace.lg, vertical = JvSpace.lg),
            verticalArrangement = Arrangement.spacedBy(JvSpace.lg),
        ) {
            if (state.messages.isEmpty() && !state.busy) item {
                EmptyState(
                    "How can I help?",
                    body = "Ask Jarvis anything. Conversations are stored on your Home Node and follow you across devices.",
                    icon = JvIcon.CHAT,
                )
            }
            items(state.messages) { message -> Message(message, { actions.photo(it) }, state.removedPhotos) { viewing = it } }
            state.researchOffer?.takeIf { !state.busy && it.conversationId == state.conversationId && state.chips.isEmpty() }?.question?.let { question ->
                item {
                    // Only this click sends a web search; the shown question is all that leaves.
                    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                        GhostButton("Search the web", actions::searchWeb)
                        Text("Sends only “$question” to your subscription provider, which searches the web.",
                            color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.busy) item {
                val searching = state.webSearchRun?.let { run -> state.messages.any { it.id == "run:$run" } } == true
                Thinking(if (searching) "Searching the web…" else "Jarvis is thinking…")
            }
        }
        Column(Modifier.fillMaxWidth().background(c.bgSurface)) {
            ListDivider()
            if (state.chips.isNotEmpty()) Chips(state.chips, actions::removeChip)
            Column(Modifier.padding(horizontal = JvSpace.md), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
                state.chips.forEach { chip -> chip.error?.let { ErrorText(it) } }
                state.chipNotice?.let { MutedText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                // Said once, until a photo was sent.
                if (state.chips.isNotEmpty() && !state.photosDisclosed) MutedText(PHOTO_DISCLOSURE)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = JvSpace.md, vertical = JvSpace.sm),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
            ) {
                CircleIconButton(
                    JvIcon.PHOTO, "Attach photos",
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = state.chips.size < MAX_PHOTOS,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).testTag("composer"),
                    placeholder = { Text("Message Jarvis") },
                    maxLines = 5,
                    shape = JvShapes.md,
                )
                val sendable = canSend(draft, state.chips, state.busy)
                CircleIconButton(
                    JvIcon.SEND, if (uploading(state.chips)) "Send (waiting for photos)" else "Send",
                    onClick = { if (actions.send(draft)) draft = "" },
                    enabled = sendable,
                    tint = c.textOnAccent,
                    background = if (sendable) c.accentSolid else c.bgSunken,
                )
            }
        }
    }
    viewing?.let { id -> PhotoViewer(id, state.photoMeta[id], id in state.removedPhotos, actions) { viewing = null } }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Text("Delete this conversation?") },
        text = {
            Text(
                "It is removed from your Home Node for good." +
                    if (state.messages.any { it.attachments.isNotEmpty() }) " Its photos go too, kept ones included." else "",
            )
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !deleting) { Text("Cancel") } },
        confirmButton = {
            TextButton(
                onClick = {
                    deleting = true
                    scope.launch {
                        val failure = actions.deleteConversation()
                        deleting = false
                        confirmDelete = false
                        deleteError = failure
                    }
                },
                enabled = !deleting,
            ) { Text("Delete for good", color = c.dangerText) }
        },
    )
}

/** The composer's photos: a preview each, its state, and ✕ to remove it. */
@Composable
private fun Chips(chips: List<PhotoChip>, remove: (Int) -> Unit) {
    val c = JvTheme.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = JvSpace.md, vertical = JvSpace.sm)
            .semantics { contentDescription = "Photos to send" },
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        chips.forEach { chip ->
            Box(Modifier.size(72.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(JvShapes.md).background(c.bgSunken).semantics(mergeDescendants = true) {
                        contentDescription = when (chip.state) {
                            ChipState.PREPARING -> "Photo, preparing"
                            ChipState.UPLOADING -> "Photo, uploading"
                            ChipState.READY -> "Photo, ready to send"
                            ChipState.FAILED -> "Photo, failed: ${chip.error.orEmpty()}"
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    chip.preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    when (chip.state) {
                        ChipState.PREPARING, ChipState.UPLOADING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        ChipState.FAILED -> JvIconView(JvIcon.ALERT, tint = c.dangerText)
                        ChipState.READY -> Unit
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).minimumInteractiveComponentSize()
                        .clickable(role = Role.Button, onClickLabel = "Remove photo") { remove(chip.key) }
                        .semantics { contentDescription = "Remove photo" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "✕", Modifier.size(22.dp).clip(CircleShape).background(c.bgSurface).wrapContentSize(),
                        color = c.textPrimary, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** A message's photos in a row; a loaded one opens full screen. */
@Composable
private fun Photos(ids: List<String>, load: suspend (String) -> PhotoResult, removed: Set<String>, open: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        ids.forEach { id -> PhotoThumb(id, load, id in removed, open) }
    }
}

@Composable
private fun PhotoThumb(id: String, load: suspend (String) -> PhotoResult, removed: Boolean, open: (String) -> Unit) {
    val c = JvTheme.colors
    // Loaded again once it is removed, which then answers without a download.
    val result by produceState<PhotoResult?>(null, id, removed) { value = load(id) }
    Box(
        Modifier.size(140.dp).clip(JvShapes.md).background(c.bgSunken)
            .then(if (result is PhotoResult.Image) Modifier.clickable(role = Role.Button, onClickLabel = "Open photo") { open(id) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (val photo = result) {
            null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            is PhotoResult.Image -> Image(photo.bitmap, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            PhotoResult.Removed -> Text("Photo removed", Modifier.padding(JvSpace.sm), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            PhotoResult.Unavailable -> Text("Photo unavailable", Modifier.padding(JvSpace.sm), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Message(message: ConversationMessage, photo: suspend (String) -> PhotoResult, removed: Set<String>, open: (String) -> Unit) {
    val c = JvTheme.colors
    if (message.role == "assistant" || message.role == "jarvis") {
        // Jarvis answers in plain text under a small name line, without a bubble. A web
        // answer gets a "Web" chip and its Sources as links; nothing is loaded by itself.
        val web = isWebModel(message.model)
        val answer = if (web) splitSources(message.content) else WebAnswer(message.content, emptyList())
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Box(Modifier.size(24.dp).clip(JvShapes.sm).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                    JvIconView(JvIcon.SPARK, tint = c.accentText, size = 14.dp)
                }
                Text("Jarvis", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                if (web) Text(
                    "Web", Modifier.clip(JvShapes.pill).background(c.accentSubtle).padding(horizontal = JvSpace.sm, vertical = 2.dp),
                    color = c.accentText, style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(answer.body, style = MaterialTheme.typography.bodyLarge)
            if (message.attachments.isNotEmpty()) Photos(message.attachments, photo, removed, open)
            if (answer.sources.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                Text("Sources", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                val uris = LocalUriHandler.current
                // Validated, normalized https URLs only (WebAnswer); opened in the browser on tap.
                // Without a browser openUri throws: the tap then does nothing. A card shows the
                // plain title (never Markdown), then the host and a short path.
                answer.sources.forEach { source ->
                    Column(
                        Modifier
                            .widthIn(max = 320.dp)
                            .clip(JvShapes.md)
                            .background(c.bgSunken)
                            .clickable(role = Role.Button, onClickLabel = "Open source") { runCatching { uris.openUri(source.url) } }
                            .padding(horizontal = JvSpace.md, vertical = JvSpace.sm),
                        verticalArrangement = Arrangement.spacedBy(JvSpace.xxs),
                    ) {
                        source.title?.let { Text(it, color = c.accentText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(
                            source.host + source.shortPath, color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
            if (message.attachments.isNotEmpty()) Photos(message.attachments, photo, removed, open)
            // A photo alone is a message too: then there is no text bubble.
            if (message.content.isNotEmpty() || message.attachments.isEmpty()) Text(
                message.content,
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(OWN_BUBBLE)
                    .background(c.accentSolid)
                    .semantics { contentDescription = "You: ${message.content}" }
                    .padding(horizontal = JvSpace.md, vertical = JvSpace.sm),
                color = c.textOnAccent,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** "Jarvis is thinking…" (or "Searching the web…") while an answer is on its way; announced once. */
@Composable
private fun Thinking(label: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        if (!rememberReduceMotion()) CircularProgressIndicator(Modifier.size(16.dp), color = JvTheme.colors.textSecondary, strokeWidth = 2.dp)
        Text(label, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** One photo full screen, to keep past its 90 days or delete (after a confirm step).
 *  Closes by itself once the photo is gone on the Home Node. */
@Composable
private fun PhotoViewer(id: String, meta: PhotoMeta?, removed: Boolean, actions: JarvisViewModel, close: () -> Unit) {
    val c = JvTheme.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val photo by produceState<PhotoResult?>(null, id) { value = actions.photo(id, full = true) }
    LaunchedEffect(removed) { if (removed) close() }
    fun act(change: suspend () -> String?) {
        if (busy) return
        busy = true
        error = null
        scope.launch { error = change(); busy = false }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bgPage).semantics { paneTitle = "Photo" }) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val shown = photo) {
                    null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    is PhotoResult.Image -> Image(shown.bitmap, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    PhotoResult.Removed -> MutedText("Photo removed")
                    PhotoResult.Unavailable -> MutedText("This photo could not be loaded.")
                }
            }
            Column(
                Modifier.fillMaxWidth().background(c.bgSurface).padding(JvSpace.lg),
                verticalArrangement = Arrangement.spacedBy(JvSpace.sm),
            ) {
                MutedText(expiryHint(meta))
                error?.let { ErrorText(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (confirming) {
                        GhostButton("Delete for good", { act { actions.deletePhoto(id) } }, enabled = !busy, danger = true)
                        GhostButton("Cancel", { confirming = false }, enabled = !busy)
                    } else {
                        val kept = meta?.saved == true
                        GhostButton(
                            if (kept) "Kept" else "Keep", { act { actions.keepPhoto(id, !kept) } },
                            Modifier.semantics { stateDescription = if (kept) "Kept" else "Not kept" },
                            enabled = !busy,
                        )
                        GhostButton("Delete", { confirming = true }, enabled = !busy)
                    }
                    Box(Modifier.weight(1f))
                    GhostButton("Close", close)
                }
            }
        }
    }
}
