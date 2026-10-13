package com.hawkeynl.jarvis.security

import org.junit.Assert.*
import org.junit.Test

class LoginMessageTest {
    private val challenge = "00112233-4455-6677-8899-aabbccddeeff"
    private val device = "ffeeddcc-bbaa-9988-7766-554433221100"
    private val nonce = Hex.encode(ByteArray(32) { it.toByte() })
    private val tail = "00112233445566778899aabbccddeeff" + "ffeeddccbbaa99887766554433221100" +
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    /** client-core `login_and_unlock_v1_match_golden_bytes`. */
    @Test fun goldenVectors() {
        assertEquals("6a61727669732d6c6f67696e2d763100$tail", Hex.encode(LoginMessage(challenge, device, nonce).message()))
        assertEquals(
            "6a61727669732d756e6c6f636b2d617070726f76616c2d763100$tail",
            Hex.encode(UnlockApprovalMessage(challenge, device, nonce).message()),
        )
    }

    @Test fun rejectsBadNonceAndUuid() {
        for (message in listOf(
            LoginMessage(challenge, device, "00".repeat(31)),
            LoginMessage("1-1-1-1-1", device, nonce),
            LoginMessage(challenge, device, "zz".repeat(32)),
            UnlockApprovalMessage(challenge, device, "+0" + "00".repeat(31)),
            UnlockApprovalMessage(challenge, "ffeeddcc-bbaa-9988-7766-55443322110", nonce),
        )) {
            assertThrows(IllegalArgumentException::class.java) { message.message() }
        }
        // Character.digit accepts fullwidth digits; the hex decoder must not.
        assertThrows(IllegalArgumentException::class.java) { Hex.decode("００") }
    }
}
