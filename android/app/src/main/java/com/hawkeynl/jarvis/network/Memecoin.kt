package com.hawkeynl.jarvis.network

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Memecoin PAPER trader (desktop src/memecoin.ts): the read contract, response guards, status
// selection and the pause call's outcome. Pure, so tests load it directly. Formatters and
// decimal rules come from Trading.kt.
//
//   GET  /v1/trading/meme/overview          -> MemeOverview (raw shape below)
//   GET  /v1/trading/meme/candidates?limit= -> { candidates: Candidate[] }
//   GET  /v1/trading/meme/trades?limit=     -> { trades: ClosedTrade[] }
//   POST /v1/trading/meme/pause             -> { paused: true }
// It is a simulation: no wallet, no signer, no execution path. The pause only stops new paper
// entries; exits keep running. Resuming is configuration-only on the Home Node, so this app
// has no resume action.

data class OpenPosition(
    val mint: String, val strategyVersion: Long, val tokens: String, val costUsd: String,
    /** Executable value along the pool curve; null without a fresh mark. */
    val valueUsd: String?, val openedAt: String, val exitFailures: Long,
    /** Exits stopped after repeated failures; the position keeps its slot. */
    val stuck: Boolean,
)

data class Gate(
    val closedTrades: Long, val minClosedTrades: Long, val durationDays: Long, val minDurationDays: Long,
    val tradingDays: Long, val minTradingDays: Long, val netResultUsd: String, val netExpectancyUsd: String,
    val enoughTrades: Boolean, val enoughTime: Boolean, val enoughDays: Boolean, val positiveExpectancy: Boolean, val passed: Boolean,
)

/** All but [multipleTarget] null until there is progress to report. */
data class Goal(val multipleTarget: String, val multiple: String?, val fractionToGoal: String?, val remainingUsd: String?, val reached: Boolean?)

data class Pnl(val realisedUsd: String, val unrealisedUsd: String, val failedTxFeesUsd: String, val aiCostUsd: String, val netUsd: String)

/** The trader's report; Core sends it only once a tick has produced one. */
data class MemeReport(
    val strategyVersion: Long, val asOf: String, val stakeUsd: String, val equityUsd: String, val pnl: Pnl,
    val openPositions: List<OpenPosition>, val gate: Gate, val goal: Goal,
)

/** Claude's call budget; Core sends it only when the paper profile has an agent section. */
data class AgentBudget(
    val calling: Boolean, val callsLastHour: Long, val callsLastDay: Long, val maxCallsPerHour: Long, val maxCallsPerDay: Long,
    val lastCallAt: String?,
    /** Three invalid replies in a row pause decisions. */
    val pausedUntil: String?,
    /** A plan limit backs every call off. */
    val backoffUntil: String?,
)

data class MemeOverview(
    val enabled: Boolean, val running: Boolean, val paused: Boolean, val pausedAt: String?,
    /** A fixed reason from Core, never error detail. */
    val lastError: String?, val agent: AgentBudget?, val report: MemeReport?,
)

data class Candidate(
    val mint: String, val observedAt: String, val strategyVersion: Long, val accepted: Boolean, val reason: String?,
    val priceUsd: String?, val liquidityUsd: String?,
)

data class ClosedTrade(
    val mint: String, val strategyVersion: Long, val tokens: String, val costUsd: String, val openedAt: String, val closedAt: String,
    val usdOut: String, val exitFeesUsd: String, val netPnlUsd: String, val exitReason: String,
)

/** Rows asked for per list; Core allows 1..100. */
const val MEME_LIST_LIMIT = 50

object MemeLimits {
    const val POSITIONS = 100
    const val LIST = 100
    const val TEXT = 200
    const val MINT = 64
}

// ---- response guards ----------------------------------------------------
// Fail closed: anything that is not exactly Core's shape, or claims a mode other than
// "paper", is an error state, never a partly drawn screen.

private val MINT = Regex("^[1-9A-HJ-NP-Za-km-z]+$")

