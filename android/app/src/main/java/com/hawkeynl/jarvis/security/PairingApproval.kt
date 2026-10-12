package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.AuthMe
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.network.PairingRequestItem
import com.hawkeynl.jarvis.storage.SessionRepository
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PairingFingerprintMismatch :
    IllegalStateException("The Home Node's fingerprint does not match the candidate key. Do not approve; check the Home Node.")

/** Mirrors client-core `key_fingerprint`, `PairingRequestItem::verified_fingerprint`
 *  and `pairing_approval_message` (v1). */
object PairingApproval {
    private val hex64 = Regex("[0-9a-fA-F]{64}")
    val canonicalUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Lowercase hex SHA-256 of a raw 32-byte public key. */
    fun fingerprint(publicKey: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(publicKey))

    fun fingerprint(publicKeyHex: String): String {
        require(hex64.matches(publicKeyHex)) { "The pairing request has an invalid device key." }
        return fingerprint(Hex.decode(publicKeyHex))
    }

    /** The fingerprint an approver shows: computed here from the key the approval
     *  signs. Any disagreement with the server's fields refuses the request. */
    fun verifiedFingerprint(item: PairingRequestItem): String {
        val local = fingerprint(item.candidate_public_key)
        if (local.take(16) != item.fingerprint || (item.fingerprint_full != null && item.fingerprint_full != local)) {
            throw PairingFingerprintMismatch()
        }
        return local
    }

    /** Domain, request id, 32-byte nonce, 32-byte candidate key, user id,
     *  approver device id, big-endian i64 expiry. */
    fun message(
        requestId: String,
        nonceHex: String,
        candidatePublicKeyHex: String,
        userId: String,
        approverDeviceId: String,
        expiresAt: Long,
    ): ByteArray {
        require(listOf(requestId, userId, approverDeviceId).all(canonicalUuid::matches)) { "Invalid UUID" }
        require(hex64.matches(nonceHex) && hex64.matches(candidatePublicKeyHex)) { "Invalid pairing payload" }
        val domain = "jarvis-device-pairing-v1\u0000".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(domain.size + 16 + 32 + 32 + 16 + 16 + 8).put(domain)
        buffer.putUuid(requestId).put(Hex.decode(nonceHex)).put(Hex.decode(candidatePublicKeyHex))
        buffer.putUuid(userId).putUuid(approverDeviceId)
        return buffer.putLong(expiresAt).array()
    }

    private fun ByteBuffer.putUuid(value: String): ByteBuffer =
        UUID.fromString(value).let { putLong(it.mostSignificantBits).putLong(it.leastSignificantBits) }

    /** Groups of four hex characters for side-by-side comparison. */
    fun grouped(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")

    /** An unauthenticated candidate hint as bounded plain text: control and format
     *  (bidi, zero-width) characters dropped, at most [max] code points. */
    fun hint(value: String?, max: Int = 64): String {
        if (value == null) return "unknown"
        val points = value.codePoints().filter {
            val type = Character.getType(it)
            type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()
        }.toArray()
        val clean = String(points, 0, points.size).trim()
        if (clean.isEmpty()) return "unknown"
        return if (clean.codePointCount(0, clean.length) > max) clean.substring(0, clean.offsetByCodePoints(0, max)) + "…" else clean
    }

    /** "m:ss" until a request expires, or "Expired". */
    fun countdown(expiresAt: Long, nowEpochSeconds: Long): String {
        val left = expiresAt - nowEpochSeconds
        return if (left <= 0) "Expired" else "${left / 60}:${(left % 60).toString().padStart(2, '0')}"
    }
}

/** Approve or deny pending pairing requests. Approval signs with [identity], whose
 *  BIOMETRIC_STRONG-only prompt unlocks the device key; deny needs [authenticate],
 *  the same kind of prompt without a key. Nothing is sent without a prompt. */
class PairingApprovalService(
    private val api: JarvisApi,
    private val sessions: SessionRepository,
    private val endpoint: suspend () -> HomeNodeEndpoint?,
    private val identity: DeviceIdentity,
    private val authenticate: suspend (String) -> Boolean,
    val biometricsAvailable: () -> Boolean,
    private val unlocked: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val decision = Mutex()

    suspend fun approve(item: PairingRequestItem) = decision.withLock {
        check(unlocked()) { "App locked" }
        val fingerprint = PairingApproval.verifiedFingerprint(item)
        val origin = endpoint() ?: error("Home Node missing")
        val session = sessions.session()
        val token = session.token ?: error("Sign in again")
        val device = session.deviceId ?: error("Device not paired")
        // Core owns the user binding; learn it from the authenticated session.
        val me = when (val result = api.getAuthorized(origin, token, "/v1/auth/me")) {
            is ApiResult.Success -> json.decodeFromJsonElement(AuthMe.serializer(), result.value)
            else -> error("Could not confirm this device's session; try again")
        }
        check(me.device_id.equals(device, ignoreCase = true)) { "Session does not belong to this device" }
        val message = PairingApproval.message(item.id, item.nonce, item.candidate_public_key, me.user_id, device, item.expires_at)
        check(item.expires_at > now()) { EXPIRED }
        val name = PairingApproval.hint(item.device_name, 40)
        val signature = identity.signHex(Hex.encode(message), "Approve $name with key ${PairingApproval.grouped(fingerprint.take(16))}")
        currentCoroutineContext().ensureActive()
        check(unlocked() && endpoint() == origin && sessions.session() == session && item.expires_at > now()) { "Approval expired or session changed" }
        decide(origin, token, "/v1/auth/pairing/requests/${item.id}/approve", JsonObject(mapOf("signature" to JsonPrimitive(signature))))
    }

    suspend fun deny(item: PairingRequestItem) = decision.withLock {
        check(unlocked()) { "App locked" }
        require(PairingApproval.canonicalUuid.matches(item.id)) { "Invalid pairing request" }
        val origin = endpoint() ?: error("Home Node missing")
        val session = sessions.session()
        val token = session.token ?: error("Sign in again")
        if (!authenticate("Deny pairing of ${PairingApproval.hint(item.device_name, 40)}")) error(CANCELLED)
        currentCoroutineContext().ensureActive()
        check(unlocked() && endpoint() == origin && sessions.session() == session) { "Session changed; try again" }
        decide(origin, token, "/v1/auth/pairing/requests/${item.id}/deny", JsonObject(emptyMap()))
    }

    // Core answers 401 for an unknown, handled or expired request too: report a
    // refusal instead of ending the session (the next read detects a real 401).
    private suspend fun decide(origin: HomeNodeEndpoint, token: String, path: String, body: JsonObject) {
        when (val result = api.postAuthorized(origin, token, path, body)) {
            is ApiResult.Success -> Unit
            ApiResult.Unauthorized -> error("Core refused this decision. The request may have expired or been handled already.")
            is ApiResult.HttpError -> error(result.message ?: "Core refused this decision (HTTP ${result.status}).")
            is ApiResult.Unreachable -> error("Home Node unreachable; refresh to see whether the decision applied.")
            is ApiResult.InvalidResponse -> error(result.message)
        }
    }

    private companion object {
        const val EXPIRED = "This pairing request has expired."
        const val CANCELLED = "Cancelled; fingerprint or face unlock is required."
    }
}
