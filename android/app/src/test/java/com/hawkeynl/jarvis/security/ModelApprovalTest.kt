package com.hawkeynl.jarvis.security

import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ModelApprovalTest {
    private fun approval() = ModelApproval(UUID.fromString("01010101-0101-0101-0101-010101010101"), ByteArray(32) { 2 },
        UUID.fromString("03030303-0303-0303-0303-030303030303"), UUID.fromString("04040404-0404-0404-0404-040404040404"),
        1, 121, "huggingface", "org/fixture", true, "05".repeat(32))

    @Test fun sharedRustPayloadAndMessageVector() {
        val approval = approval()
        val hash = "db216127795b1015f2c95877514c1e8ecb492775276bbb73412b10a7073c98c0"
        assertEquals(hash, Hex.encode(MessageDigest.getInstance("SHA-256").digest(approval.operation().toString().toByteArray())))
        val expected = Hex.encode("jarvis-privileged-config-v1\u0000".toByteArray()) + "0011" + Hex.encode("model.set_enabled".toByteArray()) + hash +
            "01".repeat(16) + "02".repeat(32) + "03".repeat(16) + "04".repeat(16) + "00000000000000010000000000000079" + "05".repeat(32)
        assertEquals(expected, Hex.encode(approval.message()))
    }

    @Test fun invalidAndAlteredApprovalCannotReuseSignatureBytes() {
        val original = approval()
        assertFalse(original.message().contentEquals(original.copy(enabled = false).message()))
        assertFalse(original.message().contentEquals(original.copy(hash = "06".repeat(32)).message()))
        for (invalid in listOf(original.copy(provider = "shell"), original.copy(model = "fixture\ncommand"), original.copy(expires = 1), original.copy(expires = 302), original.copy(nonce = ByteArray(31)))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.message() }
        }
    }
}
