package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.AuthMe
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.storage.SessionRepository
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** client-core `AccountApproval`: Core's challenge for one account action. It is signed
 *  only after [requireFor] matched every bound field to what this device asked for. */
@Serializable
data class AccountApproval(
    val request_id: String,
    val user_id: String,
    val device_id: String,
    val action: String,
    val target: String,
    val nonce: String,
    val expires_at: Long,
) : DeviceMessage {
    /** Exact v1 client-core `account_approval_message`: domain, action name, request,
     *  user, device and target ids, 32-byte nonce, big-endian i64 expiry. */
    override fun message(): ByteArray {
        val ids = listOf(request_id, user_id, device_id, target)
        require(ids.all(PairingApproval.canonicalUuid::matches)) { "Invalid UUID" }
        require(action in ACTIONS && HEX64.matches(nonce) && expires_at > 0) { "Invalid account approval" }
        val domain = "jarvis-account-approval-v1\u0000".toByteArray(Charsets.UTF_8)
        val name = "$action\u0000".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(domain.size + name.size + 4 * 16 + 32 + 8).put(domain).put(name)
        for (id in ids.map(UUID::fromString)) buffer.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
        return buffer.put(Hex.decode(nonce)).putLong(expires_at).array()
    }

    /** Core must have issued exactly the challenge this device asked for, still unexpired. */
    fun requireFor(action: String, user: String, device: String, target: String, now: Long) {
        message()
        check(
            this.action == action && user_id.equals(user, ignoreCase = true) && device_id.equals(device, ignoreCase = true) &&
                this.target.equals(target, ignoreCase = true) && expires_at > now,
        ) { "Core returned a different or expired approval request; nothing was signed." }
    }

    companion object {
        const val DEVICE_REVOKE = "device-revoke"
        const val PASSWORD_SET = "password-set"
        private val ACTIONS = setOf(DEVICE_REVOKE, PASSWORD_SET)
        private val HEX64 = Regex("[0-9a-fA-F]{64}")
    }
}

/** Device-signed account actions. Core lets only the requesting device approve its own
 *  request: ask Core, check every bound field, one strong-biometric prompt, sign, approve.
 *  Unlike the pairing and model services there is no app-unlocked check: unlinking is also
 *  offered on the lock screen, and the biometric prompt gates the signature itself. */
class AccountApprovalService(
    private val api: JarvisApi,
    private val sessions: SessionRepository,
    private val endpoint: suspend () -> HomeNodeEndpoint?,
    private val identity: DeviceIdentity,
    private val approvals: ApprovalLock,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Revokes this device on Core. Throws unless Core confirmed; erases nothing itself. */
    suspend fun unlinkThisDevice() {
        val device = sessions.session().deviceId ?: error("This device is not paired, so there is nothing to revoke on Core.")
        revoke(device, "Unlink this device from Jarvis")
    }

    private suspend fun revoke(target: String, reason: String) = approvals.exclusive {
        require(PairingApproval.canonicalUuid.matches(target)) { "Invalid device id" }
        val origin = endpoint() ?: error("Home Node missing")
        val session = sessions.session()
        val token = session.token ?: error("Not signed in, so Core cannot be asked to revoke this device.")
        val device = session.deviceId ?: error("Device not paired")
        val me = when (val result = api.getAuthorized(origin, token, "/v1/auth/me")) {
            is ApiResult.Success -> runCatching { json.decodeFromJsonElement(AuthMe.serializer(), result.value) }.getOrNull()
            else -> null
        } ?: error("Could not confirm this device's session; try again.")
        check(me.device_id.equals(device, ignoreCase = true)) { "Session does not belong to this device" }
        val approval = request(origin, token, "/v1/devices/$target/revoke-request", JsonObject(emptyMap()))
        approval.requireFor(AccountApproval.DEVICE_REVOKE, me.user_id, device, target, now())
        val signature = identity.sign(approval, reason)
        currentCoroutineContext().ensureActive()
        check(endpoint() == origin && sessions.session() == session && approval.expires_at > now()) { "Approval expired or session changed" }
        approve(origin, token, approval, signature)
    }

    private suspend fun request(origin: HomeNodeEndpoint, token: String, path: String, body: JsonObject): AccountApproval =
        when (val result = api.postAuthorizedJson(origin, token, path, body)) {
            is ApiResult.Success -> runCatching { json.decodeFromJsonElement(AccountApproval.serializer(), result.value) }
                .getOrElse { error("Core returned an invalid approval request.") }
            else -> error(refusal(result))
        }

    private suspend fun approve(origin: HomeNodeEndpoint, token: String, approval: AccountApproval, signature: String) {
        val body = JsonObject(mapOf("signature" to JsonPrimitive(signature)))
        when (val result = api.postAuthorizedJson(origin, token, "/v1/auth/account/requests/${approval.request_id}/approve", body)) {
            is ApiResult.Success -> check(
                runCatching { result.value.jsonObject["status"]?.jsonPrimitive?.content }.getOrNull() == "completed",
            ) { "Core did not confirm the change." }
            else -> error(refusal(result))
        }
    }

    // Core answers 401 for a refused account action too; report it, do not end the session here.
    private fun refusal(result: ApiResult<JsonElement>): String = when (result) {
        is ApiResult.Success -> "Core did not confirm the change."
        ApiResult.Unauthorized -> "Core refused this change."
        is ApiResult.HttpError -> result.message ?: "Core refused this change (HTTP ${result.status})."
        is ApiResult.Unreachable -> "Home Node unreachable; the change was not confirmed."
        is ApiResult.InvalidResponse -> result.message
    }
}
