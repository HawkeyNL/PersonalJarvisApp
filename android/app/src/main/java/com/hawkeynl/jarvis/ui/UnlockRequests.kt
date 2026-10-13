package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.UnlockRequestItem
import com.hawkeynl.jarvis.security.PairingApproval
import com.hawkeynl.jarvis.security.UnlockApprovalService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Other devices of this account asking to be unlocked. Polls only while composed, which
 *  is only while this app is in the foreground, unlocked and signed in. */
@Composable
fun UnlockRequests(service: UnlockApprovalService) {
    var requests by remember { mutableStateOf(emptyList<UnlockRequestItem>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(service) {
        while (true) {
            val result = try {
                Result.success(service.pending(waitSeconds = 20))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            requests = result.getOrDefault(emptyList())
            // An answer with requests returns at once; do not spin on it or on errors.
            if (result.isFailure || requests.isNotEmpty()) delay(10_000)
        }
    }
    if (requests.isEmpty() && error == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let { ErrorText(it) }
        requests.forEach { item ->
            Panel(JvIcon.SHIELD, "Unlock request", Tone.WARN, PairingApproval.displayName(item.platform, 16)) {
                MutedText("\"${PairingApproval.displayName(item.device_name, 40)}\" asks to unlock Jarvis. Approve only if you just asked on that device.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val decide: (Boolean) -> Unit = { approve ->
                        if (busy == null) {
                            busy = item.id
                            error = null
                            scope.launch {
                                val failure = try {
                                    if (approve) service.approve(item.id) else service.deny(item.id)
                                    null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    failure
                                }
                                error = failure?.let { it.message ?: "The decision could not be completed." }
                                if (failure == null) requests = requests.filter { it.id != item.id }
                                busy = null
                            }
                        }
                    }
                    GhostButton("Deny", { decide(false) }, enabled = busy == null, danger = true)
                    ActionButton(if (busy == item.id) "Verifying…" else "Approve", { decide(true) }, Modifier.weight(1f), enabled = busy == null)
                }
            }
        }
    }
}
