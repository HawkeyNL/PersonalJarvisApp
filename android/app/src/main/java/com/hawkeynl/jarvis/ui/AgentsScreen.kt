package com.hawkeynl.jarvis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hawkeynl.jarvis.network.AgentInfo
import com.hawkeynl.jarvis.network.valueOrNull
import java.text.NumberFormat
import java.util.Locale

private fun number(n: Long) = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(n)

/** Agents grouped by group (alphabetical, ungrouped last), names sorted. */
private fun ordered(agents: List<AgentInfo>) =
    agents.sortedWith(compareBy<AgentInfo>({ it.group.isNullOrBlank() }, { it.group.orEmpty().lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) }))

/** Agents: the agent bundle installed on Core (read-only metadata and this
 *  month's usage). Core reports no live run state, so none is shown. */
@Composable
fun AgentsScreen(data: CoreData, mood: Mood, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val response = data.agents.valueOrNull
    val usageReason = response?.usage_unavailable_reason
    val agents = ordered(response?.agents.orEmpty())
    val items = agents.map { agent ->
        val (tone, status) = agentStatus(agent.usage, usageReason)
        NodeItem(agent.id, agent.name, agent.name, JvIcon.AGENTS, tone, status, agent.description)
    }
    var chosen by rememberSaveable { mutableStateOf("") }
    var sub by rememberSaveable { mutableStateOf("overview") }
    val agent = agents.firstOrNull { it.id == chosen } ?: agents.firstOrNull()
    val current = items.firstOrNull { it.id == agent?.id }

    NodePage("AGENTS", "YOUR PERSONAL AI SYSTEM", items, agent?.id.orEmpty(), { chosen = it; sub = "overview" }, onNavigate, mood) {
        if (agent == null || current == null) {
            Loaded(data.agents, "Agents", JvIcon.AGENTS, "Loading agents…", "Agent is turned off in Core.") {
                Unavailable(
                    "Agents", JvIcon.AGENTS,
                    detail = if (it.unavailable_reason != null) "Core has no agent bundle installed, so there are no agents to show."
                    else "The installed agent bundle has no agents.",
                )
            }
            return@NodePage
        }
        Panel(
            current.icon, agent.name, current.tone, current.status,
            description = agent.description.ifEmpty { null },
            quote = agent.group?.takeIf { it.isNotBlank() }?.let { "Group: $it" },
        ) {
            ChipRow(
                listOf(ChipItem("overview", "Overview", JvIcon.DOC), ChipItem("tools", "Tools", JvIcon.TERMINAL), ChipItem("usage", "Usage", JvIcon.TREND)),
                sub, { sub = it }, small = true,
            )
            when (sub) {
                "overview" -> {
                    val limits = agent.limits
                    TileGrid(
                        "Policy & limits",
                        listOf(
                            TileData(JvIcon.LAYERS, "Model policy", agent.model_policy.ifEmpty { "—" }),
                            TileData(JvIcon.TERMINAL, "Allowed tools", agent.allowed_tools.size.toString()),
                            TileData(JvIcon.CLOCK, "Max runtime", limits?.let { "${number(it.max_runtime_seconds)} s" } ?: "—"),
                            TileData(JvIcon.REPEAT, "Parallel runs", limits?.let { "Up to ${it.max_parallel_runs}" } ?: "—"),
                            TileData(JvIcon.DOC, "Max context", limits?.let { "${number(it.max_context_chars)} chars" } ?: "—"),
                            TileData(JvIcon.LINES, "Max output", limits?.let { "${number(it.max_output_chars)} chars" } ?: "—"),
                        ),
                    )
                    ActivityList(
                        "All agents",
                        agents.map {
                            val (tone, label) = agentStatus(it.usage, usageReason)
                            ActivityItem(it.id, JvIcon.AGENTS, it.name, it.model_policy, tone = tone, toneLabel = label)
                        },
                        "",
                        rowActions = { item -> if (item.id != agent.id) GhostButton("Show", { chosen = item.id; sub = "overview" }) },
                    )
                }
                "tools" -> ActivityList(
                    "Allowed tools",
                    agent.allowed_tools.map { ActivityItem(it, JvIcon.TERMINAL, it, tone = Tone.OK, toneLabel = "Allowed") },
                    "This agent may not use any tools.",
                )
                else -> {
                    val usage = agent.usage
                    when {
                        usage == null && usageReason == AGENT_USAGE_NOT_INSTRUMENTED -> Unavailable(
                            "Usage", JvIcon.TREND, detail = "Not measured yet: Core does not record which agent made each call.",
                        )
                        usage == null -> Unavailable("Usage", JvIcon.TREND, kind = UnavailableKind.ERROR, detail = "Core could not read the usage statistics for this month.")
                        else -> TileGrid(
                            "This month",
                            listOf(
                                TileData(JvIcon.API, "Requests", number(usage.requests)),
                                TileData(JvIcon.TREND, "Spent", eur(usage.spent_eur)),
                                TileData(JvIcon.LAYERS, "Tokens in / out", "${number(usage.input_tokens)} / ${number(usage.output_tokens)}"),
                                TileData(JvIcon.CALENDAR, "Last used", usage.last_used?.let { relativeTime(it, System.currentTimeMillis()).ifEmpty { it } } ?: "Not this month"),
                            ),
                        )
                    }
                }
            }
        }
    }
}
