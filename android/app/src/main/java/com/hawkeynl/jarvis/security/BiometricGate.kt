package com.hawkeynl.jarvis.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

sealed interface BiometricAvailability {
    data object Available : BiometricAvailability
    data object NotEnrolled : BiometricAvailability
    data object NoHardware : BiometricAvailability
    data object TemporarilyUnavailable : BiometricAvailability
    data object SecurityUpdateRequired : BiometricAvailability
    data object Unsupported : BiometricAvailability
}

sealed interface BiometricResult {
    /** [cryptoObject] is the one passed in, now authorised for a single operation. */
    data class Authenticated(val cryptoObject: BiometricPrompt.CryptoObject? = null) : BiometricResult
    data object Cancelled : BiometricResult
    data object LockedOut : BiometricResult
    data class Unavailable(val availability: BiometricAvailability) : BiometricResult
    data object Failed : BiometricResult
}

class BiometricGate {
    fun availability(activity: FragmentActivity): BiometricAvailability =
        when (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.TemporarilyUnavailable
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.SecurityUpdateRequired
            else -> BiometricAvailability.Unsupported
        }

    fun authenticate(
        activity: FragmentActivity,
        executor: Executor,
        title: String = "Unlock Jarvis",
        subtitle: String = "Confirm with strong biometrics",
        description: String? = null,
        cryptoObject: BiometricPrompt.CryptoObject? = null,
        callback: (BiometricResult) -> Unit,
    ): BiometricPrompt? {
        val availability = availability(activity)
        if (availability != BiometricAvailability.Available) {
            callback(BiometricResult.Unavailable(availability))
            return null
        }

        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    callback(BiometricResult.Authenticated(result.cryptoObject))
                }

                override fun onAuthenticationFailed() {
                    callback(BiometricResult.Failed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    callback(
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED,
                            -> BiometricResult.Cancelled
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            -> BiometricResult.LockedOut
                            else -> BiometricResult.Unavailable(availability(activity))
                        },
                    )
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setNegativeButtonText("Cancel")
            .build()
        if (cryptoObject == null) prompt.authenticate(info) else prompt.authenticate(info, cryptoObject)
        return prompt
    }

    private companion object {
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}
