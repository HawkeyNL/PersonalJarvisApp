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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.ConversationMessage

@Composable
internal fun ChatScreen(state: JarvisUiState, actions: JarvisViewModel) {
    var draft by remember(state.conversationId) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().testTag("chat")) {
        ScreenHeader(state.conversationTitle, { actions.navigate(Screen.HUB) }) {
            GhostButton("New", actions::newConversation)
        }
        if (state.voiceEnabled) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.voiceStatus ?: "Local speech on", Modifier.weight(1f), color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                GhostButton("Stop speech", actions::stopSpeaking)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    JvOrb(120.dp, if (state.busy) Mood.THINKING else Mood.IDLE)
                    Text("How can I help?", Modifier.padding(top = 20.dp), color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(state.messages) { message -> MessageBubble(message) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                size = 52.dp,
                enabled = canSend,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val jarvis = message.role == "assistant" || message.role == "jarvis"
    val c = JvTheme.colors
    val shape = JvShapes.lg
    Box(Modifier.fillMaxWidth(), contentAlignment = if (jarvis) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .then(if (jarvis) Modifier.shadowBorder(shape) else Modifier)
                .clip(shape)
                .background(if (jarvis) c.bgSurface else c.bgSelected)
                .padding(JvSpace.md),
        ) {
            Text(if (jarvis) "Jarvis" else "You", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(message.content, Modifier.padding(top = JvSpace.xs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
