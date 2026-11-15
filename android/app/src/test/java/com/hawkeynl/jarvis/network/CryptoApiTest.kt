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

class CryptoApiTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint
    private val w1 = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"
    private val evm = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val overview = """{"source":"crypto","as_of":"2026-11-15T10:00:00Z","base_currency":"EUR","total_eur":"0","chains_available":["base"],"wallets":[],"assets":[]}"""

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = overview): Pair<KtorJarvisApi, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorJarvisApi(HttpClient(engine) { expectSuccess = false }) to engine
    }

    @Test fun `the overview is a bearer GET read through the guards`() = runTest {
        val (crypto, engine) = api()
        assertEquals(listOf(CryptoChain.BASE), (crypto.cryptoOverview(endpoint, "token") as ApiResult.Success).value.chainsAvailable)
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/trading/crypto", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        // Core's fixed error text comes along, so the page can tell "off" from another 503.
        val off = api(HttpStatusCode.ServiceUnavailable, """{"error":"crypto wallets are not enabled"}""").first.cryptoOverview(endpoint, "t")
        assertEquals(ApiResult.HttpError(503, CRYPTO_OFF_MESSAGE), off)
        assertEquals(CryptoFailure.OFF, cryptoFailureOf(off))
        assertEquals(ApiResult.HttpError(503, null), api(HttpStatusCode.ServiceUnavailable, "<html>proxy</html>").first.cryptoOverview(endpoint, "t"))
        assertTrue(api(body = overview + " ".repeat(1024 * 1024)).first.cryptoOverview(endpoint, "t") is ApiResult.InvalidResponse)
    }

    @Test fun `a wallet is added only after the app's checks, key material never leaves the phone`() = runTest {
        val (add, engine) = api(HttpStatusCode.Created, "{}")
        assertEquals(ApiResult.Success(Unit), add.addWallet(endpoint, "t", WalletKind.EVM, evm, listOf(CryptoChain.BASE), "Cold"))
        val post = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("/v1/trading/crypto/wallets", post.url.encodedPath)
        assertEquals("""{"kind":"evm","address":"$evm","label":"Cold","chains":["base"]}""", (post.body as TextContent).text)

        val phrase = "abandon ".repeat(11) + "about"
        val (refused, refusedEngine) = api(HttpStatusCode.Created, "{}")
        assertEquals(ApiResult.InvalidResponse(INVALID_WALLET), refused.addWallet(endpoint, "t", WalletKind.EVM, phrase, EVM_CHAINS, ""))
        assertEquals(ApiResult.InvalidResponse(INVALID_WALLET), refused.addWallet(endpoint, "t", WalletKind.EVM, evm, EVM_CHAINS, phrase))
        assertTrue(refusedEngine.requestHistory.isEmpty())
    }

    @Test fun `a wallet is removed by its UUID only`() = runTest {
        val (remove, engine) = api(body = """{"deleted":true}""")
        assertEquals(ApiResult.Success(Unit), remove.deleteWallet(endpoint, "t", w1))
        assertEquals(HttpMethod.Delete, engine.requestHistory.single().method)
        assertEquals("/v1/trading/crypto/wallets/$w1", engine.requestHistory.single().url.encodedPath)
        for (bad in listOf("", "../x", "$w1/x", "w1")) {
            val (wallets, badEngine) = api()
            assertEquals(bad, ApiResult.InvalidResponse(INVALID_WALLET), wallets.deleteWallet(endpoint, "t", bad))
            assertTrue(bad, badEngine.requestHistory.isEmpty())
        }
    }

    @Test fun `a refresh is queued or cools down for Core's retry_after_secs`() = runTest {
        val (refresh, engine) = api(HttpStatusCode.Accepted, """{"queued":true}""")
        assertEquals(ApiResult.Success(CryptoRefresh.Queued), refresh.refreshCrypto(endpoint, "t"))
        assertEquals(HttpMethod.Post, engine.requestHistory.single().method)
        assertEquals("/v1/trading/crypto/refresh", engine.requestHistory.single().url.encodedPath)
        assertEquals(
            ApiResult.Success(CryptoRefresh.Cooldown(37)),
            api(HttpStatusCode.TooManyRequests, """{"error":"x","retry_after_secs":37}""").first.refreshCrypto(endpoint, "t"),
        )
        assertEquals(ApiResult.Success(CryptoRefresh.Cooldown(60)), api(HttpStatusCode.TooManyRequests, "{}").first.refreshCrypto(endpoint, "t"))
        assertEquals(ApiResult.HttpError(503, null), api(HttpStatusCode.ServiceUnavailable, "{}").first.refreshCrypto(endpoint, "t"))
        val refused = api(HttpStatusCode.Conflict, """{"error":"wallet limit reached"}""").first.addWallet(endpoint, "t", WalletKind.EVM, evm, EVM_CHAINS, "")
        assertEquals(ApiResult.HttpError(409, "wallet limit reached"), refused)
    }

    @Test fun `the total is read, and an older Core answers 404`() = runTest {
        val (total, engine) = api(body = """{"source":"Jarvis","as_of":"2026-11-15T10:00:00Z","base_currency":"EUR","total":"5","parts":[],"partial":false}""")
        assertEquals("5", (total.portfolioTotal(endpoint, "t") as ApiResult.Success).value.total)
        assertEquals("/v1/trading/total", engine.requestHistory.single().url.encodedPath)
        assertEquals(ApiResult.HttpError(404, null), api(HttpStatusCode.NotFound, "").first.portfolioTotal(endpoint, "t"))
    }
}
