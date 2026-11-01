package com.hawkeynl.jarvis.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hawkeynl.jarvis.network.LinkedDevice
import com.hawkeynl.jarvis.security.PairingApproval
import java.time.Instant
import java.time.ZoneId

private val LATER = listOf(
    Triple(JvIcon.BRIEFCASE, "Work mode", "Focus on projects and code"),
    Triple(JvIcon.MOON, "Focus mode", "Only what really matters"),
    Triple(JvIcon.HOME, "Home mode", "Personal life, no work pings"),
    Triple(JvIcon.LINK, "Sources", "Where context comes from"),
    Triple(JvIcon.CALENDAR, "Schedule", "When each mode switches on"),
)

/** Context and devices (D-Devices): the linked devices are real today, with revoke for
 *  every other active device. Modes, sources and the schedule need Core support. */
@Composable
fun ContextScreen(data: CoreData, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit, onRevoke: (String) -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    var confirming by remember { mutableStateOf<LinkedDevice?>(null) }

    PushedPage(
        screenTitle(Screen.CONTEXT), "Which devices can use Jarvis", { onNavigate(backOf(Screen.CONTEXT)) },
        trailing = { GhostButton("Refresh", onRefresh) },
    ) {
        ListSection(null) {
            NavRow(JvIcon.KEY, "Pairing requests", "Approve new devices with your fingerprint or face", { onNavigate(Screen.PAIRING_APPROVALS) })
        }
        data.revokeError?.let { ErrorText(it) }
        Loaded(data.devices, "Devices", JvIcon.MONITOR, "Loading devices…", onRetry = onRefresh) { response ->
            val devices = response.devices
            Facts(
                "At a glance",
                listOf(
                    "Linked devices" to devices.size.toString(),
                    "Active" to devices.count { it.status == "active" }.toString(),
                    "Platforms" to devices.map { it.platform }.filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { "—" },
                    "Sign-in" to "Device-bound keys",
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
                        listOfNotNull(
                            d.platform.ifEmpty { null },
                            d.created_at?.let { "linked ${relativeTime(Instant.ofEpochSecond(it).toString(), now)}" },
                        ).joinToString(" · "),
                        null,
                        if (d.status == "active") Tone.OK else Tone.IDLE,
                        d.status.ifEmpty { "unknown" }.replaceFirstChar { it.uppercase() },
                    )
                },
                "No devices linked yet.",
                rowActions = { item ->
                    val device = devices.firstOrNull { it.id == item.id }
                    if (device != null && isOwnDevice(device, data.ownDevice)) StatusBadge("This device")
                    // Nothing is offered until this phone's own id is known.
                    if (device != null && revocable(device, data.ownDevice)) {
                        GhostButton(
                            if (data.revoking == device.id) "Revoking…" else "Revoke", { confirming = device },
                            enabled = data.revoking == null, danger = true,
                        )
                    }
                },
            )
        }
        LaterSection(LATER, "Context modes, sources and schedules need Core support that does not exist yet.")
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
