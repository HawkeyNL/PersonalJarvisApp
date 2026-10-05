package com.hawkeynl.jarvis.network

import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreReaderTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    private fun reader(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}", token: String? = "token"): Pair<CoreReader, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }
        val sessions = SessionRepository(InMemorySecureValueStore())
        if (token != null) sessions.saveLogin("device", token, Long.MAX_VALUE)
        return CoreReader(KtorJarvisApi(client), sessions) to engine
    }

    @Test
    fun `missing session token asks to sign in without calling Core`() = runTest {
        val (core, engine) = reader(token = null)
        assertEquals(Availability.Failed(LoadFailure.SIGNIN), core.read(endpoint, "/v1/agents", AgentsResponse.serializer()))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `an older Core without the route is unsupported, not an error`() = runTest {
        val expected = mapOf(
            HttpStatusCode.NotFound to Availability.Unsupported,
            HttpStatusCode.MethodNotAllowed to Availability.Unsupported,
            HttpStatusCode.Unauthorized to Availability.Failed(LoadFailure.SIGNIN),
            HttpStatusCode.Forbidden to Availability.Failed(LoadFailure.FORBIDDEN),
            HttpStatusCode.InternalServerError to Availability.Failed(LoadFailure.FAILED),
        )
        for ((status, availability) in expected) {
            assertEquals(availability, reader(status).first.read(endpoint, "/v1/agents", AgentsResponse.serializer()))
        }
    }

    @Test
    fun `reads send the bearer token and tolerate unknown keys and nulls`() = runTest {
        val (core, engine) = reader(
            body = """{"agent_count":1,"future":true,"agents":[{"id":"a","name":"Coder","description":null,"usage":null,"extra":[1]}]}""",
        )
        val result = core.read(endpoint, "/v1/agents", AgentsResponse.serializer())
        val agent = (result as Availability.Ok).value.agents.single()
        assertEquals("Coder", agent.name)
        assertEquals("", agent.description)
        assertEquals(null, agent.usage)
        val request = engine.requestHistory.single()
        assertEquals("https://jarvis.test/v1/agents", request.url.toString())
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `an unreadable shape is a failure, not a crash`() = runTest {
        val (core, _) = reader(body = """{"agents":"not a list"}""")
        assertEquals(Availability.Failed(LoadFailure.FAILED), core.read(endpoint, "/v1/agents", AgentsResponse.serializer()))
    }

    @Test
    fun `only fixed owner paths are requested`() = runTest {
        val (core, engine) = reader()
        for (path in listOf("/v1/../admin", "/v1/agents?x=1", "https://evil.test/v1/agents", "/readyz", "/v1/")) {
            assertEquals(Availability.Failed(LoadFailure.FAILED), core.read(endpoint, path, AgentsResponse.serializer()))
        }
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `deny posts to the pending action and refuses malformed ids`() = runTest {
        val (core, engine) = reader()
        assertTrue(core.denyPending(endpoint, "../x") is ApiResult.InvalidResponse)
        assertTrue(engine.requestHistory.isEmpty())

        assertEquals(ApiResult.Success(Unit), core.denyPending(endpoint, "p-1_a"))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://jarvis.test/v1/agent/pending/p-1_a/deny", request.url.toString())
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    }
}
