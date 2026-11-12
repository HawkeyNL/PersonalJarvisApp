package com.hawkeynl.jarvis.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoApiTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint
    private val id = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: ByteArray = jpeg, type: String = "image/jpeg"): Pair<KtorJarvisApi, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, type)) }
        return KtorJarvisApi(HttpClient(engine) { expectSuccess = false }) to engine
    }

    @Test fun `a photo is a bearer GET of a canonical id that must be a small JPEG`() = runTest {
        val (photos, engine) = api()
        val result = photos.photo(endpoint, "token", id.uppercase())
        assertArrayEquals(jpeg, (result as ApiResult.Success).value)
        val request = engine.requestHistory.single()
        assertEquals("/v1/photos/$id", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
    }

    @Test fun `a deleted or expired photo is a 404 for the placeholder`() = runTest {
        assertEquals(ApiResult.HttpError(404, null), api(HttpStatusCode.NotFound, "{}".toByteArray(), "application/json").first.photo(endpoint, "t", id))
        assertEquals(ApiResult.Unauthorized, api(HttpStatusCode.Unauthorized).first.photo(endpoint, "t", id))
    }

    @Test fun `ids other than a UUID never reach the network`() = runTest {
        for (bad in listOf("", "../auth/login", "$id/../x", "$id?x=1", "1-1-1-1-1", "%2e%2e")) {
            val (photos, engine) = api()
            assertTrue(bad, photos.photo(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, engine.requestHistory.isEmpty())
        }
    }

    @Test fun `other types, other bytes and oversized bodies are refused`() = runTest {
        val big = ByteArray(KtorJarvisApi.MAX_PHOTO_BYTES + 1).also { jpeg.copyInto(it) }
        for ((status, body, type) in listOf(
            Triple(HttpStatusCode.OK, jpeg, "image/png"),
            Triple(HttpStatusCode.OK, jpeg, "text/html"),
            Triple(HttpStatusCode.OK, "<svg onload=x>".toByteArray(), "image/jpeg"),
            Triple(HttpStatusCode.OK, ByteArray(0), "image/jpeg"),
            Triple(HttpStatusCode.OK, big, "image/jpeg"),
        )) {
            assertTrue(type, api(status, body, type).first.photo(endpoint, "t", id) is ApiResult.InvalidResponse)
        }
        assertEquals(ApiResult.HttpError(500, null), api(HttpStatusCode.InternalServerError).first.photo(endpoint, "t", id))
    }

    @Test fun `messages carry photo ids and old messages decode without them`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val old = json.decodeFromString<ConversationMessage>("""{"role":"user","content":"hi","at":"x"}""")
        assertEquals(emptyList<String>(), old.attachments)
        val photo = json.decodeFromString<ConversationMessage>("""{"role":"user","content":"","at":"x","attachments":["$id"]}""")
        assertEquals(listOf(id), photo.attachments)
    }
}
