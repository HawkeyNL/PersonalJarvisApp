package com.hawkeynl.jarvis.network

import java.math.BigDecimal
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Memecoin PAPER strategies and Claude's decisions (desktop src/memeStrategies.ts): the read
// contract, the response guards and the disable call's outcome. Pure, so tests load it.
//
//   GET  /v1/trading/meme/strategies               -> { strategies: Strategy[] }
//   GET  /v1/trading/meme/strategies/{id}          -> StrategyDetail (raw shape below)
//   POST /v1/trading/meme/strategies/{id}/disable  -> { strategy_id, enabled: false }
//   GET  /v1/trading/meme/decisions?limit=         -> { decisions: Decision[] }
// Enabling needs a device signature from desktop's native signer; this app has no enable.

data class Performance(
    val trades: Long, val netUsd: String, val costsUsd: String, val expectancyUsd: String, val winRateBps: Long,
    val maxDrawdownUsd: String, val profitFactor: String?,
)

data class Strategy(
    val id: String, val family: String?, val activeVersion: Long, val enabled: Boolean,
    /** Only a device signature enables it again. */
    val disabledByOwner: Boolean, val changedAt: String, val changedBy: String, val performance: Performance,
)

/** JSON values as compact text; "—" when the field is absent. */
data class StrategyDiffRow(val field: String, val from: String, val to: String)

/** [status] "ok" carries the results; "unbacktested" and "failed" only the data counts. */
data class Backtest(
    val status: String, val mints: Long, val points: Long, val trades: Long? = null, val netUsd: String? = null,
    val expectancyUsd: String? = null, val winRateBps: Long? = null, val maxDrawdownUsd: String? = null, val profitFactor: String? = null,
)

data class StrategyVersion(
    val version: Long, val parentVersion: Long?, val byClaude: Boolean, val createdAt: String,
    /** Parameter fields as compact JSON text, without the identity fields. */
    val config: List<Pair<String, String>>?,
    /** Core's per-field diff to the parent; null without a parent. */
    val diff: List<StrategyDiffRow>?,
    /** Written by Claude; plain text, display only. */
    val rationale: String?, val backtest: Backtest?,
)

data class StrategyGate(
    val at: String, val closedTrades: Long, val minClosedTrades: Long, val durationDays: Long, val minDurationDays: Long,
    val tradingDays: Long, val minTradingDays: Long, val netResultUsd: String, val netExpectancyUsd: String,
)

data class StrategyDetail(val strategy: Strategy, val activeConfigSha256: String?, val versions: List<StrategyVersion>, val gate: StrategyGate?)

data class Decision(
    val key: String, val at: String, val callId: String, val kind: String, val strategyVersion: Long?, val mint: String?,
    val sizeBps: Long?, val reasonCode: String?,
    /** Written by Claude; plain text, display only. */
    val rationale: String?, val model: String?,
)

/** Core lists at most 6 strategies and 20 versions each. */
object StrategyLimits {
    const val STRATEGIES = 20
    const val VERSIONS = 20
    const val DIFF = 64
    const val CONFIG = 8192
    const val VALUE = 2048
    const val RATIONALE = 600
    const val DECISION = 300
}

// ---- guards -----------------------------------------------------------------

private val STRATEGY_ID = Regex("^[a-z0-9-]{1,32}$")
private val CODE = Regex("^[a-z][a-z0-9_]*$")
private val CALL_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
private val MODEL = Regex("^[A-Za-z0-9_.:-]{1,64}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")

/** Core's strategy id rule; also what reaches a URL path. */
fun isStrategyId(id: String): Boolean = STRATEGY_ID.matches(id)

private fun JsonObject.code(key: String, max: Int = 32): String = str(key, max).also { require(CODE.matches(it)) }
private fun JsonObject.version(key: String): Long = int(key).also { require(it in 1..0xffffffffL) }
private fun JsonObject.optVersion(key: String): Long? = if (has(key)) version(key) else null

/** Display text from Claude: bounded in code points. Shown through [plain]. */
private fun JsonObject.plainText(key: String, max: Int): String? =
    optStr(key, max * 2)?.also { require(it.codePointCount(0, it.length) <= max) }?.let(::plain)

/** Drops control and format characters (bidi overrides, zero-width, tag characters) so the
 *  text reads as what it holds; a character Core lets through never fails the whole record,
 *  which would also hide the Disable button. Nothing left reads as none. */
fun plain(text: String): String? =
    text.replace(Regex("[\\p{Cc}\\u2028\\u2029]"), " ").replace(Regex("\\p{Cf}"), "").ifEmpty { null }

/** A JSON value as compact text within [max]; absent is "—". */
private fun json(x: JsonElement?, max: Int): String = if (x == null) DASH else x.toString().also { require(it.length <= max) }

