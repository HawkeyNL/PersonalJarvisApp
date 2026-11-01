package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.Availability

/** One section of a node page: selectable chip with its live status. */
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

/**
 * Shared layout of every pushed module page: back and title, a chip row with
 * every section, then the detail content for the selected section.
 */
@Composable
fun NodePage(
    title: String,
    subtitle: String,
    items: List<NodeItem>,
    selected: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CircleIconButton(JvIcon.ARROW_LEFT, "Back", onBack, Modifier.align(Alignment.CenterStart))
            PageTitle(title, subtitle, Modifier.align(Alignment.Center).padding(horizontal = 56.dp))
        }
        if (items.isNotEmpty()) ChipRow(items.map { ChipItem(it.id, it.label, it.icon) }, selected, onSelect)
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** Section status of an optional Core source. */
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
