package com.hawkeynl.jarvis.security

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ModelRoutingTest {
    private fun e(provider: String, model: String) = RouteEntry(provider, model)
    private val discovered = listOf(
        e("huggingface", "org/modèl"), e("claude-cli", "claude-haiku-4-5"), e("claude-cli", "claude-opus-5"),
        e("anthropic-api", "claude-opus-5"), e("ollama", "llama3.2"), e("codex-cli", "gpt-6-luna"), e("openai-api", "gpt-6-luna"),
    )
    private fun tier(chain: List<RouteEntry>, metered: Boolean = false, paid: Boolean = true) =
        Routing(1, paid, mapOf(Tier.DEFAULT to TierRoute(chain, metered)))
    private val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    // Fixed `model.routing_set` vector from client-core model_control.rs.
    private val canonicalPayload = """{"action":"model_routing_set","routing":{"version":1,"paid_api":"off","tiers":{"cheap":{"chain":[{"provider":"huggingface","model":"org/modèl"},{"provider":"claude-cli","model":"claude-haiku-4-5"}],"metered_after_subscription":false},"hard":{"chain":[{"provider":"claude-cli","model":"claude-opus-5"},{"provider":"anthropic-api","model":"claude-opus-5"}],"metered_after_subscription":true}}},"expected_routing_sha256":"$sha"}"""
    private val canonicalPayloadSha256 = "a9c73476991bb884fbaf43378882c25f4403a8ce3daa44d1012221ce15e30a59"
    private fun vectorRouting() = Routing(1, false, mapOf(
        Tier.CHEAP to TierRoute(listOf(e("huggingface", "org/modèl"), e("claude-cli", "claude-haiku-4-5"))),
        Tier.HARD to TierRoute(listOf(e("claude-cli", "claude-opus-5"), e("anthropic-api", "claude-opus-5")), true),
    ))
    private fun approval(routing: Routing = vectorRouting(), hash: String = sha, expires: Long = 121) = ModelRoutingApproval(
        UUID.fromString("01010101-0101-0101-0101-010101010101"), ByteArray(32) { 2 },
        UUID.fromString("03030303-0303-0303-0303-030303030303"), UUID.fromString("04040404-0404-0404-0404-040404040404"),
        1, expires, routing, hash,
    )
    private fun sha256(text: String) = Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    @Test fun canonicalPayloadMatchesCoreFixedVector() {
        assertEquals(canonicalPayload, approval().canonicalPayload())
        assertEquals(canonicalPayloadSha256, sha256(approval().canonicalPayload()))
        val message = Hex.encode(approval().message())
        val action = Hex.encode("model.routing_set".toByteArray())
        assertEquals(
            Hex.encode("jarvis-privileged-config-v1\u0000".toByteArray()) + "0011" + action + canonicalPayloadSha256 +
                "01".repeat(16) + "02".repeat(32) + "03".repeat(16) + "04".repeat(16) +
                "0000000000000001" + "0000000000000079" + sha,
            message,
        )
    }

    @Test fun parsingLooseJsonGivesTheSameCanonicalBytes() {
        // Key order, whitespace and a null tier in the input must not change the signed bytes.
        val loose = Json.parseToJsonElement("""{"tiers":{"hard":{"metered_after_subscription":true,"chain":[
            {"model":"claude-opus-5","provider":"claude-cli"},{"provider":"anthropic-api","model":"claude-opus-5"}]},
            "default":null,"cheap":{"chain":[{"provider":"huggingface","model":"org/modèl"},
            {"provider":"claude-cli","model":"claude-haiku-4-5"}]}},"paid_api":"off","version":1}""")
        assertEquals(vectorRouting(), parseRouting(loose))
        assertEquals(canonicalPayload, approval(parseRouting(loose)!!).canonicalPayload())
    }

    @Test fun researchIsWrittenLastAndOnlyWhenOn() {
        assertEquals("""{"version":1,"paid_api":"off","tiers":{},"research_web_search":"on"}""",
            Routing(paidApiAllowed = false, researchWebSearch = true).canonicalJson())
        assertEquals("""{"version":1,"paid_api":"allowed","tiers":{}}""", Routing().canonicalJson())
        assertTrue(approval(vectorRouting().copy(researchWebSearch = true)).canonicalPayload()
            .endsWith("""}},"research_web_search":"on"},"expected_routing_sha256":"$sha"}"""))
        assertEquals("""{"version":1,"paid_api":"allowed","tiers":{}}""",
            parseRouting(Json.parseToJsonElement("""{"version":1,"research_web_search":"off"}"""))!!.canonicalJson())
    }

    @Test fun stringsEscapeLikeSerdeJson() {
        val routing = tier(listOf(e("ollama", "a\"b\\c/é\u0001")))
        assertTrue(routing.canonicalJson().contains("\"model\":\"a\\\"b\\\\c/é\\u0001\""))
    }

    @Test fun changedFieldsChangeTheSignedMessageAndInvalidOnesAreRefused() {
        val original = approval().message()
        assertFalse(original.contentEquals(approval(hash = "00".repeat(32)).message()))
        assertFalse(original.contentEquals(approval(vectorRouting().copy(paidApiAllowed = true)).message()))
        assertFalse(original.contentEquals(approval(vectorRouting().copy(researchWebSearch = true)).message()))
        for (bad in listOf(approval(vectorRouting().copy(version = 2)), approval(hash = "zz"), approval(expires = 1), approval(expires = 302))) {
            assertThrows(IllegalArgumentException::class.java) { bad.message() }
        }
        assertEquals(240L, ROUTING_APPROVAL_SECONDS)
        assertTrue(ROUTING_APPROVAL_SECONDS > 120 + 60 && ROUTING_APPROVAL_SECONDS <= 300)
        assertNotNull(approval(expires = 1 + ROUTING_APPROVAL_SECONDS).message())
    }

    @Test fun parsingIsStrict() {
        for (bad in listOf("""{"version":1,"x":1}""", """{"paid_api":"off"}""", """{"version":1,"paid_api":"maybe"}""",
            """{"version":1,"tiers":{"extra":{}}}""", """{"version":1,"tiers":{"hard":{"chain":[],"x":1}}}""", """{"version":"1"}""", "[]")) {
            assertNull(bad, parseRouting(Json.parseToJsonElement(bad)))
        }
    }

    @Test fun validationMirrorsCoreRules() {
        assertNull(routingIssue(Routing(), emptyList()))
        for (ok in listOf(
            tier(listOf(e("claude-cli", "claude-opus-5"), e("anthropic-api", "claude-opus-5")), true),
            tier(listOf(e("anthropic-api", "claude-opus-5"), e("claude-cli", "claude-opus-5"))),
            tier(listOf(e("claude-cli", "claude-opus-5"), e("ollama", "llama3.2"))),
        )) assertNull(routingIssue(ok, discovered))
        val many = (0 until 10).map { e("ollama", "m$it") }
        assertNull(routingIssue(tier(many.take(9)), many))
        for (bad in listOf(
            tier(emptyList()), tier(many), tier(listOf(e("jev", "a"))), tier(listOf(e("Claude-CLI", "claude-opus-5"))),
            tier(listOf(e("ollama", ""))), tier(listOf(e("ollama", "m".repeat(257)))), tier(listOf(e("ollama", "a\nb"))),
            tier(listOf(e("ollama", "llama3.2"), e("ollama", "llama3.2"))),
            tier(listOf(e("claude-cli", "claude-opus-5"), e("anthropic-api", "claude-opus-5"))),
            tier(listOf(e("codex-cli", "gpt-6-luna"), e("openai-api", "gpt-6-luna"))),
            tier(listOf(e("claude-cli", "claude-opus-5"), e("ollama", "llama3.2"), e("huggingface", "org/modèl"))),
            tier(listOf(e("ollama", "not-discovered"))), Routing(version = 2),
        )) assertNotNull(bad.toString(), routingIssue(bad, discovered + many))
        assertFalse(meteredAfterSubscription(listOf(e("anthropic-api", "a"), e("claude-cli", "a"))))
        assertFalse(meteredAfterSubscription(listOf(e("claude-cli", "a"), e("ollama", "b"))))
        assertTrue(meteredAfterSubscription(listOf(e("claude-cli", "a"), e("ollama", "b"), e("zai-api", "c"))))
    }

    @Test fun chainHelpersKeepOrderAndOfferUnusedPairsEnabledFirst() {
        val chain = listOf(e("a", "1"), e("b", "2"), e("c", "3"))
        assertEquals(listOf("a", "c", "b"), moveEntry(chain, 2, -1).map { it.provider })
        assertSame(chain, moveEntry(chain, 0, -1))
        assertSame(chain, moveEntry(chain, 2, 1))
        val models = listOf(ModelEntry("ollama", "z", false), ModelEntry("claude-cli", "claude-opus-5", true),
            ModelEntry("jev", "x", true), ModelEntry("anthropic-api", "claude-opus-5", false))
        assertEquals(listOf("anthropic-api", "ollama"), candidates(models, listOf(e("claude-cli", "claude-opus-5"))).map { it.provider })
        assertEquals(listOf("claude-cli", "anthropic-api", "ollama"), candidates(models, emptyList()).map { it.provider })
    }

    private fun snapshot(mutation: String? = ROUTING_MUTATION, hash: String? = "ab", routing: String? = null, reason: String? = null) =
        ModelPolicySnapshot(emptyList(), "unavailable", null, "u", "d", 100, mutation, hash,
            routing?.let { Json.parseToJsonElement(it) }, reason)

    @Test fun routingModeDistinguishesOldCoreUnavailableAndEditable() {
        assertEquals(RoutingMode.UNSUPPORTED, routingMode(snapshot(mutation = null)))
        assertEquals(RoutingMode.READONLY, routingMode(snapshot(mutation = "unavailable")))
        assertEquals(RoutingMode.READONLY, routingMode(snapshot(hash = null)))
        assertEquals(RoutingMode.EDITABLE, routingMode(snapshot()))
    }

    @Test fun currentRoutingFailsClosed() {
        assertEquals(Routing(), currentRouting(snapshot()))
        assertEquals(false, currentRouting(snapshot(routing = """{"version":1,"paid_api":"off"}"""))!!.paidApiAllowed)
        assertNull(currentRouting(snapshot(routing = """{"version":1,"future":true}""")))
        assertNull(currentRouting(snapshot(reason = "routing_invalid")))
        assertNull(currentRouting(snapshot(routing = "null", reason = "routing_invalid")))
        assertEquals(Routing(), currentRouting(snapshot(routing = "null")))
    }

    @Test fun costRelaxingRoutingIsClassifiedForAFreshPrompt() {
        val sub = e("claude-cli", "claude-opus-5"); val paid = e("anthropic-api", "claude-opus-5"); val local = e("ollama", "llama3.2")
        fun routed(paidApi: Boolean, chain: List<RouteEntry>, fallback: Boolean) =
            Routing(1, paidApi, mapOf(Tier.HARD to TierRoute(chain, fallback)))
        val off = Routing(paidApiAllowed = false); val allowed = Routing()
        val withPaid = routed(true, listOf(sub, paid), true)
        // Relaxing: paid API back on, also from an unknown or failed-closed state.
        assertEquals(Relaxation(paidApi = true), relaxation(off, allowed))
        assertEquals(Relaxation(paidApi = true), relaxation(null, allowed))
        // Relaxing: paid fallback switched on, paid model added.
        assertEquals(Relaxation(paidFallback = true), relaxation(routed(true, listOf(paid, sub), false), withPaid))
        val onlySub = routed(true, listOf(sub), false)
        assertEquals(Relaxation(paidFallback = true, paidModels = true), relaxation(onlySub, routed(true, listOf(sub, local, paid), true)))
        assertEquals(Relaxation(paidModels = true), relaxation(allowed, routed(true, listOf(paid), false)))
        // Tightening or neutral: may reuse the window.
        for ((current, next) in listOf(
            withPaid to off, withPaid to onlySub, withPaid to withPaid, withPaid to routed(true, listOf(paid, sub), true),
            allowed to routed(true, listOf(sub, local), false),
        )) assertFalse(next.toString(), relaxation(current, next).any())
        assertFalse(relaxation(null, off).any())
        assertEquals("Jarvis: replace model routing; allow model changes for five minutes", Relaxation().prompt())
        val prompt = Relaxation(true, true, true).prompt()
        assertTrue(prompt.contains("allow paid APIs") && prompt.contains("allow paid fallback after subscription"))
        assertFalse(prompt.contains("five minutes"))
    }

    @Test fun resettingATierToTheBuiltInOrderNeedsAFreshPrompt() {
        val pinned = Routing(1, true, mapOf(Tier.HARD to TierRoute(listOf(e("claude-cli", "claude-opus-5")), false)))
        val builtIn = Routing()
        val relaxed = relaxation(pinned, builtIn)
        assertTrue(relaxed.paidFallback && relaxed.paidModels)
        assertFalse(relaxation(builtIn, builtIn).any())
        assertFalse(relaxation(pinned, Routing(paidApiAllowed = false)).paidFallback)
    }

    @Test fun switchingResearchWebSearchOnAlwaysNeedsAFreshPrompt() {
        fun research(paid: Boolean, on: Boolean) = Routing(paidApiAllowed = paid, researchWebSearch = on)
        val onOnly = Relaxation(researchWebSearch = true)
        assertEquals(onOnly, relaxation(research(false, false), research(false, true)))
        assertEquals(onOnly, relaxation(null, research(false, true)))
        assertTrue(relaxation(research(false, false), research(false, true)).prompt().endsWith("; allow research web search"))
        assertFalse(relaxation(research(false, true), research(false, true)).any())
        assertFalse(relaxation(research(false, true), research(false, false)).any())
    }

    @Test fun reasonsExplainFailClosedWithoutHidingUnknownCodes() {
        assertNull(routingReason(null))
        assertTrue(routingReason("routing_invalid")!!.contains("without paid APIs"))
        assertTrue(routingReason("routing_activation_unverified")!!.contains("Paid APIs stay off"))
        assertFalse(routingReason("routing_reload_required")!!.contains("without paid APIs"))
        assertTrue(routingReason("routing_new_code")!!.contains("routing_new_code"))
        assertTrue(routingRefused(409).contains("undiscovered") && routingRefused(503).contains("not verified"))
    }
}
