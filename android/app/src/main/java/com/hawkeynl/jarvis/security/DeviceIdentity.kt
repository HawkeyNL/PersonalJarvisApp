package com.hawkeynl.jarvis.security

interface DeviceIdentity {
    /** Never prompts; fails when this device has no key yet. */
    fun publicKeyHex(): String

    /** The public key to enrol, creating the key first (one strong-biometric prompt
     *  showing [reason]) when this device has none. */
    suspend fun enrollmentKeyHex(reason: String): String

    /** Signs after one strong-biometric prompt that shows [reason]. */
    suspend fun signHex(messageHex: String, reason: String): String
    suspend fun reset()
}
