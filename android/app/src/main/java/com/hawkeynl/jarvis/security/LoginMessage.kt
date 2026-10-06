package com.hawkeynl.jarvis.security

import java.nio.ByteBuffer
import java.util.UUID

/** Exact v1 client-core `login_message`: domain, challenge id, device id, 32-byte nonce. */
object LoginMessage {
    private val canonicalUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Throws IllegalArgumentException for a malformed UUID or a nonce that is not 32 bytes. */
    fun build(challengeId: String, deviceId: String, nonceHex: String): ByteArray {
        require(canonicalUuid.matches(challengeId) && canonicalUuid.matches(deviceId)) { "Invalid UUID" }
        val nonce = Hex.decode(nonceHex)
        require(nonce.size == 32) { "Nonce must be 32 bytes" }
        val domain = "jarvis-login-v1\u0000".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(domain.size + 16 + 16 + 32).put(domain)
        for (id in listOf(UUID.fromString(challengeId), UUID.fromString(deviceId))) {
            buffer.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
        }
        return buffer.put(nonce).array()
    }
}
