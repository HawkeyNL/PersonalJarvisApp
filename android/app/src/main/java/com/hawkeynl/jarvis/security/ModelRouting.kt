package com.hawkeynl.jarvis.security

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Owner routing document, helpers and signing bytes. Mirrors desktop
 *  `modelRouting.ts` / `model_control.rs` and client-core `model_control.rs`. */
enum class Tier(val id: String, val label: String, val blurb: String) {
    CHEAP("cheap", "Cheap", "Quick, light requests"),
    DEFAULT("default", "Default", "Most requests"),
    HARD("hard", "Hard", "Complex or deep work"),
}

data class RouteEntry(val provider: String, val model: String) {
    val label get() = "$provider/$model"
}

data class TierRoute(val chain: List<RouteEntry>, val meteredAfterSubscription: Boolean = false)

/** An absent tier is the built-in order. Equality is canonical equality. */
data class Routing(
    val version: Int = 1,
    val paidApiAllowed: Boolean = true,
    val tiers: Map<Tier, TierRoute> = emptyMap(),
    val researchWebSearch: Boolean = false,
)

const val ROUTING_MUTATION = "device-signed-model-route-v1"
const val MAX_CHAIN = 9
/** Covers the OS prompt (up to 120 s) and the post, within Core's 300 s maximum. */
const val ROUTING_APPROVAL_SECONDS = 240L
/** Broker frame limit; the broker counts the trailing newline. */
const val BROKER_FRAME_BYTES = 16 * 1024

/** Providers a routed chain may name (Core `ROUTING_PROVIDERS`). */
val ROUTING_PROVIDERS = listOf(
    "anthropic-api", "openai-api", "deepseek-api", "xai-api", "zai-api",
    "ollama", "ollama-cloud", "huggingface", "claude-cli", "codex-cli",
)

fun isSubscription(provider: String) = provider == "claude-cli" || provider == "codex-cli"
/** Classified by provider id only, like Core's validator. */
fun isMetered(provider: String) = provider !in setOf("ollama", "claude-cli", "codex-cli")

enum class RoutingMode { EDITABLE, READONLY, UNSUPPORTED }

/** UNSUPPORTED: Core predates signed routing. READONLY: Core cannot apply a signed route now. */
fun routingMode(policy: ModelPolicySnapshot): RoutingMode = when {
    policy.routing_mutation == null -> RoutingMode.UNSUPPORTED
    policy.routing_mutation == ROUTING_MUTATION && !policy.routing_sha256.isNullOrEmpty() -> RoutingMode.EDITABLE
    else -> RoutingMode.READONLY
}

// ---- parsing (strict, like serde deny_unknown_fields) -------------------------------------

private fun JsonObject.only(vararg keys: String): JsonObject {
    require(keys.toSet().containsAll(this.keys)) { "unknown field" }
    return this
}

/** Null when the shape is not exactly what this app understands (fail closed). */
fun parseRouting(raw: JsonElement): Routing? = try {
    val doc = (raw as JsonObject).only("version", "paid_api", "tiers", "research_web_search")
    val version = (doc["version"] as JsonPrimitive).also { require(!it.isString) }.int
    val paid = when (optString(doc, "paid_api")) {
        null, "allowed" -> true
        "off" -> false
        else -> error("paid_api")
    }
    val research = when (optString(doc, "research_web_search")) {
        null, "off" -> false
        "on" -> true
        else -> error("research_web_search")
    }
    val tiers = mutableMapOf<Tier, TierRoute>()
    val tiersObject = doc["tiers"]?.takeIf { it !is JsonNull }?.let { (it as JsonObject).only(*Tier.entries.map { t -> t.id }.toTypedArray()) }
    for (tier in Tier.entries) {
        val route = tiersObject?.get(tier.id)?.takeIf { it !is JsonNull } ?: continue
        val body = (route as JsonObject).only("chain", "metered_after_subscription")
        val chain = (body["chain"] as JsonArray).map { entry ->
            val e = (entry as JsonObject).only("provider", "model")
            RouteEntry(stringField(e, "provider"), stringField(e, "model"))
        }
        val metered = body["metered_after_subscription"]?.takeIf { it !is JsonNull }?.let {
            (it as JsonPrimitive).also { p -> require(!p.isString) }.boolean
        } ?: false
        tiers[tier] = TierRoute(chain, metered)
    }
    Routing(version, paid, tiers, research)
} catch (_: Exception) { null }

/** Absent or null is null; anything else must be a string. */
private fun optString(obj: JsonObject, name: String): String? =
    obj[name]?.takeIf { it !is JsonNull }?.let { (it as JsonPrimitive).also { p -> require(p.isString) }.content }

