package com.hawkeynl.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JarvisUiStateTest {
    @Test
    fun `the own device id shows only while signed in`() {
        val signedIn = JarvisUiState(authenticated = true, signedInDeviceId = "d1")
        assertEquals("d1", signedIn.ownDeviceId)
        // Every sign-out, session expiry or Home Node change sets authenticated to false.
        assertNull(signedIn.copy(authenticated = false).ownDeviceId)
        assertNull(JarvisUiState(authenticated = true).ownDeviceId)
    }
}