private fun parsePerformance(x: JsonElement?): Performance {
    val p = x.obj()
    return Performance(
        p.int("trades"), p.dec("net_usd"), p.dec("costs_usd"), p.dec("expectancy_usd"), p.int("win_rate_bps").also { require(it <= 10_000) },
        p.dec("max_drawdown_usd"), p.optDec("profit_factor"),
    )
}

private fun parseStrategy(o: JsonObject): Strategy = Strategy(
    o.str("id", 32).also { require(isStrategyId(it)) },
    o.optStr("family")?.also { require(it in FAMILY_LABEL) },
    o.version("active_version"), o.bool("enabled"), o.bool("disabled_by_owner"), o.time("changed_at"), o.str("changed_by", 64),
    parsePerformance(o["performance"]),
)

fun readStrategies(x: JsonElement): List<Strategy>? = guarded { x.obj().array("strategies", StrategyLimits.STRATEGIES).map { parseStrategy(it.obj()) } }

private fun parseBacktest(x: JsonElement?): Backtest? {
    if (x == null || x == JsonNull) return null
    val b = x.obj()
    return when (val status = b.optStr("status")) {
        "unbacktested", "failed" -> Backtest(status, b.int("mints"), b.int("points"))
        "ok" -> Backtest(
            status, b.int("mints"), b.int("points"), b.int("trades"), b.dec("net_usd"), b.dec("expectancy_usd"),
            b.int("win_rate_bps"), b.dec("max_drawdown_usd"), b.optDec("profit_factor"),
        )
        else -> throw IllegalArgumentException()
    }
}

/** Identity fields are shown in the version header, not as parameters. */
private val IDENTITY = setOf("id", "version", "parent", "family")

private fun parseVersion(x: JsonElement): StrategyVersion {
    val v = x.obj()
    val author = v.optStr("author").also { require(it == "seed" || it == "claude") }
    val config = if (!v.has("config")) null else v["config"].obj().also { json(it, StrategyLimits.CONFIG) }
        .filterKeys { it !in IDENTITY }.map { (k, value) -> k to value.toString() }
    val diff = if (!v.has("diff")) null else v.array("diff", StrategyLimits.DIFF).map {
        val d = it.obj()
        StrategyDiffRow(d.str("field", 64), json(d["from"], StrategyLimits.VALUE), json(d["to"], StrategyLimits.VALUE))
    }
    return StrategyVersion(
        v.version("version"), v.optVersion("parent_version"), author == "claude", v.time("created_at"), config, diff,
        v.plainText("rationale", StrategyLimits.RATIONALE), parseBacktest(v["backtest"]),
    )
}

/** One strategy; null also when Core answers for another id. */
fun readStrategy(x: JsonElement, id: String): StrategyDetail? = guarded {
    val o = x.obj()
    val strategy = parseStrategy(o).also { require(it.id == id) }
    val gate = if (!o.has("gate")) null else o["gate"].obj().let { g ->
        StrategyGate(
            g.time("at"), g.int("closed_trades"), g.int("min_closed_trades"), g.int("duration_days"), g.int("min_duration_days"),
            g.int("trading_days"), g.int("min_trading_days"), g.dec("net_result_usd"), g.dec("net_expectancy_usd"),
        )
    }
    StrategyDetail(
        strategy, o.optStr("active_config_sha256", 64)?.also { require(SHA256.matches(it)) },
        o.array("versions", StrategyLimits.VERSIONS).map(::parseVersion), gate,
    )
}

fun readDecisions(x: JsonElement): List<Decision>? = guarded {
    x.obj().array("decisions", 100).mapIndexed { i, e ->
        val d = e.obj()
        val id = if (!d.has("id")) "" else (d["id"] as? JsonPrimitive)?.let { p ->
            if (p.isString) p.content.takeIf { it.length <= 128 } else p.content.toLongOrNull()?.takeIf { it >= 0 }?.toString()
        } ?: throw IllegalArgumentException()
        if (d.has("input_tokens")) d.int("input_tokens")
        if (d.has("output_tokens")) d.int("output_tokens")
        val at = d.time("at")
        Decision(
            "$id-$at-$i", at, d.str("call_id", 64).also { require(CALL_ID.matches(it)) }, d.code("kind"), d.optVersion("strategy_version"),
            d.optMint("mint"), if (d.has("size_bps")) d.int("size_bps").also { require(it in 1..10_000) } else null,
            if (d.has("reason_code")) d.code("reason_code") else null, d.plainText("rationale", StrategyLimits.DECISION),
            d.optStr("model", 64)?.also { require(MODEL.matches(it)) },
        )
    }
}

// ---- display ------------------------------------------------------------------