private fun stringField(obj: JsonObject, name: String): String =
    (obj[name] as JsonPrimitive).also { require(it.isString) }.content

/** The routing Core reports, for display and editing. No routing file means
 *  built-in order with paid APIs allowed. Null when the shape is not understood. */
fun reportedRouting(policy: ModelPolicySnapshot): Routing? {
    val raw = policy.routing
    return if (raw == null || raw is JsonNull) Routing() else parseRouting(raw)
}

/** The routing Core currently runs. Null when it cannot be known or Core failed
 *  closed (paid APIs off): every paid permission then counts as new. */
fun currentRouting(policy: ModelPolicySnapshot): Routing? =
    if (policy.routing_unavailable_reason != null) null else reportedRouting(policy)

// ---- canonical bytes ----------------------------------------------------------------------

/** serde_json string escaping: only `"`, `\`, and control characters are escaped;
 *  `/` and non-ASCII are written as UTF-8. */
private fun StringBuilder.jsonString(value: String): StringBuilder {
    append('"')
    for (c in value) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\b' -> append("\\b")
        c == '\u000C' -> append("\\f")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u00").append("%02x".format(c.code))
        else -> append(c)
    }
    return append('"')
}

/** Core's canonical routing JSON: fixed field order, absent tiers omitted,
 *  `paid_api` and `metered_after_subscription` always written, research last and only when on. */
fun Routing.canonicalJson(): String = buildString {
    append("{\"version\":").append(version).append(",\"paid_api\":")
    jsonString(if (paidApiAllowed) "allowed" else "off").append(",\"tiers\":{")
    var first = true
    for (tier in Tier.entries) {
        val route = tiers[tier] ?: continue
        if (!first) append(',')
        first = false
        jsonString(tier.id).append(":{\"chain\":[")
        route.chain.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("{\"provider\":"); jsonString(e.provider); append(",\"model\":"); jsonString(e.model); append('}')
        }
        append("],\"metered_after_subscription\":").append(route.meteredAfterSubscription).append('}')
    }
    append('}')
    if (researchWebSearch) append(",\"research_web_search\":\"on\"")
    append('}')
}

// ---- validation ---------------------------------------------------------------------------

fun meteredAfterSubscription(chain: List<RouteEntry>): Boolean {
    var afterSubscription = false
    for (e in chain) {
        if (afterSubscription && isMetered(e.provider)) return true
        afterSubscription = afterSubscription || isSubscription(e.provider)
    }
    return false
}

/** First reason Core would refuse this document, or null. */
fun routingIssue(routing: Routing, discovered: List<RouteEntry>): String? {
    if (routing.version != 1) return "Unsupported routing version"
    val known = discovered.toSet()
    for (tier in Tier.entries) {
        val route = routing.tiers[tier] ?: continue
        if (route.chain.size !in 1..MAX_CHAIN) return "${tier.id}: a routed tier needs 1 to $MAX_CHAIN models"
        val seen = mutableSetOf<RouteEntry>()
        for (e in route.chain) {
            if (e.provider !in ROUTING_PROVIDERS) return "${tier.id}: unknown provider ${e.provider}"
            if (e.model.isEmpty() || e.model.codePointCount(0, e.model.length) > 256 || e.model.any { it.isISOControl() }) {
                return "${tier.id}: invalid model name"
            }
            if (!seen.add(e)) return "${tier.id}: ${e.label} is listed twice"
            if (e !in known) return "${tier.id}: ${e.label} is not discovered on the Home Node"
        }
        if (meteredAfterSubscription(route.chain) && !route.meteredAfterSubscription) {
            return "${tier.id}: a paid API after a subscription needs explicit approval"
        }
    }
    return null
}

/** Discovered routable pairs not yet in `chain`, enabled first. */
fun candidates(models: List<ModelEntry>, chain: List<RouteEntry>): List<ModelEntry> {
    val used = chain.toSet()
    return models.filter { it.provider in ROUTING_PROVIDERS && RouteEntry(it.provider, it.model) !in used }
        .sortedWith(compareByDescending<ModelEntry> { it.enabled }.thenBy { it.provider }.thenBy { it.model })
}

/** `chain` with entry `index` moved by `delta`; unchanged when out of range. */
fun moveEntry(chain: List<RouteEntry>, index: Int, delta: Int): List<RouteEntry> {
    val target = index + delta
    if (index !in chain.indices || target !in chain.indices) return chain
    return chain.toMutableList().also { java.util.Collections.swap(it, index, target) }
}

