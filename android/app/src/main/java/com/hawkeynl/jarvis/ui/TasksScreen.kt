package com.hawkeynl.jarvis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.CodingSession
import com.hawkeynl.jarvis.network.valueOrNull
import com.hawkeynl.jarvis.security.AgentApprovalService

private val TASK_DETAILS = mapOf(
    "active" to "Coding sessions Jarvis is running or has paused on your Home Node.",
    "waiting" to "Changes an agent proposed. Nothing runs until you approve it with this phone's key or on a trusted desktop.",
    "completed" to "Coding sessions that ended, and every agent action Core recorded.",
    "scheduled" to "Core cannot run tasks at a set time yet.",
    "recurring" to "Core cannot repeat tasks on a schedule yet.",
    "goals" to "Core does not track goals or split them into tasks yet.",
)

private const val AGENT_OFF = "Agent is turned off in Core."

/** Tasks: coding sessions, agent actions waiting for the owner and the agent
 *  audit history. Each waiting action is shown in full and approved or denied
 *  with a strong-biometric prompt. Scheduled and recurring tasks and goals need
 *  Core support. */
@Composable
fun TasksScreen(data: CoreData, mood: Mood, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit, agentApprovals: AgentApprovalService?) {
    LaunchedEffect(Unit) { onRefresh() }
    val now = remember(data.loadedAt) { System.currentTimeMillis() }
    val sessionList = data.sessions.valueOrNull?.sessions.orEmpty()
    val (open, finished) = splitSessions(sessionList)
    val running = open.count { it.state == "active" }
    val waiting = data.pending.valueOrNull?.pending.orEmpty()

    val active = statusOf(data.sessions) { (if (running > 0) Tone.OK else Tone.IDLE) to "$running running" }
    val wait = statusOf(data.pending) {
        if (waiting.isEmpty()) Tone.IDLE to "Nothing waiting"
        else Tone.WARN to "${waiting.size} ${if (waiting.size == 1) "approval" else "approvals"}"
    }
    val done = statusOf(data.sessions) { (if (finished.isEmpty()) Tone.IDLE else Tone.OK) to "${finished.size} finished" }
    val items = listOf(
        NodeItem("active", "Active", "Active", JvIcon.PLAY, active.first, active.second, "Coding sessions in progress"),
        planned("scheduled", "Scheduled", "Scheduled", JvIcon.CALENDAR, "Runs at a set time or day"),
        NodeItem("waiting", "Waiting", "Waiting on you", JvIcon.ALERT, wait.first, wait.second, "Agent actions that need your OK"),
        NodeItem("completed", "Completed", "Completed", JvIcon.TASKS, done.first, done.second, "Finished sessions and agent history"),
        planned("recurring", "Recurring", "Recurring", JvIcon.REPEAT, "Things Jarvis does every week"),
        planned("goals", "Goals", "Goals", JvIcon.GOAL, "Bigger outcomes split into tasks"),
    )
    var selected by rememberSaveable { mutableStateOf("active") }
    var sub by rememberSaveable { mutableStateOf("sessions") }
    val current = items.first { it.id == selected }

    fun sessionRows(list: List<CodingSession>) = list.map { s ->
        val (tone, label) = sessionState(s.state)
        ActivityItem(
            s.id, JvIcon.CODE, s.objective?.ifEmpty { null } ?: "Coding session",
            listOfNotNull(label, s.repository).joinToString(" · "), relativeTime(s.updated_at, now), tone, label,
        )
    }

    NodePage("Tasks", "What Jarvis is working on", items, selected, { selected = it; sub = "sessions" }, onNavigate, mood) {
        Panel(
            current.icon, current.title, current.tone, current.status,
            description = TASK_DETAILS[current.id],
            quote = if (current.id == "waiting") "Jarvis proposes, you decide." else null,
            action = if (current.id in setOf("active", "waiting", "completed")) ({ ActionButton("Refresh", onRefresh) }) else null,
        ) {
            when (current.id) {
                "active" -> Loaded(data.sessions, "Coding sessions", JvIcon.CODE, "Loading sessions…", AGENT_OFF) {
                    TileGrid(
                        "At a glance",
                        listOf(
                            TileData(JvIcon.PLAY, "Running", running.toString()),
                            TileData(JvIcon.CLOCK, "Paused", (open.size - running).toString()),
                            TileData(JvIcon.ALERT, "Waiting on you", if (data.pending is Availability.Ok) waiting.size.toString() else "Unavailable"),
                            TileData(JvIcon.TASKS, "Finished", finished.size.toString()),
                            TileData(
                                JvIcon.SHIELD, "Agent actions",
                                data.agentAudit.valueOrNull?.let { if (it.enabled) "Enabled" else "Disabled on Core" } ?: "Unavailable",
                            ),
                            TileData(JvIcon.CALENDAR, "Last update", relativeTime(sessionList.firstOrNull()?.updated_at, now).ifEmpty { "—" }),
                        ),
                    )
                    ActivityList("Active & paused", sessionRows(open).take(5), "No coding session is running.")
                }
                "waiting" -> Loaded(data.pending, "Approvals", JvIcon.ALERT, "Loading approvals…", AGENT_OFF) {
                    MutedText("Read the whole action first. Approving signs exactly what is shown with this phone's device key; anything this app cannot show in full can only be denied here.")
                    if (waiting.isEmpty()) ActivityList("Waiting for approval", emptyList(), "Nothing is waiting for you.")
                    waiting.forEach { p ->
                        key(p.pending_id) { AgentActionCard(p, relativeTime(p.created_at, now), agentApprovals, onRefresh) }
                    }
                }
                "completed" -> {
                    ChipRow(
                        listOf(ChipItem("sessions", "Sessions", JvIcon.CODE), ChipItem("history", "History", JvIcon.CLOCK)),
                        sub, { sub = it }, small = true,
                    )
                    if (sub == "sessions") {
                        Loaded(data.sessions, "Coding sessions", JvIcon.CODE, "Loading sessions…", AGENT_OFF) {
                            ActivityList("Finished sessions", sessionRows(finished), "No finished sessions yet.")
                        }
                    } else {
                        Loaded(data.agentAudit, "Agent history", JvIcon.SHIELD, "Loading history…", AGENT_OFF) { audit ->
                            ActivityList(
                                "Agent actions",
                                audit.entries.mapIndexed { i, e ->
                                    ActivityItem(
                                        "${e.ts}-$i", JvIcon.SHIELD, e.action, listOfNotNull(e.risk.ifEmpty { null }, e.note).joinToString(" · "),
                                        relativeTime(e.ts, now), outcomeTone(e.outcome), e.outcome,
                                    )
                                },
                                "No agent actions recorded.",
                            )
                        }
                    }
                }
                else -> Unavailable(current.title, current.icon, detail = TASK_DETAILS[current.id])
            }
        }
    }
}
