package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hawkeynl.jarvis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.ConversationMessage

@Composable
internal fun ChatScreen(state: JarvisUiState, actions: JarvisViewModel) {
    var draft by remember(state.conversationId) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().testTag("chat")) {
        Text(
            state.conversationTitle,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.voiceEnabled) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.voiceStatus ?: "Lokale spraak ingeschakeld", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = actions::stopSpeaking) { Text("Stop spraak") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) item { Text("Waarmee kan ik helpen?") }
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
                placeholder = { Text("Bericht aan Jarvis") },
                maxLines = 5,
            )
            Button(
                onClick = {
                    val message = draft
                    draft = ""
                    actions.send(message)
                },
                enabled = draft.isNotBlank() && !state.busy,
            ) { Text("Stuur") }
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val jarvis = message.role == "assistant" || message.role == "jarvis"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(if (jarvis) "JARVIS" else "JIJ", color = MaterialTheme.colorScheme.primary)
            Text(message.content)
        }
    }
}
