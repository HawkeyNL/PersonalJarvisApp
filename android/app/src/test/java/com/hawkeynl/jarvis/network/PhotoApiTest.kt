package com.hawkeynl.jarvis.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun json(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}"): Pair<KtorJarvisApi, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
        return KtorJarvisApi(HttpClient(engine) { expectSuccess = false; install(HttpTimeout) }) to engine
    }

    @Test fun `an upload is a bearer POST of raw bytes that answers with a canonical id`() = runTest {
        val (photos, engine) = json(body = """{"id":"${id.uppercase()}","width":2,"height":1,"bytes":6}""")
        assertEquals(ApiResult.Success(id), photos.uploadPhoto(endpoint, "token", jpeg))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/v1/photos", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals(ContentType.Application.OctetStream, request.body.contentType)
        assertArrayEquals(jpeg, (request.body as ByteArrayContent).bytes())
    }

    @Test fun `an upload refusal keeps its status and odd answers are invalid`() = runTest {
        assertEquals(ApiResult.HttpError(404, null), json(HttpStatusCode.NotFound).first.uploadPhoto(endpoint, "t", jpeg))
        assertEquals(ApiResult.HttpError(413, null), json(HttpStatusCode.PayloadTooLarge).first.uploadPhoto(endpoint, "t", jpeg))
        assertEquals(ApiResult.Unauthorized, json(HttpStatusCode.Unauthorized).first.uploadPhoto(endpoint, "t", jpeg))
        for (body in listOf("{}", """{"id":"../x"}""", """{"id":1}""", "[]", "nope", """{"id":"$id","pad":"${"x".repeat(5000)}"}""")) {
            assertTrue(body.take(30), json(body = body).first.uploadPhoto(endpoint, "t", jpeg) is ApiResult.InvalidResponse)
        }
        val (photos, engine) = json()
        assertTrue(photos.uploadPhoto(endpoint, "t", ByteArray(0)) is ApiResult.InvalidResponse)
        assertTrue(photos.uploadPhoto(endpoint, "t", ByteArray(KtorJarvisApi.MAX_UPLOAD_BYTES + 1)) is ApiResult.InvalidResponse)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test fun `keeping a photo PATCHes saved and returns its keep state`() = runTest {
        val (photos, engine) = json(body = """{"id":"$id","saved":true,"expires_at":null}""")
        assertEquals(ApiResult.Success(PhotoMeta(id, true, null)), photos.keepPhoto(endpoint, "t", id, true))
        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Patch, request.method)
        assertEquals("/v1/photos/$id", request.url.encodedPath)
        assertEquals("""{"saved":true}""", (request.body as TextContent).text)
        assertEquals(ContentType.Application.Json, request.body.contentType)
        assertEquals(ApiResult.HttpError(404, null), json(HttpStatusCode.NotFound).first.keepPhoto(endpoint, "t", id, false))
        // Another photo's details are not this photo's.
        val other = "1b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b"
        assertTrue(json(body = """{"id":"$other","saved":true}""").first.keepPhoto(endpoint, "t", id, true) is ApiResult.InvalidResponse)
    }

    @Test fun `deleting a photo or a conversation is a bearer DELETE of a checked id`() = runTest {
        val (api, engine) = json()
        assertEquals(ApiResult.Success(Unit), api.deletePhoto(endpoint, "t", id))
        assertEquals(ApiResult.Success(Unit), api.deleteConversation(endpoint, "t", "c-1_a"))
        assertEquals(listOf("/v1/photos/$id", "/v1/conversations/c-1_a"), engine.requestHistory.map { it.url.encodedPath })
        assertTrue(engine.requestHistory.all { it.method == HttpMethod.Delete && it.headers[HttpHeaders.Authorization] == "Bearer t" })
        assertEquals(ApiResult.HttpError(404, null), json(HttpStatusCode.NotFound).first.deletePhoto(endpoint, "t", id))
        for (bad in listOf("", "../auth", "a/b", "a?b", "%2e")) {
            val (refusing, quiet) = json()
            assertTrue(bad, refusing.deletePhoto(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, refusing.deleteConversation(endpoint, "t", bad) is ApiResult.InvalidResponse)
            assertTrue(bad, quiet.requestHistory.isEmpty())
        }
    }

    @Test fun `a conversation read carries its photos and older Core sends none`() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val old = json.decodeFromString<ConversationDetailResponse>("""{"id":"c","title":"t","messages":[]}""")
        assertEquals(emptyList<PhotoMeta>(), old.photos)
        val read = json.decodeFromString<ConversationDetailResponse>(
            """{"id":"c","title":"t","messages":[],"photos":[{"id":"$id","saved":false,"expires_at":"2027-02-10T12:00:00Z"},{"id":"x","saved":true,"expires_at":null}]}""",
        )
        assertEquals(PhotoMeta(id, false, "2027-02-10T12:00:00Z"), read.photos[0])
        assertNull(read.photos[1].expires_at)
    }
}
