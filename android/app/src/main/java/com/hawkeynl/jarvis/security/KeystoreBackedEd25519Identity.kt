package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.storage.SecureValueStore
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Signing stopped before a signature existed; the message is safe to show. */
open class DeviceSigningException(message: String) : IllegalStateException(message)

/** The biometric-bound key is gone (biometrics or screen lock changed), and with it the seed. */
class DeviceKeyInvalidatedException : DeviceSigningException(
    "Fingerprint or face data on this phone changed, so its Jarvis device key no longer works. Pair this phone again.",
)

/** Android Keystore side of the device key, replaced by a fake in JVM tests. */
interface DeviceKeyVault {
    /** Plain app-private storage: the public key and the seed ciphertext. */
    fun load(name: String): ByteArray?
    fun store(name: String, value: ByteArray)
    fun remove(name: String)

    /** Encrypt cipher on the biometric-bound key, replacing an invalidated key. Usable only after a prompt. */
    fun encrypter(): Cipher

    /** Decrypt cipher; throws [DeviceKeyInvalidatedException] when the key was invalidated. Usable only after a prompt. */
    fun decrypter(iv: ByteArray): Cipher
    fun deleteKey()
}

/**
 * Android Keystore does not offer Ed25519 signing on all supported Android releases.
 * A unique 32-byte Ed25519 seed is therefore generated in native code and persisted
 * only as ciphertext under a non-exportable Android Keystore AES key that needs a
 * strong biometric prompt for every use. The seed is decrypted only with the cipher
 * that prompt authorised and is cleared after signing. The public key is stored in
 * plain form so showing it never prompts.
 *
 * Until its first signature a seed sits under the shared secrets key, as every seed
 * did before this key existed; the first signature moves it (one prompt).
 */
class KeystoreBackedEd25519Identity(
    private val legacy: SecureValueStore,
    private val vault: DeviceKeyVault,
    private val random: SecureRandom = SecureRandom(),
) : DeviceIdentity {
    /** Shows one strong-biometric prompt bound to the cipher and returns it authorised,
     *  or null when cancelled. Set by the visible Activity. */
    @Volatile
    var prompt: (suspend (reason: String, cipher: Cipher) -> Cipher?)? = null
    private val signing = Mutex()

    @Synchronized
    override fun publicKeyHex(): String {
        vault.load(PUBLIC_KEY)?.let { return Hex.encode(it) }
        check(vault.load(SEED) == null) { "Device public key missing; explicitly re-enroll this device." }
        val seed = legacy.read(LEGACY_SEED) ?: ByteArray(Ed25519PrivateKeyParameters.KEY_SIZE).also {
            random.nextBytes(it)
            legacy.write(LEGACY_SEED, it)
        }
        return try {
            publicKey(seed).also { vault.store(PUBLIC_KEY, it) }.let(Hex::encode)
        } finally {
            seed.fill(0)
        }
    }

    override suspend fun signHex(messageHex: String, reason: String): String = signing.withLock {
        val message = Hex.decode(messageHex)
        val authenticate = prompt ?: throw DeviceSigningException("Open Jarvis to confirm with biometrics.")
        val sealed = synchronized(this) { vault.load(SEED) }
        val seed = if (sealed != null) unseal(sealed, reason, authenticate) else migrate(reason, authenticate)
        try {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
            signer.update(message, 0, message.size)
            Hex.encode(signer.generateSignature())
        } finally {
            seed.fill(0)
        }
    }

    override suspend fun reset() = signing.withLock {
        synchronized(this) {
            legacy.remove(LEGACY_SEED)
            vault.remove(SEED)
            vault.remove(PUBLIC_KEY)
            vault.deleteKey()
        }
    }

    private suspend fun unseal(sealed: ByteArray, reason: String, authenticate: suspend (String, Cipher) -> Cipher?): ByteArray {
        val buffer = ByteBuffer.wrap(sealed)
        val ivLength = buffer.get().toInt() and 0xff
        check(ivLength in 12..16 && sealed.size > ivLength + 1) { "Device key storage is damaged; explicitly re-enroll this device." }
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = authorised(reason, vault.decrypter(iv), authenticate)
        cipher.updateAAD(SEED.toByteArray(Charsets.UTF_8))
        val seed = cipher.doFinal(ciphertext)
        try {
            // A copy left by an interrupted move is the same seed without biometric protection.
            synchronized(this) { if (legacy.exists(LEGACY_SEED)) legacy.remove(LEGACY_SEED) }
        } catch (error: Exception) {
            seed.fill(0)
            throw error
        }
        return seed
    }

    /** One prompt authorises encrypting the legacy seed under the biometric key; the
     *  legacy copy is deleted only after the new ciphertext and public key are stored. */
    private suspend fun migrate(reason: String, authenticate: suspend (String, Cipher) -> Cipher?): ByteArray {
        val seed = synchronized(this) { legacy.read(LEGACY_SEED) }
            ?: throw IllegalStateException("Original device key unavailable; explicitly re-enroll this device.")
        try {
            val cipher = authorised(reason, vault.encrypter(), authenticate)
            cipher.updateAAD(SEED.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(seed)
            val sealed = ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
                .put(cipher.iv.size.toByte()).put(cipher.iv).put(ciphertext).array()
            synchronized(this) {
                vault.store(PUBLIC_KEY, publicKey(seed))
                vault.store(SEED, sealed)
                legacy.remove(LEGACY_SEED)
            }
            return seed
        } catch (error: Throwable) {
            seed.fill(0)
            throw error
        }
    }

    /** Only the Keystore cipher handed to the prompt may touch the seed. */
    private suspend fun authorised(reason: String, cipher: Cipher, authenticate: suspend (String, Cipher) -> Cipher?): Cipher =
        authenticate(reason, cipher)?.takeIf { it === cipher } ?: throw cancelled()

    private fun publicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    private fun cancelled() = DeviceSigningException("Cancelled; fingerprint or face unlock is required.")

    private companion object {
        const val LEGACY_SEED = "device-ed25519-seed.v1"
        const val SEED = "device-ed25519-seed.v2"
        const val PUBLIC_KEY = "device-ed25519-public.v2"
    }
}
