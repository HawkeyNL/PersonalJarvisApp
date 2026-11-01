package com.hawkeynl.jarvis.auth

import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.BootstrapRequest
import com.hawkeynl.jarvis.network.ChallengeRequest
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.network.LoginRequest
import com.hawkeynl.jarvis.network.PairingCreateRequest
import com.hawkeynl.jarvis.network.PairingTicket
import com.hawkeynl.jarvis.network.UnreachableReason
import com.hawkeynl.jarvis.security.BiometricNotRecognisedException
import com.hawkeynl.jarvis.security.DeviceIdentity
import com.hawkeynl.jarvis.security.DeviceKeyInvalidatedException
import com.hawkeynl.jarvis.security.DeviceSigningException
import com.hawkeynl.jarvis.security.LoginMessage
import com.hawkeynl.jarvis.storage.ProtectedSession
import com.hawkeynl.jarvis.storage.SessionRepository
import kotlin.coroutines.cancellation.CancellationException

sealed interface EnrollmentOutcome {
    data object PasswordRequired : EnrollmentOutcome
    data object ActivationRequired : EnrollmentOutcome
    data class Pending(val ticket: PairingTicket) : EnrollmentOutcome
    data class Authenticated(val expiresAt: Long) : EnrollmentOutcome
    data object Denied : EnrollmentOutcome
    data object Expired : EnrollmentOutcome
    data object Unauthorized : EnrollmentOutcome
    data class Unreachable(val reason: UnreachableReason) : EnrollmentOutcome
    data class Rejected(val status: Int, val message: String?) : EnrollmentOutcome
    data class InvalidResponse(val message: String) : EnrollmentOutcome

    /** Keystore invalidated the device key; only the user's "erase and pair again" clears it. */
    data object DeviceKeyLost : EnrollmentOutcome
}

