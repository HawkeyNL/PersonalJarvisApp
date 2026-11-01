package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RootTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.example.test") as EndpointValidation.Valid).endpoint

    @Test
    fun `no dock before a Home Node is set and a session exists`() {
        assertEquals(Root.ONBOARDING, rootFor(JarvisUiState()))
        assertEquals(Root.ONBOARDING, rootFor(JarvisUiState(authenticated = true)))
        assertEquals(Root.ONBOARDING, rootFor(JarvisUiState(endpoint = endpoint)))
        assertEquals(Root.ONBOARDING, rootFor(JarvisUiState(endpoint = endpoint, pairingPending = true)))
        assertEquals(Root.SHELL, rootFor(JarvisUiState(endpoint = endpoint, authenticated = true)))
    }

    @Test
    fun `the lock screen wins over everything`() {
        assertEquals(Root.LOCK, rootFor(JarvisUiState(locked = true)))
        assertEquals(Root.LOCK, rootFor(JarvisUiState(locked = true, endpoint = endpoint, authenticated = true)))
    }
}
