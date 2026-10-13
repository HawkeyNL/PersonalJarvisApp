package com.hawkeynl.jarvis.security

import kotlinx.coroutines.sync.Mutex

/** Everything the device key may sign. Each type builds its exact client-core message
 *  from typed, validated fields; raw server bytes never reach the signer. */
sealed interface DeviceMessage {
    fun message(): ByteArray
}

interface DeviceIdentity {
    /** Never prompts; fails when this device has no key yet. */
    fun publicKeyHex(): String

    /** The public key to enrol, creating the key first (one strong-biometric prompt
     *  showing [reason]) when this device has none. */
    suspend fun enrollmentKeyHex(reason: String): String

    /** Signs [message] after one strong-biometric prompt that shows [reason]. */
    suspend fun sign(message: DeviceMessage, reason: String): String
    suspend fun reset()
}

class ApprovalBusyException : IllegalStateException("Another approval is in progress.")

/** One approval (fetch, prompt, sign, send) at a time across the whole app. A second
 *  one is refused rather than queued, so prompts never stack up behind each other. */
class ApprovalLock {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T {
        if (!mutex.tryLock()) throw ApprovalBusyException()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