private const val FAIL_CLOSED = " Until it is fixed, Core uses the built-in order without paid APIs."
private val REASONS = mapOf(
    "routing_invalid" to "The routing file on the Home Node is invalid.$FAIL_CLOSED",
    "routing_unsafe" to "The routing file on the Home Node has unsafe ownership or permissions.$FAIL_CLOSED",
    "routing_unreadable" to "Core cannot read the routing file.$FAIL_CLOSED",
    "routing_too_large" to "The routing file on the Home Node is too large.$FAIL_CLOSED",
    "routing_unavailable" to "Core cannot use the routing file.$FAIL_CLOSED",
    "routing_activation_unverified" to "A signed routing change could not be verified. Paid APIs stay off until the owner verifies routing and restarts Core.",
    "routing_reload_required" to "The routing file on disk differs from what Core runs; restart Core to apply it.",
)
/** Owner-facing copy for `routing_unavailable_reason`; unknown codes stay visible. */
fun routingReason(code: String?): String? =
    if (code.isNullOrEmpty()) null else REASONS[code] ?: "Routing unavailable ($code).$FAIL_CLOSED"

fun routingRefused(status: Int) = when (status) {
    409 -> "Routing changed or names an undiscovered model; refresh and try again"
    503 -> "Routing not applied or not verified; refresh to see the current state"
    else -> "Routing change refused; refresh and try again"
}

// ---- cost relaxation ----------------------------------------------------------------------

/** Ways a routing change lets Jarvis spend more than the routing it replaces. */
data class Relaxation(
    val paidApi: Boolean = false, val paidFallback: Boolean = false,
    val paidModels: Boolean = false, val researchWebSearch: Boolean = false,
) {
    fun any() = paidApi || paidFallback || paidModels || researchWebSearch

    fun prompt(): String = buildString {
        append("Jarvis: replace model routing")
        if (!this@Relaxation.any()) append("; allow model changes for five minutes")
        if (paidApi) append("; allow paid APIs")
        if (paidFallback) append("; allow paid fallback after subscription")
        if (paidModels) append("; add paid API models")
        if (researchWebSearch) append("; allow research web search")
    }
}

/** `current` null means unknown or failed closed: every paid permission is new. */
fun relaxation(current: Routing?, next: Routing): Relaxation {
    // Web search sends research questions to a provider tool whatever the paid API setting.
    val research = next.researchWebSearch && current?.researchWebSearch != true
    if (!next.paidApiAllowed) return Relaxation(researchWebSearch = research)
    var fallback = false
    var models = false
    for (tier in Tier.entries) {
        val route = next.tiers[tier] ?: continue
        // A built-in (absent) tier counts as no paid fallback and no pinned paid models.
        val before = current?.tiers?.get(tier)
        if (route.meteredAfterSubscription && before?.meteredAfterSubscription != true) fallback = true
        if (route.chain.any { isMetered(it.provider) && before?.chain?.contains(it) != true }) models = true
    }
    return Relaxation(current == null || !current.paidApiAllowed, fallback, models, research)
}

// ---- approval -----------------------------------------------------------------------------

/** Exact v1 client-core `ModelRoutingApproval`, never an arbitrary signing interface. */
data class ModelRoutingApproval(
    val request: UUID, val nonce: ByteArray, val user: UUID, val device: UUID,
    val issued: Long, val expires: Long, val routing: Routing, val hash: String,
) {
    /** Canonical payload: compact JSON of the operation, in client-core field order. */
    fun canonicalPayload(): String {
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        return buildString {
            append("{\"action\":\"model_routing_set\",\"routing\":").append(routing.canonicalJson())
            append(",\"expected_routing_sha256\":\"").append(hash).append("\"}")
        }
    }

    fun operation(): JsonObject = Json.parseToJsonElement(canonicalPayload()).jsonObject

    fun message(): ByteArray {
        require(routing.version == 1)
        return approvalMessage(
            "model.routing_set", MessageDigest.getInstance("SHA-256").digest(canonicalPayload().toByteArray(Charsets.UTF_8)),
            request, nonce, user, device, issued, expires, Hex.decode(hash),
        )
    }

    fun signed(signature: String): JsonObject {
        message()
        require(signature.matches(Regex("[0-9a-fA-F]{128}")))
        return buildJsonObject {
            put("request_id", request.toString()); put("nonce_hex", Hex.encode(nonce))
            put("user_id", user.toString()); put("device_id", device.toString())
            put("issued_at", Instant.ofEpochSecond(issued).toString())
            put("expires_at", Instant.ofEpochSecond(expires).toString())
            put("operation", operation()); put("signature_hex", signature)
        }
    }
}
