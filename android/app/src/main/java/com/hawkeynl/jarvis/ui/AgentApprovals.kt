package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/** One pending agent action, shown in full from the JSON whose hash the approval signs.
 *  Anything this app cannot parse exactly is shown raw (escaped) and can only be denied. */
@Composable
fun AgentActionCard(item: PendingAction, time: String, service: AgentApprovalService?, onDecided: () -> Unit) {
    val review = remember(item) { AgentReview.of(item) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun decide(step: suspend (AgentApprovalService) -> Unit) {
        if (busy || service == null) return
        busy = true
        error = null
        scope.launch {
            try {
                step(service)
                onDecided()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Server text is shown sanitised and bounded.
                error = PairingApproval.displayName(failure.message ?: "The decision could not be completed.", 160)
            }
            busy = false
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(Jv.R14).background(Jv.ListBg).border(1.dp, Jv.Warn.copy(alpha = 0.4f), Jv.R14).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title(review, item), Modifier.weight(1f), style = exo(14f, FontWeight.Medium, Jv.Bright))
            Text(time, style = exo(11f, color = Jv.Text5))
        }
        when (review) {
            is AgentReview.Approvable -> when (val action = review.action) {
                is ReviewedAgentAction.WriteFile -> {
                    Field("Path", AgentReview.asciiPath(action.path))
                    // Whether the file existed is Home Node state, not part of what is signed.
                    item.preview.lineSequence().first().takeIf { it.isNotBlank() }?.let {
                        MutedText("When proposed (not signed): " + AgentReview.visible(it, keepNewlines = false))
                    }
                    Field("Content · ${action.content.toByteArray(Charsets.UTF_8).size} bytes", AgentReview.visible(action.content))
                }
            }
            is AgentReview.Refused -> {
                ErrorText(review.reason)
                review.raw?.let { Field("Raw action", AgentReview.visible(it)) }
            }
        }
        error?.let { ErrorText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(if (busy) "Verifying…" else "Deny", { decide { it.deny(item.pending_id) } }, enabled = !busy && service != null, danger = true)
            if (review is AgentReview.Approvable && service != null) {
                ActionButton("Approve", { decide { it.approve(item.pending_id, review.sha256) } }, Modifier.weight(1f), enabled = !busy)
            }
        }
    }
}

private fun title(review: AgentReview, item: PendingAction): String = when ((review as? AgentReview.Approvable)?.action) {
    is ReviewedAgentAction.WriteFile -> "Write a file"
    null -> PairingApproval.displayName(item.action, 40)
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(label)
        // Strictly left-to-right: each line isolated at display time only.
        Text(
            AgentReview.ltrLines(value),
            Modifier.fillMaxWidth().clip(Jv.R10).background(Jv.Tile).padding(8.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = Jv.Text1,
                textDirection = TextDirection.Ltr,
            ),
        )
    }
}
