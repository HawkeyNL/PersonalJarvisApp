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
        // client-core `password_reset_approval_matches_golden_bytes`: target is the user.
        assertEquals(
            "6a61727669732d6163636f756e742d617070726f76616c2d763100" + "70617373776f72642d726573657400" +
                "00112233445566778899aabbccddeeff" + "102132435465768798a9bacbdcedfe0f" +
                "ffeeddccbbaa99887766554433221100" + "102132435465768798a9bacbdcedfe0f" + NONCE + "000000006b49d200",
            Hex.encode(vector.copy(action = AccountApproval.PASSWORD_RESET, target = vector.user_id).message()),
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
        fixture.service.revokeDevice(OTHER_ID) { true }
        assertEquals("Revoke \"Old laptop\" (linux, 05050505)", fixture.reason)
        assertEquals("/v1/devices/$OTHER_ID/revoke-request", fixture.paths[2])
        assertTrue(fixture.paths.last().endsWith("/approve"))

        // Unknown, revoked and own ids are refused before Core is asked for a challenge.
        for (id in listOf(UNKNOWN_ID, THIRD_ID, DEVICE_ID)) {
            val refused = Fixture(approvalJson(target = id), devicesBody = devices)
            assertTrue(runCatching { refused.service.revokeDevice(id) { true } }.isFailure)
            assertEquals(0, refused.prompts)
            assertTrue(refused.paths.none { it.endsWith("/revoke-request") })
        }
    }

    @Test fun revokingAnotherDeviceNeedsTheAppUnlockedBeforeAndAfterThePrompt() = runTest {
        val devices = """{"devices":[{"id":"$OTHER_ID","name":"Old laptop","platform":"linux","status":"active"}]}"""
        val locked = Fixture(approvalJson(target = OTHER_ID), devicesBody = devices)
        assertTrue(runCatching { locked.service.revokeDevice(OTHER_ID) { false } }.isFailure)
        assertEquals(0, locked.prompts)
        assertTrue(locked.paths.isEmpty())
        // Locked while the prompt was open: signed, but never sent.
        val lockedDuring = Fixture(approvalJson(target = OTHER_ID), devicesBody = devices)
        assertTrue(runCatching { lockedDuring.service.revokeDevice(OTHER_ID) { lockedDuring.prompts == 0 } }.isFailure)
        assertEquals(1, lockedDuring.prompts)
        assertTrue(lockedDuring.paths.none { it.endsWith("/approve") })
    }

    @Test fun theCurrentPasswordIsOnlyCheckedByCore() = runTest {
        // Short or old-style current passwords still reach Core.
        val short = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_SET))
        short.service.changePassword("old", NEW_PASSWORD)
        assertEquals("/v1/auth/account/password/requests", short.paths[1])
        for (current in listOf("", "old\u0007pw", "x".repeat(1025))) {
            val refused = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_SET))
            assertEquals(
                "Enter the current account password.",
                runCatching { refused.service.changePassword(current, NEW_PASSWORD) }.exceptionOrNull()?.message,
            )
            assertTrue(refused.paths.isEmpty())
        }
    }

    @Test fun passwordChangeSignsCoresPasswordChallengeAndClearsTheSession() = runTest {
        val fixture = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_SET))
        fixture.service.changePassword(OLD_PASSWORD, NEW_PASSWORD)
        assertEquals("/v1/auth/account/password/requests", fixture.paths[1])
        val request = Json.parseToJsonElement(fixture.bodies[1]).jsonObject
        assertEquals(NEW_PASSWORD, request["password"]!!.jsonPrimitive.content)
        assertEquals(OLD_PASSWORD, request["current_password"]!!.jsonPrimitive.content)
        val signature = Json.parseToJsonElement(fixture.bodies.last()).jsonObject["signature"]!!.jsonPrimitive.content
        val message = approval(target = USER_ID, action = AccountApproval.PASSWORD_SET).message()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(fixture.publicKey), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(Hex.decode(signature)))
        assertEquals(null, fixture.sessions.session().token)
        assertEquals(DEVICE_ID, fixture.sessions.session().deviceId)
    }

    @Test fun passwordChangeRefusesBeforeSigning() = runTest {
        val short = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_SET))
        assertTrue(runCatching { short.service.changePassword(OLD_PASSWORD, "too short") }.isFailure)
        assertTrue(short.paths.isEmpty())
        val wrong = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_SET), revokeStatus = HttpStatusCode.Unauthorized)
        assertEquals(
            "The current password was not accepted, or the session expired.",
            runCatching { wrong.service.changePassword(OLD_PASSWORD, NEW_PASSWORD) }.exceptionOrNull()?.message,
        )
        // A challenge for another target or action is never signed.
        for (json in listOf(approvalJson(target = DEVICE_ID, action = AccountApproval.PASSWORD_SET), approvalJson(target = USER_ID))) {
            val swapped = Fixture(json)
            assertTrue(runCatching { swapped.service.changePassword(OLD_PASSWORD, NEW_PASSWORD) }.isFailure)
            assertEquals(0, swapped.prompts)
            assertEquals("fixture-session-not-a-secret", swapped.sessions.session().token)
        }
    }

    @Test fun passwordResetSignsCoresResetChallengeAndKeepsTheSession() = runTest {
        val pending = """{"status":"pending_confirmation","request_id":"$REQUEST_ID","expires_in_seconds":900}"""
        val fixture = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_RESET), approveBody = pending)
        assertEquals(REQUEST_ID, fixture.service.resetPassword(NEW_PASSWORD))
        assertEquals("Reset Jarvis account password", fixture.reason)
        assertEquals(1, fixture.prompts)
        assertEquals("/v1/auth/account/password/reset-requests", fixture.paths[1])
        val request = Json.parseToJsonElement(fixture.bodies[1]).jsonObject
        assertEquals(setOf("password"), request.keys)
        assertEquals(NEW_PASSWORD, request["password"]!!.jsonPrimitive.content)
        val signature = Json.parseToJsonElement(fixture.bodies.last()).jsonObject["signature"]!!.jsonPrimitive.content
        val message = approval(target = USER_ID, action = AccountApproval.PASSWORD_RESET).message()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(fixture.publicKey), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(Hex.decode(signature)))
        // Core ends the session only after the Home Node confirms.
        assertEquals("fixture-session-not-a-secret", fixture.sessions.session().token)
    }

    @Test fun passwordResetRefusesBeforeSigningOrWithoutPendingConfirmation() = runTest {
        val short = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_RESET))
        assertTrue(runCatching { short.service.resetPassword("too short") }.isFailure)
        assertTrue(short.paths.isEmpty())
        // A password-set challenge, or a reset for another target, is never signed.
        for (json in listOf(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_SET), approvalJson(action = AccountApproval.PASSWORD_RESET))) {
            val swapped = Fixture(json)
            assertTrue(runCatching { swapped.service.resetPassword(NEW_PASSWORD) }.isFailure)
            assertEquals(0, swapped.prompts)
        }
        // "completed" or another request id is not a pending reset.
        for (answer in listOf("""{"status":"completed"}""", """{"status":"pending_confirmation","request_id":"$OTHER_ID"}""")) {
            val fixture = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_RESET), approveBody = answer)
            assertEquals("Core did not confirm the change.", runCatching { fixture.service.resetPassword(NEW_PASSWORD) }.exceptionOrNull()?.message)
            assertEquals(1, fixture.prompts)
            assertTrue(fixture.paths.last().endsWith("/approve"))
        }
    }

    @Test fun aPendingConfirmationIsNotACompletedChangeOrUnlink() = runTest {
        val pending = """{"status":"pending_confirmation","request_id":"$REQUEST_ID"}"""
        val change = Fixture(approvalJson(target = USER_ID, action = AccountApproval.PASSWORD_SET), approveBody = pending)
        assertEquals("Core did not confirm the change.", runCatching { change.service.changePassword(OLD_PASSWORD, NEW_PASSWORD) }.exceptionOrNull()?.message)
        assertEquals("fixture-session-not-a-secret", change.sessions.session().token)
        val unlink = Fixture(approvalJson(), approveBody = pending)
        assertEquals("Core did not confirm the change.", runCatching { unlink.service.unlinkThisDevice() }.exceptionOrNull()?.message)
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
                    path.endsWith("/revoke-request") || path.startsWith("/v1/auth/account/password/") -> respond(revokeBody, revokeStatus, JSON)
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
        const val OLD_PASSWORD = "fixture old password only"
        const val NEW_PASSWORD = "fixture new password only"
        const val THIRD_ID = "06060606-0606-0606-0606-060606060606"
        const val UNKNOWN_ID = "07070707-0707-0707-0707-070707070707"
        const val NONCE = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
