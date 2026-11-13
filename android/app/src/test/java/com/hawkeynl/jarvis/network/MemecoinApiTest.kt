package com.hawkeynl.jarvis.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemecoinApiTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint
    private val off = """{"mode":"paper","enabled":false,"running":false,"paused":false,"paused_at":null,"last_error":null}"""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = off): Pair<KtorJarvisApi, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorJarvisApi(HttpClient(engine) { expectSuccess = false }) to engine
    }

    @Test fun `the overview is a bearer GET read through the guards`() = runTest {
        val (meme, engine) = api()
        assertEquals(false, (meme.memeOverview(endpoint, "token") as ApiResult.Success).value.enabled)
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/trading/meme/overview", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        // Anything but paper fails closed.
        assertTrue(api(body = off.replace("paper", "live")).first.memeOverview(endpoint, "t") is ApiResult.InvalidResponse)
        assertTrue(api(body = off + " ".repeat(1024 * 1024)).first.memeOverview(endpoint, "t") is ApiResult.InvalidResponse)
        assertEquals(ApiResult.Unauthorized, api(HttpStatusCode.Unauthorized).first.memeOverview(endpoint, "t"))
    }

    @Test fun `lists ask for 50 rows`() = runTest {
        val (candidates, cEngine) = api(body = """{"candidates":[]}""")
        assertEquals(ApiResult.Success(emptyList<Candidate>()), candidates.memeCandidates(endpoint, "t"))
        assertEquals("/v1/trading/meme/candidates", cEngine.requestHistory.single().url.encodedPath)
        assertEquals("50", cEngine.requestHistory.single().url.parameters["limit"])
        val (trades, tEngine) = api(body = """{"trades":[]}""")
        assertEquals(ApiResult.Success(emptyList<ClosedTrade>()), trades.memeTrades(endpoint, "t"))
        assertEquals("50", tEngine.requestHistory.single().url.parameters["limit"])
        val (decisions, dEngine) = api(body = """{"decisions":[]}""")
        assertEquals(ApiResult.Success(emptyList<Decision>()), decisions.memeDecisions(endpoint, "t"))
        assertEquals("/v1/trading/meme/decisions", dEngine.requestHistory.single().url.encodedPath)
        assertEquals("50", dEngine.requestHistory.single().url.parameters["limit"])
    }

    @Test fun `pause and disable post and hand back Core's raw answer`() = runTest {
        val (pause, pEngine) = api(body = """{"paused":true}""")
        val answer = (pause.memePause(endpoint, "t") as ApiResult.Success).value
        assertEquals(JsonPrimitive(true), answer.jsonObject["paused"])
        assertEquals(HttpMethod.Post, pEngine.requestHistory.single().method)
        assertEquals("/v1/trading/meme/pause", pEngine.requestHistory.single().url.encodedPath)
        // An answer that is not Core's confirmation never confirms.
        for (body in listOf("ok", "", "{}", """{"paused":"true"}""")) {
            val raw = api(body = body).first.memePause(endpoint, "t")
            assertFalse(body, pauseOutcome(raw, "now").ok)
        }
        assertEquals(ApiResult.HttpError(503, null), api(HttpStatusCode.ServiceUnavailable).first.memePause(endpoint, "t"))

        val (disable, dEngine) = api(body = """{"strategy_id":"a-survivor-momentum","enabled":false}""")
        assertTrue(disable.memeDisable(endpoint, "t", "a-survivor-momentum") is ApiResult.Success)
        assertEquals("/v1/trading/meme/strategies/a-survivor-momentum/disable", dEngine.requestHistory.single().url.encodedPath)
        assertEquals(HttpMethod.Post, dEngine.requestHistory.single().method)
    }

    @Test fun `strategy ids other than Core's rule never reach the network`() = runTest {
        for (bad in listOf("", "../x", "A-b", "a/b", "a?b", "%2e", "a".repeat(33))) {
            val (meme, engine) = api()
            assertTrue(bad, meme.memeStrategy(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, meme.memeDisable(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, engine.requestHistory.isEmpty())
        }
        // An answer for another strategy fails closed.
        val other = """{"id":"b-base-reclaim","family":"b","active_version":2,"enabled":true,"disabled_by_owner":false,
            "changed_at":"2026-11-01T02:00:00Z","changed_by":"seed","versions":[],
            "performance":{"trades":0,"net_usd":"0","costs_usd":"0","expectancy_usd":"0","win_rate_bps":0,"max_drawdown_usd":"0"}}"""
        val (meme, engine) = api(body = other)
        assertTrue(meme.memeStrategy(endpoint, "t", "a-survivor-momentum") is ApiResult.InvalidResponse)
        assertEquals("/v1/trading/meme/strategies/a-survivor-momentum", engine.requestHistory.single().url.encodedPath)
        assertTrue(api(body = other).first.memeStrategy(endpoint, "t", "b-base-reclaim") is ApiResult.Success)
    }
}
