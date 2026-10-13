package com.hawkeynl.jarvis.security

import java.nio.ByteBuffer
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
