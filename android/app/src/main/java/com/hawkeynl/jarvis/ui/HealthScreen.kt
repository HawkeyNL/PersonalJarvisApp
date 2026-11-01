package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.SystemUsage
import com.hawkeynl.jarvis.network.valueOrNull
import com.hawkeynl.jarvis.security.ModelControlService
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun tokens(n: Long): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(n)

/** System health: Core and Home Node status, services, disks and security events; usage
 *  against the budget; owner model access. A deep link opens it on Usage ([healthView]). */
@Composable
fun HealthScreen(
    state: JarvisUiState,
    data: CoreData,
    onNavigate: (Screen) -> Unit,
    onRefresh: () -> Unit,
    onCheckConnection: () -> Unit,
    modelControls: ModelControlService?,
    initialNode: String = "core",
) {
    LaunchedEffect(Unit) { onRefresh() }
    var view by rememberSaveable { mutableStateOf(healthView(initialNode)) }
    PushedPage(
        screenTitle(Screen.HEALTH), "The state of your Home Node", { onNavigate(backOf(Screen.HEALTH)) },
        trailing = { GhostButton("Refresh", onRefresh) },
    ) {
        Segmented(listOf("status" to "Status", "usage" to "Usage", "models" to "Models"), view, { view = it })
        when (view) {
            "status" -> StatusView(state, data, onRefresh, onCheckConnection)
            "usage" -> Loaded(data.usage, "Usage", JvIcon.TREND, "Loading usage…", onRetry = onRefresh) { usage ->
                ListSection("Monthly budget") { Box(Modifier.padding(JvSpace.lg)) { Budget(usage) } }
                Facts(
                    "This month",
                    listOf(
                        "Remaining" to eur(usage.remaining_eur),
                        "Reserved" to eur(usage.reserved_eur),
                        "Tokens" to tokens(usage.total_tokens),
                        "Input / output" to "${tokens(usage.input_tokens)} / ${tokens(usage.output_tokens)}",
                        "Calls" to tokens(usage.requests),
                    ),
                )
                ActivityList(
                    "By backend",
                    usage.by_backend.map {
                        ActivityItem(it.backend, JvIcon.SPARK, it.backend, "${tokens(it.total_tokens)} tokens", eur(it.spent_eur), Tone.OK, "")
                    },
                    "No usage this month.",
                )
            }
            else -> if (modelControls != null) {
                MutedText(
                    "Owner-controlled model access. Allowing a model never selects it for a running request. Every change asks " +
                        "Android to confirm it is you and is signed with this device's key.",
                    Modifier.padding(horizontal = JvSpace.xs),
                )
                ModelControls(modelControls)
                ModelRoutingEditor(modelControls)
            } else {
                Unavailable("Models", JvIcon.LAYERS, kind = UnavailableKind.ERROR, detail = "Model controls are not available in this session.")
            }
        }
    }
}

