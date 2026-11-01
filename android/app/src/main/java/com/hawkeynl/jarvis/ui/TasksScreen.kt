package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hawkeynl.jarvis.network.AgentAuditEntry
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.CodingSession
import com.hawkeynl.jarvis.network.valueOrNull
import com.hawkeynl.jarvis.security.AgentApprovalService

private val LATER = listOf(
    Triple(JvIcon.CALENDAR, "Scheduled", "Runs at a set time or day"),
    Triple(JvIcon.REPEAT, "Recurring", "Things Jarvis does every week"),
    Triple(JvIcon.GOAL, "Goals", "Bigger outcomes split into tasks"),
)

private const val AGENT_OFF = "Agent is turned off in Core."

/** Tasks (D-Runs): coding sessions, agent actions waiting for the owner and the agent
 *  audit history, one view at a time. Each waiting action opens in a sheet, is shown in
 *  full and is approved or denied with a strong-biometric prompt. Scheduled and recurring
 *  tasks and goals need Core support. */
@Composable
fun TasksScreen(data: CoreData, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit, agentApprovals: AgentApprovalService?) {
    LaunchedEffect(Unit) { onRefresh() }
    val now = remember(data.loadedAt) { System.currentTimeMillis() }
    val sessionList = data.sessions.valueOrNull?.sessions.orEmpty()
    val (open, finished) = splitSessions(sessionList)
    val running = open.count { it.state == "active" }
    val waiting = data.pending.valueOrNull?.pending.orEmpty()
    var view by rememberSaveable { mutableStateOf("active") }

    fun sessionRows(list: List<CodingSession>) = list.map { s ->
        val (tone, label) = sessionState(s.state)
        ActivityItem(s.id, JvIcon.CODE, s.objective?.ifEmpty { null } ?: "Coding session", s.repository, relativeTime(s.updated_at, now), tone, label)
    }

    PushedPage(
        screenTitle(Screen.TASKS), "What Jarvis is working on", { onNavigate(backOf(Screen.TASKS)) },
        trailing = { GhostButton("Refresh", onRefresh) },
    ) {
        Segmented(
            listOf("active" to "Active", "waiting" to if (waiting.isEmpty()) "Waiting" else "Waiting · ${waiting.size}", "done" to "Done"),
            view, { view = it },
        )
        when (view) {
            "active" -> Loaded(data.sessions, "Coding sessions", JvIcon.CODE, "Loading sessions…", AGENT_OFF, onRefresh) {
                Facts(
                    "At a glance",
                    listOf(
                        "Running" to running.toString(),
                        "Paused" to (open.size - running).toString(),
                        "Waiting on you" to if (data.pending is Availability.Ok) waiting.size.toString() else "Unavailable",
                        "Finished" to finished.size.toString(),
                        "Agent actions" to (data.agentAudit.valueOrNull?.let { if (it.enabled) "Enabled" else "Disabled on Core" } ?: "Unavailable"),
                        "Last update" to relativeTime(sessionList.firstOrNull()?.updated_at, now).ifEmpty { "—" },
                    ),
                )
                ActivityList("Running and paused", sessionRows(open), "No coding session is running.")
            }
            "waiting" -> Loaded(data.pending, "Approvals", JvIcon.ALERT, "Loading approvals…", AGENT_OFF, onRefresh) {
                MutedText(
                    "Changes an agent proposed. Nothing runs until you approve it. Read the whole action first: approving signs " +
                        "exactly what is shown with this phone's device key; anything this app cannot show in full can only be denied here.",
                    Modifier.padding(horizontal = JvSpace.xs),
                )
                if (waiting.isEmpty()) {
                    ListSection(null) {
                        EmptyState("Nothing is waiting for you", body = "Agent actions that need your OK show up here.", icon = JvIcon.SHIELD)
                    }
                }
                PendingActions(waiting, now, agentApprovals, onRefresh)
            }
            else -> {
                Loaded(data.sessions, "Coding sessions", JvIcon.CODE, "Loading sessions…", AGENT_OFF, onRefresh) {
                    ActivityList("Finished sessions", sessionRows(finished), "No finished sessions yet.")
                }
                Loaded(data.agentAudit, "Agent history", JvIcon.SHIELD, "Loading history…", AGENT_OFF, onRefresh) { audit ->
                    ActivityList("Agent history", auditRows(audit.entries, now), "No agent actions recorded.")
                }
            }
        }
        LaterSection(LATER)
    }
}

/** Agent audit entries as list rows, in the order Core sends them. */
fun auditRows(entries: List<AgentAuditEntry>, now: Long): List<ActivityItem> = entries.mapIndexed { i, e ->
    ActivityItem(
        "${e.ts}-$i", JvIcon.SHIELD, e.action, listOfNotNull(e.risk.ifEmpty { null }, e.note).joinToString(" · "),
        relativeTime(e.ts, now), outcomeTone(e.outcome), e.outcome,
    )
}
