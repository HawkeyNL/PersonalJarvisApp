package com.hawkeynl.jarvis

import android.os.Bundle
import android.os.Build
import android.app.KeyguardManager
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.hawkeynl.jarvis.security.ModelControlService
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hawkeynl.jarvis.ui.JarvisApp
import com.hawkeynl.jarvis.ui.JarvisTheme
import com.hawkeynl.jarvis.ui.JarvisViewModel

class MainActivity : FragmentActivity() {
    private val container: AppContainer
        get() = (application as JarvisApplication).container

    private val viewModel: JarvisViewModel by viewModels { JarvisViewModel.factory(container) }
    private var biometricPromptActive = false
    private var modelAuthenticationActive = false
    private var modelCredentialResult: ((Boolean) -> Unit)? = null
    private val modelCredentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = modelCredentialResult
        modelCredentialResult = null
        modelAuthenticationActive = false
        callback?.invoke(result.resultCode == Activity.RESULT_OK)
    }
    private val modelControls by lazy { ModelControlService(container.api, container.sessions, container.settings, container.identity, ::authenticateModelChange) }

    private suspend fun authenticateModelChange(reason: String): Boolean = suspendCancellableCoroutine { continuation ->
        if (biometricPromptActive || modelCredentialResult != null) { continuation.resume(false); return@suspendCancellableCoroutine }
        modelAuthenticationActive = true
        if (Build.VERSION.SDK_INT < 30) {
            // Strong biometric + credential fallback is unsupported as a
            // combined BiometricPrompt policy on API 28/29; use OS credentials.
            val manager = getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = manager.createConfirmDeviceCredentialIntent("Jarvis-modelbeleid", reason)
            if (intent == null) { modelAuthenticationActive = false; continuation.resume(false); return@suspendCancellableCoroutine }
            modelCredentialResult = { if (continuation.isActive) continuation.resume(it) }
            continuation.invokeOnCancellation { runOnUiThread { modelCredentialResult = null; modelAuthenticationActive = false; viewModel.lockForBackground() } }
            modelCredentialLauncher.launch(intent)
        } else {
            biometricPromptActive = true
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    biometricPromptActive = false
                    modelAuthenticationActive = false
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    biometricPromptActive = false
                    modelAuthenticationActive = false
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) viewModel.lockForBackground()
                    if (continuation.isActive) continuation.resume(false)
                }
            })
            continuation.invokeOnCancellation { runOnUiThread { prompt.cancelAuthentication(); biometricPromptActive = false; modelAuthenticationActive = false; viewModel.lockForBackground() } }
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Jarvis-modelbeleid")
                .setDescription(reason).setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.locked) {
                if (state.locked) requestBiometricUnlock()
            }
            JarvisTheme {
                JarvisApp(
                    state = state,
                    actions = viewModel,
                    onRequestBiometric = ::requestBiometricUnlock,
                    onInstallUpdate = ::installVerifiedUpdate,
                    modelControls = modelControls,
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Android's own credential confirmation may temporarily cover this
        // Activity. Cancelling it still fails closed; do not cancel the signed
        // operation solely because the OS password screen takes foreground.
        if (!isChangingConfigurations && !modelAuthenticationActive) viewModel.lockForBackground()
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
