package com.hawkeynl.jarvis.testing

import com.hawkeynl.jarvis.security.DeviceKeyInvalidatedException
import com.hawkeynl.jarvis.security.DeviceKeyVault
import java.util.Collections
import java.util.IdentityHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Software AES-GCM stand-in for the biometric-bound Keystore key. Like Keystore, a
 *  cipher works only once a prompt has authorised it ([authorise]). */
class FakeDeviceKeyVault : DeviceKeyVault {
    val values = mutableMapOf<String, ByteArray>()
    private var key: SecretKey? = null
    private val authorised = Collections.newSetFromMap(IdentityHashMap<Cipher, Boolean>())

    /** Ciphers handed out for a prompt. */
    var handedOut = 0
        private set

    /** What Android does after a biometric enrolment change. */
    var invalidated = false

    /** What a successful CryptoObject prompt does to its cipher. */
    fun authorise(cipher: Cipher) { authorised += cipher }

    override fun load(name: String): ByteArray? = values[name]?.copyOf()
    override fun store(name: String, value: ByteArray) { values[name] = value.copyOf() }
    override fun remove(name: String) { values.remove(name) }

    override fun encrypter(): Cipher {
        if (key == null || invalidated) { key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); invalidated = false }
        handedOut++
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decrypter(iv: ByteArray): Cipher {
        val current = key?.takeUnless { invalidated } ?: throw DeviceKeyInvalidatedException()
        handedOut++
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(128, iv)) }
    }

    override fun finish(cipher: Cipher, aad: ByteArray, input: ByteArray): ByteArray {
        check(authorised.remove(cipher)) { "Key user not authenticated" }
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    override fun deleteKey() { key = null; invalidated = false }
}
