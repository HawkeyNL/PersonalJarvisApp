package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.ProposalState
import com.hawkeynl.jarvis.network.ROADMAP_ADDED
import com.hawkeynl.jarvis.network.ROADMAP_CHECK
import com.hawkeynl.jarvis.network.ROADMAP_NOT_CONFIRMED
import com.hawkeynl.jarvis.network.ROADMAP_VIEW
import com.hawkeynl.jarvis.network.RoadmapChatCard
import com.hawkeynl.jarvis.network.linkLabel
import com.hawkeynl.jarvis.network.roadmapGroups
import com.hawkeynl.jarvis.network.roadmapHeading
import com.hawkeynl.jarvis.network.roadmapSummary

/** A roadmap a chat answer proposed, under that answer (desktop RoadmapProposalCard.vue): every
 *  item by phase with its status, as Add sends it. Nothing is made until Add. Core's text shows
 *  as plain text only, never as a link. */
@Composable
internal fun RoadmapProposalCard(card: RoadmapChatCard, locked: Boolean, actions: JarvisViewModel) {
    val c = JvTheme.colors
    val p = card.proposal
    val busy = card.state == ProposalState.ADDING
    Column(
        Modifier.clip(JvShapes.md).background(c.bgSunken).padding(horizontal = JvSpace.md, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xs),
    ) {
        Text(roadmapHeading(p), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        MutedText(roadmapSummary(p))
        roadmapGroups(p).forEach { g ->
            SectionLabel(g.phase ?: "No phase", Modifier.padding(top = JvSpace.xs).semantics { heading() })
            g.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    StatusBadge(item.status.label, tone = statusTone(item.status))
                }
                if (item.links.isNotEmpty()) MutedText(item.links.joinToString(", ", transform = ::linkLabel))
                item.notes?.let { MutedText(it) }
            }
        }
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        when (card.state) {
            ProposalState.ADDED -> Text(ROADMAP_ADDED, live, style = MaterialTheme.typography.bodyMedium)
            ProposalState.UNCONFIRMED -> Text(ROADMAP_NOT_CONFIRMED, live, style = MaterialTheme.typography.bodyMedium)
            else -> if (card.error.isNotEmpty()) ErrorText(card.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            when (card.state) {
                ProposalState.ADDED -> GhostButton(ROADMAP_VIEW, { actions.openRoadmap(card.roadmapId) })
                ProposalState.UNCONFIRMED -> GhostButton(ROADMAP_CHECK, { actions.openRoadmap(null) })
                else -> GhostButton("Add", { actions.addRoadmapProposal(p.id) }, enabled = !locked && !busy)
            }
            GhostButton("Dismiss", { actions.dismissRoadmapProposal(p.id) }, enabled = !busy)
        }
    }
}
