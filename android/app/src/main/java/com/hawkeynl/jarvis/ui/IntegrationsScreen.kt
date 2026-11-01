package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.LoadFailure
import com.hawkeynl.jarvis.network.valueOrNull

private val INTEGRATION_DETAILS = mapOf(
    "brains" to "The brains Core found on your Home Node. Ready means it is set up, not that it is in use.",
    "models" to "Allowing a model lets Jarvis choose it; it never selects a model for a running request. Change access under System Health.",
    "ibkr" to "Core's read-only link to Interactive Brokers. Jarvis only reads; it never places orders.",
    "codex" to "Core does not report the Codex broker status to the app yet.",
    "github" to "Core does not report a GitHub connection to the app yet.",
    "sandbox" to "Core does not report OpenSandbox status to the app yet.",
)

private val COST = mapOf("plan" to "Subscription", "metered" to "Pay per use", "local" to "Local", "cheap" to "Cheap", "mid" to "Mid", "pricey" to "Pricey")

/** Integrations: the brains, model catalog and host tools Core probed, the
 *  owner model policy and the IBKR link. Codex, GitHub and OpenSandbox are
 *  not reported by Core yet. */
@Composable
fun IntegrationsScreen(data: CoreData, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val registry = data.registry.valueOrNull
    val brains = registry?.brains.orEmpty()
    val software = registry?.software.orEmpty()
    val catalog = registry?.models.orEmpty()
    val policy = data.models.valueOrNull?.models.orEmpty()
    val allowed = policy.filter { it.enabled }

    val b = statusOf(data.registry) { (if (brains.any { it.available }) Tone.OK else Tone.IDLE) to "${countOf(brains) { it.available }} ready" }
    val m = statusOf(data.models) { (if (allowed.isEmpty()) Tone.IDLE else Tone.OK) to "${allowed.size} allowed" }
    val i = ibkrState(data.ibkr, data.loadedAt)
    val items = listOf(
        NodeItem("brains", "Brains", "AI brains", JvIcon.SPARK, b.first, b.second, "Who does the thinking, and how it is paid"),
        NodeItem("models", "Models", "Models", JvIcon.LAYERS, m.first, m.second, "Which models Jarvis may use"),
        NodeItem("ibkr", "IBKR", "Interactive Brokers", JvIcon.TREND, i.first, i.second, "Read-only broker link for trading"),
        planned("codex", "Codex", "Codex", JvIcon.TERMINAL, "Second opinion, behind the broker"),
        planned("github", "GitHub", "GitHub", JvIcon.BRANCH, "Repos, PRs and releases"),
        planned("sandbox", "Sandbox", "OpenSandbox", JvIcon.SANDBOX, "Disposable space for code runs"),
    )
    var selected by rememberSaveable { mutableStateOf("brains") }
    var sub by rememberSaveable { mutableStateOf("overview") }
    val current = items.first { it.id == selected }

    NodePage("Integrations", "The systems Jarvis can reach", items, selected, { selected = it; sub = "overview" }, { onNavigate(backOf(Screen.INTEGRATIONS)) }) {
        Panel(
            current.icon, current.title, current.tone, current.status,
            description = INTEGRATION_DETAILS[current.id],
            quote = registry?.active_brain?.takeIf { current.id == "brains" }?.let { "Active brain: $it" },
            action = if (current.id in setOf("brains", "models", "ibkr")) ({ ActionButton("Refresh", onRefresh) }) else null,
        ) {
            when (current.id) {
                "brains" -> {
                    ChipRow(listOf(ChipItem("overview", "Overview", JvIcon.DOC), ChipItem("tools", "Host tools", JvIcon.SANDBOX)), sub, { sub = it }, small = true)
                    Loaded(data.registry, "Registry", JvIcon.SPARK, "Loading registry…") {
                        if (sub == "overview") {
                            fun ready(cost: String) = "${countOf(brains.filter { it.cost == cost }) { it.available }} ready"
                            TileGrid(
                                "At a glance",
                                listOf(
                                    TileData(JvIcon.SPARK, "Active brain", registry?.active_brain ?: "Not reported"),
                                    TileData(JvIcon.HEALTH, "Brains ready", countOf(brains) { it.available }),
                                    TileData(JvIcon.USER, "Subscription", ready("plan")),
                                    TileData(JvIcon.TREND, "Pay per use", ready("metered")),
                                    TileData(JvIcon.HOME, "Local", ready("local")),
                                    TileData(JvIcon.SANDBOX, "Host tools", "${countOf(software) { it.present }} present"),
                                ),
                            )
                            ActivityList(
                                "Brains",
                                brains.map {
                                    ActivityItem(
                                        it.id, JvIcon.SPARK, it.label.ifEmpty { it.id }, listOf(COST[it.cost] ?: it.cost, it.note).filter { s -> s.isNotEmpty() }.joinToString(" · "),
                                        tone = if (it.available) Tone.OK else Tone.IDLE, toneLabel = if (it.available) "Ready" else "Not set up",
                                    )
                                },
                                "Core reported no brains.",
                            )
                        } else {
                            ActivityList("Host tools", softwareRows(software), "Core reported no host tools.")
                        }
                    }
                }
                "models" -> {
                    ChipRow(listOf(ChipItem("overview", "Overview", JvIcon.DOC), ChipItem("catalog", "Catalog", JvIcon.LAYERS)), sub, { sub = it }, small = true)
                    if (sub == "overview") {
                        Loaded(data.models, "Model policy", JvIcon.LAYERS, "Loading model policy…") {
                            TileGrid(
                                "Owner policy",
                                listOf(
                                    TileData(JvIcon.HEALTH, "Allowed", allowed.size.toString()),
                                    TileData(JvIcon.SHIELD, "Blocked", (policy.size - allowed.size).toString()),
                                    TileData(JvIcon.LAYERS, "In catalog", if (registry != null) catalog.size.toString() else "Unavailable"),
                                    TileData(JvIcon.KEY, "Changes", "Under System Health"),
                                ),
                            )
                            ActivityList(
                                "Allowed models",
                                allowed.map {
                                    ActivityItem("${it.provider}/${it.model}", JvIcon.LAYERS, it.model, it.provider + (it.route?.let { r -> " · route $r" } ?: ""), tone = Tone.OK, toneLabel = "Allowed")
                                },
                                "No model is allowed yet.",
                            )
                        }
                    } else {
                        MutedText("Set up means Core has a key or local install for the provider. Whether Jarvis may use a model is the owner policy.")
                        Loaded(data.registry, "Model catalog", JvIcon.LAYERS, "Loading catalog…") {
                            ActivityList(
                                "Catalog",
                                catalog.map {
                                    ActivityItem(
                                        "${it.backend}/${it.id}", JvIcon.LAYERS, it.id, "${it.backend} · ${it.modelClass} · ${COST[it.cost] ?: it.cost}",
                                        tone = if (it.available) Tone.OK else Tone.IDLE, toneLabel = if (it.available) "Provider set up" else "Provider not set up",
                                    )
                                },
                                "Core reported no models.",
                            )
                        }
                    }
                }
                "ibkr" -> IbkrDetails(data)
                else -> Unavailable(current.title, current.icon, detail = INTEGRATION_DETAILS[current.id])
            }
        }
    }
}

