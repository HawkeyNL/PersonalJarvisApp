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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountApprovalTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    private fun approval(
        action: String = AccountApproval.DEVICE_REVOKE, user: String = USER_ID, device: String = DEVICE_ID,
        target: String = DEVICE_ID, nonce: String = NONCE, expiresAt: Long = 2_000,
    ) = AccountApproval(REQUEST_ID, user, device, action, target, nonce, expiresAt)

    /** client-core `account_approval_v1_matches_golden_bytes`. */
    @Test fun messageMatchesClientCoreGoldenVectors() {
        val vector = AccountApproval(
            "00112233-4455-6677-8899-aabbccddeeff", "10213243-5465-7687-98a9-bacbdcedfe0f",
            "ffeeddcc-bbaa-9988-7766-554433221100", AccountApproval.DEVICE_REVOKE,
            "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", NONCE, 1_800_000_000,
        )
        val tail = "00112233445566778899aabbccddeeff" + "102132435465768798a9bacbdcedfe0f" +
            "ffeeddccbbaa99887766554433221100" + "0f1e2d3c4b5a69788796a5b4c3d2e1f0" + NONCE + "000000006b49d200"
        assertEquals(
            "6a61727669732d6163636f756e742d617070726f76616c2d7631006465766963652d7265766f6b6500$tail",
            Hex.encode(vector.message()),
        )
        assertEquals(
            "6a61727669732d6163636f756e742d617070726f76616c2d76310070617373776f72642d73657400$tail",
            Hex.encode(vector.copy(action = AccountApproval.PASSWORD_SET).message()),
        )
        for (bad in listOf(approval(action = "device-delete"), approval(nonce = "ab".repeat(31)), approval(expiresAt = 0), approval(target = "1-1-1-1-1"))) {
            assertThrows(IllegalArgumentException::class.java) { bad.message() }
        }
    }

    @Test fun onlyTheRequestedChallengeIsAccepted() {
        approval().requireFor(AccountApproval.DEVICE_REVOKE, USER_ID, DEVICE_ID, DEVICE_ID, 1_000)
        for (other in listOf(
            approval(action = AccountApproval.PASSWORD_SET), approval(user = OTHER_ID), approval(device = OTHER_ID),
            approval(target = OTHER_ID), approval(expiresAt = 1_000),
        )) {
            assertThrows(IllegalStateException::class.java) {
                other.requireFor(AccountApproval.DEVICE_REVOKE, USER_ID, DEVICE_ID, DEVICE_ID, 1_000)
            }
        }
    }

    @Test fun unlinkSignsCoresRevokeChallengeForThisDevice() = runTest {
        val fixture = Fixture(approvalJson())
        fixture.service.unlinkThisDevice()
        assertEquals(1, fixture.prompts)
        assertEquals(listOf("/v1/auth/me", "/v1/devices/$DEVICE_ID/revoke-request", "/v1/auth/account/requests/$REQUEST_ID/approve"), fixture.paths)
        val signature = Json.parseToJsonElement(fixture.bodies.last()).jsonObject["signature"]!!.jsonPrimitive.content
        val message = approval().message()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(fixture.publicKey), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(Hex.decode(signature)))
        assertEquals("Erasing is the caller's decision", DEVICE_ID, fixture.sessions.session().deviceId)
    }

    @Test fun aSwappedChallengeOrRefusalNeverSignsOrApproves() = runTest {
        for (json in listOf(approvalJson(target = OTHER_ID), approvalJson(action = AccountApproval.PASSWORD_SET), approvalJson(user = OTHER_ID), "{\"error\":1}")) {
            val fixture = Fixture(json)
            assertTrue(runCatching { fixture.service.unlinkThisDevice() }.isFailure)
            assertEquals(0, fixture.prompts)
            assertTrue(fixture.paths.none { it.endsWith("/approve") })
        }
        val refused = Fixture(approvalJson(), revokeStatus = HttpStatusCode.Unauthorized)
        assertEquals("Core refused this change.", runCatching { refused.service.unlinkThisDevice() }.exceptionOrNull()?.message)
        assertEquals(0, refused.prompts)
        val unconfirmed = Fixture(approvalJson(), approveBody = """{"status":"pending"}""")
        assertTrue(runCatching { unconfirmed.service.unlinkThisDevice() }.isFailure)
    }

    @Test fun revokingAnotherDeviceNamesItFromAFreshList() = runTest {
        val devices = """{"devices":[{"id":"$OTHER_ID","name":"Old\u202E laptop","platform":"linux","status":"active"},""" +
            """{"id":"$THIRD_ID","name":"Gone","platform":"ios","status":"revoked"},{"id":"$DEVICE_ID","name":"Me","platform":"android","status":"active"}]}"""
        val fixture = Fixture(approvalJson(target = OTHER_ID), devicesBody = devices)
        fixture.service.revokeDevice(OTHER_ID)
        assertEquals("Revoke \"Old laptop\" (linux)", fixture.reason)
        assertEquals("/v1/devices/$OTHER_ID/revoke-request", fixture.paths[2])
        assertTrue(fixture.paths.last().endsWith("/approve"))

        // Unknown, revoked and own ids are refused before Core is asked for a challenge.
        for (id in listOf(UNKNOWN_ID, THIRD_ID, DEVICE_ID)) {
            val refused = Fixture(approvalJson(target = id), devicesBody = devices)
            assertTrue(runCatching { refused.service.revokeDevice(id) }.isFailure)
            assertEquals(0, refused.prompts)
            assertTrue(refused.paths.none { it.endsWith("/revoke-request") })
        }
    }

    @Test fun aSecondApprovalIsRefusedWhileOneIsRunning() = runTest {
        val lock = ApprovalLock()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { lock.exclusive { inside.complete(Unit); release.await() } }
        inside.await()
        assertEquals("Another approval is in progress.", runCatching { lock.exclusive { } }.exceptionOrNull()?.message)
        release.complete(Unit)
        first.await()
        lock.exclusive { }
    }

    private fun approvalJson(target: String = DEVICE_ID, action: String = AccountApproval.DEVICE_REVOKE, user: String = USER_ID) =
        """{"request_id":"$REQUEST_ID","user_id":"$user","device_id":"$DEVICE_ID","action":"$action","target":"$target","nonce":"$NONCE","expires_at":2000}"""

    private inner class Fixture(
        revokeBody: String,
        revokeStatus: HttpStatusCode = HttpStatusCode.OK,
        approveBody: String = """{"status":"completed"}""",
        devicesBody: String = """{"devices":[]}""",
    ) {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var prompts = 0
        var reason: String? = null
        private val store = InMemorySecureValueStore()
        private val vault = FakeDeviceKeyVault()
        private val identity = KeystoreBackedEd25519Identity(store, vault).apply {
            prompt = { text, cipher -> prompts++; reason = text; cipher.also(vault::authorise) }
        }
        val publicKey: String = kotlinx.coroutines.runBlocking { identity.enrollmentKeyHex("fixture") }.also { prompts = 0 }
        val sessions = SessionRepository(store).apply { saveLogin(DEVICE_ID, "fixture-session-not-a-secret", Long.MAX_VALUE) }
        private val api = KtorJarvisApi(
            HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                bodies += request.body.toByteArray().decodeToString()
                when {
                    path == "/v1/auth/me" -> respond("""{"user_id":"$USER_ID","device_id":"$DEVICE_ID"}""", HttpStatusCode.OK, JSON)
                    path.endsWith("/revoke-request") -> respond(revokeBody, revokeStatus, JSON)
                    path == "/v1/devices" -> respond(devicesBody, HttpStatusCode.OK, JSON)
                    else -> respond(approveBody, HttpStatusCode.OK, JSON)
                }
            }) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            },
        )
        val service = AccountApprovalService(api, sessions, { endpoint }, identity, ApprovalLock(), now = { 1_000 })
    }

    private companion object {
        const val REQUEST_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val USER_ID = "03030303-0303-0303-0303-030303030303"
        const val DEVICE_ID = "04040404-0404-0404-0404-040404040404"
        const val OTHER_ID = "05050505-0505-0505-0505-050505050505"
        const val THIRD_ID = "06060606-0606-0606-0606-060606060606"
        const val UNKNOWN_ID = "07070707-0707-0707-0707-070707070707"
        const val NONCE = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