internal fun JsonObject.has(key: String) = this[key] != null && this[key] != JsonNull
internal fun JsonObject.time(key: String): String = str(key, 64).also { require(isoMillis(it) != null) }
internal fun JsonObject.optTime(key: String): String? = optStr(key, 64)?.also { require(isoMillis(it) != null) }
internal fun JsonObject.dec(key: String): String = str(key, 64).also { require(decimalSign(it) != null) }
internal fun JsonObject.optDec(key: String): String? = optStr(key, 64)?.also { require(decimalSign(it) != null) }
internal fun JsonObject.mint(key: String): String = str(key, MemeLimits.MINT).also { require(MINT.matches(it)) }
internal fun JsonObject.optMint(key: String): String? = optStr(key, MemeLimits.MINT)?.also { require(MINT.matches(it)) }

private fun parsePosition(x: JsonElement): OpenPosition {
    val p = x.obj()
    return OpenPosition(p.mint("mint"), p.int("strategy_version"), p.dec("tokens"), p.dec("cost_usd"), p.optDec("value_usd"), p.time("opened_at"), p.int("exit_failures"), p.bool("stuck"))
}

private fun parseGate(x: JsonElement?): Gate {
    val g = x.obj()
    return Gate(
        g.int("closed_trades"), g.int("min_closed_trades"), g.int("duration_days"), g.int("min_duration_days"),
        g.int("trading_days"), g.int("min_trading_days"), g.dec("net_result_usd"), g.dec("net_expectancy_usd"),
        g.bool("enough_trades"), g.bool("enough_time"), g.bool("enough_days"), g.bool("positive_expectancy"), g.bool("passed"),
    )
}

private fun parseAgent(x: JsonElement?): AgentBudget? {
    if (x == null || x == JsonNull) return null
    val a = x.obj()
    return AgentBudget(
        a.bool("calling"), a.int("calls_last_hour"), a.int("calls_last_day"), a.int("max_calls_per_hour"), a.int("max_calls_per_day"),
        a.optTime("last_call_at"), a.optTime("paused_until"), a.optTime("backoff_until"),
    )
}

fun readOverview(x: JsonElement): MemeOverview? = guarded {
    val o = x.obj()
    require(o.optStr("mode") == "paper")
    val status = MemeOverview(
        o.bool("enabled"), o.bool("running"), o.bool("paused"), o.optTime("paused_at"), o.optStr("last_error", MemeLimits.TEXT),
        parseAgent(o["agent"]), null,
    )
    // Core adds the report fields together, or none of them.
    if ("as_of" !in o && "strategy_version" !in o) return@guarded status
    val pnl = o["pnl"].obj()
    val goal = o["goal"].obj()
    status.copy(
        report = MemeReport(
            o.int("strategy_version"), o.time("as_of"), o.dec("stake_usd"), o.dec("equity_usd"),
            Pnl(pnl.dec("realised_usd"), pnl.dec("unrealised_usd"), pnl.dec("failed_tx_fees_usd"), pnl.dec("ai_cost_usd"), pnl.dec("net_usd")),
            o.array("open_positions", MemeLimits.POSITIONS).map(::parsePosition), parseGate(o["gate"]),
            Goal(goal.dec("multiple_target"), goal.optDec("multiple"), goal.optDec("fraction_to_goal"), goal.optDec("remaining_usd"),
                if (goal.has("reached")) goal.bool("reached") else null),
        ),
    )
}

fun readCandidates(x: JsonElement): List<Candidate>? = guarded {
    x.obj().array("candidates", MemeLimits.LIST).map {
        val c = it.obj()
        val accepted = when (c.optStr("verdict")) {
            "accept" -> true.also { require(!c.has("reason")) }
            "reject" -> false
            else -> throw IllegalArgumentException()
        }
        Candidate(
            c.mint("mint"), c.time("observed_at"), c.int("strategy_version"), accepted, if (accepted) null else c.str("reason", 64),
            c.optDec("price_usd"), c.optDec("liquidity_usd"),
        )
    }
}

