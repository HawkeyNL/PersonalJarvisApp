package com.hawkeynl.jarvis.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RealtimeTransportTest {
    @Test fun nativeEngineConfiguresHeartbeatAndRejectsRedirects() {
        val engine = realtimeSocketEngine()
        assertEquals(30_000, engine.pingIntervalMillis)
        assertFalse(engine.followRedirects)
        assertFalse(engine.followSslRedirects)
    }
}