val FAMILY_LABEL = mapOf("a" to "A · survivor momentum", "b" to "B · base reclaim", "c" to "C · established trend")

/** Strategy state, most restrictive first. */
fun strategyState(s: Strategy): Pair<String, MemeTone> = when {
    s.enabled -> "Enabled" to MemeTone.ACCENT
    s.disabledByOwner -> "Disabled by you" to MemeTone.NEGATIVE
    else -> "Disabled" to MemeTone.NEUTRAL
}

/** Basis points as a percentage: 4250 -> "42.5%". */
fun bpsPct(bps: Long): String = "${BigDecimal(bps).movePointLeft(2).stripTrailingZeros().toPlainString()}%"

private val KINDS = mapOf(
    "enter" to "Enter", "exit" to "Exit", "hold" to "Hold", "skip" to "Skipped", "propose" to "Proposal",
    "worker_unavailable" to "Worker busy", "invalid_output" to "Invalid reply",
)

private val REASONS = REJECT_REASONS + mapOf(
    "accepted" to "Accepted",
    "strategy_limit" to "Strategy limit reached",
    "strategy_disabled" to "Strategy disabled",
    "strategy_changed" to "Strategy changed",
    "no_slot" to "No free slot",
    "no_price" to "No price",
    "apply_deadline" to "Too late to apply",
    "book_unavailable" to "Paper ledger unavailable",
    "plan_limit" to "Plan limit",
    "malformed_reply" to "Malformed reply",
    "out_of_bounds" to "Outside the bounds",
    "loosening" to "Would loosen the filters",
    "parent_mismatch" to "Wrong parent version",
    "family_changed" to "Family changed",
    "unchanged" to "No change",
    "version_cap" to "Daily version limit",
    "strategy_cap" to "Strategy limit",
)

data class DecisionView(val kind: String, val reason: String, val muted: Boolean)

/** A decision row for display. `skip` / `call_started` only marks that a call began (it
 *  counts toward the budget), so it reads as "Claude called" and is de-emphasised. Unknown
 *  kinds and codes stay visible as their code. */
fun decisionView(d: Decision): DecisionView {
    if (d.kind == "skip" && d.reasonCode == "call_started") return DecisionView("Claude called", "", true)
    return DecisionView(KINDS[d.kind] ?: "Other (${d.kind})", d.reasonCode?.let { REASONS[it] ?: it }.orEmpty(), false)
}

/** Which strategy a version belongs to, when it is a strategy's active one. */
fun versionOwner(strategies: List<Strategy>, version: Long?): String? =
    version?.let { v -> strategies.firstOrNull { it.activeVersion == v }?.id }

// ---- disable ------------------------------------------------------------------

/** Word the disable's outcome; every message says when and whether it holds (audit event
 *  memecoin.strategy.disable). It succeeds only on Core's confirmation for that strategy. */
fun disableOutcome(id: String, result: ApiResult<JsonElement>, at: String): WriteOutcome = when (result) {
    is ApiResult.Success -> {
        val answer = result.value as? JsonObject
        if (answer?.get("strategy_id") == JsonPrimitive(id) && answer["enabled"] == JsonPrimitive(false)) WriteOutcome(true, at)
        else WriteOutcome(false, at, "Core answered at $at, but did not confirm the disable. Reload to check it.")
    }
    is ApiResult.Unreachable ->
        WriteOutcome(false, at, "No answer from the Home Node at $at. It is unknown whether the disable arrived; reload before trying again.")
    ApiResult.Unauthorized ->
        WriteOutcome(false, at, "Disable refused at $at (HTTP 401): your session is not valid, so nothing changed. Sign this device in again.")
    is ApiResult.HttpError -> when (result.status) {
        401 -> WriteOutcome(false, at, "Disable refused at $at (HTTP 401): your session is not valid, so nothing changed. Sign this device in again.")
        503 -> WriteOutcome(
            false, at,
            "Disable not saved at $at (HTTP 503): Core stopped this strategy's entries, but a restart would undo it. Disable again until it is confirmed.",
            503,
        )
        404 -> WriteOutcome(false, at, "Core has no strategy $id (HTTP 404), so nothing changed.")
        429 -> WriteOutcome(false, at, "Too many strategy changes at $at (HTTP 429). Wait a minute, then try again.")
        else -> WriteOutcome(false, at, "Disable failed at $at (HTTP ${result.status}). It is not confirmed; try again.")
    }
    is ApiResult.InvalidResponse -> WriteOutcome(false, at, "Disable could not be sent at $at. It is not confirmed; try again.")
}

const val NO_EDGE_TEXT =
    "Paper results are a filter, not proof. Even 100 closed trades cannot prove an edge: memecoin results hang on a few outliers, and paper fills are simulated."

const val ENABLE_ELSEWHERE = "Enable from your Mac or phone"
