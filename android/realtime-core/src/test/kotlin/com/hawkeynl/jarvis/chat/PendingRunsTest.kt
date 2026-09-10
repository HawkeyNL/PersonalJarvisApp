package com.hawkeynl.jarvis.chat

import org.junit.Assert.*
import org.junit.Test

class PendingRunsTest {
    @Test fun uncertaintyIsBoundedWithoutEviction() {
        val pending = PendingRuns()
        repeat(32) { assertTrue(pending.add("request-$it", "row-$it")) }
        assertFalse(pending.add("extra", "extra"))
        assertEquals("row-0", pending["request-0"])
        assertEquals(32, pending.requests().size)
        assertFalse(pending.add("request-0", "replacement"))
    }
    @Test fun onlyMatchingTerminalResultsClearRequests() {
        val pending = PendingRuns()
        pending.add("request", "row")
        pending.reconcile("request", RecoveredRun("other", "run", "conversation", "completed"))
        pending.reconcile("request", RecoveredRun("request", "run", "conversation", "running"))
        pending.reconcile("request", RecoveredRun("request", "run", "conversation", "unknown"))
        assertEquals("row", pending["request"])
        pending.reconcile("request", RecoveredRun("request", "run", "conversation", "interrupted"))
        assertNull(pending["request"])
        pending.reconcile("request", RecoveredRun("request", "run", "conversation", "running"))
        assertNull(pending["request"])
    }
    @Test fun snapshotsAndLogoutDoNotRetainOldSessionState() {
        val pending = PendingRuns()
        pending.add("request", "row")
        val snapshot = pending.requests()
        pending.clear()
        assertEquals(listOf("request"), snapshot)
        assertTrue(pending.requests().isEmpty())
    }
}
