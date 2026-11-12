package com.hawkeynl.jarvis.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.UnreachableReason
import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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

    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    private fun realtime(status: HttpStatusCode, body: String): Pair<RealtimeService, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        val sessions = SessionRepository(InMemorySecureValueStore()).apply { saveLogin("device", "token", 1) }
        return RealtimeService(sessions, engine) to engine
    }

    @Test fun runRequestsCarryPhotosOnlyWhenThereAreSome() = runTest {
        val (service, engine) = realtime(HttpStatusCode.OK, """{"run_id":"r","request_id":"q","conversation_id":"c"}""")
        assertEquals("c", service.submit(endpoint, "q", null, emptyList(), "hi").conversation_id)
        service.submit(endpoint, "q2", "c", emptyList(), "", attachments = listOf("p1", "p2"))
        val bodies = engine.requestHistory.map {
            Json.parseToJsonElement((it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
        }
        assertFalse("attachments" in bodies[0])
        assertFalse("mode" in bodies[0])
        assertEquals(JsonArray(listOf(JsonPrimitive("p1"), JsonPrimitive("p2"))), bodies[1]["attachments"])
        assertEquals("Bearer token", engine.requestHistory[1].headers[HttpHeaders.Authorization])
    }

    @Test fun aRefusedRunCarriesItsStatus() = runTest {
        for (status in listOf(400, 404, 503)) {
            val error = runCatching { realtime(HttpStatusCode.fromValue(status), "{}").first.submit(endpoint, "q", null, emptyList(), "hi") }
                .exceptionOrNull()
            assertEquals(status, (error as SubmitFailed).status)
        }
    }
}