class EnrollmentService(
    private val api: JarvisApi,
    private val identity: DeviceIdentity,
    private val sessions: SessionRepository,
) {
    fun currentSession(): ProtectedSession = sessions.session()

    suspend fun startOrResume(
        endpoint: HomeNodeEndpoint,
        deviceName: String,
        nowEpochSeconds: Long,
        password: String? = null,
        activationCode: String? = null,
    ): EnrollmentOutcome {
        if (activationCode != null) {
            val publicKey = enrollmentKey().getOrElse { return it.keyFailure() }
            return when (val result = api.activateFirstDevice(endpoint, BootstrapRequest(deviceName.take(80), "android", publicKey, password), activationCode)) {
                is ApiResult.Success -> {
                    sessions.saveDeviceId(result.value.device_id)
                    login(endpoint, result.value.device_id, password)
                }
                else -> result.toEnrollmentFailure()
            }
        }
        sessions.session().deviceId?.let { return login(endpoint, it, password) }

        val existing = sessions.pairingTicket()
        if (existing != null) {
            if (existing.expires_at <= nowEpochSeconds) {
                sessions.clearPairingTicket()
                return EnrollmentOutcome.Expired
            }
            return poll(endpoint, existing)
        }

        when (val status = api.accountStatus(endpoint)) {
            is ApiResult.Success -> {
                if (status.value.bootstrap_required) return EnrollmentOutcome.ActivationRequired
                if (status.value.password_required && password == null) return EnrollmentOutcome.PasswordRequired
            }
            else -> return status.toEnrollmentFailure()
        }
        val publicKey = enrollmentKey().getOrElse { return it.keyFailure() }
        return when (val result = api.createPairing(
            endpoint,
            PairingCreateRequest(
                name = deviceName.take(80),
                platform = "android",
                public_key = publicKey,
                password = password,
            ),
        )) {
            is ApiResult.Success -> {
                sessions.savePairingTicket(result.value)
                EnrollmentOutcome.Pending(result.value)
            }
            else -> result.toEnrollmentFailure()
        }
    }

    suspend fun poll(endpoint: HomeNodeEndpoint): EnrollmentOutcome {
        val ticket = sessions.pairingTicket()
            ?: return EnrollmentOutcome.InvalidResponse("No pending pairing request.")
        return poll(endpoint, ticket)
    }

    suspend fun login(endpoint: HomeNodeEndpoint): EnrollmentOutcome {
        val deviceId = sessions.session().deviceId ?: return EnrollmentOutcome.Unauthorized
        return login(endpoint, deviceId)
    }

    suspend fun logout(endpoint: HomeNodeEndpoint) {
        val token = sessions.session().token
        if (token != null) api.logout(endpoint, token)
        sessions.clearToken()
    }

    /** Erases this device's session, binding and key here only. Revoking it on Core is a
     *  separate signed approval ([com.hawkeynl.jarvis.security.AccountApprovalService]). */
    suspend fun eraseLocally() {
        sessions.reset()
        identity.reset()
    }

    /** A new device creates its key here, while the user is starting enrolment. */
    private suspend fun enrollmentKey(): Result<String> = try {
        Result.success(identity.enrollmentKeyHex("Create this phone's Jarvis device key"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun poll(endpoint: HomeNodeEndpoint, ticket: PairingTicket): EnrollmentOutcome {
        return when (val result = api.pairingStatus(endpoint, ticket)) {
            is ApiResult.Success -> when (result.value.status) {
                "pending" -> EnrollmentOutcome.Pending(ticket)
                "approved" -> {
                    val deviceId = result.value.device_id
                        ?: return EnrollmentOutcome.InvalidResponse("Approved request is missing device_id.")
                    sessions.saveDeviceId(deviceId)
                    sessions.clearPairingTicket()
                    // No password sign-in yet on a just-paired device: ask for it without a prompt.
                    login(endpoint, deviceId, renew = false)
                }
                "denied" -> {
                    sessions.clearPairingTicket()
                    EnrollmentOutcome.Denied
                }
                "expired" -> {
                    sessions.clearPairingTicket()
                    EnrollmentOutcome.Expired
                }
                else -> EnrollmentOutcome.InvalidResponse("Onbekende koppelstatus.")
            }
            else -> result.toEnrollmentFailure()
        }
    }

    /** Without a password and with [renew], signs in with biometrics and the device key
     *  alone. Core accepts that within 30 days of the last password sign-in and answers
     *  401 otherwise (older Core always does); both 401 and [SIGN_IN_ATTEMPTS] rejected
     *  fingers or faces lead to the password screen, which still needs the device key. */
    private suspend fun login(
        endpoint: HomeNodeEndpoint, deviceId: String, password: String? = null, renew: Boolean = true,
    ): EnrollmentOutcome {
        val renewing = when (val status = api.accountStatus(endpoint)) {
            is ApiResult.Success -> status.value.password_required && password == null
            else -> return status.toEnrollmentFailure()
        }
        if (renewing && !renew) return EnrollmentOutcome.PasswordRequired
        val challenge = when (val result = api.challenge(endpoint, ChallengeRequest(deviceId))) {
            is ApiResult.Success -> result.value
            ApiResult.Unauthorized -> return EnrollmentOutcome.Rejected(401,
                "Device challenge refused. Check in Core Admin whether this device is still approved; pairing again requires explicit approval.")
            else -> return result.toEnrollmentFailure()
        }
        if (challenge.nonce.length != 64) {
            return EnrollmentOutcome.InvalidResponse("Login challenge has an invalid nonce.")
        }
        val signature = try {
            identity.sign(LoginMessage(challenge.challenge_id, deviceId, challenge.nonce), SIGN_IN_REASON)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: BiometricNotRecognisedException) {
            return EnrollmentOutcome.PasswordRequired
        } catch (_: DeviceKeyInvalidatedException) {
            // Erasing is the user's decision; this may be a background sign-in.
            return EnrollmentOutcome.DeviceKeyLost
        } catch (refused: DeviceSigningException) {
            return EnrollmentOutcome.InvalidResponse(refused.message.orEmpty())
        } catch (_: Exception) {
            return EnrollmentOutcome.InvalidResponse("Device identity cannot sign.")
        }
        return when (val result = api.login(
            endpoint,
            LoginRequest(deviceId, challenge.challenge_id, signature, password),
        )) {
            is ApiResult.Success -> {
                sessions.saveLogin(deviceId, result.value.token, result.value.expires_at)
                EnrollmentOutcome.Authenticated(result.value.expires_at)
            }
            ApiResult.Unauthorized -> if (renewing) EnrollmentOutcome.PasswordRequired else EnrollmentOutcome.Rejected(401,
                "Password/device signature refused. Check your password and original device identity. No settings or keys were erased.")
            else -> result.toEnrollmentFailure()
        }
    }

    companion object {
        const val SIGN_IN_REASON = "Sign in to Jarvis"
        const val SIGN_IN_ATTEMPTS = 3
    }
}

private fun Throwable.keyFailure() =
    EnrollmentOutcome.InvalidResponse((this as? DeviceSigningException)?.message ?: "This phone's device key cannot be created.")

private fun ApiResult<*>.toEnrollmentFailure(): EnrollmentOutcome = when (this) {
    ApiResult.Unauthorized -> EnrollmentOutcome.Unauthorized
    is ApiResult.Unreachable -> EnrollmentOutcome.Unreachable(reason)
    is ApiResult.HttpError -> EnrollmentOutcome.Rejected(status, message)
    is ApiResult.InvalidResponse -> EnrollmentOutcome.InvalidResponse(message)
    is ApiResult.Success -> error("Success is not a failure")
}
