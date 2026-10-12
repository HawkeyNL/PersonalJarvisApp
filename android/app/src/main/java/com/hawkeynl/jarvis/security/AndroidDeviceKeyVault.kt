package com.hawkeynl.jarvis.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM key that Android Keystore releases for one operation per strong-biometric
 * prompt, never while the device is locked, and invalidates when biometrics change.
 * StrongBox when the phone has it, otherwise the TEE.
 */
class AndroidDeviceKeyVault(context: Context) : DeviceKeyVault {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(name: String): ByteArray? = preferences.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    override fun store(name: String, value: ByteArray) {
        check(preferences.edit().putString(name, Base64.encodeToString(value, Base64.NO_WRAP)).commit()) { "Device key storage cannot be updated." }
    }

    override fun remove(name: String) {
        check(preferences.edit().remove(name).commit()) { "Device key storage cannot be cleared." }
    }

    override fun encrypter(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey())
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Only reached while moving a legacy seed: no ciphertext uses this key yet.
            deleteKey()
            cipher.init(Cipher.ENCRYPT_MODE, generateKey())
        }
        return cipher
    }

    override fun decrypter(iv: ByteArray): Cipher {
        val key = existingKey() ?: throw DeviceKeyInvalidatedException()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw DeviceKeyInvalidatedException()
        }
    }

    override fun deleteKey() {
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey() = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun generateKey(): SecretKey = try {
        try {
            generateKey(strongBox = true)
        } catch (_: ProviderException) {
            // StrongBoxUnavailableException is a ProviderException; fall back to the TEE.
            generateKey(strongBox = false)
        }
    } catch (error: Exception) {
        throw DeviceSigningException("Set up fingerprint or face unlock on this phone first.").apply { initCause(error) }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 28-29: -1 means every use needs a biometric prompt with a CryptoObject.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.hawkeynl.jarvis.identity.v2"
        const val PREFERENCES = "jarvis_device_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
