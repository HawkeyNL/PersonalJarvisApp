package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hawkeynl.jarvis.network.PROPOSAL_ADDED
import com.hawkeynl.jarvis.network.PROPOSAL_CHECK_JOBS
import com.hawkeynl.jarvis.network.PROPOSAL_EDITOR_TITLE
import com.hawkeynl.jarvis.network.PROPOSAL_VIEW_JOB
import com.hawkeynl.jarvis.network.ProposalCard
import com.hawkeynl.jarvis.network.ProposalState
import com.hawkeynl.jarvis.network.proposalDetail
import com.hawkeynl.jarvis.network.proposalLabel
import com.hawkeynl.jarvis.network.proposalSchedule
import com.hawkeynl.jarvis.network.proposalTitle

/** One job a chat answer proposed, under that answer (desktop JobProposalCard.vue). Nothing
 *  is made until Add (the proposal as is) or Add in the editor (the edited job). Core's text
 *  shows as plain text only. The lock replaces the whole shell, so it also closes the editor. */
@Composable
internal fun JobProposalCard(card: ProposalCard, locked: Boolean, actions: JarvisViewModel) {
    val c = JvTheme.colors
    val draft = card.draft
    val title = proposalTitle(draft)
    val busy = card.state == ProposalState.ADDING
    var editing by remember(card.id) { mutableStateOf(false) }
    // A resolved card closes its editor.
    LaunchedEffect(card.state) { if (card.state == ProposalState.ADDED || card.state == ProposalState.UNCONFIRMED) editing = false }
    Column(
        Modifier.clip(JvShapes.md).background(c.bgSunken).padding(horizontal = JvSpace.md, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            StatusBadge(draft.kind.label)
            Text(title, Modifier.weight(1f, fill = false).semantics { contentDescription = proposalLabel(title) }, style = MaterialTheme.typography.titleSmall)
        }
        MutedText(proposalSchedule(draft))
        proposalDetail(draft)?.let { MutedText(it) }
        when (card.state) {
            ProposalState.ADDED -> Text(PROPOSAL_ADDED, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            ProposalState.UNCONFIRMED -> Text(card.error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            else -> if (card.error.isNotEmpty() && !editing) ErrorText(card.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            when (card.state) {
                ProposalState.ADDED -> GhostButton(PROPOSAL_VIEW_JOB, { actions.openJob(card.jobId) })
                ProposalState.UNCONFIRMED -> GhostButton(PROPOSAL_CHECK_JOBS, { actions.openJob(null) })
                else -> {
                    GhostButton("Add", { actions.addProposal(card.id) }, enabled = !locked && !busy)
                    GhostButton("Edit", { editing = true }, enabled = !locked && !busy)
                }
            }
            GhostButton("Dismiss", { actions.dismissProposal(card.id) }, enabled = !busy)
        }
    }
    if (editing && !locked) {
        Dialog(onDismissRequest = { editing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxSize().background(c.bgPage).imePadding().verticalScroll(rememberScrollState()).padding(JvSpace.lg)
                    .semantics { paneTitle = PROPOSAL_EDITOR_TITLE },
                verticalArrangement = Arrangement.spacedBy(JvSpace.md),
            ) {
                Text(PROPOSAL_EDITOR_TITLE, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                JobForm(actions, draft, creating = true, busy, "Add", onCancel = { editing = false }, onSave = { actions.addProposal(card.id, it) })
                if (card.error.isNotEmpty()) ErrorText(card.error)
            }
        }
    }
}
