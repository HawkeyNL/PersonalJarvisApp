package com.hawkeynl.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.security.ModelControlService
import com.hawkeynl.jarvis.security.PairingApprovalService
import com.hawkeynl.jarvis.security.AgentApprovalService
import com.hawkeynl.jarvis.security.UnlockApprovalService

/** What the root shows. The shell, with its tabs, needs an unlocked app, a
 *  Home Node and a signed-in session; anything else gets onboarding. */
internal enum class Root { LOCK, ONBOARDING, SHELL }

internal fun rootFor(state: JarvisUiState): Root = when {
    state.locked -> Root.LOCK
    state.endpoint == null || !state.authenticated -> Root.ONBOARDING
    else -> Root.SHELL
}

@Composable
fun JarvisApp(
    state: JarvisUiState,
    actions: JarvisViewModel,
    core: CoreViewModel,
    onRequestBiometric: () -> Unit,
    onInstallUpdate: () -> Unit,
    modelControls: ModelControlService? = null,
    pairingApprovals: PairingApprovalService? = null,
    unlockApprovals: UnlockApprovalService? = null,
    agentApprovals: AgentApprovalService? = null,
) {
    val root = rootFor(state)
    val signedIn = root == Root.SHELL
    // Locking or signing out drops every Core read and cancels running ones.
    LaunchedEffect(signedIn) { if (!signedIn) core.clear() }
    Box(Modifier.fillMaxSize().background(JvTheme.colors.bgPage)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (root) {
                Root.LOCK -> AppLockScreen(
                    message = state.biometricMessage,
                    onRetry = onRequestBiometric,
                    onReset = actions::resetDevice,
                )
                Root.ONBOARDING -> OnboardingScreen(state, actions)
                Root.SHELL -> AuthenticatedShell(state, actions, core, onInstallUpdate, modelControls, pairingApprovals, unlockApprovals, agentApprovals)
            }
        }
    }
    state.localEraseOffer?.let { reason -> LocalEraseDialog(reason, actions::eraseLocally, actions::keepDevice) }
}

/** Shown only when Core did not confirm revoking this device: erasing is then the user's
 *  explicit choice, never a silent fallback. */
@Composable
private fun LocalEraseDialog(reason: String, onErase: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Erase locally only?") },
        text = {
            Text(
                "$reason\n\nThis phone may stay active on your Home Node until you revoke it from another device. " +
                    "Erase locally only if you will do that.",
            )
        },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep this device") } },
        confirmButton = { TextButton(onClick = onErase) { Text("Erase locally only", color = JvTheme.colors.dangerText) } },
    )
}

@Composable
private fun AuthenticatedShell(
    state: JarvisUiState,
    actions: JarvisViewModel,
    core: CoreViewModel,
    onInstallUpdate: () -> Unit,
    modelControls: ModelControlService?,
    pairingApprovals: PairingApprovalService?,
    unlockApprovals: UnlockApprovalService?,
    agentApprovals: AgentApprovalService?,
) {
    val data by core.state.collectAsStateWithLifecycle()
    val screen = state.screen
    BackHandler(enabled = screen != Screen.HUB) { actions.navigate(backOf(screen)) }
    val navigate = actions::navigate
    Column(Modifier.fillMaxSize()) {
        ConnectionBanner(state.connection, actions::checkConnection)
        state.error?.let { ErrorText(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) }
        unlockApprovals?.let { UnlockRequests(it) }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.HUB -> HomeScreen(state, data, navigate) { core.refreshHome(); core.refreshPairingIfStale() }
                Screen.TRADING -> TradingScreen(data, core::refreshTrading)
                Screen.ACTIVITY -> ActivityScreen(data, navigate, { core.refreshActivity(); core.refreshPairingIfStale() }, agentApprovals)
                Screen.SETTINGS -> SettingsScreen(state, actions, data, onInstallUpdate, core::refreshSettings)
                Screen.CHAT -> ChatScreen(state, actions)
                Screen.CONVERSATIONS -> ConversationsScreen(state, actions)
                Screen.AGENTS -> AgentsScreen(data, navigate, core::refreshAgents)
                Screen.TASKS -> TasksScreen(data, navigate, core::refreshTasks, agentApprovals)
                Screen.INTEGRATIONS -> IntegrationsScreen(data, navigate, core::refreshIntegrations)
                Screen.HEALTH -> key(state.healthNode) {
                    HealthScreen(state, data, navigate, core::refreshHealth, actions::checkConnection, modelControls, state.healthNode)
                }
                Screen.MEMORY -> MemoryScreen(navigate)
                Screen.CONTEXT -> ContextScreen(data, navigate, core::refreshContext) { id ->
                    core.revokeDevice(id) { actions.state.value.let { !it.locked && it.authenticated } }
                }
                Screen.PAIRING_APPROVALS -> PairingApprovalsScreen(data, navigate, core::refreshPairing, core::dropPairing, pairingApprovals)
            }
        }
        // Chat keeps the whole height for the conversation and its composer.
        if (screen != Screen.CHAT) TabBar(tabOf(screen)) { navigate(it.root) }
    }
}

/** The four tabs (Material3 NavigationBar). Picking a tab opens its root, also when one
 *  of its pushed screens is showing. */
@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val c = JvTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.separator))
        NavigationBar(containerColor = c.bgSurface, contentColor = c.textSecondary, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
            for (tab in Tab.entries) {
                val (icon, label) = when (tab) {
                    Tab.JARVIS -> JvIcon.CHAT to "Jarvis"
                    Tab.TRADING -> JvIcon.TREND to "Trading"
                    Tab.ACTIVITY -> JvIcon.SHIELD to "Activity"
                    Tab.SETTINGS -> JvIcon.GEAR to "Settings"
                }
                NavigationBarItem(
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                    icon = { JvIconView(icon, size = 22.dp, tint = LocalContentColor.current) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accentText,
                        selectedTextColor = c.accentText,
                        indicatorColor = c.accentSubtle,
                        unselectedIconColor = c.textSecondary,
                        unselectedTextColor = c.textSecondary,
                    ),
                )
            }
        }
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
        CircleIconButton(JvIcon.ARROW_LEFT, "Back", onBack, Modifier.align(Alignment.CenterStart))
        Text(
            title,
            Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.let { Box(Modifier.align(Alignment.CenterEnd)) { it() } }
    }
}
