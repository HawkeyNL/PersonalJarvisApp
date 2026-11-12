package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.chat.WebAnswer
import com.hawkeynl.jarvis.chat.isWebModel
import com.hawkeynl.jarvis.chat.splitSources
import com.hawkeynl.jarvis.network.ConversationMessage

/** Your messages in an accent bubble with its tail corner bottom-end (D-Home). */
private val OWN_BUBBLE = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)

/** The chat (D-Home): header with the conversation title, the messages, a thinking line
 *  while Jarvis answers and the composer. */
@Composable
internal fun ChatScreen(state: JarvisUiState, actions: JarvisViewModel) {
    var draft by remember(state.conversationId) { mutableStateOf("") }
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
            GhostButton("New", actions::newConversation)
        }
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
            items(state.messages) { message -> Message(message) }
            state.researchOffer?.takeIf { !state.busy && it.conversationId == state.conversationId }?.question?.let { question ->
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = JvSpace.md, vertical = JvSpace.sm),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).testTag("composer"),
                    placeholder = { Text("Message Jarvis") },
                    maxLines = 5,
                    shape = JvShapes.md,
                )
                val canSend = draft.isNotBlank() && !state.busy
                CircleIconButton(
                    JvIcon.SEND, "Send",
                    onClick = {
                        val message = draft
                        draft = ""
                        actions.send(message)
                    },
                    enabled = canSend,
                    tint = c.textOnAccent,
                    background = if (canSend) c.accentSolid else c.bgSunken,
                )
            }
        }
    }
}

@Composable
private fun Message(message: ConversationMessage) {
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
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
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
