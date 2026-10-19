package com.hawkeynl.jarvis.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random
import java.util.UUID

private val canonicalUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val hex64 = Regex("[0-9a-fA-F]{64}")

/** Exact v1 client-core `login_message`: domain, challenge id, device id, 32-byte nonce. */
data class LoginMessage(val challengeId: String, val deviceId: String, val nonceHex: String) : DeviceMessage {
    /** Throws IllegalArgumentException for a malformed UUID or a nonce that is not 32 bytes. */
    override fun message(): ByteArray = challengeMessage("jarvis-login-v1", challengeId, deviceId, nonceHex)
}

/** Exact v1 client-core `unlock_approval_message`: domain, request id, approver device id, 32-byte nonce. */
data class UnlockApprovalMessage(val requestId: String, val approverDeviceId: String, val nonceHex: String) : DeviceMessage {
    override fun message(): ByteArray = challengeMessage("jarvis-unlock-approval-v1", requestId, approverDeviceId, nonceHex)
}

/** Exact v1 client-core `unlock_code`: the first two bytes (big-endian) of SHA-256(domain,
 *  request id, 32-byte nonce), modulo 100. The requesting device shows it; the owner must pick it here. */
object UnlockCode {
    private val domain = "jarvis-unlock-code-v1\u0000".toByteArray(Charsets.UTF_8)

    /** Throws IllegalArgumentException for a malformed UUID or a nonce that is not 32 bytes. */
    fun code(requestId: String, nonceHex: String): Int {
        require(canonicalUuid.matches(requestId)) { "Invalid UUID" }
        require(hex64.matches(nonceHex)) { "Nonce must be 32 bytes" }
        val id = UUID.fromString(requestId)
        val input = ByteBuffer.allocate(domain.size + 16 + 32).put(domain)
            .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .put(Hex.decode(nonceHex)).array()
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return ((digest[0].toInt() and 0xff) shl 8 or (digest[1].toInt() and 0xff)) % 100
    }

    /** The code and two distinct random decoys, shuffled. */
    fun choices(code: Int, random: Random = SecureRandom()): List<Int> {
        val choices = mutableListOf(code)
        while (choices.size < 3) {
            val decoy = random.nextInt(100)
            if (decoy !in choices) choices += decoy
        }
        return choices.shuffled(random)
    }

    fun display(code: Int): String = code.toString().padStart(2, '0')
}

private fun challengeMessage(domain: String, id: String, deviceId: String, nonceHex: String): ByteArray {
    require(canonicalUuid.matches(id) && canonicalUuid.matches(deviceId)) { "Invalid UUID" }
    require(hex64.matches(nonceHex)) { "Nonce must be 32 bytes" }
    val prefix = "$domain\u0000".toByteArray(Charsets.UTF_8)
    val buffer = ByteBuffer.allocate(prefix.size + 16 + 16 + 32).put(prefix)
    for (value in listOf(UUID.fromString(id), UUID.fromString(deviceId))) {
        buffer.putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)
    }
    return buffer.put(Hex.decode(nonceHex)).array()
}
