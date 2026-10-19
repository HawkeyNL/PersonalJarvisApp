package com.hawkeynl.jarvis.ui

import com.hawkeynl.jarvis.security.ApprovalBusyException
import com.hawkeynl.jarvis.security.SigningCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed or unconfirmed revoke of this device never erases it silently. */
class UnlinkOutcomeTest {
    private val signedIn = JarvisUiState(authenticated = true)

    @Test fun aRefusalOnlyOffersErasingAndKeepsTheSession() {
        val shown = afterFailedUnlink(signedIn, IllegalStateException("Core refused‮ this change.\n"))
        assertEquals("Core refused this change.", shown.localEraseOffer)
        assertTrue(shown.authenticated)
        assertNull(shown.error)
        val long = afterFailedUnlink(signedIn, IllegalStateException("x".repeat(500)))
        assertTrue(long.localEraseOffer!!.length <= 160)
    }

    @Test fun aCancelledOrBusyPromptOffersNothing() {
        for (failure in listOf(ApprovalBusyException(), SigningCancelledException())) {
            val shown = afterFailedUnlink(signedIn, failure)
            assertNull(shown.localEraseOffer)
            assertEquals(failure.message, shown.error)
            assertTrue(shown.authenticated)
        }
    }
}
