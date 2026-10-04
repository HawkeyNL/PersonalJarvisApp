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
fun JarvisApp(
    state: JarvisUiState,
    actions: JarvisViewModel,
    onRequestBiometric: () -> Unit,
    onInstallUpdate: () -> Unit,
    modelControls: com.hawkeynl.jarvis.security.ModelControlService? = null,
) {
    when {
        state.locked -> AppLockScreen(
            message = state.biometricMessage,
            onRetry = onRequestBiometric,
            onReset = actions::resetDevice,
        )
        state.endpoint == null || !state.authenticated -> OnboardingScreen(state, actions)
        else -> AuthenticatedShell(state, actions, onInstallUpdate, modelControls)
    }
}

@Composable
private fun AuthenticatedShell(
    state: JarvisUiState,
    actions: JarvisViewModel,
    onInstallUpdate: () -> Unit,
    modelControls: com.hawkeynl.jarvis.security.ModelControlService?,
) {
    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("bottom-navigation")) {
                listOf(
                    AppTab.CHAT to ("●" to "Chat"),
                    AppTab.CONVERSATIONS to ("≡" to "Gesprekken"),
                    AppTab.SETTINGS to ("⚙" to "Instellingen"),
                ).forEach { (tab, presentation) ->
                    NavigationBarItem(
                        selected = state.selectedTab == tab,
                        onClick = { actions.selectTab(tab) },
                        icon = { Text(presentation.first) },
                        label = { Text(presentation.second) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(state.connection, actions::checkConnection)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            when (state.selectedTab) {
                AppTab.CHAT -> ChatScreen(state, actions)
                AppTab.CONVERSATIONS -> ConversationsScreen(state, actions)
                AppTab.SETTINGS -> SettingsScreen(state, actions, onInstallUpdate, modelControls)
            }
        }
    }
}

@Composable
internal fun ConnectionLine(connection: ConnectionState) {
    Text(
        when (connection) {
            ConnectionState.NotConfigured -> "Nog niet ingesteld"
            ConnectionState.Checking -> "Verbinding controleren…"
            is ConnectionState.Reachable -> "Verbonden · ${connection.status}"
            is ConnectionState.Unreachable -> "Niet bereikbaar · ${connection.reason.name.lowercase()}"
            is ConnectionState.Rejected -> "Home Node antwoordde met HTTP ${connection.status}"
        },
        color = when (connection) {
            is ConnectionState.Reachable -> MaterialTheme.colorScheme.primary
            is ConnectionState.Unreachable, is ConnectionState.Rejected -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun ConnectionBanner(connection: ConnectionState, retry: () -> Unit) {
    if (connection is ConnectionState.Reachable) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f)) { ConnectionLine(connection) }
        OutlinedButton(onClick = retry) { Text("Opnieuw") }
    }
    HorizontalDivider()
}