fun readTrades(x: JsonElement): List<ClosedTrade>? = guarded {
    x.obj().array("trades", MemeLimits.LIST).map {
        val t = it.obj()
        ClosedTrade(
            t.mint("mint"), t.int("strategy_version"), t.dec("tokens"), t.dec("cost_usd"), t.time("opened_at"), t.time("closed_at"),
            t.dec("usd_out"), t.dec("exit_fees_usd"), t.dec("net_pnl_usd"), t.str("exit_reason", 64),
        )
    }
}

// ---- status -------------------------------------------------------------

enum class MemeTone { NEUTRAL, ACCENT, NEGATIVE }

enum class MemeStatusKind { DISABLED, STOPPED, PAUSED, HALTED, STARTING, RUNNING }
data class MemeStatus(val kind: MemeStatusKind, val label: String, val tone: MemeTone, val detail: String)

/** One honest headline for the trader, most restrictive first: off, not running, paused, last
 *  tick stopped, no report yet, running. */
fun memeStatus(o: MemeOverview): MemeStatus = when {
    !o.enabled -> MemeStatus(
        MemeStatusKind.DISABLED, "Off", MemeTone.NEUTRAL,
        "Paper trading is off, which is the default. Only the owner can turn it on, in Core's configuration on the Home Node; this app cannot.",
    )
    !o.running -> MemeStatus(
        MemeStatusKind.STOPPED, "Not running", MemeTone.NEGATIVE,
        o.lastError?.let { "$it." } ?: "Paper trading is turned on, but the trader is not running. The Core log says why.",
    )
    // A failing tick also stops exits, so do not claim they run.
    o.paused -> MemeStatus(
        MemeStatusKind.PAUSED, "Paused", MemeTone.NEGATIVE,
        o.lastError?.let {
            "No new paper entries. The last tick also stopped: $it. Open paper positions may not be valued or exited until it recovers; the Core log has the details."
        } ?: "No new paper entries. Open paper positions are still valued and exited.",
    )
    o.lastError != null -> MemeStatus(
        MemeStatusKind.HALTED, "Tick stopped", MemeTone.NEGATIVE,
        "The last tick stopped: ${o.lastError}. Core tries again every minute; the Core log has the details.",
    )
    o.report == null -> MemeStatus(
        MemeStatusKind.STARTING, "Starting", MemeTone.NEUTRAL, "The trader is running but has not reported yet. A tick runs every minute.",
    )
    else -> MemeStatus(
        MemeStatusKind.RUNNING, "Running · paper", MemeTone.ACCENT, "Simulating on read-only market data. Exits and entries run every minute.",
    )
}

enum class AgentStatusKind { OFF, STOPPED, PAUSED, BACKOFF, CALLING, ACTIVE }
data class AgentStatus(val kind: AgentStatusKind, val label: String, val tone: MemeTone, val detail: String, val until: String?)

private fun later(t: String?, now: Long) = t != null && (isoMillis(t) ?: Long.MIN_VALUE) > now

/** Claude's state as the strategy agent, most restrictive first: off, stopped by the trader's
 *  state, paused after invalid replies, backing off after a plan limit, calling, active. Code
 *  exits run in every state. */
