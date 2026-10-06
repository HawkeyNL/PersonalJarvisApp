package com.hawkeynl.jarvis.security

import org.junit.Assert.*
import org.junit.Test

class LoginMessageTest {
    private val challenge = "00112233-4455-6677-8899-aabbccddeeff"
    private val device = "ffeeddcc-bbaa-9988-7766-554433221100"
    private val nonce = Hex.encode(ByteArray(32) { it.toByte() })

    @Test fun goldenVector() {
        val expected = "6a61727669732d6c6f67696e2d763100" + "00112233445566778899aabbccddeeff" +
            "ffeeddccbbaa99887766554433221100" + "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        assertEquals(expected, Hex.encode(LoginMessage.build(challenge, device, nonce)))
    }

    @Test fun rejectsBadNonceAndUuid() {
        assertThrows(IllegalArgumentException::class.java) { LoginMessage.build(challenge, device, "00".repeat(31)) }
        assertThrows(IllegalArgumentException::class.java) { LoginMessage.build("1-1-1-1-1", device, nonce) }
        assertThrows(IllegalArgumentException::class.java) { LoginMessage.build(challenge, device, "zz".repeat(32)) }
    }
}
