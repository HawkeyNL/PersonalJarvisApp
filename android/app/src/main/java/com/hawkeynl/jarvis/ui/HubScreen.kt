package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.map

private data class HubCard(val screen: Screen, val icon: JvIcon, val title: String, val summary: CardSummary)

// Module satellites on the orb ring (dp from the orb centre), as in core-mobile.
private val HUB_SATELLITES = listOf(
    Triple(Screen.CONVERSATIONS, JvIcon.CHAT, 0 to -124),
    Triple(Screen.MEMORY, JvIcon.MEMORY, 98 to -76),
    Triple(Screen.INTEGRATIONS, JvIcon.INTEGRATIONS, 120 to 30),
    Triple(Screen.HEALTH, JvIcon.HEALTH, 62 to 107),
    Triple(Screen.CONTEXT, JvIcon.CONTEXT, -62 to 107),
    Triple(Screen.TASKS, JvIcon.TASKS, -120 to 30),
    Triple(Screen.AGENTS, JvIcon.AGENTS, -98 to -76),
)

/** The hub (core-mobile): the living orb with the seven modules around it,
 *  the command bar into chat and a live card per module. */
@Composable
fun HubScreen(state: JarvisUiState, data: CoreData, mood: Mood, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val online = onlineOf(state.connection)
    val now = remember(state.conversations, data.loadedAt) { System.currentTimeMillis() }
    val cards = listOf(
        HubCard(Screen.CONVERSATIONS, JvIcon.CHAT, "Conversations", conversationsCard(state.conversations.map { it.updated_at }, now)),
        HubCard(Screen.MEMORY, JvIcon.MEMORY, "Memory", CardSummary("Not yet available", "Needs Core support", Tone.IDLE)),
        HubCard(Screen.INTEGRATIONS, JvIcon.INTEGRATIONS, "Integrations", integrationsCard(data.registry)),
        HubCard(Screen.HEALTH, JvIcon.HEALTH, "System Health", healthCard(online, data.registry)),
        HubCard(Screen.CONTEXT, JvIcon.CONTEXT, "Context", contextCard(data.devices.map { it.devices.size })),
        HubCard(Screen.TASKS, JvIcon.TASKS, "Tasks", tasksCard(data.sessions.map { it.sessions }, data.pending.map { it.pending.size })),
        HubCard(Screen.AGENTS, JvIcon.AGENTS, "Agents", agentsCard(data.agents)),
    )
    val titles = cards.associate { it.screen to it.title }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            StatusDot(
                when (online) { true -> Tone.OK; false -> Tone.ERROR; null -> Tone.IDLE },
                when (online) { true -> "Core online"; false -> "Core offline"; null -> "Connecting" },
            )
            Spacer(Modifier.width(12.dp))
            ProfileButton({ onNavigate(Screen.SETTINGS) })
        }
        CommandBar { onNavigate(Screen.CHAT) }

        val rings = JvTheme.colors.border
        Box(Modifier.fillMaxWidth().height(330.dp).orbRings(rings)) {
            JvOrb(182.dp, mood, Modifier.align(Alignment.Center))
            for ((screen, icon, offset) in HUB_SATELLITES) {
                CircleIconButton(
                    icon, titles.getValue(screen), { onNavigate(screen) },
                    Modifier.align(Alignment.Center).offset(offset.first.dp, offset.second.dp), size = 46.dp,
                )
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatusPillView(statusPill(mood, online), Modifier.align(Alignment.CenterHorizontally))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Your system")
                for (row in cards.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (card in row) {
                            ModuleCard(card.icon, card.title, card.summary, { onNavigate(card.screen) }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** "Ask Jarvis anything…": opens the chat. */
@Composable
private fun CommandBar(onClick: () -> Unit) {
    val c = JvTheme.colors
    Row(
        Modifier
            .padding(horizontal = JvSpace.lg)
            .fillMaxWidth()
            .height(48.dp)
            .pressScale()
            .clip(JvShapes.md)
            .background(c.bgSurface)
            .border(1.dp, c.borderStrong, JvShapes.md)
            .clickable(onClickLabel = "Open chat", role = Role.Button, onClick = onClick)
            .padding(horizontal = JvSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JvSpace.md),
    ) {
        JvIconView(JvIcon.WAVE, tint = c.textSecondary)
        Text("Ask Jarvis anything…", color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The three hairline rings around the hub orb (r 124 / 134 / dotted 158). */
private fun Modifier.orbRings(color: Color): Modifier = drawBehind {
    drawCircle(color, 124.dp.toPx(), style = Stroke(1.dp.toPx()))
    drawCircle(color, 134.dp.toPx(), style = Stroke(1.dp.toPx()))
    drawCircle(
        color, 158.dp.toPx(),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 6.dp.toPx()))),
    )
}

@Composable
private fun StatusPillView(pill: StatusPill, modifier: Modifier = Modifier) {
    val c = JvTheme.colors
    val shape = JvShapes.pill
    Row(
        modifier
            .heightIn(min = 40.dp)
            .shadowBorder(shape)
            .clip(shape)
            .background(c.bgSurface)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = JvSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(pill.tone)
        Text(pill.label, color = toneTextColor(pill.tone), style = MaterialTheme.typography.labelLarge)
        Box(Modifier.width(1.dp).height(16.dp).background(c.separator))
        Text(pill.hint, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
