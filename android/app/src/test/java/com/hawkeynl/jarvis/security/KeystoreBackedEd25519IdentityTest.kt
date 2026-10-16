package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.testing.FakeDeviceKeyVault
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import javax.crypto.Cipher
import kotlinx.coroutines.awaitCancellation
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
        identity.prompt = { _, cipher -> prompts++; cipher.takeIf { accept }?.also(vault::authorise) }
    }

    @Test(expected = IllegalStateException::class)
    fun `signing never creates a missing device key`() = runTest {
        identity.sign(MESSAGE, "fixture")
    }

    @Test(expected = IllegalStateException::class)
    fun `public key never creates a device key`() {
        identity.publicKeyHex()
    }

    @Test
    fun `new key is created behind biometrics and every signature prompts once`() = runTest {
        val publicKey = identity.enrollmentKeyHex("fixture")
        assertEquals(1, prompts)
        assertFalse("A new key never sits under the legacy key", legacy.exists(LEGACY_SEED))
        assertTrue(vault.load(SEED) != null)
        assertEquals(publicKey, identity.enrollmentKeyHex("fixture"))
        assertEquals(publicKey, identity.publicKeyHex())
        assertEquals(64, publicKey.length)
        assertEquals("Reading the public key never prompts", 1, prompts)

        repeat(2) { assertValid(publicKey, identity.sign(MESSAGE, "fixture")) }
        assertEquals(3, prompts)
        assertEquals("Every cipher handed out went through a prompt", prompts, vault.handedOut)

        identity.reset()
        assertTrue(vault.values.isEmpty())
        assertFalse(publicKey == identity.enrollmentKeyHex("fixture"))
    }

    @Test
    fun `cancelled creation stores nothing`() = runTest {
        accept = false
        val error = runCatching { identity.enrollmentKeyHex("fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceSigningException)
        assertTrue(vault.values.isEmpty())
        assertFalse(legacy.exists(LEGACY_SEED))
    }

    @Test
    fun `first signature moves a legacy seed behind biometrics and keeps the public key`() = runTest {
        val seed = ByteArray(32) { (it * 7).toByte() }
        legacy.write(LEGACY_SEED, seed)
        val publicKey = Hex.encode(Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded)
        assertEquals(publicKey, identity.publicKeyHex())
        assertEquals(publicKey, identity.enrollmentKeyHex("fixture"))
        assertEquals(0, prompts)

        accept = false
        val cancelled = runCatching { identity.sign(MESSAGE, "fixture") }.exceptionOrNull()
        assertTrue("$cancelled", cancelled is DeviceSigningException)
        assertTrue("A cancelled move keeps the legacy seed", legacy.exists(LEGACY_SEED))
        assertNull(vault.load(SEED))

        accept = true
        assertValid(publicKey, identity.sign(MESSAGE, "fixture"))
        assertFalse(legacy.exists(LEGACY_SEED))
        assertTrue(vault.load(SEED) != null)
        assertEquals(publicKey, identity.publicKeyHex())
        assertValid(publicKey, identity.sign(MESSAGE, "fixture"))
        assertEquals(3, prompts)
        assertEquals(prompts, vault.handedOut)
    }

    @Test
    fun `invalidated key reports loss but erases nothing`() = runTest {
        val publicKey = identity.enrollmentKeyHex("fixture")
        vault.invalidated = true
        val sealed = vault.load(SEED)

        val error = runCatching { identity.sign(MESSAGE, "fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceKeyInvalidatedException)
        assertEquals(publicKey, identity.publicKeyHex())
        assertTrue(sealed.contentEquals(vault.load(SEED)))
    }

    @Test
    fun `signing without a visible Activity fails closed`() = runTest {
        legacy.write(LEGACY_SEED, ByteArray(32) { 1 })
        identity.prompt = null
        val error = runCatching { identity.sign(MESSAGE, "fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceSigningException)
        assertTrue(legacy.exists(LEGACY_SEED))
    }

    @Test
    fun `a cipher other than the Keystore one is refused`() = runTest {
        legacy.write(LEGACY_SEED, ByteArray(32) { 1 })
        identity.prompt = { _, _ -> Cipher.getInstance("AES/GCM/NoPadding") }
        val error = runCatching { identity.sign(MESSAGE, "fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceSigningException)
        assertNull(vault.load(SEED))
        assertTrue(legacy.exists(LEGACY_SEED))
    }

    @Test
    fun `a prompt that never answers releases the signing lock`() = runTest {
        identity.enrollmentKeyHex("fixture")
        identity.prompt = { _, _ -> awaitCancellation() }
        val error = runCatching { identity.sign(MESSAGE, "fixture") }.exceptionOrNull()
        assertTrue("$error", error is DeviceSigningException)
        identity.reset()
        assertTrue(vault.values.isEmpty())
    }

    private fun assertValid(publicKey: String, signatureHex: String) {
        val signature = Hex.decode(signatureHex)
        assertEquals(64, signature.size)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(publicKey), 0))
            val bytes = MESSAGE.message()
            update(bytes, 0, bytes.size)
        }
        assertTrue(verifier.verifySignature(signature))
    }

    private companion object {
        val MESSAGE = LoginMessage("00112233-4455-6677-8899-aabbccddeeff", "ffeeddcc-bbaa-9988-7766-554433221100", "00".repeat(32))
        const val LEGACY_SEED = "device-ed25519-seed.v1"
        const val SEED = "device-ed25519-seed.v2"
    }
}
