package com.hawkeynl.jarvis.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ModelEntry(val provider: String, val model: String, val enabled: Boolean)

@Serializable
data class ModelPolicySnapshot(
    val models: List<ModelEntry>, val mutation: String, val policy_sha256: String? = null,
    val user_id: String, val device_id: String, val server_time: Long,
    // Absent on a Core without signed routing. `routing` stays untyped so a newer
    // routing shape never breaks model toggles.
    val routing_mutation: String? = null, val routing_sha256: String? = null,
    val routing: JsonElement? = null, val routing_unavailable_reason: String? = null,
)

/** Exact v1 client-core wire contract, never an arbitrary signing interface. */
data class ModelApproval(
    val request: UUID, val nonce: ByteArray, val user: UUID, val device: UUID,
    val issued: Long, val expires: Long, val provider: String, val model: String,
    val enabled: Boolean, val hash: String,
) {
    fun operation(): JsonObject {
        require(provider in setOf("anthropic-api", "openai-api", "deepseek-api", "xai-api", "zai-api", "ollama", "ollama-cloud", "huggingface", "claude-cli", "codex-cli"))
        require(model.isNotEmpty() && model.toByteArray(Charsets.UTF_8).size <= 256 && model.none { it.isISOControl() })
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        return buildJsonObject {
            put("action", "model_set_enabled"); put("provider", provider); put("model", model)
            put("enabled", enabled); put("expected_policy_sha256", hash)
        }
    }

    fun message(): ByteArray {
        require(nonce.size == 32 && issued >= 0 && expires > issued && expires - issued <= 300)
        val payload = MessageDigest.getInstance("SHA-256").digest(operation().toString().toByteArray(Charsets.UTF_8))
        return approvalMessage("model.set_enabled", payload, request, nonce, user, device, issued, expires, Hex.decode(hash))
    }

    fun signed(signature: String): JsonObject {
        message()
        require(signature.matches(Regex("[0-9a-fA-F]{128}")))
        return buildJsonObject {
            put("request_id", request.toString()); put("nonce_hex", Hex.encode(nonce))
            put("user_id", user.toString()); put("device_id", device.toString())
            put("issued_at", Instant.ofEpochSecond(issued).toString())
            put("expires_at", Instant.ofEpochSecond(expires).toString())
            put("operation", operation()); put("signature_hex", signature)
        }
    }
}

/** client-core `approval_message` (jarvis-privileged-config-v1), shared by every privileged operation. */
fun approvalMessage(
    action: String, payloadHash: ByteArray, request: UUID, nonce: ByteArray, user: UUID, device: UUID,
    issued: Long, expires: Long, targetStateHash: ByteArray,
): ByteArray {
    val domain = "jarvis-privileged-config-v1\u0000".toByteArray(Charsets.UTF_8)
    val name = action.toByteArray(Charsets.UTF_8)
    require(name.isNotEmpty() && name.size <= 64 && action.none { it.isISOControl() })
    require(payloadHash.size == 32 && targetStateHash.size == 32 && nonce.size == 32 && issued >= 0 && expires > issued && expires - issued <= 300)
    return ByteBuffer.allocate(domain.size + 2 + name.size + 32 + 16 + 32 + 16 + 16 + 8 + 8 + 32)
        .put(domain).putShort(name.size.toShort()).put(name).put(payloadHash)
        .putLong(request.mostSignificantBits).putLong(request.leastSignificantBits).put(nonce)
        .putLong(user.mostSignificantBits).putLong(user.leastSignificantBits)
        .putLong(device.mostSignificantBits).putLong(device.leastSignificantBits)
        .putLong(issued).putLong(expires).put(targetStateHash).array()
}
