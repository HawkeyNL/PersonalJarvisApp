package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.hawkeynl.jarvis.network.AgentInfo
import com.hawkeynl.jarvis.network.valueOrNull
import java.text.NumberFormat
import java.util.Locale

private fun number(n: Long) = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(n)

/** Agents grouped by group (alphabetical, ungrouped last), names sorted. */
private fun ordered(agents: List<AgentInfo>) =
    agents.sortedWith(compareBy<AgentInfo>({ it.group.isNullOrBlank() }, { it.group.orEmpty().lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) }))

/** Agents: the agent bundle installed on Core as lists per group; a row opens the agent's
 *  policy, tools and this month's usage in a sheet. Core reports no live run state, so
 *  none is shown. */
@Composable
fun AgentsScreen(data: CoreData, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    val response = data.agents.valueOrNull
    val usageReason = response?.usage_unavailable_reason
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }

    PushedPage(
        screenTitle(Screen.AGENTS), "The agent bundle installed on Core", { onNavigate(backOf(Screen.AGENTS)) },
        trailing = { GhostButton("Refresh", onRefresh) },
    ) {
        Loaded(data.agents, "Agents", JvIcon.AGENTS, "Loading agents…", "Agent is turned off in Core.", onRefresh) { loaded ->
            val groups = ordered(loaded.agents).groupBy { it.group?.takeIf { g -> g.isNotBlank() } }
            if (groups.isEmpty()) {
                ListSection(null) {
                    EmptyState(
                        "No agents",
                        body = if (loaded.unavailable_reason != null) "Core has no agent bundle installed, so there are no agents to show."
                        else "The installed agent bundle has no agents.",
                        icon = JvIcon.AGENTS,
                    )
                }
            }
            for ((group, agents) in groups) {
                ActivityList(
                    group ?: if (groups.size == 1) "Installed agents" else "Other",
                    agents.map {
                        val (tone, label) = agentStatus(it.usage, usageReason)
                        ActivityItem(it.id, JvIcon.AGENTS, it.name, it.model_policy, tone = tone, toneLabel = label)
                    },
                    "",
                    onRowClick = { chosen = it.id },
                    rowClickLabel = "Show details",
                )
            }
        }
    }
    response?.agents?.firstOrNull { it.id == chosen }?.let { AgentSheet(it, usageReason) { chosen = null } }
}

/** One agent's policy and limits, allowed tools and usage this month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSheet(agent: AgentInfo, usageReason: String?, onClose: () -> Unit) {
    val c = JvTheme.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bgRaised,
        contentColor = c.textPrimary,
        scrimColor = c.bgScrim,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = JvSpace.lg, end = JvSpace.lg, bottom = JvSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(JvSpace.xl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                Text(agent.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                agent.description.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                agent.group?.takeIf { it.isNotBlank() }?.let { MutedText("Group: $it") }
            }
            val limits = agent.limits
            Facts(
                "Policy and limits",
                listOf(
                    "Model policy" to agent.model_policy.ifEmpty { "—" },
                    "Allowed tools" to agent.allowed_tools.size.toString(),
                    "Max runtime" to (limits?.let { "${number(it.max_runtime_seconds)} s" } ?: "—"),
                    "Parallel runs" to (limits?.let { "Up to ${it.max_parallel_runs}" } ?: "—"),
                    "Max context" to (limits?.let { "${number(it.max_context_chars)} chars" } ?: "—"),
                    "Max output" to (limits?.let { "${number(it.max_output_chars)} chars" } ?: "—"),
                ),
            )
            ActivityList(
                "Allowed tools",
                agent.allowed_tools.map { ActivityItem(it, JvIcon.TERMINAL, it, tone = Tone.OK, toneLabel = "Allowed") },
                "This agent may not use any tools.",
            )
            val usage = agent.usage
            when {
                usage == null && usageReason == AGENT_USAGE_NOT_INSTRUMENTED -> Unavailable(
                    "Usage", JvIcon.TREND, detail = "Not measured yet: Core does not record which agent made each call.",
                )
                usage == null -> Unavailable("Usage", JvIcon.TREND, kind = UnavailableKind.ERROR, detail = "Core could not read the usage statistics for this month.")
                else -> Facts(
                    "This month",
                    listOf(
                        "Requests" to number(usage.requests),
                        "Spent" to eur(usage.spent_eur),
                        "Tokens in / out" to "${number(usage.input_tokens)} / ${number(usage.output_tokens)}",
                        "Last used" to (usage.last_used?.let { relativeTime(it, System.currentTimeMillis()).ifEmpty { it } } ?: "Not this month"),
                    ),
                )
            }
        }
    }
}
