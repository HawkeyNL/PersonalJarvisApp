package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val HEALTH_DETAILS = mapOf(
    "core" to "The heart of Jarvis on your Home Node — everything else talks through it.",
    "node" to "The machine Jarvis runs on: hardware inventory, live load and installed tools.",
    "usage" to "Monthly spend against the hard budget, with token use per backend.",
    "models" to "Owner-controlled model access. Allowing a model never selects it for a running request.",
    "services" to "The Jarvis services on your Home Node and the free space on its disks.",
    "events" to "Sign-ins, pairings and other security events Core recorded.",
)

private fun tokens(n: Long): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(n)

/** System Health: connection, Home Node vitals, usage, model access,
 *  services and security events on the shared node layout. */
@Composable
fun HealthScreen(
    state: JarvisUiState,
    data: CoreData,
    mood: Mood,
    onNavigate: (Screen) -> Unit,
    onRefresh: () -> Unit,
    onCheckConnection: () -> Unit,
    modelControls: ModelControlService?,
) {
    LaunchedEffect(Unit) { onRefresh() }
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
        if (u.over_budget) Tone.ERROR to "Budget reached" else Tone.OK to "${eur(u.spent_eur)} of ${eur(u.budget_eur)}"
    }
    var svc = statusOf(data.services) { servicesSummary(it.services) }
    // A nearly full disk outranks "all running".
    val fullDisk = data.services.valueOrNull?.disks.orEmpty().any { diskState(it).first in setOf(Tone.WARN, Tone.ERROR) }
    if (fullDisk && svc.first == Tone.OK) svc = Tone.WARN to "${svc.second} · disk nearly full"
    val events = statusOf(data.systemAudit) { (if (it.entries.isEmpty()) Tone.IDLE else Tone.OK) to "${it.entries.size} recorded" }
    val items = listOf(
        NodeItem("core", "Core", "Jarvis Core", JvIcon.CHIP, core.first, core.second, "The heart of Jarvis"),
        NodeItem("node", "Home Node", "Home Node", JvIcon.HOME, node.first, node.second, "Hardware, live load and software"),
        NodeItem("usage", "Usage", "Usage", JvIcon.TREND, spend.first, spend.second, "Spend, budget and tokens this month"),
        NodeItem("models", "Models", "Models", JvIcon.LAYERS, Tone.IDLE, "Owner policy", "Which models Jarvis may use"),
        NodeItem("services", "Services", "Services & disk", JvIcon.DISK, svc.first, svc.second, "Service status and disk space"),
        NodeItem("events", "Events", "Security events", JvIcon.SHIELD, events.first, events.second, "What Core recorded"),
    )
    var selected by rememberSaveable { mutableStateOf("core") }
    var sub by rememberSaveable { mutableStateOf("overview") }
    val current = items.first { it.id == selected }
    val auditRows = data.systemAudit.valueOrNull?.entries.orEmpty().take(20).mapIndexed { i, e ->
        val good = e.outcome.lowercase(Locale.ROOT) in setOf("ok", "success", "approved", "allowed")
        ActivityItem("${e.ts}-$i", if (e.event.startsWith("auth")) JvIcon.KEY else JvIcon.SHIELD, e.event, e.outcome, relativeTime(e.ts, now), if (good) Tone.OK else Tone.WARN, e.outcome)
    }

    NodePage("SYSTEM HEALTH", "THE STATE OF YOUR HOME NODE", items, selected, { selected = it; sub = "overview" }, onNavigate, mood) {
        Panel(
            current.icon, current.title, current.tone, current.status,
            description = HEALTH_DETAILS[current.id],
            quote = if (current.id == "core") state.endpoint?.baseUrl ?: "No Home Node configured" else null,
            action = when (current.id) {
                "core" -> ({ ActionButton("Check again", onCheckConnection) })
                "models" -> null
                else -> ({ ActionButton("Refresh", onRefresh) })
            },
        ) {
            when (current.id) {
                "core" -> {
                    val endpoint = state.endpoint
                    TileGrid(
                        "Connection",
                        listOf(
                            TileData(
                                JvIcon.CHIP, "Ready · /readyz",
                                when (val c = state.connection) {
                                    is ConnectionState.Reachable -> "OK · ${c.status}"
                                    is ConnectionState.Unreachable -> "Failed · ${c.reason.name.lowercase(Locale.ROOT)}"
                                    is ConnectionState.Rejected -> "Failed · HTTP ${c.status}"
                                    ConnectionState.Checking -> "Checking…"
                                    ConnectionState.NotConfigured -> "Not configured"
                                },
                            ),
                            TileData(JvIcon.API, "Server", endpoint?.baseUrl ?: "Not configured"),
                            TileData(JvIcon.SHIELD, "Transport", if (endpoint?.baseUrl?.startsWith("https://") == true) "HTTPS" else "HTTP, local network only"),
                            TileData(JvIcon.KEY, "Sign-in", "Device-bound keys"),
                        ),
                    )
                    Loaded(data.systemAudit, "Recent events", JvIcon.SHIELD, "Loading events…") {
                        ActivityList("Recent events", auditRows.take(5), "No security events recorded.")
                    }
                }
                "node" -> {
                    ChipRow(listOf(ChipItem("overview", "Overview", JvIcon.DOC), ChipItem("software", "Software", JvIcon.LAYERS)), sub, { sub = it }, small = true)
                    Loaded(data.registry, "Home Node", JvIcon.HOME, "Loading resources…") { registry ->
                        val rows = softwareRows(registry.software)
                        if (sub == "software") {
                            ActivityList("Software", rows, "No software reported.")
                            return@Loaded
                        }
                        val live = registry.live_host
                        val host = registry.host
                        val tiles = buildList {
                            if (live != null) {
                                add(TileData(JvIcon.CHIP, "CPU load", live.cpu_percent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "First sample…"))
                                add(TileData(JvIcon.LAYERS, "Memory in use", "${gib(live.memory_used_bytes)} / ${gib(live.memory_total_bytes)}"))
                                add(TileData(JvIcon.HEALTH, "Uptime", formatUptime(live.uptime_seconds)))
                                add(
                                    TileData(
                                        JvIcon.CLOCK, "Last sample",
                                        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(live.sampled_at)),
                                    ),
                                )
                            }
                            if (host != null) {
                                add(TileData(JvIcon.CHIP, "Processor", "${host.cpu} · ${host.cpu_cores} cores"))
                                add(TileData(JvIcon.MONITOR, "System", "${host.os} · ${host.arch} · ${host.mem_total_gb} GB · ${host.gpu}"))
                            }
                        }
                        if (tiles.isNotEmpty()) TileGrid("Vitals", tiles)
                        if (live == null) {
                            Unavailable(
                                "Live load", JvIcon.HEALTH, kind = UnavailableKind.CORE_UPDATE,
                                detail = "This Core reports a hardware inventory only, not the current load.",
                            )
                        }
                        ActivityList(
                            "Software", rows.take(5), "No software reported.",
                            action = if (rows.size > 5) ({ GhostButton("View all", { sub = "software" }) }) else null,
                        )
                    }
                }
                "usage" -> Loaded(data.usage, "Usage", JvIcon.TREND, "Loading usage…") { usage ->
                    Budget(usage)
                    TileGrid(
                        "This month",
                        listOf(
                            TileData(JvIcon.TREND, "Remaining", eur(usage.remaining_eur)),
                            TileData(JvIcon.LAYERS, "Tokens", tokens(usage.total_tokens)),
                            TileData(JvIcon.ARROW_RIGHT, "Input / output", "${tokens(usage.input_tokens)} / ${tokens(usage.output_tokens)}"),
                            TileData(JvIcon.API, "Calls", tokens(usage.requests)),
                        ),
                    )
                    ActivityList(
                        "By backend",
                        usage.by_backend.map {
                            ActivityItem(it.backend, JvIcon.SPARK, it.backend, "${tokens(it.total_tokens)} tokens", eur(it.spent_eur), Tone.OK, "Used this month")
                        },
                        "No usage this month.",
                    )
                }
                "models" -> if (modelControls != null) {
                    MutedText("Every change asks Android to confirm it is you and is signed with this device's key.")
                    ModelControls(modelControls)
                } else {
                    Unavailable("Models", JvIcon.LAYERS, kind = UnavailableKind.ERROR, detail = "Model controls are not available in this session.")
                }
                "services" -> Loaded(data.services, "Services & disk", JvIcon.DISK, "Loading services…") { response ->
                    ActivityList(
                        "Services",
                        response.services.map {
                            val (tone, label) = serviceState(it.state)
                            ActivityItem(it.unit.ifEmpty { it.label }, JvIcon.CHIP, it.label, it.unit, label, tone, label)
                        },
                        "No services reported.",
                    )
                    ActivityList(
                        "Disks",
                        response.disks.mapIndexed { i, disk ->
                            val (tone, line) = diskState(disk)
                            ActivityItem("${disk.label}-$i", JvIcon.DISK, disk.label, line, tone = tone, toneLabel = line)
                        },
                        "No disks reported.",
                    )
                }
                else -> Loaded(data.systemAudit, "Security events", JvIcon.SHIELD, "Loading events…") {
                    ActivityList("Security events", auditRows, "No security events recorded.")
                }
            }
        }
    }
}

@Composable
private fun Budget(usage: SystemUsage) {
    val fraction = if (usage.budget_eur > 0) (usage.spent_eur / usage.budget_eur).coerceIn(0.0, 1.0).toFloat() else 0f
    val color = if (usage.over_budget) Jv.Danger else Jv.Accent
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("Monthly budget", Modifier.weight(1f), style = exo(12.5f, color = Jv.Text2))
            Text(
                "${eur(usage.spent_eur)} / ${eur(usage.budget_eur)}" + if (usage.over_budget) " · cap reached" else "",
                style = exo(12.5f, color = if (usage.over_budget) Jv.Danger else Jv.Text2),
            )
        }
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Jv.accent(0.12f))
                .semantics { contentDescription = "${Math.round(fraction * 100)}% of the budget used" },
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
        }
    }
}
