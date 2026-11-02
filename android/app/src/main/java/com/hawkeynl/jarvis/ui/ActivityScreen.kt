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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hawkeynl.jarvis.network.map
import com.hawkeynl.jarvis.network.valueOrNull
import com.hawkeynl.jarvis.security.AgentApprovalService
import com.hawkeynl.jarvis.security.PairingApproval

/** The Activity tab (I-Activity): what waits for the owner first (agent actions, then
 *  pairing requests), then Tasks, Agents and the agent history. Unlock requests show
 *  above every tab (see [UnlockRequests]). */
@Composable
fun ActivityScreen(data: CoreData, onNavigate: (Screen) -> Unit, onRefresh: () -> Unit, agentApprovals: AgentApprovalService?) {
    LaunchedEffect(Unit) { onRefresh() }
    val now = remember(data.loadedAt) { System.currentTimeMillis() }
    val actions = data.pending.valueOrNull?.pending
    val pairing = data.pairing.valueOrNull?.requests.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabTitle("Activity", "What waits for you and what agents did", Modifier.weight(1f))
            GhostButton("Refresh", onRefresh)
        }
        Column(verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            SectionLabel("Waiting on you")
            Loaded(data.pending, "Agent approvals", JvIcon.ALERT, "Loading approvals…", "Agent is turned off in Core.") {
                PendingActions(it.pending, now, agentApprovals, onRefresh)
            }
            if (pairing.isNotEmpty()) {
                ListSection(null) {
                    pairing.forEachIndexed { i, item ->
                        if (i > 0) ListDivider()
                        NavRow(
                            JvIcon.PHONE, "${PairingApproval.displayName(item.device_name, 40)} wants to pair",
                            "${PairingApproval.displayName(item.platform, 16)} · compare the key fingerprint",
                            { onNavigate(Screen.PAIRING_APPROVALS) }, Tone.WARN,
                        )
                    }
                }
            }
            if (actions?.isEmpty() == true && pairing.isEmpty()) MutedText("Nothing is waiting for you.")
        }
        ListSection("More") {
            linksOf(Tab.ACTIVITY).forEachIndexed { i, screen ->
                if (i > 0) ListDivider()
                when (screen) {
                    Screen.PAIRING_APPROVALS -> {
                        val (tone, status) = statusOf(data.pairing) { if (it.requests.isEmpty()) Tone.IDLE to "None waiting" else Tone.WARN to "${it.requests.size} waiting" }
                        NavRow(JvIcon.KEY, "Pairing requests", status, { onNavigate(screen) }, tone)
                    }
                    Screen.TASKS -> tasksCard(data.sessions.map { it.sessions }, data.pending.map { it.pending.size }).let {
                        NavRow(JvIcon.TASKS, "Tasks", it.detail, { onNavigate(screen) }, it.tone)
                    }
                    Screen.AGENTS -> agentsCard(data.agents).let {
                        NavRow(JvIcon.AGENTS, "Agents", it.detail, { onNavigate(screen) }, it.tone)
                    }
                    else -> MissingLinkRow(Tab.ACTIVITY, screen, onNavigate)
                }
            }
        }
        Loaded(data.agentAudit, "Agent history", JvIcon.SHIELD, "Loading history…", "Agent is turned off in Core.") { audit ->
            ActivityList("History", auditRows(audit.entries, now), "No agent actions recorded.")
        }
    }
}
