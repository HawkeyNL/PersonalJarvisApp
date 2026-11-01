package com.hawkeynl.jarvis.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hawkeynl.jarvis.network.LinkedDevice
import com.hawkeynl.jarvis.security.PairingApproval
import java.time.Instant
import java.time.ZoneId

/** Context: only the linked devices are real today. Modes, sources and the
 *  schedule need Core support that does not exist yet. */
@Composable
fun ContextScreen(data: CoreData, mood: Mood, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit, onRevoke: (String) -> Unit) {
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
    var confirming by remember { mutableStateOf<LinkedDevice?>(null) }
    val current = items.first { it.id == selected }

    NodePage("Context", "What Jarvis pays attention to", items, selected, { selected = it }, onNavigate, mood) {
        Panel(current.icon, current.title, current.tone, current.status, description = current.description) {
            if (current.id != "devices") {
                Unavailable(
                    current.title, current.icon,
                    detail = "Context modes, sources and schedules need Core support that does not exist yet. Nothing is shown until it does.",
                )
                return@Panel
            }
            ActionButton("Pairing requests", { onNavigate(Screen.PAIRING_APPROVALS) }, arrow = true)
            data.revokeError?.let { ErrorText(it) }
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
                    rowActions = { item ->
                        val device = devices.firstOrNull { it.id == item.id }
                        // Nothing is offered until this phone's own id is known.
                        val own = data.ownDevice
                        if (device != null && own != null && device.status == "active" && !device.id.equals(own, ignoreCase = true)) {
                            GhostButton(
                                if (data.revoking == device.id) "Revoking…" else "Revoke", { confirming = device },
                                enabled = data.revoking == null, danger = true,
                            )
                        }
                    },
                )
            }
        }
    }
    confirming?.let { device ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Revoke this device?") },
            text = {
                Text(
                    "\"${PairingApproval.displayName(device.name, 40)}\" (${PairingApproval.displayName(device.platform, 16)}), " +
                        "device ${PairingApproval.displayName(device.id.take(8).lowercase(), 8)}" +
                        (device.created_at?.let { ", linked ${Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate()}" } ?: "") +
                        ", is signed out at once and must be paired again to use Jarvis.",
                )
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = { confirming = null; onRevoke(device.id) }) { Text("Revoke", color = JvTheme.colors.dangerText) }
            },
        )
    }
}
