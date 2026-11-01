package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.valueOrNull
import com.hawkeynl.jarvis.security.PairingApproval
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/** The Jarvis tab (I-Home): today, what waits for you, the trading link and the
 *  Jarvis pages, with the "Ask Jarvis" bar into chat at the bottom. */
@Composable
fun HomeScreen(state: JarvisUiState, data: CoreData, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val now = remember(state.conversations, data.loadedAt) { LocalDateTime.now() }
    val millis = remember(now) { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TabTitle(greeting(now.hour), DAY.format(now))
            WaitingCard(data) { onNavigate(Screen.ACTIVITY) }
            HomeCard({ onNavigate(Screen.TRADING) }) {
                val (tone, label) = ibkrState(data.ibkr, data.loadedAt)
                Text("Trading", color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
                StatusDot(tone, "Interactive Brokers · $label")
                MutedText(
                    data.ibkr.valueOrNull?.let { "${it.positions.size} positions · read-only" }
                        ?: "Read-only link. Jarvis never places orders.",
                )
            }
            ListSection("Jarvis") {
                linksOf(Tab.JARVIS).filter { it != Screen.CHAT }.forEachIndexed { i, screen ->
                    if (i > 0) ListDivider()
                    when (screen) {
                        Screen.CONVERSATIONS -> conversationsCard(state.conversations.map { it.updated_at }, millis).let {
                            NavRow(JvIcon.CHAT, "Conversations", it.detail, { onNavigate(screen) }, it.tone)
                        }
                        Screen.MEMORY -> NavRow(JvIcon.MEMORY, "Memory", "Not yet available · needs Core support", { onNavigate(screen) }, Tone.IDLE)
                        else -> error("No Home row for $screen")
                    }
                }
            }
        }
        CommandBar { onNavigate(Screen.CHAT) }
    }
}

/** Agent actions and pairing requests waiting for the owner; opens Activity. */
@Composable
private fun WaitingCard(data: CoreData, onClick: () -> Unit) {
    val actions = data.pending.valueOrNull?.pending
    val pairing = data.pairing.valueOrNull?.requests.orEmpty()
    val lines = remember(actions, pairing) {
        actions.orEmpty().map { pendingTitle(it) } + pairing.map { "${PairingApproval.displayName(it.device_name, 40)} wants to pair" }
    }
    HomeCard(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(JvShapes.md).background(JvTheme.colors.accentSubtle), contentAlignment = Alignment.Center) {
                JvIconView(JvIcon.SHIELD, tint = JvTheme.colors.accentText, size = 16.dp)
            }
            Spacer(Modifier.width(JvSpace.sm))
            Text("Waiting on you", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (lines.isNotEmpty()) Text("${lines.size}", color = JvTheme.colors.accentText, style = MaterialTheme.typography.labelLarge.tabular())
        }
        when {
            lines.isNotEmpty() -> lines.take(2).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            actions == null && data.pending !is Availability.Loading -> MutedText("Agent approvals are unavailable right now.")
            actions == null -> MutedText("Loading…")
            else -> MutedText("Nothing is waiting for you.")
        }
    }
}

/** A tappable Instrument card; TalkBack reads its text as one button. */
@Composable
private fun HomeCard(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale()
            .shadowBorder(JvShapes.lg)
            .clip(JvShapes.lg)
            .background(JvTheme.colors.bgSurface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(JvSpace.sm),
        content = content,
    )
}

/** "Ask Jarvis anything…": opens the chat. */
@Composable
private fun CommandBar(onClick: () -> Unit) {
    val c = JvTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bgSurface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.separator))
        Row(
            Modifier
                .padding(horizontal = JvSpace.lg, vertical = JvSpace.sm)
                .fillMaxWidth()
                .height(48.dp)
                .pressScale()
                .clip(JvShapes.pill)
                .background(c.bgSunken)
                .border(1.dp, c.border, JvShapes.pill)
                .clickable(onClickLabel = "Open chat", role = Role.Button, onClick = onClick)
                .padding(horizontal = JvSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JvSpace.md),
        ) {
            JvIconView(JvIcon.WAVE, tint = c.textSecondary)
            Text("Ask Jarvis anything…", color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
