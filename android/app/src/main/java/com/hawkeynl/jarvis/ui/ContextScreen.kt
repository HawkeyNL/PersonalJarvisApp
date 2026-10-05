package com.hawkeynl.jarvis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.Instant

/** Context: only the linked devices are real today. Modes, sources and the
 *  schedule need Core support that does not exist yet. */
@Composable
fun ContextScreen(data: CoreData, mood: Mood, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val (deviceTone, deviceStatus) = statusOf(data.devices) {
        (if (it.devices.isEmpty()) Tone.IDLE else Tone.OK) to "${it.devices.size} linked"
    }
    val items = listOf(
        planned("work", "Work", "Work mode", JvIcon.BRIEFCASE, "Focus on projects and code"),
        planned("focus", "Focus", "Focus mode", JvIcon.MOON, "Only what really matters"),
        planned("home", "Home", "Home mode", JvIcon.HOME, "Personal life, no work pings"),
        planned("sources", "Sources", "Sources", JvIcon.LINK, "Where context comes from"),
        planned("schedule", "Schedule", "Schedule", JvIcon.CALENDAR, "When each mode switches on"),
        NodeItem("devices", "Devices", "Devices", JvIcon.MONITOR, deviceTone, deviceStatus, "Desktop and mobile app"),
    )
    var selected by rememberSaveable { mutableStateOf("devices") }
    val current = items.first { it.id == selected }

    NodePage("CONTEXT", "WHAT JARVIS PAYS ATTENTION TO", items, selected, { selected = it }, onNavigate, mood) {
        Panel(current.icon, current.title, current.tone, current.status, description = current.description) {
            if (current.id != "devices") {
                Unavailable(
                    current.title, current.icon,
                    detail = "Context modes, sources and schedules need Core support that does not exist yet. Nothing is shown until it does.",
                )
                return@Panel
            }
            Loaded(data.devices, "Devices", JvIcon.MONITOR, "Loading devices…") { response ->
                val devices = response.devices
                TileGrid(
                    "Linked",
                    listOf(
                        TileData(JvIcon.MONITOR, "Linked devices", devices.size.toString()),
                        TileData(JvIcon.HEALTH, "Active", devices.count { it.status == "active" }.toString()),
                        TileData(JvIcon.LAYERS, "Platforms", devices.map { it.platform }.filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { "—" }),
                        TileData(JvIcon.SHIELD, "Sign-in", "Device-bound keys"),
                    ),
                )
                val now = System.currentTimeMillis()
                ActivityList(
                    "Devices",
                    devices.map { d ->
                        ActivityItem(
                            d.id,
                            if (Regex("ios|android", RegexOption.IGNORE_CASE).containsMatchIn(d.platform)) JvIcon.PHONE else JvIcon.MONITOR,
                            d.name.ifEmpty { "Unnamed device" },
                            d.platform,
                            d.created_at?.let { "linked ${relativeTime(Instant.ofEpochSecond(it).toString(), now)}" },
                            if (d.status == "active") Tone.OK else Tone.IDLE,
                            d.status.ifEmpty { "unknown" },
                        )
                    },
                    "No devices linked yet.",
                )
            }
        }
    }
}
