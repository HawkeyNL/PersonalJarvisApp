package com.hawkeynl.jarvis.security

import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.KtorJarvisApi
import com.hawkeynl.jarvis.network.PendingAction
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
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalTest {
    private val endpoint = (HomeNodeEndpoint.parse("https://jarvis.test") as EndpointValidation.Valid).endpoint

    /** Same bytes as client-core `agent_approval_v1_matches_golden_bytes`. */
    @Test fun messageMatchesTheClientCoreVector() {
        val message = AgentApprovalMessage(PENDING_ID, NONCE, ACTION_SHA, "ffeeddcc-bbaa-9988-7766-554433221100").message()
        assertEquals(
            "6a61727669732f6167656e742d617070726f76616c2f763100" +
                "00112233445566778899aabbccddeeff" + NONCE + ACTION_SHA + "ffeeddccbbaa99887766554433221100",
            Hex.encode(message),
        )
        assertTrue(runCatching { AgentApprovalMessage(PENDING_ID, NONCE.drop(2), ACTION_SHA, DEVICE_ID).message() }.isFailure)
        assertTrue(runCatching { AgentApprovalMessage(PENDING_ID, NONCE, ACTION_SHA.drop(2), DEVICE_ID).message() }.isFailure)
    }

    /** Same JSON and hash as Core `stored_action_json_escaping_matches_the_mobile_vector`. */
    @Test fun canonicalJsonMatchesSerdeEscaping() {
        val action = ReviewedAgentAction.WriteFile("a/\"b\".txt", "\\\b\u000c\n\r\t\u0001\u001f\u007f\u00E9\u2028\uD83D\uDE00")
        val json = action.canonicalJson()
        assertEquals(
            "{\"type\":\"write_file\",\"path\":\"a/\\\"b\\\".txt\",\"content\":\"\\\\\\b\\f\\n\\r\\t\\u0001\\u001f\u007f\u00E9\u2028\uD83D\uDE00\"}",
            json,
        )
        assertEquals("e151c338cf311d5531b50c2451032bb04fe5bb8e6bfb5e6ab6535a1080f51aa5", sha(json))
        assertEquals(WRITE, ReviewedAgentAction.WriteFile("note.txt", "ok").canonicalJson())
        assertEquals(ACTION_SHA, sha(WRITE))
        val review = AgentReview.of(item(json, sha = sha(json)))
        assertEquals(AgentReview.Approvable(action, sha(json)), review)
    }

    @Test fun onlyExactKnownActionsWithTheirHashAreApprovable() {
        assertEquals(AgentReview.Approvable(ReviewedAgentAction.WriteFile("note.txt", "ok"), ACTION_SHA), AgentReview.of(item(WRITE)))
        val refused = listOf(
            // A commit's effect is not in the JSON: never approvable on a phone.
            item("""{"type":"git_commit","message":"Fix"}""", "git_commit"),
            // A lone surrogate (listed as an escape) would show here but hash as '?'.
            item(WRITE.replace("ok", "x\uD800")),
            item(null),
            item(WRITE, approvalMessage = "agent-approval-v2"),
            item(WRITE, approvalMessage = null),
            item(WRITE, sha = "00".repeat(32)),
            item(WRITE, kind = "git_commit"),
            item("""{"type":"claude_code","prompt":"x"}""", "claude_code"),
            item("""{"type":"write_file","path":"note.txt","content":"ok","mode":"x"}"""),
            item("""{"type":"write_file","path":"note.txt"}"""),
            item("""{"type":"write_file","content":"ok","path":"note.txt"}"""),
            item("""{"type":"write_file","path":"note.txt","content":1}"""),
            item("""{"type":"write_file","path":"note.txt","path":"other.txt","content":"ok"}"""),
            item("""{ "type":"write_file","path":"note.txt","content":"ok"}"""),
            item("{\"type\":\"write_file\",\"path\":\"note.txt\",\"content\":\"\\u006fk\"}"),
            item("""["write_file"]"""),
            item("not json"),
            item("""{"type":"write_file","path":"n","content":"${"x".repeat(AgentReview.MAX_ACTION_JSON_BYTES)}"}"""),
        )
        for (pending in refused) {
            // Each hash matches the listed bytes, so only the content check refuses these.
            val listed = pending.action_json?.let { if (pending.action_sha256 == "00".repeat(32)) pending else pending.copy(action_sha256 = sha(it)) } ?: pending
            assertTrue(listed.toString(), AgentReview.of(listed) is AgentReview.Refused)
        }
    }

    @Test fun hiddenCharactersAreShownEscaped() {
        assertEquals("a\\u{202E}b\\u{200B}c\nd\te", AgentReview.visible("a\u202Eb\u200Bc\nd\te"))
        assertEquals("a\\u{000A}b\\u{000D}", AgentReview.visible("a\nb\r", keepNewlines = false))
        assertEquals("e\u0301\u0301\\u{0301}", AgentReview.visible("e\u0301\u0301\u0301"))
        // Stacked marks outside the common blocks (Thai) are counted too.
        assertEquals("\u0E01\u0E34\u0E48\\u{0E49}", AgentReview.visible("\u0E01\u0E34\u0E48\u0E49"))
        assertEquals("x\\u{00A0}y\\u{2028}\u00E9", AgentReview.visible("x\u00A0y\u2028\u00E9"))
    }

    @Test fun pathsShowOnlyPrintableAscii() {
        assertEquals("a/b c.txt", AgentReview.asciiPath("a/b c.txt"))
        assertEquals("\\u{00E9}/\\u{05D0}/\\u{202E}x\\u{000A}\\u{1F600}", AgentReview.asciiPath("\u00E9/\u05D0/\u202Ex\n\uD83D\uDE00"))
    }

    @Test fun contentLinesAreIsolatedLeftToRightForDisplayOnly() {
        assertEquals("\u2066a\u2069\n\u2066\u05D0b\u2069", AgentReview.ltrLines("a\n\u05D0b"))
    }

    @Test fun approvalRereadsTheActionAndSignsItsHash() = runTest {
        val fixture = Fixture()
        fixture.service.approve(PENDING_ID.uppercase(), ACTION_SHA)
        assertEquals(1, fixture.prompts)
        assertEquals("Write note.txt (2 bytes)", fixture.reason)
        assertEquals(listOf("/v1/agent/pending", "/v1/agent/pending/$PENDING_ID/approve"), fixture.paths)
        val signature = Json.parseToJsonElement(fixture.bodies.last()).jsonObject["signature"]!!.jsonPrimitive.content
        val message = AgentApprovalMessage(PENDING_ID, NONCE, ACTION_SHA, DEVICE_ID).message()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Hex.decode(fixture.publicKey), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(Hex.decode(signature)))
    }

    @Test fun aChangedUnlistedDuplicatedOrUnknownActionNeverPrompts() = runTest {
        val cases = listOf(
            Fixture() to OTHER_ID,
            Fixture(listed = listOf(entry(), entry())) to PENDING_ID,
            Fixture(listed = listOf(entry(json = """{"type":"write_file","path":"note.txt","content":"no"}"""))) to PENDING_ID,
            Fixture(listed = listOf(entry(json = """{"type":"run","cmd":"x"}""", kind = "run"))) to PENDING_ID,
        )
        for ((fixture, id) in cases) {
            assertTrue(runCatching { fixture.service.approve(id, ACTION_SHA) }.isFailure)
            assertEquals(0, fixture.prompts)
            assertTrue(fixture.paths.none { it.endsWith("/approve") })
        }
    }

    @Test fun aRefusedApprovalIsReportedAsRefusedNotUnknown() = runTest {
        val fixture = Fixture(approveStatus = HttpStatusCode.Unauthorized)
        val message = runCatching { fixture.service.approve(PENDING_ID, ACTION_SHA) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.startsWith("Core refused"))
    }

    @Test fun lockingDuringThePromptSendsNothing() = runTest {
        var unlocked = true
        val fixture = Fixture(onPrompt = { unlocked = false }, unlocked = { unlocked })
        unlocked = true // creating the fixture's key prompted once
        assertTrue(runCatching { fixture.service.approve(PENDING_ID, ACTION_SHA) }.isFailure)
        assertEquals(1, fixture.prompts)
        assertTrue(fixture.paths.none { it.endsWith("/approve") })
        unlocked = true
        val denying = Fixture(onPrompt = { unlocked = false }, unlocked = { unlocked })
        unlocked = true
        assertTrue(runCatching { denying.service.deny(PENDING_ID) }.isFailure)
        assertTrue(denying.paths.none { it.endsWith("/deny") })
    }

    @Test fun denyNeedsBiometricsAndConfirmation() = runTest {
        val cancelled = Fixture(accept = false)
        assertTrue(runCatching { cancelled.service.deny(PENDING_ID) }.isFailure)
        assertTrue(cancelled.paths.none { it.endsWith("/deny") })
        val accepted = Fixture()
        accepted.service.deny(PENDING_ID)
        assertEquals("/v1/agent/pending/$PENDING_ID/deny", accepted.paths.last())
        assertEquals("Deny agent action write_file", accepted.reason)
        // Even an action this app cannot show can be denied.
        Fixture(listed = listOf(entry(json = null))).service.deny(PENDING_ID)
    }

    private fun item(json: String?, kind: String = "write_file", sha: String = ACTION_SHA, approvalMessage: String? = "agent-approval-v1") =
        PendingAction(PENDING_ID, kind, "", "", NONCE, sha, json, approvalMessage)

    private fun entry(json: String? = WRITE, kind: String = "write_file"): String =
        """{"pending_id":"$PENDING_ID","action":"$kind","preview":"p","nonce":"$NONCE","action_sha256":"${sha(json ?: "")}",""" +
            """"action_json":${json?.let { JsonPrimitive(it).toString() } ?: "null"},"approval_message":"agent-approval-v1","created_at":"2026-10-17T00:00:00Z"}"""

    private inner class Fixture(
        listed: List<String> = listOf(entry()),
        private val accept: Boolean = true,
        onPrompt: () -> Unit = {},
        unlocked: () -> Boolean = { true },
        approveStatus: HttpStatusCode = HttpStatusCode.OK,
    ) {
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var prompts = 0
        var reason: String? = null
        private val store = InMemorySecureValueStore()
        private val vault = FakeDeviceKeyVault()
        private val identity = KeystoreBackedEd25519Identity(store, vault).apply {
            prompt = { text, cipher -> prompts++; reason = text; onPrompt(); cipher.also(vault::authorise) }
        }
        val publicKey: String = kotlinx.coroutines.runBlocking { identity.enrollmentKeyHex("fixture") }.also { prompts = 0; reason = null }
        private val sessions = SessionRepository(store).apply { saveLogin(DEVICE_ID, "fixture-session-not-a-secret", Long.MAX_VALUE) }
        private val api = KtorJarvisApi(
            HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                bodies += request.body.toByteArray().decodeToString()
                val body = when {
                    path == "/v1/agent/pending" -> """{"pending":[${listed.joinToString(",")}]}"""
                    path.endsWith("/approve") -> """{"action":"write_file","output":"written","truncated":false}"""
                    else -> """{"status":"denied"}"""
                }
                respond(body, if (path.endsWith("/approve")) approveStatus else HttpStatusCode.OK, JSON)
            }) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            },
        )
        val service = AgentApprovalService(
            api, sessions, { endpoint }, identity, ApprovalLock(),
            authenticate = { text -> prompts++; reason = text; onPrompt(); accept }, unlocked = unlocked,
        )
    }

    private companion object {
        const val PENDING_ID = "00112233-4455-6677-8899-aabbccddeeff"
        const val OTHER_ID = "05050505-0505-0505-0505-050505050505"
        const val DEVICE_ID = "04040404-0404-0404-0404-040404040404"
        const val NONCE = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val WRITE = """{"type":"write_file","path":"note.txt","content":"ok"}"""
        const val ACTION_SHA = "8d2dd02e0f1e485d0be7f748f2fdd0d2160dd245b000ca1bff1ca15cd043dbe5"
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")

        fun sha(text: String): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }
}