/** The IBKR link from GET /v1/trading/portfolio, on Integrations and the Trading tab. */
@Composable
fun IbkrDetails(data: CoreData) {
    if (data.ibkr == Availability.Failed(LoadFailure.UNAVAILABLE)) {
        Unavailable("IBKR", JvIcon.TREND, kind = UnavailableKind.ERROR, detail = IBKR_NOT_SYNCED)
        return
    }
    Loaded(data.ibkr, "IBKR", JvIcon.TREND, "Checking the IBKR link…") { p ->
        TileGrid(
            "IBKR link",
            listOf(
                TileData(JvIcon.LINK, "Connection", ibkrState(data.ibkr, data.loadedAt).second),
                TileData(JvIcon.CLOCK, "Last sync", relativeTime(p.as_of, data.loadedAt).ifEmpty { "—" }),
                TileData(JvIcon.TREND, "Positions", p.positions.size.toString()),
                TileData(JvIcon.SHIELD, "Access", "Read-only"),
            ),
        )
    }
}

/** The Trading tab: the read-only IBKR link Core reports today. Dashboards, proposals
 *  and orders are not built yet, so the tab says so instead of showing sample data. */
@Composable
fun TradingScreen(data: CoreData, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabTitle("Trading", "Your read-only link to Interactive Brokers", Modifier.weight(1f))
            GhostButton("Refresh", onRefresh)
        }
        IbkrDetails(data)
        Unavailable(
            "Dashboard and proposals", JvIcon.TREND,
            detail = "Coming soon: totals per pot, trade proposals to approve and orders. Today Jarvis only reads from IBKR; it never places orders.",
        )
    }
}

fun softwareRows(software: List<com.hawkeynl.jarvis.network.SoftwareItem>) = software.map {
    ActivityItem(
        it.name, JvIcon.SANDBOX, it.name + (it.version?.let { v -> " $v" } ?: ""), it.detail,
        tone = if (it.present) Tone.OK else Tone.IDLE, toneLabel = if (it.present) "Present" else "Missing",
    )
}
