package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.testing.FakeDeviceKeyVault
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreBackedEd25519IdentityTest {
    private val legacy = InMemorySecureValueStore()
    private val vault = FakeDeviceKeyVault()
    private val identity = KeystoreBackedEd25519Identity(legacy, vault)
    private var prompts = 0
    private var accept = true

    init {
        identity.prompt = { _, cipher -> prompts++; cipher.takeIf { accept } }
    }

    @Test(expected = IllegalStateException::class)
    fun `signing never creates a missing device key`() = runTest {
        identity.signHex("00".repeat(32), "fixture")
    }

    @Test
    fun `public key needs no prompt and every signature prompts once`() = runTest {
        val publicKey = identity.publicKeyHex()
        assertEquals(publicKey, identity.publicKeyHex())
        assertEquals(64, publicKey.length)
        assertEquals(0, prompts)

        repeat(2) { assertValid(publicKey, identity.signHex(Hex.encode(MESSAGE), "fixture")) }
        assertEquals(2, prompts)
        assertEquals(publicKey, identity.publicKeyHex())
        assertEquals(2, prompts)

        identity.reset()
        assertTrue(vault.values.isEmpty())
        assertFalse(publicKey == identity.publicKeyHex())
    }

    @Test
    fun `first signature moves a legacy seed behind biometrics and keeps the public key`() = runTest {
        val seed = ByteArray(32) { (it * 7).toByte() }
        legacy.write(LEGACY_SEED, seed)
        val publicKey = Hex.encode(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)
        assertEquals(publicKey, identity.publicKeyHex())
        assertEquals(0, prompts)

        accept = false
        val cancelled = runCatching { identity.signHex(Hex.encode(MESSAGE), "fixture") }.exceptionOrNull()
        assertTrue("$cancelled", cancelled is DeviceSigningException)
        assertTrue("A cancelled move keeps the legacy seed", legacy.exists(LEGACY_SEED))
        assertNull(vault.load(SEED))

        accept = true
        assertValid(publicKey, identity.signHex(Hex.encode(MESSAGE), "fixture"))
        assertFalse(legacy.exists(LEGACY_SEED))
        assertTrue(vault.load(SEED) != null)
        assertEquals(publicKey, identity.publicKeyHex())
        assertValid(publicKey, identity.signHex(Hex.encode(MESSAGE), "fixture"))
        assertEquals(3, prompts)
    }

    @Test
    fun `invalidated key asks to pair again without losing the public key display`() = runTest {
        val publicKey = identity.publicKeyHex()
        identity.signHex(Hex.encode(MESSAGE), "fixture")
        vault.invalidated = true

        val error = runCatching { identity.signHex(Hex.encode(MESSAGE), "fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceKeyInvalidatedException)
        assertEquals(publicKey, identity.publicKeyHex())
    }

    @Test
    fun `signing without a visible Activity fails closed`() = runTest {
        identity.publicKeyHex()
        identity.prompt = null
        val error = runCatching { identity.signHex(Hex.encode(MESSAGE), "fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceSigningException)
        assertTrue(legacy.exists(LEGACY_SEED))
    }

    @Test
    fun `a cipher other than the Keystore one is refused`() = runTest {
        identity.publicKeyHex()
        identity.prompt = { _, _ -> javax.crypto.Cipher.getInstance("AES/GCM/NoPadding") }
        val error = runCatching { identity.signHex(Hex.encode(MESSAGE), "fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceSigningException)
        assertNull(vault.load(SEED))
        assertTrue(legacy.exists(LEGACY_SEED))
    }

    private fun assertValid(publicKey: String, signatureHex: String) {
        val signature = Hex.decode(signatureHex)
        assertEquals(64, signature.size)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(publicKey), 0))
            update(MESSAGE, 0, MESSAGE.size)
        }
        assertTrue(verifier.verifySignature(signature))
    }

    private companion object {
        val MESSAGE = ByteArray(32) { it.toByte() }
        const val LEGACY_SEED = "device-ed25519-seed.v1"
        const val SEED = "device-ed25519-seed.v2"
    }
}
