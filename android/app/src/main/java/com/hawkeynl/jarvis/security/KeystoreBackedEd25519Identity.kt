package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.storage.SecureValueStore
import java.security.SecureRandom
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Signing stopped before a signature existed; the message is safe to show. */
open class DeviceSigningException(message: String) : IllegalStateException(message)

/** Keystore reports the biometric-bound key permanently invalidated (biometrics or screen
 *  lock changed), so the seed cannot be recovered. Nothing is erased until the user asks. */
class DeviceKeyInvalidatedException : DeviceSigningException(
    "Fingerprint or face data on this phone changed, so its Jarvis device key no longer works. Erase this device and pair it again.",
)

/** The user cancelled or failed the biometric prompt; nothing was signed. */
class SigningCancelledException : DeviceSigningException("Cancelled; fingerprint or face unlock is required.")

/** The sign-in prompt rejected the finger or face too often; nothing was signed. */
class BiometricNotRecognisedException : DeviceSigningException("Fingerprint or face not recognised. Enter your account password.")

/** Android Keystore side of the device key, replaced by a fake in JVM tests. */
interface DeviceKeyVault {
    /** Plain app-private storage: the public key and the seed ciphertext. */
    fun load(name: String): ByteArray?
    fun store(name: String, value: ByteArray)
    fun remove(name: String)

    /** Encrypt cipher on the biometric-bound key. Called only while no ciphertext uses
     *  the key, so a key that cannot be loaded or used is replaced. */
    fun encrypter(): Cipher

    /** Decrypt cipher; throws [DeviceKeyInvalidatedException] only when Keystore reports
     *  the key permanently invalidated. */
    fun decrypter(iv: ByteArray): Cipher

    /** Runs a cipher the prompt authorised (AAD, then doFinal); maps permanent
     *  invalidation to [DeviceKeyInvalidatedException]. */
    fun finish(cipher: Cipher, aad: ByteArray, input: ByteArray): ByteArray
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
 * A seed from an older version, kept under the shared secrets key, moves behind the
 * biometric key on its next signature (one prompt). New seeds start there directly.
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
    private val lock = Any()

    override fun publicKeyHex(): String = synchronized(lock) {
        vault.load(PUBLIC_KEY)?.let { return Hex.encode(it) }
        val seed = legacy.read(LEGACY_SEED) ?: throw IllegalStateException("No device key; pair this device first.")
        try {
            Hex.encode(publicKey(seed).also { vault.store(PUBLIC_KEY, it) })
        } finally {
            seed.fill(0)
        }
    }

    override suspend fun enrollmentKeyHex(reason: String): String = signing.withLock {
        val existing = io {
            synchronized(lock) {
                check(vault.load(PUBLIC_KEY) != null || vault.load(SEED) == null) { "Device key storage is damaged; erase this device and pair again." }
                if (vault.load(PUBLIC_KEY) != null || legacy.exists(LEGACY_SEED)) publicKeyHex() else null
            }
        }
        if (existing != null) return@withLock existing
        val seed = ByteArray(Ed25519PrivateKeyParameters.KEY_SIZE).also(random::nextBytes)
        try {
            seal(seed, reason)
            Hex.encode(publicKey(seed))
        } finally {
            seed.fill(0)
        }
    }

    override suspend fun sign(message: DeviceMessage, reason: String): String = signing.withLock {
        val bytes = message.message()
        val sealed = io { synchronized(lock) { vault.load(SEED) } }
        val seed = if (sealed != null) unseal(sealed, reason) else migrate(reason)
        try {
            // Known limit: BouncyCastle keeps its own copy of the seed inside the key
            // parameters, which cannot be cleared; it is garbage once signing returns.
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
            signer.update(bytes, 0, bytes.size)
            Hex.encode(signer.generateSignature())
        } finally {
            seed.fill(0)
        }
    }

    override suspend fun reset() = signing.withLock {
        io {
            synchronized(lock) {
                legacy.remove(LEGACY_SEED)
                vault.remove(SEED)
                vault.remove(PUBLIC_KEY)
                vault.deleteKey()
            }
        }
    }

    private suspend fun unseal(sealed: ByteArray, reason: String): ByteArray {
        val ivLength = sealed[0].toInt() and 0xff
        check(ivLength in 12..16 && sealed.size >= 1 + ivLength + TAG_BYTES + Ed25519PrivateKeyParameters.KEY_SIZE) {
            "Device key storage is damaged; erase this device and pair again."
        }
        val iv = sealed.copyOfRange(1, 1 + ivLength)
        val ciphertext = sealed.copyOfRange(1 + ivLength, sealed.size)
        val cipher = io { vault.decrypter(iv) }
        authorise(reason, cipher)
        val seed = io { vault.finish(cipher, AAD, ciphertext) }
        try {
            // A copy left by an interrupted move is the same seed without biometric protection.
            io { synchronized(lock) { if (legacy.exists(LEGACY_SEED)) legacy.remove(LEGACY_SEED) } }
        } catch (error: Exception) {
            seed.fill(0)
            throw error
        }
        return seed
    }

    /** Moves the legacy seed behind the biometric key; [seal] deletes the legacy copy
     *  only after the new ciphertext and public key are stored. */
    private suspend fun migrate(reason: String): ByteArray {
        val seed = io { synchronized(lock) { legacy.read(LEGACY_SEED) } }
            ?: throw IllegalStateException("Original device key unavailable; explicitly re-enroll this device.")
        try {
            seal(seed, reason)
            return seed
        } catch (error: Throwable) {
            seed.fill(0)
            throw error
        }
    }

    /** One prompt authorises encrypting [seed]. Only runs while no v2 ciphertext exists. */
    private suspend fun seal(seed: ByteArray, reason: String) {
        val cipher = io { vault.encrypter() }
        authorise(reason, cipher)
        val ciphertext = try {
            io { vault.finish(cipher, AAD, seed) }
        } catch (_: DeviceKeyInvalidatedException) {
            // Nothing is sealed under this key yet: drop it so the next try makes a new one.
            io { vault.deleteKey() }
            throw DeviceSigningException("The device key could not be stored; try again.")
        }
        val sealed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        io {
            synchronized(lock) {
                vault.store(PUBLIC_KEY, publicKey(seed))
                vault.store(SEED, sealed)
                if (legacy.exists(LEGACY_SEED)) legacy.remove(LEGACY_SEED)
            }
        }
    }

    /** Only the Keystore cipher handed to the prompt may touch the seed. A prompt that
     *  never answers must not hold the signing lock for good. */
    private suspend fun authorise(reason: String, cipher: Cipher) {
        val authenticate = prompt ?: throw DeviceSigningException("Open Jarvis to confirm with biometrics.")
        val result = withTimeoutOrNull(PROMPT_TIMEOUT_MS) { authenticate(reason, cipher) }
        if (result !== cipher) throw SigningCancelledException()
    }

    private fun publicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    /** Keystore and preference I/O stays off the main thread. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val LEGACY_SEED = "device-ed25519-seed.v1"
        const val SEED = "device-ed25519-seed.v2"
        const val PUBLIC_KEY = "device-ed25519-public.v2"
        const val TAG_BYTES = 16
        const val PROMPT_TIMEOUT_MS = 120_000L
        val AAD = SEED.toByteArray(Charsets.UTF_8)
    }
}
