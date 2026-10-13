package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.KtorJarvisApi
import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.testing.FakeDeviceKeyVault
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockApprovalTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    @Test fun approvalRereadsTheRequestAndSignsTheUnlockMessage() = runTest {
        val fixture = Fixture(createdAt = NOW - 10)
        fixture.service.approve(REQUEST_ID.uppercase())
        assertEquals(1, fixture.prompts)
        assertEquals("Unlock \"Work Mac\" (macos)", fixture.reason)
        assertEquals(listOf("/v1/auth/unlock/pending", "/v1/auth/unlock/$REQUEST_ID/approve"), fixture.paths)
        assertEquals("0", fixture.waits.single())
        val signature = Json.parseToJsonElement(fixture.bodies.last()).jsonObject["signature"]!!.jsonPrimitive.content
        val message = UnlockApprovalMessage(REQUEST_ID, DEVICE_ID, NONCE).message()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(fixture.publicKey), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(Hex.decode(signature)))
    }

    @Test fun staleOrUnlistedRequestsNeverPrompt() = runTest {
        for ((createdAt, id) in listOf(NOW - 131 to REQUEST_ID, NOW to OTHER_ID)) {
            val fixture = Fixture(createdAt = createdAt)
            assertTrue(runCatching { fixture.service.approve(id) }.isFailure)
            assertTrue(runCatching { fixture.service.deny(id) }.isFailure)
            assertEquals(0, fixture.prompts)
            assertTrue(fixture.paths.none { it.endsWith("/approve") || it.endsWith("/deny") })
        }
        assertTrue(Fixture(createdAt = NOW - 131).service.pending(0).isEmpty())
    }

    @Test fun denyNeedsBiometricsToo() = runTest {
        val cancelled = Fixture(createdAt = NOW, accept = false)
        assertTrue(runCatching { cancelled.service.deny(REQUEST_ID) }.isFailure)
        assertTrue(cancelled.paths.none { it.endsWith("/deny") })
        val accepted = Fixture(createdAt = NOW)
        accepted.service.deny(REQUEST_ID)
        assertEquals("/v1/auth/unlock/$REQUEST_ID/deny", accepted.paths.last())
        assertEquals("Deny unlock of \"Work Mac\"", accepted.reason)
    }

    private inner class Fixture(createdAt: Long, private val accept: Boolean = true) {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val waits = mutableListOf<String?>()
        var prompts = 0
        var reason: String? = null
        private val store = InMemorySecureValueStore()
        private val vault = FakeDeviceKeyVault()
        private val identity = KeystoreBackedEd25519Identity(store, vault).apply {
            prompt = { text, cipher -> prompts++; reason = text; cipher.takeIf { accept }?.also(vault::authorise) }
        }
        val publicKey: String = kotlinx.coroutines.runBlocking { identity.enrollmentKeyHex("fixture") }.also { prompts = 0; reason = null }
        private val sessions = SessionRepository(store).apply { saveLogin(DEVICE_ID, "fixture-session-not-a-secret", Long.MAX_VALUE) }
        private val api = KtorJarvisApi(
            HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                bodies += request.body.toByteArray().decodeToString()
                if (path == "/v1/auth/unlock/pending") {
                    waits += request.url.parameters["wait"]
                    respond(
                        """{"requests":[{"id":"$REQUEST_ID","device_name":"Work‮ Mac","platform":"macos","nonce":"$NONCE","created_at":$createdAt}]}""",
                        HttpStatusCode.OK, JSON,
                    )
                } else {
                    respond(if (path.endsWith("/approve")) """{"status":"approved"}""" else """{"status":"denied"}""", HttpStatusCode.OK, JSON)
                }
            }) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            },
        )
        val service = UnlockApprovalService(
            api, sessions, { endpoint }, identity, ApprovalLock(),
            authenticate = { text -> prompts++; reason = text; accept }, unlocked = { true }, now = { NOW },
        )
    }

    private companion object {
        const val NOW = 1_900_000_000L
        const val REQUEST_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val OTHER_ID = "05050505-0505-0505-0505-050505050505"
        const val DEVICE_ID = "04040404-0404-0404-0404-040404040404"
        const val NONCE = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
