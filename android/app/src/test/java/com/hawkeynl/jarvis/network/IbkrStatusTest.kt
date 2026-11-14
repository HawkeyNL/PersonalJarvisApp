package com.hawkeynl.jarvis.network

import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IbkrStatusTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    // Shape of Core's docs/IBKR_PORTFOLIO.md "Status", with neutral values.
    private val full = """{"checked_at":"2026-11-14T12:00:00Z","overall":"warn",
        "sync":{"enabled":true,"state":"failing","last_success_at":"2026-11-14T09:00:00Z","last_attempt_at":null,
          "next_attempt_at":null,"consecutive_failures":1,"last_error":{"code":"broker_timeout","at":"2026-11-14T11:00:00Z"}},
        "broker":{"service":"active","enabled_at_boot":true,"credentials":"present","reachable":true,"started_at":null},
        "token":{"state":"valid","usable":true,"refreshed_at":"2026-11-14T09:00:00Z","expires_at":"2026-11-15T09:00:00Z",
          "last_refresh_attempt_at":null,"last_refresh_error":null},
        "ibkr":{"last_ok_at":null,"last_error":null,"rate_limited_until":null,"brokerage_session":"competing"},
        "problems":[{"code":"sync_failing","severity":"warn","summary":"The portfolio sync is failing.",
          "commands":["sudo jarvis logs core --lines 100"]}]}"""

    private fun read(body: String) = readIbkrStatus(Json.parseToJsonElement(body))

    @Test fun `the full answer reads every shown field`() {
        val s = requireNotNull(read(full))
        assertEquals("warn", s.overall)
        assertEquals(true, s.syncEnabled)
        assertEquals("failing", s.syncState)
        assertEquals("2026-11-14T09:00:00Z", s.lastSuccessAt)
        assertEquals(IbkrError("broker_timeout", "2026-11-14T11:00:00Z"), s.syncError)
        assertEquals("active", s.service)
        assertEquals("present", s.credentials)
        assertEquals(true, s.reachable)
        assertEquals(IbkrToken("valid", true, "2026-11-14T09:00:00Z", "2026-11-15T09:00:00Z"), s.token)
        assertEquals(IbkrLink(null, null, "competing"), s.ibkr)
        assertEquals(
            listOf(IbkrProblem("sync_failing", "warn", "The portfolio sync is failing.", listOf("sudo jarvis logs core --lines 100"))),
            s.problems,
        )
    }

    @Test fun `nulls, missing parts and wrong types read as unknown, not as a failure`() {
        val empty = requireNotNull(read("{}"))
        assertNull(empty.overall)
        assertNull(empty.token)
        assertNull(empty.ibkr)
        assertTrue(empty.problems.isEmpty())

        val odd = requireNotNull(
            read(
                """{"overall":7,"sync":"on","broker":{"service":["x"],"reachable":"true"},"token":null,"ibkr":[],
                "problems":[{"code":"New Code!","severity":null,"summary":42,"commands":[1,null,"ok"]},"x"]}""",
            ),
        )
        assertNull(odd.overall)
        assertNull(odd.syncEnabled)
        assertNull(odd.service)
        assertNull(odd.reachable)
        assertNull(odd.ibkr)
        assertEquals(listOf(IbkrProblem(null, null, null, listOf("ok"))), odd.problems)

        // Unknown but well-formed codes pass through, so the page can say "Unknown" or show the code.
        assertEquals("future_state", read("""{"sync":{"state":"future_state"}}""")?.syncState)
        assertNull(read("[]"))
        assertNull(read("null"))
    }

    @Test fun `lists, texts and commands are capped`() {
        val problem = """{"code":"x","severity":"info","summary":"s","commands":[${List(20) { "\"c$it\"" }.joinToString(",")}]}"""
        val s = requireNotNull(read("""{"problems":[${List(20) { problem }.joinToString(",")}]}"""))
        assertEquals(IbkrStatusLimits.PROBLEMS, s.problems.size)
        assertEquals(IbkrStatusLimits.COMMANDS, s.problems.first().commands.size)

        val long = "a".repeat(IbkrStatusLimits.TEXT + 1)
        val capped = requireNotNull(read("""{"problems":[{"summary":"$long","commands":["$long","two\nlines","fine"]}]}""")).problems.single()
        assertNull(capped.summary)
        assertEquals(listOf("fine"), capped.commands)
        assertNull(read("""{"overall":"${"a".repeat(65)}"}""")?.overall)
    }

    private fun reader(status: HttpStatusCode = HttpStatusCode.OK, body: String = full): Pair<CoreReader, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        val sessions = SessionRepository(InMemorySecureValueStore())
        sessions.saveLogin("device", "token", Long.MAX_VALUE)
        return CoreReader(KtorJarvisApi(HttpClient(engine) { expectSuccess = false }), sessions) to engine
    }

    @Test fun `the status is a bearer GET of the status route`() = runTest {
        val (core, engine) = reader()
        assertEquals("warn", (core.ibkrStatus(endpoint) as Availability.Ok).value.overall)
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/trading/ibkr/status", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    }

    @Test fun `an older Core falls back, and bad answers fail closed`() = runTest {
        assertEquals(Availability.Unsupported, reader(HttpStatusCode.NotFound).first.ibkrStatus(endpoint))
        assertEquals(Availability.Unsupported, reader(HttpStatusCode.MethodNotAllowed).first.ibkrStatus(endpoint))
        assertEquals(Availability.Failed(LoadFailure.SIGNIN), reader(HttpStatusCode.Unauthorized).first.ibkrStatus(endpoint))
        assertEquals(Availability.Failed(LoadFailure.FAILED), reader(body = "not json").first.ibkrStatus(endpoint))
        assertEquals(Availability.Failed(LoadFailure.FAILED), reader(body = full + " ".repeat(IbkrStatusLimits.BYTES)).first.ibkrStatus(endpoint))
    }
}
