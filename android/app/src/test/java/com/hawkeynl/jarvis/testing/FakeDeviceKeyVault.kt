package com.hawkeynl.jarvis.testing

import com.hawkeynl.jarvis.security.DeviceKeyInvalidatedException
import com.hawkeynl.jarvis.security.DeviceKeyVault
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Software AES-GCM stand-in for the biometric-bound Keystore key. */
class FakeDeviceKeyVault : DeviceKeyVault {
    val values = mutableMapOf<String, ByteArray>()
    private var key: SecretKey? = null

    /** What Android does after a biometric enrolment change. */
    var invalidated = false

    override fun load(name: String): ByteArray? = values[name]?.copyOf()
    override fun store(name: String, value: ByteArray) { values[name] = value.copyOf() }
    override fun remove(name: String) { values.remove(name) }

    override fun encrypter(): Cipher {
        if (key == null || invalidated) { key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); invalidated = false }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decrypter(iv: ByteArray): Cipher {
        val current = key?.takeUnless { invalidated } ?: throw DeviceKeyInvalidatedException()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(128, iv)) }
    }

    override fun deleteKey() { key = null; invalidated = false }
}
