package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hawkeynl.jarvis.network.UnlockRequestItem
import com.hawkeynl.jarvis.security.PairingApproval
import com.hawkeynl.jarvis.security.UNLOCK_EXPIRES_AFTER_SECONDS
import com.hawkeynl.jarvis.security.UnlockApprovalService
import com.hawkeynl.jarvis.security.UnlockCode
import com.hawkeynl.jarvis.security.WrongUnlockNumber
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Other devices of this account asking to be unlocked: a row above every tab that opens
 *  the number-match sheet (I-Unlock). Polls only while composed, which is only while this
 *  app is in the foreground, unlocked and signed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockRequests(service: UnlockApprovalService) {
    var requests by remember { mutableStateOf(emptyList<UnlockRequestItem>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Pair<String, List<Int>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(service, lifecycle) {
        // Compose keeps this on screen while the app is stopped; poll only while resumed.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val result = try {
                    Result.success(service.pending(waitSeconds = 20))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
                requests = result.getOrDefault(emptyList())
                // An answer with requests, an error or a locked app returns at once: never spin.
                delay(if (result.isFailure || requests.isNotEmpty()) 10_000 else 2_000)
            }
        }
    }
    // Approve first shows the numbers; a pick then approves (or, if wrong, denies).
    val launchStep: (UnlockRequestItem, suspend () -> Boolean) -> Unit = { item, step ->
        if (busy == null) {
            busy = item.id
            error = null
            scope.launch {
                val failure = try {
                    if (step()) {
                        requests = requests.filter { it.id != item.id }
                        open = null
                    }
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failure
                }
                // Server text is shown sanitised and bounded.
                error = failure?.let { PairingApproval.displayName(it.message ?: "The decision could not be completed.", 160) }
                // A wrong number already denied it.
                if (failure is WrongUnlockNumber) {
                    requests = requests.filter { it.id != item.id }
                    open = null
                }
                busy = null
            }
        }
    }
    // A request that left the list (decided, expired or a failed poll) never reopens its sheet by itself.
    LaunchedEffect(requests) {
        if (busy == null && requests.none { it.id == open }) {
            open = null
            picking = null
        }
    }
    if (requests.isEmpty() && error == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // While the sheet is open it shows the error itself.
        if (open == null) error?.let { ErrorText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (requests.isNotEmpty()) {
            ListSection(null) {
                requests.forEachIndexed { i, item ->
                    if (i > 0) ListDivider()
                    NavRow(
                        JvIcon.SHIELD, "Unlock request from \"${PairingApproval.displayName(item.device_name, 40)}\"",
                        "${PairingApproval.displayName(item.platform, 16)} · tap to review", { open = item.id }, Tone.WARN,
                    )
                }
            }
        }
    }
    // The sheet shows the request as listed now; decided or gone, it closes.
    val item = requests.firstOrNull { it.id == open } ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || busy == null })
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(item.id) { while (true) { now = System.currentTimeMillis() / 1000; delay(1_000) } }
    val c = JvTheme.colors
    ModalBottomSheet(
        onDismissRequest = { if (busy == null) { open = null; picking = null } },
        sheetState = sheet,
        containerColor = c.bgRaised,
        contentColor = c.textPrimary,
        scrimColor = c.bgScrim,
    ) {
        val device = PairingApproval.displayName(item.device_name, 40)
        val left = PairingApproval.countdown(item.created_at + UNLOCK_EXPIRES_AFTER_SECONDS, now)
        Column(
            Modifier.fillMaxWidth().padding(start = JvSpace.xxl, end = JvSpace.xxl, bottom = JvSpace.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(JvSpace.lg),
        ) {
            Box(Modifier.size(56.dp).clip(JvShapes.xl).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                JvIconView(JvIcon.SHIELD, tint = c.accentText, size = 26.dp)
            }
            Text("Unlock \"$device\"?", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "${PairingApproval.displayName(item.platform, 16)} · " + if (left == "Expired") "expired" else "expires in $left",
                color = c.textSecondary, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.Center,
            )
            Text(
                "\"$device\" asks to unlock Jarvis. Approve only if you just asked on that device.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            error?.let { ErrorText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            val choices = picking?.takeIf { it.first == item.id }?.second
            if (choices != null) {
                Text("Tap the number shown on that device. A wrong number denies the request.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                    choices.forEach { choice ->
                        ActionButton(UnlockCode.display(choice), {
                            picking = null
                            launchStep(item) { service.approve(item.id, choice); true }
                        }, Modifier.weight(1f).heightIn(min = 72.dp), enabled = busy == null, style = MaterialTheme.typography.titleLarge.tabular(), maxLines = 1)
                    }
                }
            } else {
                ActionButton(if (busy == item.id) "Verifying…" else "Approve", {
                    picking = null
                    launchStep(item) { picking = item.id to service.choices(item.id); false }
                }, enabled = busy == null)
            }
            GhostButton("Deny, this was not me", {
                picking = null
                launchStep(item) { service.deny(item.id); true }
            }, Modifier.fillMaxWidth(), enabled = busy == null, danger = true)
        }
    }
}
