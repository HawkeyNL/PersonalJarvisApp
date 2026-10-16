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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
                                // Server text is shown sanitised and bounded.
                                error = failure?.let { PairingApproval.displayName(it.message ?: "The decision could not be completed.", 160) }
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