fun agentStatus(o: MemeOverview, now: Long): AgentStatus {
    val a = o.agent ?: return AgentStatus(
        AgentStatusKind.OFF, "Off", MemeTone.NEUTRAL,
        "Claude does not decide. Each enabled strategy enters on its own signal. The paper profile's agent section on the Home Node turns Claude on.", null,
    )
    val exits = "Code exits (stop loss, trailing stop, take profit, liquidity drop, time stop) keep running."
    if (!o.enabled || !o.running || o.paused) {
        return AgentStatus(
            AgentStatusKind.STOPPED, "Not deciding", MemeTone.NEUTRAL,
            "The trader is ${if (o.paused) "paused" else "not running"}, so Claude makes no new calls. $exits", null,
        )
    }
    if (later(a.pausedUntil, now)) {
        return AgentStatus(AgentStatusKind.PAUSED, "Paused", MemeTone.NEGATIVE, "Three invalid replies in a row paused Claude's decisions. $exits", a.pausedUntil)
    }
    if (later(a.backoffUntil, now)) {
        return AgentStatus(AgentStatusKind.BACKOFF, "Backing off", MemeTone.NEGATIVE, "A plan limit was hit, so calls wait. $exits", a.backoffUntil)
    }
    val budget = "${a.callsLastHour} of ${a.maxCallsPerHour} calls this hour, ${a.callsLastDay} of ${a.maxCallsPerDay} in the last day."
    if (a.calling) return AgentStatus(AgentStatusKind.CALLING, "Deciding now", MemeTone.ACCENT, "A call is running. $budget", null)
    return AgentStatus(AgentStatusKind.ACTIVE, "Active", MemeTone.ACCENT, "Claude decides paper entries and early exits, at most every 15 minutes. $budget", null)
}

/** How old the report is when that is past the stale limit; else null. */
fun reportAge(o: MemeOverview, now: Long): Long? {
    val report = o.report ?: return null
    val age = isoMillis(report.asOf)?.let { now - it } ?: Long.MAX_VALUE
    return age.takeIf { it > STALE_AFTER_MS }
}

fun stuckCount(o: MemeOverview): Int = o.report?.openPositions?.count { it.stuck } ?: 0

const val RESUME_TEXT =
    "Resuming is configuration-only, on the Home Node: copy the paused-at time into JARVIS_MEMECOIN_PAPER_RESUME_PAUSED_AT in core.env and restart Core. This app cannot resume."

// ---- read failures ------------------------------------------------------

enum class MemeFailure { NO_API, UNREACHABLE, SIGNIN, UNAVAILABLE, FAILED }

fun memeFailure(result: ApiResult<*>): MemeFailure = when {
    result is ApiResult.Unreachable -> MemeFailure.UNREACHABLE
    result == ApiResult.Unauthorized -> MemeFailure.SIGNIN
    result is ApiResult.HttpError -> when (result.status) {
        404, 405, 501 -> MemeFailure.NO_API
        401 -> MemeFailure.SIGNIN
        503 -> MemeFailure.UNAVAILABLE
        else -> MemeFailure.FAILED
    }
    else -> MemeFailure.FAILED
}

fun memeFailureText(f: MemeFailure): String = when (f) {
    MemeFailure.NO_API -> "This Core has no memecoin paper trader yet. Update Core on your Home Node."
    MemeFailure.UNREACHABLE -> "No answer from your Home Node. Check the connection."
    MemeFailure.SIGNIN -> "Your session is not valid. Sign this device in again."
    MemeFailure.UNAVAILABLE -> "Core could not read the paper trader's data just now. Try again in a minute."
    MemeFailure.FAILED -> "Core returned an answer this app does not understand, or an error. Try again later."
}

// ---- pause --------------------------------------------------------------

/** The outcome of a pause or a disable: when, and whether it holds. [status] 503 means Core
 *  acted in memory but did not save it. */
data class WriteOutcome(val ok: Boolean, val at: String, val message: String? = null, val status: Int? = null)

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

/** "10:42:18 UTC", to match Core's audit log. */
fun clock(millis: Long): String = "${CLOCK.format(Instant.ofEpochMilli(millis))} UTC"

/** Word the pause's outcome for the owner. Every message says when, what Core answered and
 *  whether the pause is in effect, so it can be matched with Core's audit log (event
 *  memecoin.paper.pause). It succeeds only on Core's confirmation. */
