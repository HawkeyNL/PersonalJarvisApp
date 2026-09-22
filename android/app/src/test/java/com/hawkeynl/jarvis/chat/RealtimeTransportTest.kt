package com.hawkeynl.jarvis.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.UnreachableReason

class RealtimeTransportTest {
    @Test fun missingSelectedConversationDoesNotCauseReconnectLoop() {
        assertNull(ApiResult.HttpError(404, null).realtimeSelectedHistory())
        assertEquals("canonical", ApiResult.Success("canonical").realtimeSelectedHistory())
        assertThrows(IllegalStateException::class.java) {
            ApiResult.HttpError(503, null).realtimeSelectedHistory()
        }
        // A missing collection endpoint is not a deleted selected conversation.
        assertThrows(IllegalStateException::class.java) {
            ApiResult.HttpError(404, null).realtimeSnapshot()
        }
    }

    @Test fun recoveryFailuresEscapeWithoutEchoingResponseDetails() {
        val failures = listOf(
            ApiResult.Unauthorized,
            ApiResult.HttpError(503, "untrusted response detail"),
            ApiResult.InvalidResponse("untrusted payload"),
            ApiResult.Unreachable(UnreachableReason.TIMEOUT),
        )
        for (failure in failures) {
            val error = assertThrows(IllegalStateException::class.java) { failure.realtimeSnapshot() }
            assertEquals("Realtime history recovery failed", error.message)
        }
        // A later successful GET provides exactly the canonical snapshot.
        val canonical = listOf("canonical fixture message")
        assertEquals(canonical, ApiResult.Success(canonical).realtimeSnapshot())
    }

    @Test fun nativeEngineConfiguresHeartbeatAndRejectsRedirects() {
        val engine = realtimeSocketEngine()
        assertEquals(30_000, engine.pingIntervalMillis)
        assertFalse(engine.followRedirects)
        assertFalse(engine.followSslRedirects)
    }
}