@Composable
private fun StatusView(state: JarvisUiState, data: CoreData, onRefresh: () -> Unit, onCheckConnection: () -> Unit) {
    val now = System.currentTimeMillis()
    val core = when (state.connection) {
        is ConnectionState.Reachable -> Tone.OK to "Running"
        is ConnectionState.Unreachable, is ConnectionState.Rejected -> Tone.ERROR to "Unreachable"
        ConnectionState.Checking -> Tone.IDLE to "Checking…"
        ConnectionState.NotConfigured -> Tone.IDLE to "Not configured"
    }
    val node = statusOf(data.registry) { r ->
        r.live_host?.let { Tone.OK to "Up ${formatUptime(it.uptime_seconds)}" } ?: (Tone.IDLE to "No live vitals")
    }
    val spend = statusOf(data.usage) { u ->
        when {
            u.over_budget -> Tone.ERROR to "Budget reached"
            u.above_soft_budget -> Tone.WARN to "Above soft budget"
            else -> Tone.OK to "${eur(u.spent_eur)} of ${eur(u.budget_eur)}"
        }
    }
    var svc = statusOf(data.services) { servicesSummary(it.services) }
    // A nearly full disk outranks "all running".
    val fullDisk = data.services.valueOrNull?.disks.orEmpty().any { diskState(it).first in setOf(Tone.WARN, Tone.ERROR) }
    if (fullDisk && svc.first == Tone.OK) svc = Tone.WARN to "${svc.second} · disk nearly full"
    val auditRows = data.systemAudit.valueOrNull?.entries.orEmpty().take(20).mapIndexed { i, e ->
        val good = e.outcome.lowercase(Locale.ROOT) in setOf("ok", "success", "approved", "allowed")
        ActivityItem("${e.ts}-$i", if (e.event.startsWith("auth")) JvIcon.KEY else JvIcon.SHIELD, e.event, null, relativeTime(e.ts, now), if (good) Tone.OK else Tone.WARN, e.outcome)
    }
    var allSoftware by rememberSaveable { mutableStateOf(false) }

    ListSection("Overview") {
        FactRow("Jarvis Core", core.second, core.first)
        ListDivider()
        FactRow("Home Node", node.second, node.first)
        ListDivider()
        FactRow("Services and disks", svc.second, svc.first)
        ListDivider()
        FactRow("Spend this month", spend.second, spend.first)
    }

    val endpoint = state.endpoint
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        Facts(
            "Connection",
            listOf(
                "Ready check" to when (val c = state.connection) {
                    is ConnectionState.Reachable -> "OK · ${c.status}"
                    is ConnectionState.Unreachable -> "Failed · ${c.reason.name.lowercase(Locale.ROOT)}"
                    is ConnectionState.Rejected -> "Failed · HTTP ${c.status}"
                    ConnectionState.Checking -> "Checking…"
                    ConnectionState.NotConfigured -> "Not configured"
                },
                "Server" to (endpoint?.baseUrl ?: "Not configured"),
                "Transport" to if (endpoint?.baseUrl?.startsWith("https://") == true) "HTTPS" else "HTTP, local network only",
                "Sign-in" to "Device-bound keys",
            ),
        )
        GhostButton("Check again", onCheckConnection, Modifier.fillMaxWidth())
    }

    Loaded(data.registry, "Home Node", JvIcon.HOME, "Loading resources…", onRetry = onRefresh) { registry ->
        val live = registry.live_host
        val host = registry.host
        val vitals = buildList {
            if (live != null) {
                add("CPU load" to (live.cpu_percent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "First sample…"))
                add("Memory in use" to "${gib(live.memory_used_bytes)} / ${gib(live.memory_total_bytes)}")
                add("Uptime" to formatUptime(live.uptime_seconds))
                add("Last sample" to DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(live.sampled_at)))
            }
            if (host != null) {
                add("Processor" to "${host.cpu} · ${host.cpu_cores} cores")
                add("System" to "${host.os} · ${host.arch} · ${host.mem_total_gb} GB · ${host.gpu}")
            }
        }
        if (vitals.isNotEmpty()) Facts("Home Node", vitals)
        if (live == null) {
            Unavailable(
                "Live load", JvIcon.HEALTH, kind = UnavailableKind.CORE_UPDATE,
                detail = "This Core reports a hardware inventory only, not the current load.",
            )
        }
        val rows = softwareRows(registry.software)
        ActivityList(
            "Software", if (allSoftware) rows else rows.take(5), "No software reported.",
            action = if (rows.size > 5 && !allSoftware) ({ GhostButton("View all", { allSoftware = true }) }) else null,
        )
    }

    Loaded(data.services, "Services and disks", JvIcon.DISK, "Loading services…", onRetry = onRefresh) { response ->
        ActivityList(
            "Services",
            response.services.map {
                val (tone, label) = serviceState(it.state)
                ActivityItem(it.unit.ifEmpty { it.label }, JvIcon.CHIP, it.label, it.unit, null, tone, label)
            },
            "No services reported.",
        )
        ActivityList(
            "Disks",
            response.disks.mapIndexed { i, disk ->
                val (tone, line) = diskState(disk)
                ActivityItem("${disk.label}-$i", JvIcon.DISK, disk.label, line.takeIf { tone != Tone.IDLE }, tone = tone, toneLabel = diskWord(tone))
            },
            "No disks reported.",
        )
    }

    Loaded(data.systemAudit, "Security events", JvIcon.SHIELD, "Loading events…", onRetry = onRefresh) {
        ActivityList("Security events", auditRows, "No security events recorded.")
    }
}

/** Badge word of a disk state ([diskState] warns at 80% and 90% used). */
private fun diskWord(tone: Tone): String = when (tone) {
    Tone.OK -> "OK"
    Tone.WARN -> "Filling up"
    Tone.ERROR -> "Nearly full"
    Tone.IDLE -> "Unknown"
}

@Composable
private fun Budget(usage: SystemUsage) {
    val fraction = if (usage.budget_eur > 0) (usage.spent_eur / usage.budget_eur).coerceIn(0.0, 1.0).toFloat() else 0f
    val c = JvTheme.colors
    val color = if (usage.over_budget) c.dangerSolid else c.accentSolid
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("Monthly budget", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(
                "${eur(usage.spent_eur)} / ${eur(usage.budget_eur)}" + if (usage.over_budget) " · cap reached" else "",
                color = if (usage.over_budget) c.dangerText else c.textSecondary,
                style = MaterialTheme.typography.bodySmall.tabular(),
            )
        }
        Box(
            Modifier.padding(top = JvSpace.sm).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.bgSunken)
                .semantics { contentDescription = "${Math.round(fraction * 100)}% of the budget used" },
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
        }
    }
}
