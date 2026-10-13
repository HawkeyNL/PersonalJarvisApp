package com.hawkeynl.jarvis.security

import org.junit.Assert.*
import org.junit.Test

class ModelAuthorizationTest {
    @Test fun lockDuringPromptRevokesTheChange() {
        val lease = ModelAuthorization()
        val ticket = lease.ticket()
        assertTrue(lease.accepts(ticket))
        lease.invalidate()
        assertFalse(lease.accepts(ticket))
        assertTrue(lease.accepts(lease.ticket()))
    }
}
