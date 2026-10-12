package com.hawkeynl.jarvis.security

interface DeviceIdentity {
    /** Never prompts. */
    fun publicKeyHex(): String

    /** Signs after one strong-biometric prompt that shows [reason]. */
    suspend fun signHex(messageHex: String, reason: String): String
    suspend fun reset()
}
