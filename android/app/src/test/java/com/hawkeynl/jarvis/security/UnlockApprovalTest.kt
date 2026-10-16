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
import java.security.SecureRandom
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
        fixture.service.approve(REQUEST_ID.uppercase(), 89)
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
            assertTrue(runCatching { fixture.service.approve(id, 89) }.isFailure)
            assertTrue(runCatching { fixture.service.choices(id) }.isFailure)
            assertTrue(runCatching { fixture.service.deny(id) }.isFailure)
            assertEquals(0, fixture.prompts)
            assertTrue(fixture.paths.none { it.endsWith("/approve") || it.endsWith("/deny") })
        }
        // Listing shows what Core returns; only a decision refuses a stale request.
        assertEquals(1, Fixture(createdAt = NOW - 131).service.pending(0).size)
    }

    /** Same vectors as client-core `unlock_code` and the desktop and iOS clients. */
    @Test fun unlockCodeMatchesClientCoreVectors() {
        assertEquals(89, UnlockCode.code(REQUEST_ID, NONCE))
        assertEquals(89, UnlockCode.code(REQUEST_ID.uppercase(), NONCE.uppercase()))
        assertEquals(0, UnlockCode.code("ffeeddcc-bbaa-9988-7766-554433221100", "0b".repeat(32)))
        assertEquals("00", UnlockCode.display(0))
        assertEquals("89", UnlockCode.display(89))
        assertTrue(runCatching { UnlockCode.code(REQUEST_ID, NONCE.drop(2)) }.isFailure)
        assertTrue(runCatching { UnlockCode.code("not-a-uuid", NONCE) }.isFailure)
    }

    @Test fun choicesHoldTheCodeAndTwoDistinctDecoys() {
        val random = SecureRandom()
        val positions = IntArray(3)
        for (code in (0 until 3000).map { it % 100 }) {
            val choices = UnlockCode.choices(code, random)
            assertEquals(3, choices.toSet().size)
            assertTrue(choices.all { it in 0..99 })
            positions[choices.indexOf(code)]++
        }
        // Shuffled: the code is not stuck in one place (each slot ~1000 of 3000).
        assertTrue(positions.contentToString(), positions.all { it > 800 })
    }

    @Test fun choicesComeFromTheRequestListedForThatId() = runTest {
        val fixture = Fixture(createdAt = NOW)
        val choices = fixture.service.choices(REQUEST_ID)
        assertTrue(89 in choices)
        assertEquals(3, choices.toSet().size)
        assertEquals(0, fixture.prompts)
        assertEquals(listOf("/v1/auth/unlock/pending"), fixture.paths)
    }

    /** Fresh decoys per look would leak the code: it is the one number in every set. */
    @Test fun repeatedLooksAtOneRequestShowTheSameChoices() = runTest {
        val fixture = Fixture(createdAt = NOW)
        val first = fixture.service.choices(REQUEST_ID)
        repeat(20) { assertEquals(first, fixture.service.choices(REQUEST_ID.uppercase())) }
    }

    /** The sets live in AppContainer: a new service (new Activity after a rotation) must not re-roll them. */
    @Test fun servicesSharingTheContainerCacheShowTheSameChoices() = runTest {
        val shared = UnlockChoiceSets { NOW }
        val first = Fixture(createdAt = NOW, choiceSets = shared).service.choices(REQUEST_ID)
        assertEquals(first, Fixture(createdAt = NOW, choiceSets = shared).service.choices(REQUEST_ID))
    }

    /** A full cache refuses a new set instead of evicting a live one (that would re-roll it). */
    @Test fun aFullChoiceCacheRefusesInsteadOfReRolling() {
        var now = NOW
        val sets = UnlockChoiceSets { now }
        val ids = (0 until UnlockChoiceSets.MAX).map { "00000000-0000-0000-0000-%012d".format(it) }
        val first = ids.map { sets.choices(it, NOW, 89) }
        assertEquals(
            "Too many unlock requests; deny some first.",
            runCatching { sets.choices(REQUEST_ID, NOW, 89) }.exceptionOrNull()?.message,
        )
        assertEquals(first, ids.map { sets.choices(it, NOW, 89) })
        // Deciding one, or letting them expire, frees room.
        sets.forget(ids[0])
        assertTrue(89 in sets.choices(REQUEST_ID, NOW, 89))
        now += 131
        assertTrue(89 in sets.choices(OTHER_ID, now, 89))
    }

    @Test fun wrongPickDeniesWithoutPromptOrSignature() = runTest {
        for (choice in listOf(0, 88, 90, 99)) {
            val fixture = Fixture(createdAt = NOW)
            assertTrue(runCatching { fixture.service.approve(REQUEST_ID, choice) }.exceptionOrNull() is WrongUnlockNumber)
            assertEquals(0, fixture.prompts)
            assertEquals(listOf("/v1/auth/unlock/pending", "/v1/auth/unlock/$REQUEST_ID/deny"), fixture.paths)
            assertTrue(fixture.bodies.none { "signature" in it })
        }
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

    private inner class Fixture(
        createdAt: Long, private val accept: Boolean = true, choiceSets: UnlockChoiceSets = UnlockChoiceSets { NOW },
    ) {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val waits = mutableListOf<String?>()
        var prompts = 0
        var reason: String? = null
        private val store = InMemorySecureValueStore()
        private val vault = FakeDeviceKeyVault()
        private val identity = KeystoreBackedEd25519Identity(store, vault).apply {
            // [accept] only drives the deny prompt; the key itself is always created.
            prompt = { text, cipher -> prompts++; reason = text; cipher.also(vault::authorise) }
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
            authenticate = { text -> prompts++; reason = text; accept }, unlocked = { true }, choiceSets = choiceSets, now = { NOW },
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