fun pauseOutcome(result: ApiResult<JsonElement>, at: String): WriteOutcome = when (result) {
    is ApiResult.Success ->
        if ((result.value as? JsonObject)?.get("paused") == JsonPrimitive(true)) WriteOutcome(true, at)
        else WriteOutcome(false, at, "Core answered at $at, but did not confirm the pause. Reload the status to check it.")
    is ApiResult.Unreachable ->
        WriteOutcome(false, at, "No answer from the Home Node at $at. It is unknown whether the pause arrived; reload the status before trying again.")
    ApiResult.Unauthorized -> WriteOutcome(false, at, signinRefused("Pause", at))
    is ApiResult.HttpError -> when (result.status) {
        401 -> WriteOutcome(false, at, signinRefused("Pause", at))
        503 -> WriteOutcome(
            false, at,
            "Pause not saved at $at (HTTP 503): Core stopped new entries, but could not store the pause, so a restart would undo it. Pause again until it is confirmed.",
            503,
        )
        404, 405, 501 -> WriteOutcome(false, at, "Pause not available at $at (HTTP ${result.status}): this Core has no memecoin pause, so nothing changed.")
        else -> WriteOutcome(false, at, "Pause failed at $at (HTTP ${result.status}). The pause is not confirmed; try again.")
    }
    is ApiResult.InvalidResponse -> WriteOutcome(false, at, "Pause could not be sent at $at. The pause is not confirmed; try again.")
}

/** A pause or disable whose answer never came back to this page: the sign-in changed, the app
 *  locked, or the page was left while it ran. It may have landed. */
fun unconfirmedOutcome(at: String): WriteOutcome = WriteOutcome(false, at, "Sent at $at but not confirmed; reload to check.")

private fun signinRefused(what: String, at: String) =
    "$what refused at $at (HTTP 401): your session is not valid, so nothing changed. Sign this device in again, then ${what.lowercase()} again."

// ---- display --------------------------------------------------------------

/** "7xKX…gAsU": a mint is long and unreadable. */
fun shortMint(mint: String): String = if (mint.length > 12) "${mint.take(4)}…${mint.takeLast(4)}" else mint

/** Dollars; amounts below a cent keep their digits so a fee never reads $0.00. */
fun usd(value: String?, sign: Boolean = false): String {
    val n = chartNumber(value)
    val frac = value?.substringAfter('.', "")?.trimEnd('0')?.length ?: 0
    val tiny = n != null && n != 0.0 && kotlin.math.abs(n) < 0.01
    return formatMoney(value, "USD", sign = sign, places = if (tiny) minOf(8, maxOf(2, frac)) else 2)
}

/** Share of a minimum reached, 0..100, for a meter. */
fun progressPct(have: Long, need: Long): Double = if (need <= 0) 100.0 else (have * 100.0 / need).coerceIn(0.0, 100.0)

/** fraction_to_goal (0..1 as a decimal string) as 0..100; null when unknown. */
fun goalPct(fraction: String?): Double? = chartNumber(fraction)?.let { (it * 100).coerceIn(0.0, 100.0) }

val REJECT_REASONS = mapOf(
    "data_unavailable" to "Market data unavailable",
    "stale_snapshot" to "Market data too old",
    "invalid_snapshot" to "Market data inconsistent",
    "denied" to "On the deny list",
    "mint_authority_active" to "Mint authority still active",
    "freeze_authority_active" to "Freeze authority still active",
    "unknown_extension" to "Unknown token extension",
    "extension_not_allowed" to "Token extension not allowed",
    "extension_authority_active" to "Extension authority active",
    "lp_not_secured" to "Liquidity not locked or burned",
    "lp_unlocks_too_soon" to "Liquidity unlocks too soon",
    "liquidity_too_low" to "Liquidity too low",
    "pool_too_young" to "Pool too young",
    "holder_concentration_too_high" to "Top holders own too much",
    "no_sell_route" to "No route to sell",
    "sell_impact_too_high" to "Selling moves the price too much",
)

val EXIT_REASONS = mapOf(
    "liquidity_drop" to "Liquidity dropped",
    "stop_loss" to "Stop loss",
    "take_profit" to "Take profit",
    "time_stop" to "Time stop",
    "trailing_stop" to "Trailing stop",
    "agent_exit" to "Exited by Claude",
)

/** A known code's label; an unknown one is shown as its code, marked as such. */
fun reasonLabel(labels: Map<String, String>, code: String?): String = if (code == null) DASH else labels[code] ?: "Other ($code)"
