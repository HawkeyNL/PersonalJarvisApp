package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.KtorJarvisApi
import com.hawkeynl.jarvis.network.PairingCreateRequest
import com.hawkeynl.jarvis.network.PairingRequestItem
import com.hawkeynl.jarvis.network.PairingRequestList
import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.testing.FakeDeviceKeyVault
import com.hawkeynl.jarvis.testing.InMemorySecureValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingApprovalTest {
    private val key = ByteArray(32) { (it + 32).toByte() }
    private val full = "72dbb7336c76780023f83da4c355f2eeea85733b13d3477697917790c1229084"
    private val other = "ab".repeat(32)
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    private fun item(fingerprint: String = full.take(16), fingerprintFull: String? = null, publicKey: String = Hex.encode(key), expiresAt: Long = 2_000_000_000) =
        PairingRequestItem(REQUEST_ID, "Fixture phone", "ios", fingerprint, "00".repeat(32), publicKey, 1, expiresAt, fingerprintFull)

    @Test fun messageMatchesClientCoreGoldenVector() {
        val message = PairingApproval.message(
            "00112233-4455-6677-8899-aabbccddeeff", Hex.encode(ByteArray(32) { it.toByte() }), Hex.encode(key),
            "10213243-5465-7687-98a9-bacbdcedfe0f", "ffeeddcc-bbaa-9988-7766-554433221100", 1_800_000_000,
        )
        assertEquals(
            "6a61727669732d6465766963652d70616972696e672d763100" + "00112233445566778899aabbccddeeff" +
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "102132435465768798a9bacbdcedfe0f" + "ffeeddccbbaa99887766554433221100" + "000000006b49d200",
            Hex.encode(message),
        )
        assertThrows(IllegalArgumentException::class.java) { PairingApproval.message(REQUEST_ID, "00", Hex.encode(key), USER_ID, DEVICE_ID, 1) }
        assertThrows(IllegalArgumentException::class.java) { PairingApproval.message("1-1-1-1-1", "00".repeat(32), Hex.encode(key), USER_ID, DEVICE_ID, 1) }
        assertThrows(IllegalArgumentException::class.java) { PairingApproval.message(REQUEST_ID, "00".repeat(32), "+f" + "00".repeat(31), USER_ID, DEVICE_ID, 1) }
    }

    @Test fun fingerprintIsComputedLocallyAndAnyMismatchIsRefused() {
        assertEquals(full, PairingApproval.fingerprint(key))
        assertEquals(full, PairingApproval.verifiedFingerprint(item(fingerprintFull = full)))
        // Older Core: no fingerprint_full, but the legacy prefix must still match.
        assertEquals(full, PairingApproval.verifiedFingerprint(item()))
        for (tampered in listOf(item(fingerprintFull = other), item(fingerprint = other.take(16)), item(fingerprintFull = full.uppercase()))) {
            assertThrows(PairingFingerprintMismatch::class.java) { PairingApproval.verifiedFingerprint(tampered) }
        }
        for (bad in listOf("", "zz", "07".repeat(31))) {
            assertThrows(IllegalArgumentException::class.java) { PairingApproval.verifiedFingerprint(item(publicKey = bad)) }
        }
        assertEquals("0123 4567 89ab cdef 01", PairingApproval.grouped("0123456789abcdef01"))
        assertEquals("unknown", PairingApproval.hint(null))
        assertEquals("Pixel 9", PairingApproval.hint("Pixel‮ 9\u0007"))
        assertEquals("x".repeat(64) + "…", PairingApproval.hint("x".repeat(70)))
        assertEquals("1:40", PairingApproval.countdown(100, 0))
        assertEquals("Expired", PairingApproval.countdown(100, 100))
    }

    @Test fun decodesPairingListWithAndWithoutDetails() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val legacy = json.decodeFromString(
            PairingRequestList.serializer(),
            """{"requests":[{"id":"$REQUEST_ID","device_name":"Phone","platform":"ios","fingerprint":"72dbb7336c767800","nonce":"00","candidate_public_key":"20","created_at":1,"expires_at":2}]}""",
        ).requests.single()
        assertNull(legacy.fingerprint_full); assertNull(legacy.ip); assertNull(legacy.model)
        val current = json.decodeFromString(
            PairingRequestList.serializer(),
            """{"requests":[{"id":"$REQUEST_ID","device_name":"Phone","platform":"ios","fingerprint":"72dbb7336c767800","fingerprint_full":"$full","nonce":"00","candidate_public_key":"20","created_at":1,"expires_at":2,"ip":"192.0.2.7","user_agent":null,"app_version":"0.1.21","model":"Pixel 9"}]}""",
        ).requests.single()
        assertEquals(full, current.fingerprint_full)
        assertEquals("192.0.2.7", current.ip); assertNull(current.user_agent)
        assertEquals("0.1.21", current.app_version); assertEquals("Pixel 9", current.model)
    }

    @Test fun pairingWithoutStoredPasswordNamesTheFix() = runTest {
        val api = KtorJarvisApi(client { respond("""{"error":"account password required","code":"pairing_password_not_set"}""", HttpStatusCode.Forbidden, JSON_HEADERS) })
        val result = api.createPairing(endpoint, PairingCreateRequest("Phone", "android", Hex.encode(key), "fixture password"))
        assertEquals(ApiResult.HttpError(403, KtorJarvisApi.PAIRING_PASSWORD_NOT_SET_MESSAGE), result)
    }

    @Test fun decisionsSignOnlyAfterBiometricSuccess() = runTest {
        val decisions = mutableListOf<Pair<String, String>>()
        val api = KtorJarvisApi(client { request ->
            when (val path = request.url.encodedPath) {
                "/v1/auth/me" -> respond("""{"user_id":"$USER_ID","device_id":"$DEVICE_ID"}""", HttpStatusCode.OK, JSON_HEADERS)
                else -> {
                    decisions += path to request.body.toByteArray().decodeToString()
                    respond("{}", HttpStatusCode.OK, JSON_HEADERS)
                }
            }
        })
        val store = InMemorySecureValueStore()
        val identity = KeystoreBackedEd25519Identity(store, FakeDeviceKeyVault())
        val publicKey = identity.publicKeyHex()
        val sessions = SessionRepository(store).apply { saveLogin(DEVICE_ID, "fixture-session-not-a-secret", Long.MAX_VALUE) }
        var accept = false
        var prompts = 0
        identity.prompt = { _, cipher -> prompts++; cipher.takeIf { accept } }
        val service = PairingApprovalService(
            api, sessions, { endpoint }, identity,
            authenticate = { prompts++; accept }, biometricsAvailable = { true }, unlocked = { true }, now = { 1_000 },
        )
        val request = item(fingerprintFull = full)

        refuses<IllegalStateException> { service.approve(request) }
        refuses<IllegalStateException> { service.deny(request) }
        assertTrue(decisions.isEmpty())

        accept = true
        refuses<PairingFingerprintMismatch> { service.approve(item(fingerprintFull = other)) }
        refuses<IllegalStateException> { service.approve(item(fingerprintFull = full, expiresAt = 1_000)) }
        assertEquals("Mismatched or expired requests never prompt", 2, prompts)
        assertTrue(decisions.isEmpty())

        service.approve(request)
        assertEquals(listOf("/v1/auth/pairing/requests/$REQUEST_ID/approve"), decisions.map { it.first })
        val signature = Json.parseToJsonElement(decisions.single().second).jsonObject["signature"]!!.jsonPrimitive.content
        val message = PairingApproval.message(REQUEST_ID, request.nonce, request.candidate_public_key, USER_ID, DEVICE_ID, request.expires_at)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(publicKey), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(Hex.decode(signature)))
    }

    private suspend inline fun <reified T : Throwable> refuses(crossinline block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ${T::class.java.simpleName}, got $error", error is T)
    }

    private fun client(handler: MockRequestHandler) =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        }

    private companion object {
        const val REQUEST_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val USER_ID = "03030303-0303-0303-0303-030303030303"
        const val DEVICE_ID = "04040404-0404-0404-0404-040404040404"
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
