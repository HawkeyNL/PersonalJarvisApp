package com.hawkeynl.jarvis.security

import org.junit.Assert.*
import org.junit.Test

class ModelAuthorizationTest {
    @Test fun fixedWindowDoesNotSlideAndIsBoundToSession() {
        val lease = ModelAuthorization()
        val ticket = lease.ticket()
        assertFalse(lease.valid("session", ticket, 0, 0))
        assertTrue(lease.remember("session", ticket, 1000, 2000))
        assertTrue(lease.valid("session", ticket, 300999, 301999))
        assertFalse(lease.valid("session", ticket, 301000, 302000))
        assertFalse(lease.valid("other", ticket, 1001, 2001))
        assertFalse(lease.valid("session", ticket, 1001, 1999))
        assertFalse(lease.valid("session", ticket, 1001, 302001))
    }
    @Test fun lockDuringPromptCannotRestoreGrant() {
        val lease = ModelAuthorization()
        val ticket = lease.ticket()
        lease.invalidate()
        assertFalse(lease.accepts(ticket))
        assertFalse(lease.remember("session", ticket, 0, 0))
        assertFalse(lease.valid("session", lease.ticket(), 0, 0))
    }
}
