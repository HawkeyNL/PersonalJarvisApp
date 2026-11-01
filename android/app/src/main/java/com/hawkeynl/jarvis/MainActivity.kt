package com.hawkeynl.jarvis

import android.content.Intent
import android.os.Bundle
import androidx.biometric.BiometricPrompt
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import com.hawkeynl.jarvis.security.AgentApprovalService
import com.hawkeynl.jarvis.auth.EnrollmentService
import com.hawkeynl.jarvis.security.BiometricAvailability
import com.hawkeynl.jarvis.security.BiometricNotRecognisedException
import com.hawkeynl.jarvis.security.BiometricResult
import com.hawkeynl.jarvis.security.PromptAttempts
import com.hawkeynl.jarvis.security.ModelControlService
import com.hawkeynl.jarvis.security.PairingApprovalService
import com.hawkeynl.jarvis.security.UnlockApprovalService
import kotlinx.coroutines.flow.first
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleDestroyedException
import androidx.lifecycle.withResumed
import com.hawkeynl.jarvis.ui.CoreViewModel
import com.hawkeynl.jarvis.ui.JarvisApp
import com.hawkeynl.jarvis.ui.JarvisTheme
import com.hawkeynl.jarvis.ui.JarvisViewModel
import com.hawkeynl.jarvis.ui.parseDeepLink

class MainActivity : FragmentActivity() {
    private val container: AppContainer
        get() = (application as JarvisApplication).container

    private val viewModel: JarvisViewModel by viewModels { JarvisViewModel.factory(container) }
    private val coreViewModel: CoreViewModel by viewModels { CoreViewModel.factory(container) }
    private var biometricPromptActive = false
    private val modelControls by lazy { ModelControlService(container.api, container.sessions, container.settings, container.identity, container.approvals,
        { !viewModel.state.value.locked && viewModel.state.value.authenticated }) }

    // Every device signature: one strong-biometric prompt that authorises the device key's cipher.
    // Sign-in gives up after three rejected fingers or faces, so the password screen takes over.
    private val signingPrompt: suspend (String, Cipher) -> Cipher? = { reason, cipher ->
        val maxFailures = if (reason == EnrollmentService.SIGN_IN_REASON) EnrollmentService.SIGN_IN_ATTEMPTS else Int.MAX_VALUE
        val result = strongBiometric("Jarvis device key", reason, BiometricPrompt.CryptoObject(cipher), maxFailures)
        if (result == BiometricResult.Failed) throw BiometricNotRecognisedException()
        (result as? BiometricResult.Authenticated)?.cryptoObject?.cipher
    }

    // Pairing decisions accept only strong biometrics: no device credential fallback.
    private val pairingApprovals by lazy {
        PairingApprovalService(
            container.api, container.sessions, { container.settings.endpoint.first() }, container.identity, container.approvals,
            ::authenticatePairing,
            { container.biometricGate.availability(this) == BiometricAvailability.Available },
            { !viewModel.state.value.locked && viewModel.state.value.authenticated },
        )
    }

    private val unlockApprovals by lazy {
        UnlockApprovalService(
            container.api, container.sessions, { container.settings.endpoint.first() }, container.identity, container.approvals,
            { reason -> strongBiometric("Unlock request", reason) is BiometricResult.Authenticated },
            { !viewModel.state.value.locked && viewModel.state.value.authenticated },
            container.unlockChoices,
        )
    }

    private val agentApprovals by lazy {
        AgentApprovalService(
            container.api, container.sessions, { container.settings.endpoint.first() }, container.identity, container.approvals,
            { reason -> strongBiometric("Agent action", reason) is BiometricResult.Authenticated },
            { !viewModel.state.value.locked && viewModel.state.value.authenticated },
        )
    }

    private suspend fun authenticatePairing(reason: String): Boolean =
        strongBiometric("Pairing request", reason) is BiometricResult.Authenticated

    /** One BIOMETRIC_STRONG prompt, no device credential. Null while another prompt is open
     *  or the app is not in the foreground; the result is handed back only once this
     *  Activity is resumed again. */
    private suspend fun strongBiometric(
        title: String, reason: String, cryptoObject: BiometricPrompt.CryptoObject? = null, maxFailures: Int = Int.MAX_VALUE,
    ): BiometricResult? = withContext(Dispatchers.Main.immediate) {
        val activity = this@MainActivity
        val result = suspendCancellableCoroutine<BiometricResult?> { continuation ->
            // A prompt started after onSaveInstanceState is dropped without a callback.
            if (biometricPromptActive || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || supportFragmentManager.isStateSaved) {
                continuation.resume(null); return@suspendCancellableCoroutine
            }
            biometricPromptActive = true
            // A rejected finger or face keeps the prompt open for another try, up to maxFailures.
            val attempts = PromptAttempts(maxFailures)
            var prompt: BiometricPrompt? = null
            prompt = container.biometricGate.authenticate(
                activity, ContextCompat.getMainExecutor(activity), title, description = reason, cryptoObject = cryptoObject,
            ) { raw ->
                val result = attempts.settle(raw) ?: return@authenticate
                biometricPromptActive = false
                if (result == BiometricResult.Failed) prompt?.cancelAuthentication()
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation { runOnUiThread { prompt?.cancelAuthentication(); biometricPromptActive = false } }
        }
        try {
            result?.takeIf { lifecycle.withResumed { !isFinishing && !isDestroyed } }
        } catch (_: LifecycleDestroyedException) {
            null // This Activity was recreated while the prompt was open.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.identity.prompt = signingPrompt
        // Jarvis is dark only: keep light system bar icons in every system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.locked) {
                if (state.locked) requestBiometricUnlock()
            }
            JarvisTheme {
                JarvisApp(
                    state = state,
                    actions = viewModel,
                    core = coreViewModel,
                    onRequestBiometric = ::requestBiometricUnlock,
                    onInstallUpdate = ::installVerifiedUpdate,
                    modelControls = modelControls,
                    pairingApprovals = pairingApprovals,
                    unlockApprovals = unlockApprovals,
                    agentApprovals = agentApprovals,
                )
            }
        }
        // A recreated Activity already applied its launch link.
        if (savedInstanceState == null) openDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openDeepLink(intent)
    }

    private fun openDeepLink(intent: Intent?) {
        parseDeepLink(intent?.dataString)?.let(viewModel::openDeepLink)
    }

    override fun onDestroy() {
        if (container.identity.prompt === signingPrompt) container.identity.prompt = null
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.lockForBackground()
            // Drop Core data now; the UI stops recomposing while stopped.
            coreViewModel.clear()
        }
    }

    private fun requestBiometricUnlock() {
        if (biometricPromptActive) return
        biometricPromptActive = true
        container.biometricGate.authenticate(
            activity = this,
            executor = ContextCompat.getMainExecutor(this),
        ) { result ->
            biometricPromptActive = false
            viewModel.retryUnlockResult(result)
        }
    }

    private fun installVerifiedUpdate() {
        viewModel.installerHandoff(container.appUpdates.handOffToPackageInstaller(this))
    }
}
