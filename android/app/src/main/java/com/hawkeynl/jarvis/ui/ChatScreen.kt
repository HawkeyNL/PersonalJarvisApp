package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            if (state.busy) item { Thinking() }
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
        // Jarvis answers in plain text under a small name line, without a bubble.
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Box(Modifier.size(24.dp).clip(JvShapes.sm).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                    JvIconView(JvIcon.SPARK, tint = c.accentText, size = 14.dp)
                }
                Text("Jarvis", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text(message.content, style = MaterialTheme.typography.bodyLarge)
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

/** "Jarvis is thinking…" while an answer is on its way; announced once. */
@Composable
private fun Thinking() {
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        if (!rememberReduceMotion()) CircularProgressIndicator(Modifier.size(16.dp), color = JvTheme.colors.textSecondary, strokeWidth = 2.dp)
        Text("Jarvis is thinking…", color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
