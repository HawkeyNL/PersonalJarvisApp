package com.hawkeynl.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.security.ModelControlService

@Composable
fun JarvisApp(
    state: JarvisUiState,
    actions: JarvisViewModel,
    core: CoreViewModel,
    onRequestBiometric: () -> Unit,
    onInstallUpdate: () -> Unit,
    modelControls: ModelControlService? = null,
) {
    val signedIn = !state.locked && state.endpoint != null && state.authenticated
    // Locking or signing out drops every Core read and cancels running ones.
    LaunchedEffect(signedIn) { if (!signedIn) core.clear() }
    Box(Modifier.fillMaxSize().jvBackdrop()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when {
                state.locked -> AppLockScreen(
                    message = state.biometricMessage,
                    onRetry = onRequestBiometric,
                    onReset = actions::resetDevice,
                )
                state.endpoint == null || !state.authenticated -> OnboardingScreen(state, actions)
                else -> AuthenticatedShell(state, actions, core, onInstallUpdate, modelControls)
            }
        }
    }
}

@Composable
private fun AuthenticatedShell(
    state: JarvisUiState,
    actions: JarvisViewModel,
    core: CoreViewModel,
    onInstallUpdate: () -> Unit,
    modelControls: ModelControlService?,
) {
    val data by core.state.collectAsStateWithLifecycle()
    val screen = state.screen
    BackHandler(enabled = screen != Screen.HUB) { actions.navigate(Screen.HUB) }
    val mood = if (state.busy) Mood.THINKING else Mood.IDLE
    val navigate = actions::navigate
    Column(Modifier.fillMaxSize()) {
        ConnectionBanner(state.connection, actions::checkConnection)
        state.error?.let { ErrorText(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.HUB -> HubScreen(state, data, mood, navigate, core::refreshHub)
                Screen.CHAT -> ChatScreen(state, actions)
                Screen.SETTINGS -> SettingsScreen(state, actions, onInstallUpdate)
                Screen.CONVERSATIONS -> ConversationsScreen(state, actions, mood)
                Screen.AGENTS -> AgentsScreen(data, mood, navigate, core::refreshAgents)
                Screen.TASKS -> TasksScreen(data, mood, navigate, core::refreshTasks, core::deny)
                Screen.INTEGRATIONS -> IntegrationsScreen(data, mood, navigate, core::refreshIntegrations)
                Screen.HEALTH -> HealthScreen(state, data, mood, navigate, core::refreshHealth, actions::checkConnection, modelControls)
                Screen.MEMORY -> MemoryScreen(mood, navigate)
                Screen.CONTEXT -> ContextScreen(data, mood, navigate, core::refreshContext)
            }
        }
        if (screen != Screen.CHAT && screen != Screen.SETTINGS) Dock(screen, navigate)
    }
}

/** One-line text of the Home Node connection check. */
fun connectionText(connection: ConnectionState): String = when (connection) {
    ConnectionState.NotConfigured -> "Not set up yet"
    ConnectionState.Checking -> "Checking the connection…"
    is ConnectionState.Reachable -> "Connected · ${connection.status}"
    is ConnectionState.Unreachable -> "Unreachable · ${connection.reason.name.lowercase()}"
    is ConnectionState.Rejected -> "Home Node answered with HTTP ${connection.status}"
}

fun connectionTone(connection: ConnectionState): Tone = when (onlineOf(connection)) {
    true -> Tone.OK
    false -> Tone.ERROR
    null -> Tone.IDLE
}

@Composable
fun ConnectionLine(connection: ConnectionState) {
    StatusDot(connectionTone(connection), connectionText(connection))
}

@Composable
private fun ConnectionBanner(connection: ConnectionState, retry: () -> Unit) {
    if (connection is ConnectionState.Reachable) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { ConnectionLine(connection) }
        GhostButton("Retry", retry)
    }
}

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        CircleIconButton(JvIcon.ARROW_LEFT, "Back to Core", onBack, Modifier.align(Alignment.CenterStart))
        Text(
            title,
            Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            style = exo(15f, FontWeight.Medium, Jv.Text0, 0.04f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.let { Box(Modifier.align(Alignment.CenterEnd)) { it() } }
    }
}
