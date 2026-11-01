package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkeynl.jarvis.network.PendingAction
import com.hawkeynl.jarvis.security.AgentApprovalService
import com.hawkeynl.jarvis.security.AgentReview
import com.hawkeynl.jarvis.security.PairingApproval
import com.hawkeynl.jarvis.security.ReviewedAgentAction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** Pending agent actions as rows; each opens [ApproveSheet]. [onDecided] runs after
 *  Core confirmed a decision, so the caller can refresh the list. */
@Composable
fun PendingActions(waiting: List<PendingAction>, now: Long, service: AgentApprovalService?, onDecided: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    notice?.let {
        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = JvTheme.colors.positiveText, style = MaterialTheme.typography.bodySmall)
    }
    waiting.forEach { item ->
        key(item.pending_id) { PendingActionRow(item, relativeTime(item.created_at, now)) { notice = null; open = item.pending_id } }
    }
    // The sheet shows the entry as listed now; once it leaves the list the sheet closes.
    waiting.firstOrNull { it.pending_id == open }?.let { item ->
        key(item.pending_id) {
            ApproveSheet(
                item, relativeTime(item.created_at, now), service,
                onDecided = { done -> open = null; notice = done; onDecided() },
                onClose = { open = null },
            )
        }
    }
}

/** What a pending action is called in lists and on its sheet. */
fun pendingTitle(item: PendingAction): String = reviewTitle(AgentReview.of(item), item)

private fun reviewTitle(review: AgentReview, item: PendingAction): String = when ((review as? AgentReview.Approvable)?.action) {
    is ReviewedAgentAction.WriteFile -> "Write a file"
    null -> PairingApproval.displayName(item.action, 40)
}

@Composable
private fun PendingActionRow(item: PendingAction, time: String, onOpen: () -> Unit) {
    val c = JvTheme.colors
    val review = remember(item) { AgentReview.of(item) }
    val approvable = review is AgentReview.Approvable
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale()
            .shadowBorder(JvShapes.lg)
            .clip(JvShapes.lg)
            .background(c.bgSurface)
            .clickable(onClickLabel = "Review", role = Role.Button, onClick = onOpen)
            .padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agent action", Modifier.weight(1f), color = c.accentText, style = MaterialTheme.typography.labelSmall)
            Text(time, color = c.textSecondary, style = MaterialTheme.typography.labelSmall.tabular())
        }
        Text(reviewTitle(review, item), style = MaterialTheme.typography.titleSmall)
        MutedText(if (approvable) "Read all of it before you approve" else "Can only be denied on this phone")
    }
}

/**
 * One pending agent action (I-Approve), shown in full from the JSON whose hash the
 * approval signs. Approve stays off until the content has been scrolled to its end
 * ([reviewedToEnd]); then the service re-reads the entry, checks the hash and asks for
 * a strong biometric to sign. Anything this app cannot parse exactly is shown raw
 * (escaped) and can only be denied. Core has no undo for an approval, so none is offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApproveSheet(item: PendingAction, time: String, service: AgentApprovalService?, onDecided: (String) -> Unit, onClose: () -> Unit) {
    val c = JvTheme.colors
    val review = remember(item) { AgentReview.of(item) }
    var busy by remember { mutableStateOf(false) }
    var denying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Keyed on the action like the gate: new content never inherits an old scroll position.
    val body = remember(item) { ScrollState(0) }
    var seen by remember(item) { mutableStateOf(false) }
    LaunchedEffect(body, item) {
        snapshotFlow { body.value to body.maxValue }.collect { (value, max) -> seen = reviewedToEnd(seen, value, max) }
    }
    val scope = rememberCoroutineScope()
    // No swipe-away while a prompt or request runs: the result must reach this sheet.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })
    fun decide(deny: Boolean, done: String, step: suspend (AgentApprovalService) -> Unit) {
        if (busy || service == null) return
        busy = true
        denying = deny
        error = null
        scope.launch {
            val failure = try {
                step(service)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
            busy = false
            // Server text is shown sanitised and bounded.
            if (failure == null) {
                onDecided(done)
            } else {
                error = PairingApproval.displayName(failure.message ?: "The decision could not be completed.", 160)
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onClose() },
        sheetState = sheet,
        containerColor = c.bgRaised,
        contentColor = c.textPrimary,
        scrimColor = c.bgScrim,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = JvSpace.lg, end = JvSpace.lg, bottom = JvSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(JvShapes.md).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                    JvIconView(JvIcon.SHIELD, tint = c.accentText, size = 22.dp)
                }
                Spacer(Modifier.width(JvSpace.md))
                Column(Modifier.weight(1f)) {
                    Text(reviewTitle(review, item) + "?", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    if (time.isNotEmpty()) MutedText("Proposed $time")
                }
            }
            when (review) {
                is AgentReview.Approvable -> when (val action = review.action) {
                    is ReviewedAgentAction.WriteFile -> {
                        val size = remember(action) { "${action.content.toByteArray(Charsets.UTF_8).size} bytes · ${action.content.lineSequence().count()} lines" }
                        Column(
                            Modifier.fillMaxWidth().clip(JvShapes.md).background(c.bgSunken).padding(JvSpace.md),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Fact("Path", AgentReview.asciiPath(action.path), mono = true)
                            Fact("Size", size, mono = true)
                            // Whether the file existed is Home Node state, not part of what is signed.
                            item.preview.lineSequence().first().takeIf { it.isNotBlank() }?.let {
                                MutedText("When proposed (not signed): " + AgentReview.visible(it, keepNewlines = false))
                            }
                        }
                        Content("File content", AgentReview.visible(action.content), body)
                    }
                }
                is AgentReview.Refused -> {
                    ErrorText(review.reason)
                    review.raw?.let { Content("Raw action", AgentReview.visible(it), body) }
                }
            }
            error?.let { ErrorText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (review is AgentReview.Approvable && service != null) {
                ActionButton(
                    when {
                        busy && !denying -> "Verifying…"
                        seen -> "Approve with fingerprint or face"
                        else -> "Scroll to the end to approve"
                    },
                    { if (seen) decide(false, "Approved. Core runs the action now.") { it.approve(item.pending_id, review.sha256) } },
                    enabled = canApprove(approvable = true, seen = seen, busy = busy, hasService = service != null),
                )
                MutedText("You can only approve what you have seen in full. Approving signs exactly what is shown with this phone's key.")
            }
            GhostButton(
                if (busy && denying) "Verifying…" else "Deny", { decide(true, "Denied. Nothing was run.") { it.deny(item.pending_id) } }, Modifier.fillMaxWidth(),
                enabled = !busy && service != null, danger = true,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(56.dp), color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(
            AgentReview.ltrLines(value), Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.let { if (mono) it.copy(fontFamily = PlexMono, textDirection = TextDirection.Ltr) else it },
        )
    }
}

/** The reviewed text in its own scroll box; [scroll] feeds the scroll-to-end gate. */
@Composable
private fun Content(label: String, value: String, scroll: ScrollState) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
        SectionLabel(label)
        // Strictly left-to-right: each line isolated at display time only.
        Text(
            AgentReview.ltrLines(value),
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(JvShapes.md)
                .background(JvTheme.colors.bgSunken)
                .then(if (focused) Modifier.border(2.dp, JvTheme.colors.focusRing, JvShapes.md) else Modifier)
                // Focusable, so a keyboard or switch user can scroll it to the end too.
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .verticalScroll(scroll)
                .padding(JvSpace.md),
            style = TextStyle(
                fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 18.sp, color = JvTheme.colors.textPrimary,
                textDirection = TextDirection.Ltr,
            ),
        )
    }
}
