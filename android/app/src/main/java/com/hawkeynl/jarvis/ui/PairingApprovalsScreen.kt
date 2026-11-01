package com.hawkeynl.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.PairingRequestItem
import com.hawkeynl.jarvis.security.PairingApproval
import com.hawkeynl.jarvis.security.PairingApprovalService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pending new-device pairing requests. Approve and Deny need a strong biometric
 *  (never the device PIN, pattern or password); the fingerprint is computed here
 *  from the key the approval signs. Candidate details are unverified hints. */
@Composable
fun PairingApprovalsScreen(
    data: CoreData,
    onNavigate: (Screen) -> Unit,
    onRefresh: () -> Unit,
    onDecided: (String) -> Unit,
    service: PairingApprovalService?,
) {
    // Core allows five pairing-list reads per source IP per minute.
    LaunchedEffect(Unit) { while (true) { onRefresh(); delay(30_000) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() / 1000 } }
    val biometrics = remember(data.loadedAt) { service?.biometricsAvailable?.invoke() == true }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val decide: (PairingRequestItem, Boolean) -> Unit = { item, approve ->
        if (service != null && busy == null) {
            busy = item.id
            error = null
            scope.launch {
                val failure = runCatching { if (approve) service.approve(item) else service.deny(item) }.exceptionOrNull()
                // Server text is shown sanitised and bounded.
                error = failure?.let { PairingApproval.displayName(it.message ?: "The decision could not be completed.", 160) }
                busy = null
                // No extra list read: the 30-second poll picks up any other change.
                if (failure == null) onDecided(item.id)
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Pairing requests", { onNavigate(backOf(Screen.PAIRING_APPROVALS)) })
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!biometrics) {
                Unavailable(
                    "Biometrics required", JvIcon.SHIELD, kind = UnavailableKind.ERROR,
                    detail = "Set up a strong fingerprint or face unlock to approve or deny pairing requests.",
                )
            }
            error?.let { ErrorText(it) }
            Loaded(data.pairing, "Pairing requests", JvIcon.KEY, "Loading pairing requests…") { response ->
                if (response.requests.isEmpty()) MutedText("No devices are waiting for approval.")
                response.requests.forEach { item -> PairingRequestCard(item, now, biometrics, busy, decide) }
            }
        }
    }
}

@Composable
private fun PairingRequestCard(
    item: PairingRequestItem,
    now: Long,
    biometrics: Boolean,
    busy: String?,
    decide: (PairingRequestItem, Boolean) -> Unit,
) {
    val checked = remember(item) { runCatching { PairingApproval.verifiedFingerprint(item) } }
    val fingerprint = checked.getOrNull()
    val left = PairingApproval.countdown(item.expires_at, now)
    Panel(JvIcon.PHONE, PairingApproval.displayName(item.device_name, 128), Tone.WARN, if (left == "Expired") left else "Expires in $left") {
        Detail("Platform", PairingApproval.displayName(item.platform, 32))
        PairingApproval.approverWarning(item.platform)?.let { Text(it, color = JvTheme.colors.negativeText, style = MaterialTheme.typography.bodySmall) }
        Detail("Model", PairingApproval.displayName(item.model))
        Detail("App version", PairingApproval.displayName(item.app_version, 32))
        Detail("IP address", PairingApproval.displayName(item.ip))
        Detail("User agent", PairingApproval.displayName(item.user_agent, 128))
        Detail("Requested", TIME.format(Instant.ofEpochSecond(item.created_at)))
        SectionLabel("Key fingerprint")
        if (fingerprint != null) {
            Text(PairingApproval.grouped(fingerprint), style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono))
            MutedText("Compare it with the fingerprint the new device shows. Model, version, IP address and user agent are unverified hints.")
        } else {
            ErrorText(checked.exceptionOrNull()?.message ?: "Invalid fingerprint.")
        }
        if (biometrics) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Deny", { decide(item, false) }, enabled = busy == null, danger = true)
                ActionButton(
                    if (busy == item.id) "Verifying…" else "Approve", { decide(item, true) },
                    Modifier.weight(1f), enabled = busy == null && fingerprint != null,
                )
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(96.dp), color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
