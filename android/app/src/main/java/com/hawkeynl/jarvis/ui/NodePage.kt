package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.Availability

/** One satellite of a node page: selectable section with its live status. */
data class NodeItem(
    val id: String,
    val label: String,
    val title: String,
    val icon: JvIcon,
    val tone: Tone,
    val status: String,
    val description: String,
)

fun planned(id: String, label: String, title: String, icon: JvIcon, description: String) =
    NodeItem(id, label, title, icon, Tone.IDLE, "Not yet available", description)

// Satellite positions around the small orb (dp from its centre), as in the mobile export.
private val SATELLITES = listOf(-88 to -62, -108 to 0, -88 to 62, 88 to -62, 108 to 0, 88 to 62)

/**
 * Shared layout of every module page (mobile export): back, title and
 * profile; the small orb with up to six satellites; a chip row with every
 * section; then the detail content for the selected section.
 */
@Composable
fun NodePage(
    title: String,
    subtitle: String,
    items: List<NodeItem>,
    selected: String,
    onSelect: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
    mood: Mood = Mood.IDLE,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CircleIconButton(
                JvIcon.ARROW_LEFT, "Back to Core", { onNavigate(Screen.HUB) }, Modifier.align(Alignment.CenterStart),
                background = Color(5, 40, 30, 166),
            )
            PageTitle(title, subtitle, Modifier.align(Alignment.Center).padding(horizontal = 52.dp))
            ProfileButton({ onNavigate(Screen.SETTINGS) }, Modifier.align(Alignment.CenterEnd))
        }
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            JvOrb(150.dp, mood, Modifier.align(Alignment.Center))
            items.take(SATELLITES.size).forEachIndexed { i, item ->
                val (x, y) = SATELLITES[i]
                CircleIconButton(
                    item.icon, item.title, { onSelect(item.id) },
                    Modifier.align(Alignment.Center).offset(x.dp, y.dp),
                    selected = item.id == selected,
                )
            }
        }
        if (items.isNotEmpty()) ChipRow(items.map { ChipItem(it.id, it.label, it.icon) }, selected, onSelect)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** The design's bottom dock: Core, Tasks, chat, Memory, Agents. */
@Composable
fun Dock(current: Screen, onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(64.dp)
            .clip(shape)
            .background(Jv.Dock)
            .border(1.dp, Jv.accent(0.4f), shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockItem(JvIcon.HOME, "Core", current == Screen.HUB) { onNavigate(Screen.HUB) }
        DockItem(JvIcon.TASKS, "Tasks", current == Screen.TASKS) { onNavigate(Screen.TASKS) }
        Box(
            Modifier
                .size(52.dp)
                .drawBehind { drawCircle(Jv.Accent, size.minDimension * 0.7f, alpha = 0.2f) }
                .clip(CircleShape)
                .background(Jv.Accent)
                .clickable(role = Role.Button, onClick = { onNavigate(Screen.CHAT) }),
            contentAlignment = Alignment.Center,
        ) { JvIconView(JvIcon.CHAT, tint = Jv.OnAccent, size = 22.dp, contentDescription = "Chat with Jarvis") }
        DockItem(JvIcon.MEMORY, "Memory", current == Screen.MEMORY) { onNavigate(Screen.MEMORY) }
        DockItem(JvIcon.AGENTS, "Agents", current == Screen.AGENTS) { onNavigate(Screen.AGENTS) }
    }
}

@Composable
private fun DockItem(icon: JvIcon, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 60.dp, height = 52.dp)
            .clip(Jv.R12)
            .selectable(selected = on, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val color = if (on) Jv.Accent else Jv.Muted
        JvIconView(icon, tint = color, size = 20.dp)
        Text(label, style = exo(11f, color = color, spacing = 0.06f))
    }
}

/** Satellite status of an optional Core source. */
fun <T> statusOf(source: Availability<T>, ok: (T) -> Pair<Tone, String>): Pair<Tone, String> = when (source) {
    is Availability.Ok -> ok(source.value)
    Availability.Loading -> Tone.IDLE to "Loading…"
    Availability.Unsupported -> Tone.IDLE to "Requires newer Core"
    is Availability.Failed -> Tone.WARN to "Unavailable"
}

/** Loading text, "Requires newer Core" or the failure for [source]; [content] once it is there. */
@Composable
fun <T> Loaded(
    source: Availability<T>,
    title: String,
    icon: JvIcon,
    loading: String = "Loading…",
    forbidden: String = "Turned off in Core.",
    content: @Composable (T) -> Unit,
) {
    when (source) {
        Availability.Loading -> MutedText(loading)
        Availability.Unsupported -> Unavailable(title, icon, kind = UnavailableKind.CORE_UPDATE, detail = "This Core does not offer this yet. Update Core to see it here.")
        is Availability.Failed -> Unavailable(title, icon, kind = UnavailableKind.ERROR, detail = failureText(source.reason, forbidden))
        is Availability.Ok -> content(source.value)
    }
}
