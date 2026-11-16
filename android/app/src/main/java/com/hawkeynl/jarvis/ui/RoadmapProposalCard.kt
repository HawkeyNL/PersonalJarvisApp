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
import com.hawkeynl.jarvis.network.ROADMAP_NOT_CONFIRMED
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
        p.description?.let { MutedText(it) }
        roadmapGroups(p).forEach { g ->
            SectionLabel(g.phase ?: "No phase", Modifier.padding(top = JvSpace.xs).semantics { heading() })
            g.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    StatusBadge(item.status.label, tone = statusTone(item.status))
                }
                val meta = item.links.map(::linkLabel) + listOfNotNull(item.notes)
                if (meta.isNotEmpty()) MutedText(meta.joinToString(" · "))
            }
        }
        if (card.error.isNotEmpty() && card.state == ProposalState.OPEN) ErrorText(card.error)
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            when (card.state) {
                ProposalState.ADDED -> GhostButton(ROADMAP_ADDED, { actions.openRoadmap(card.roadmapId) }, live.weight(1f, fill = false))
                ProposalState.UNCONFIRMED -> GhostButton(ROADMAP_NOT_CONFIRMED, { actions.openRoadmap(null) }, live.weight(1f, fill = false))
                else -> GhostButton("Add", { actions.addRoadmapProposal(p.id) }, enabled = !locked && !busy)
            }
            GhostButton("Dismiss", { actions.dismissRoadmapProposal(p.id) }, enabled = !busy)
        }
    }
}
