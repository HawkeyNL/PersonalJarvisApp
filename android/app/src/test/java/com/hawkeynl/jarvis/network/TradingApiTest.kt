package com.hawkeynl.jarvis.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradingApiTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint
    private val meta = """"source":"IBKR","delay_minutes":15,"as_of":"2026-11-01T12:00:00Z""""
    private val portfolio = """{$meta,"base_currency":"EUR","net_liquidation":"1","cash":"0","positions":[]}"""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = portfolio): Pair<KtorJarvisApi, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorJarvisApi(HttpClient(engine) { expectSuccess = false }) to engine
    }

    @Test fun `the portfolio is a bearer GET read through the guards`() = runTest {
        val (trading, engine) = api()
        assertEquals("EUR", (trading.portfolio(endpoint, "token") as ApiResult.Success).value.baseCurrency)
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/trading/portfolio", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    }

    @Test fun `401, 503, oversized and invalid answers fail closed`() = runTest {
        assertEquals(ApiResult.Unauthorized, api(HttpStatusCode.Unauthorized).first.portfolio(endpoint, "t"))
        assertEquals(ApiResult.HttpError(503, null), api(HttpStatusCode.ServiceUnavailable).first.portfolio(endpoint, "t"))
        assertTrue(api(body = portfolio + " ".repeat(1024 * 1024)).first.portfolio(endpoint, "t") is ApiResult.InvalidResponse)
        assertTrue(api(body = """{$meta}""").first.portfolio(endpoint, "t") is ApiResult.InvalidResponse)
        assertTrue(api(body = "not json").first.holdings(endpoint, "t") is ApiResult.InvalidResponse)
    }

    @Test fun `history and bars ask for a known range of a valid conid only`() = runTest {
        val (history, historyEngine) = api(body = """{$meta,"base_currency":"EUR","points":[]}""")
        assertTrue(history.valueHistory(endpoint, "t", "1Y") is ApiResult.Success)
        assertEquals("/v1/trading/portfolio/history", historyEngine.requestHistory.single().url.encodedPath)
        assertEquals("1Y", historyEngine.requestHistory.single().url.parameters["range"])

        val (bars, barsEngine) = api(body = """{$meta,"conid":265598,"symbol":"A","currency":"USD","bars":[]}""")
        assertTrue(bars.instrumentBars(endpoint, "t", 265598, "5Y") is ApiResult.Success)
        assertEquals("/v1/trading/instruments/265598/bars", barsEngine.requestHistory.single().url.encodedPath)
        assertEquals("5Y", barsEngine.requestHistory.single().url.parameters["range"])

        val (refused, refusedEngine) = api()
        assertTrue(refused.valueHistory(endpoint, "t", "5Y") is ApiResult.InvalidResponse)
        assertTrue(refused.valueHistory(endpoint, "t", "1Y&x=1") is ApiResult.InvalidResponse)
        assertTrue(refused.instrumentBars(endpoint, "t", 0, "1Y") is ApiResult.InvalidResponse)
        assertTrue(refused.instrumentBars(endpoint, "t", -5, "1Y") is ApiResult.InvalidResponse)
        assertTrue(refused.instrumentBars(endpoint, "t", 1, "ALL") is ApiResult.InvalidResponse)
        assertTrue(refusedEngine.requestHistory.isEmpty())
        // 404 on bars stays a 404, so the page can say the instrument is no longer held.
        assertEquals(ApiResult.HttpError(404, null), api(HttpStatusCode.NotFound).first.instrumentBars(endpoint, "t", 1, "1M"))
    }

    @Test fun `a holding is added with the typed strings and removed by a path-safe id`() = runTest {
        val (add, addEngine) = api(HttpStatusCode.Created, "{}")
        assertEquals(ApiResult.Success(Unit), add.addHolding(endpoint, "t", "AAPL", "2", "150.5"))
        val post = addEngine.requestHistory.single()
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("/v1/holdings", post.url.encodedPath)
        assertEquals("""{"symbol":"AAPL","quantity":"2","avg_cost":"150.5"}""", (post.body as TextContent).text)

        val (remove, removeEngine) = api(HttpStatusCode.NoContent, "")
        assertEquals(ApiResult.Success(Unit), remove.deleteHolding(endpoint, "t", "h1"))
        assertEquals(HttpMethod.Delete, removeEngine.requestHistory.single().method)
        assertEquals("/v1/holdings/h1", removeEngine.requestHistory.single().url.encodedPath)

        for (bad in listOf("", "../x", "a/b", "a?b", "%2e")) {
            val (holdings, engine) = api()
            assertTrue(bad, holdings.deleteHolding(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, engine.requestHistory.isEmpty())
        }
        assertEquals(ApiResult.HttpError(400, null), api(HttpStatusCode.BadRequest).first.addHolding(endpoint, "t", "", "", ""))
        // A 2xx with an oversized answer fails closed instead of counting as added or removed.
        val big = "{}" + " ".repeat(1024 * 1024)
        assertTrue(api(HttpStatusCode.Created, big).first.addHolding(endpoint, "t", "AAPL", "2", "1") is ApiResult.InvalidResponse)
        assertTrue(api(body = big).first.deleteHolding(endpoint, "t", "h1") is ApiResult.InvalidResponse)
    }
}
