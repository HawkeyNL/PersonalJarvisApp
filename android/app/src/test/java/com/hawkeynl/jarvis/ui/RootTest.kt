package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RootTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.example.test") as EndpointValidation.Valid).endpoint

    @Test
    fun `no tabs before a Home Node is set and a session exists`() {
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

    @Test
    fun `a deep-linked screen waits for unlock and sign-in`() {
        for (screen in Screen.entries) {
            assertEquals(Root.ONBOARDING, rootFor(JarvisUiState(screen = screen)))
            assertEquals(Root.ONBOARDING, rootFor(JarvisUiState(screen = screen, endpoint = endpoint)))
            assertEquals(Root.LOCK, rootFor(JarvisUiState(screen = screen, locked = true, endpoint = endpoint, authenticated = true)))
            assertEquals(Root.SHELL, rootFor(JarvisUiState(screen = screen, endpoint = endpoint, authenticated = true)))
        }
    }

    @Test
    fun `onboarding progress follows the real sign-in state`() {
        assertEquals(0, onboardingStep(JarvisUiState(), editingEndpoint = false))
        assertEquals(1, onboardingStep(JarvisUiState(endpoint = endpoint), editingEndpoint = false))
        assertEquals(1, onboardingStep(JarvisUiState(endpoint = endpoint, activationRequired = true), editingEndpoint = false))
        assertEquals(1, onboardingStep(JarvisUiState(endpoint = endpoint, deviceKeyLost = true), editingEndpoint = false))
        assertEquals(2, onboardingStep(JarvisUiState(endpoint = endpoint, pairingPending = true), editingEndpoint = false))
        assertEquals(0, onboardingStep(JarvisUiState(endpoint = endpoint, pairingPending = true), editingEndpoint = true))
    }
}
