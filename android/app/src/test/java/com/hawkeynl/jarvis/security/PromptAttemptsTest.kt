package com.hawkeynl.jarvis.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptAttemptsTest {
    @Test
    fun `third rejected finger or face settles the prompt as failed once`() {
        val attempts = PromptAttempts(3)
        assertEquals(null, attempts.settle(BiometricResult.Failed))
        assertEquals(null, attempts.settle(BiometricResult.Failed))
        assertEquals(BiometricResult.Failed, attempts.settle(BiometricResult.Failed))
        // The cancel that closes the prompt is not reported again.
        assertEquals(null, attempts.settle(BiometricResult.Cancelled))
    }

    @Test
    fun `success, cancel and lockout settle at once`() {
        for (result in listOf(BiometricResult.Authenticated(), BiometricResult.Cancelled, BiometricResult.LockedOut)) {
            val attempts = PromptAttempts(3)
            assertEquals(null, attempts.settle(BiometricResult.Failed))
            assertEquals(result, attempts.settle(result))
        }
    }

    @Test
    fun `without a limit rejected attempts keep the prompt open`() {
        val attempts = PromptAttempts()
        repeat(10) { assertEquals(null, attempts.settle(BiometricResult.Failed)) }
        assertEquals(BiometricResult.LockedOut, attempts.settle(BiometricResult.LockedOut))
    }
}
