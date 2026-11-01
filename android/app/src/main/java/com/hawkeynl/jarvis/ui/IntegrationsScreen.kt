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
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.LoadFailure
import com.hawkeynl.jarvis.network.valueOrNull

private val INTEGRATION_DETAILS = mapOf(
    "brains" to "The brains Core found on your Home Node. Ready means it is set up, not that it is in use.",
    "models" to "Allowing a model lets Jarvis choose it; it never selects a model for a running request. Change access under System health.",
    "tools" to "The software Core found on your Home Node.",
    "ibkr" to "Core's read-only link to Interactive Brokers. Jarvis only reads; it never places orders.",
)

private val LATER = listOf(
    Triple(JvIcon.TERMINAL, "Codex", "Second opinion, behind the broker"),
    Triple(JvIcon.BRANCH, "GitHub", "Repos, PRs and releases"),
    Triple(JvIcon.SANDBOX, "OpenSandbox", "Disposable space for code runs"),
)

private val COST = mapOf("plan" to "Subscription", "metered" to "Pay per use", "local" to "Local", "cheap" to "Cheap", "mid" to "Mid", "pricey" to "Pricey")

/** Integrations: the brains, model catalog and host tools Core probed, the owner model
 *  policy and the IBKR link, one view at a time. Codex, GitHub and OpenSandbox are not
 *  reported by Core yet. */
@Composable
fun IntegrationsScreen(data: CoreData, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val policy = data.models.valueOrNull?.models.orEmpty()
    val allowed = policy.filter { it.enabled }
    var view by rememberSaveable { mutableStateOf("brains") }

    PushedPage(
        screenTitle(Screen.INTEGRATIONS), "The systems Jarvis can reach", { onNavigate(backOf(Screen.INTEGRATIONS)) },
        trailing = { GhostButton("Refresh", onRefresh) },
    ) {
        Segmented(listOf("brains" to "Brains", "models" to "Models", "tools" to "Tools", "ibkr" to "IBKR"), view, { view = it })
        MutedText(INTEGRATION_DETAILS.getValue(view), Modifier.padding(horizontal = JvSpace.xs))
        when (view) {
            "brains" -> Loaded(data.registry, "Registry", JvIcon.SPARK, "Loading registry…", onRetry = onRefresh) { registry ->
                val brains = registry.brains
                fun ready(cost: String) = "${countOf(brains.filter { it.cost == cost }) { it.available }} ready"
                Facts(
                    "At a glance",
                    listOf(
                        "Active brain" to (registry.active_brain ?: "Not reported"),
                        "Brains ready" to countOf(brains) { it.available },
                        "Subscription" to ready("plan"),
                        "Pay per use" to ready("metered"),
                        "Local" to ready("local"),
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
            }
            "models" -> {
                Loaded(data.models, "Model policy", JvIcon.LAYERS, "Loading model policy…", onRetry = onRefresh) {
                    Facts(
                        "Owner policy",
                        listOf(
                            "Allowed" to allowed.size.toString(),
                            "Blocked" to (policy.size - allowed.size).toString(),
                            "In catalog" to (data.registry.valueOrNull?.models?.size?.toString() ?: "Unavailable"),
                            "Changes" to "Under System health",
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
                Loaded(data.registry, "Model catalog", JvIcon.LAYERS, "Loading catalog…", onRetry = onRefresh) { registry ->
                    ActivityList(
                        "Catalog",
                        registry.models.map {
                            ActivityItem(
                                "${it.backend}/${it.id}", JvIcon.LAYERS, it.id, "${it.backend} · ${it.modelClass} · ${COST[it.cost] ?: it.cost}",
                                tone = if (it.available) Tone.OK else Tone.IDLE, toneLabel = if (it.available) "Provider set up" else "Provider not set up",
                            )
                        },
                        "Core reported no models.",
                    )
                    MutedText(
                        "Set up means Core has a key or local install for the provider. Whether Jarvis may use a model is the owner policy.",
                        Modifier.padding(horizontal = JvSpace.xs),
                    )
                }
            }
            "tools" -> Loaded(data.registry, "Host tools", JvIcon.SANDBOX, "Loading registry…", onRetry = onRefresh) { registry ->
                ActivityList("Host tools", softwareRows(registry.software), "Core reported no host tools.")
            }
            else -> IbkrDetails(data, onRefresh)
        }
        LaterSection(LATER, "Core does not report Codex, GitHub or OpenSandbox to the app yet.")
    }
}

/** The IBKR link from GET /v1/trading/portfolio, on Integrations and the Trading tab. */
@Composable
fun IbkrDetails(data: CoreData, onRetry: (() -> Unit)? = null) {
    if (data.ibkr == Availability.Failed(LoadFailure.UNAVAILABLE)) {
        Unavailable("IBKR", JvIcon.TREND, kind = UnavailableKind.ERROR, detail = IBKR_NOT_SYNCED, onRetry = onRetry)
        return
    }
    Loaded(data.ibkr, "IBKR", JvIcon.TREND, "Checking the IBKR link…", onRetry = onRetry) { p ->
        val (tone, state) = ibkrState(data.ibkr, data.loadedAt)
        ListSection("IBKR link") {
            FactRow("Connection", state, tone)
            ListDivider()
            FactRow("Last sync", relativeTime(p.as_of, data.loadedAt).ifEmpty { "—" })
            ListDivider()
            FactRow("Positions", p.positions.size.toString())
            ListDivider()
            FactRow("Access", "Read-only")
        }
    }
}

/** The Trading tab: the read-only IBKR link Core reports today. Dashboards, proposals
 *  and orders are not built yet, so the tab says so instead of showing sample data. */
@Composable
fun TradingScreen(data: CoreData, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabTitle("Trading", "Your read-only link to Interactive Brokers", Modifier.weight(1f))
            GhostButton("Refresh", onRefresh)
        }
        IbkrDetails(data, onRefresh)
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
