package com.hawkeynl.jarvis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hawkeynl.jarvis.network.valueOrNull

private val INTEGRATION_DETAILS = mapOf(
    "brains" to "The brains Core found on your Home Node. Ready means it is set up, not that it is in use.",
    "models" to "Allowing a model lets Jarvis choose it; it never selects a model for a running request. Change access under System Health.",
    "ibkr" to "Gateway status for Interactive Brokers. Jarvis only reads; it never places orders.",
    "codex" to "Core does not report the Codex broker status to the app yet.",
    "github" to "Core does not report a GitHub connection to the app yet.",
    "sandbox" to "Core does not report OpenSandbox status to the app yet.",
)

private val COST = mapOf("plan" to "Subscription", "metered" to "Pay per use", "local" to "Local", "cheap" to "Cheap", "mid" to "Mid", "pricey" to "Pricey")

/** Integrations: the brains, model catalog and host tools Core probed, the
 *  owner model policy and the IBKR gateway. Codex, GitHub and OpenSandbox are
 *  not reported by Core yet. */
@Composable
fun IntegrationsScreen(data: CoreData, mood: Mood, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val registry = data.registry.valueOrNull
    val brains = registry?.brains.orEmpty()
    val software = registry?.software.orEmpty()
    val catalog = registry?.models.orEmpty()
    val policy = data.models.valueOrNull?.models.orEmpty()
    val allowed = policy.filter { it.enabled }

    val b = statusOf(data.registry) { (if (brains.any { it.available }) Tone.OK else Tone.IDLE) to "${countOf(brains) { it.available }} ready" }
    val m = statusOf(data.models) { (if (allowed.isEmpty()) Tone.IDLE else Tone.OK) to "${allowed.size} allowed" }
    val i = statusOf(data.ibkr) { ibkrState(it) }
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

    NodePage("INTEGRATIONS", "THE SYSTEMS JARVIS CAN REACH", items, selected, { selected = it; sub = "overview" }, onNavigate, mood) {
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
                "ibkr" -> Loaded(data.ibkr, "IBKR", JvIcon.TREND, "Checking the gateway…") { s ->
                    TileGrid(
                        "Gateway",
                        listOf(
                            TileData(JvIcon.API, "Gateway", if (s.reachable) "Reachable" else "Unreachable"),
                            TileData(JvIcon.KEY, "Session", if (s.authenticated) "Logged in" else "Not logged in"),
                            TileData(JvIcon.LINK, "Connection", when (s.connected) { null -> "Not reported"; true -> "Connected"; false -> "Not connected" }),
                            TileData(JvIcon.SHIELD, "Access", "Read-only"),
                        ),
                    )
                }
                else -> Unavailable(current.title, current.icon, detail = INTEGRATION_DETAILS[current.id])
            }
        }
    }
}

fun softwareRows(software: List<com.hawkeynl.jarvis.network.SoftwareItem>) = software.map {
    ActivityItem(
        it.name, JvIcon.SANDBOX, it.name + (it.version?.let { v -> " $v" } ?: ""), it.detail,
        tone = if (it.present) Tone.OK else Tone.IDLE, toneLabel = if (it.present) "Present" else "Missing",
    )
}
