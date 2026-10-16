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

    /** Groups of four hex characters for side-by-side comparison. */
    fun grouped(fingerprint: String): String = fingerprint.chunked(4).joinToString(" ")

    /** Server text for display and prompts, ported from desktop `display_name`: control,
     *  hidden and quote characters dropped (prompts quote it), at most two combining marks
     *  per character, whitespace collapsed, at most [max] code points. Longer text keeps
     *  its start and end, so a long name cannot push its end out of view. */
    fun displayName(value: String?, max: Int = 64): String {
        if (value == null) return "unknown"
        var marks = 0
        val kept = value.codePoints().toArray().asSequence()
            .map { if (Character.isSpaceChar(it) || it in 9..13 || it == 0x85) ' '.code else it }
            // Lone surrogates cannot occur in Rust or Swift strings; drop them like controls.
            .filter {
                val type = Character.getType(it)
                type != Character.CONTROL.toInt() && type != Character.SURROGATE.toInt() && !isHidden(it) && it != '"'.code
            }
            .filter {
                marks = if (isCombining(it)) marks + 1 else 0
                marks <= 2
            }
            .toList().toIntArray()
        val clean = String(kept, 0, kept.size).split(' ').filter { it.isNotEmpty() }.joinToString(" ").codePoints().toArray()
        if (clean.isEmpty()) return "unknown"
        if (clean.size <= max) return String(clean, 0, clean.size)
        val head = max / 2
        val tail = max - head - 1
        return String(clean, 0, head) + "…" + String(clean, clean.size - tail, tail)
    }

    /** Unicode category Cf (bidi controls, zero-width, tags) plus other invisible or
     *  blank-looking default-ignorable and private-use characters, and quotation marks. */
    private val hidden = listOf(
        0xAD..0xAD, 0x34F..0x34F, 0x600..0x605, 0x61C..0x61C, 0x6DD..0x6DD, 0x70F..0x70F,
        0x890..0x891, 0x8E2..0x8E2, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F,
        0x200B..0x200F, 0x202A..0x202E, 0x2060..0x2064, 0x2066..0x206F,
        // Quotation marks (Pi/Pf and fullwidth): prompts quote server text.
        0xAB..0xAB, 0xBB..0xBB, 0x2018..0x201F, 0x2039..0x203A, 0x2E02..0x2E05, 0x2E09..0x2E0A,
        0x2E0C..0x2E0D, 0x2E1C..0x2E1D, 0x2E20..0x2E21, 0xFF02..0xFF02,
        0x2800..0x2800, 0x3164..0x3164, 0xE000..0xF8FF, 0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0,
        0xFFF9..0xFFFC, 0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3,
        0x1D173..0x1D17A, 0xE0000..0xE0FFF, 0xF0000..0x10FFFF,
    )

    internal fun isHidden(c: Int) = hidden.any { c in it }

    /** Common combining-mark blocks, as on desktop. */
    internal fun isCombining(c: Int) =
        c in 0x300..0x36F || c in 0x1AB0..0x1AFF || c in 0x1DC0..0x1DFF || c in 0x20D0..0x20FF || c in 0xFE20..0xFE2F

    /** A device on these platforms can approve unlocks, account changes and agent actions
     *  once paired, so its pairing card says so. */
    fun approverWarning(platform: String): String? =
        if (platform.trim().lowercase() in setOf("ios", "android", "macos")) {
            "Once paired, this ${displayName(platform, 16)} device can approve unlocks, account changes and agent actions."
        } else {
            null
        }

    /** "m:ss" until a request expires, or "Expired". */
    fun countdown(expiresAt: Long, nowEpochSeconds: Long): String {
        val left = expiresAt - nowEpochSeconds
        return if (left <= 0) "Expired" else "${left / 60}:${(left % 60).toString().padStart(2, '0')}"
    }
}

/** Exact v1 client-core `pairing_approval_message`: domain, request id, 32-byte nonce,
 *  32-byte candidate key, user id, approver device id, big-endian i64 expiry. */
data class PairingApprovalMessage(
    val requestId: String,
    val nonceHex: String,
    val candidatePublicKeyHex: String,
    val userId: String,
    val approverDeviceId: String,
    val expiresAt: Long,
) : DeviceMessage {
    override fun message(): ByteArray {
        require(listOf(requestId, userId, approverDeviceId).all(PairingApproval.canonicalUuid::matches)) { "Invalid UUID" }
        require(HEX64.matches(nonceHex) && HEX64.matches(candidatePublicKeyHex)) { "Invalid pairing payload" }
        val domain = "jarvis-device-pairing-v1\u0000".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(domain.size + 16 + 32 + 32 + 16 + 16 + 8).put(domain)
        buffer.putUuid(requestId).put(Hex.decode(nonceHex)).put(Hex.decode(candidatePublicKeyHex))
        buffer.putUuid(userId).putUuid(approverDeviceId)
        return buffer.putLong(expiresAt).array()
    }

    private fun ByteBuffer.putUuid(value: String): ByteBuffer =
        UUID.fromString(value).let { putLong(it.mostSignificantBits).putLong(it.leastSignificantBits) }

    private companion object {
        val HEX64 = Regex("[0-9a-fA-F]{64}")
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
    private val approvals: ApprovalLock,
    private val authenticate: suspend (String) -> Boolean,
    val biometricsAvailable: () -> Boolean,
    private val unlocked: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun approve(item: PairingRequestItem) = approvals.exclusive {
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
        // Built once before prompting, so a malformed request never prompts.
        val message = PairingApprovalMessage(item.id, item.nonce, item.candidate_public_key, me.user_id, device, item.expires_at)
            .also { it.message() }
        check(item.expires_at > now()) { EXPIRED }
        // The verified key comes first, so a candidate-chosen name cannot pose as it.
        val signature = identity.sign(
            message,
            "Pair key ${PairingApproval.grouped(fingerprint.take(16))}: \"${PairingApproval.displayName(item.device_name, 40)}\" " +
                "(${PairingApproval.displayName(item.platform, 16)})",
        )
        currentCoroutineContext().ensureActive()
        check(unlocked() && endpoint() == origin && sessions.session() == session && item.expires_at > now()) { "Approval expired or session changed" }
        decide(origin, token, "/v1/auth/pairing/requests/${item.id}/approve", JsonObject(mapOf("signature" to JsonPrimitive(signature))))
    }

    suspend fun deny(item: PairingRequestItem) = approvals.exclusive {
        check(unlocked()) { "App locked" }
        require(PairingApproval.canonicalUuid.matches(item.id)) { "Invalid pairing request" }
        val origin = endpoint() ?: error("Home Node missing")
        val session = sessions.session()
        val token = session.token ?: error("Sign in again")
        if (!authenticate("Deny pairing of \"${PairingApproval.displayName(item.device_name, 40)}\"")) error(CANCELLED)
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
