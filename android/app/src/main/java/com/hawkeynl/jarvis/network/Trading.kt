package com.hawkeynl.jarvis.network

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Trading reads (desktop src/trading.ts): Core's IBKR contract (read-only), the decimal-string
// formatters, the screen states and the response guards; plus the manual holdings
// (/v1/holdings, desktop src/portfolio.ts). Pure, so tests load it directly.
//
//   GET /v1/trading/portfolio                               -> Portfolio
//   GET /v1/trading/portfolio/history?range=<HistoryRange>  -> PortfolioHistory
//   GET /v1/trading/instruments/{conid}/bars?range=<BarRange> -> InstrumentBars
// Money, prices and quantities are decimal strings ("-1234.5600"), never JSON numbers; they
// become numbers only to draw a chart (chartNumber).

/** Where the numbers come from and how old they are; on every response. [delayMinutes]
 *  null is unknown, shown as "delay unknown", never as real time. */
data class Freshness(val source: String, val delayMinutes: Double?, val asOf: String)

data class Position(
    val conid: Long, val symbol: String, val name: String?, val assetClass: String, val exchange: String?, val currency: String,
    val quantity: String, val avgCost: String, val lastPrice: String, val marketValue: String,
    val dayPnl: String?, val dayChangePct: String?, val unrealizedPnl: String?, val weightPct: String?,
)

data class AllocationTarget(val label: String, val currentPct: String, val targetPct: String)
data class MonthlyDeposit(val amount: String, val dayOfMonth: Long, val nextDate: String)
data class Dividend(val conid: Long, val symbol: String, val exDate: String, val payDate: String?, val amount: String?, val currency: String)
data class SkippedPosition(val symbol: String, val currency: String?)

data class Portfolio(
    val fresh: Freshness, val baseCurrency: String, val netLiquidation: String, val cash: String,
    val dayPnl: String?, val dayChangePct: String?, val totalPnl: String?, val totalReturnPct: String?, val dividends12m: String?,
    val positions: List<Position>, val allocation: List<AllocationTarget>?, val monthlyDeposit: MonthlyDeposit?,
    val upcomingDividends: List<Dividend>?, val skippedCount: Long, val skippedPositions: List<SkippedPosition>,
)

val HISTORY_RANGES = listOf("1M", "3M", "1Y", "ALL")
val BAR_RANGES = listOf("1D", "1W", "1M", "3M", "1Y", "5Y")

data class HistoryPoint(val t: String, val value: String)
data class PortfolioHistory(val fresh: Freshness, val baseCurrency: String, val points: List<HistoryPoint>)

/** One price bar; [v] null when Core sent no readable volume. */
data class Bar(val t: String, val o: String, val h: String, val l: String, val c: String, val v: String? = null)

/** fact = from a source; calculation = computed by code; inference = an AI estimate. */
enum class NoteKind(val wire: String, val label: String) { FACT("fact", "Fact"), CALCULATION("calculation", "Calculation"), INFERENCE("inference", "AI inference") }
data class InstrumentNote(val t: String, val kind: NoteKind, val text: String, val sources: List<String>)

data class InstrumentBars(
    val fresh: Freshness, val conid: Long, val symbol: String, val name: String?, val exchange: String?, val currency: String,
    val bars: List<Bar>, val notes: List<InstrumentNote>,
)

// ---- decimal strings ----------------------------------------------------

private val DECIMAL = Regex("^(-)?([0-9]+)(?:\\.([0-9]+))?$")
private val LEADING_ZEROS = Regex("^0+(?=[0-9])")
private val ISO_CODE = Regex("^[A-Z]{3}$")
private val CONID = Regex("^[0-9]{1,12}$")
const val DASH = "—"

private data class Parsed(val neg: Boolean, val int: String, val frac: String)

private fun parse(value: String?): Parsed? {
    val m = DECIMAL.matchEntire(value?.trim() ?: return null) ?: return null
    return Parsed(m.groupValues[1].isNotEmpty(), m.groupValues[2].replace(LEADING_ZEROS, ""), m.groupValues[3])
}

/** Round half away from zero to [places] fraction digits, on the digits. */
private fun round(p: Parsed, places: Int): Parsed {
    if (p.frac.length <= places) return p.copy(frac = p.frac.padEnd(places, '0'))
    val digits = (p.int + p.frac.take(places)).map { it - '0' }.toMutableList()
    if (p.frac[places] >= '5') {
        var i = digits.size - 1
        while (i >= 0 && digits[i] == 9) digits[i--] = 0
        if (i < 0) digits.add(0, 1) else digits[i]++
    }
    val all = digits.joinToString("")
    val cut = all.length - places
    return Parsed(p.neg, all.take(cut).ifEmpty { "0" }, all.drop(cut))
}

private fun isZero(p: Parsed) = (p.int + p.frac).all { it == '0' }
private fun group(int: String) = int.reversed().chunked(3).joinToString(",").reversed()

/** -1, 0 or 1 for a decimal string; null when it is not one. */
fun decimalSign(value: String?): Int? = parse(value)?.let { if (isZero(it)) 0 else if (it.neg) -1 else 1 }

/** A decimal string with "," grouping: [places] fixed fraction digits (rounded), or up to
 *  [maxPlaces] without trailing zeros (quantities); [sign] puts "+" on positive values. Not a
 *  decimal: "—". */
fun formatDecimal(value: String?, places: Int? = null, maxPlaces: Int? = null, sign: Boolean = false): String {
    val raw = parse(value) ?: return DASH
    var p = round(raw, places ?: maxPlaces ?: raw.frac.length)
    if (places == null && maxPlaces != null) p = p.copy(frac = p.frac.trimEnd('0'))
    val body = group(p.int) + if (p.frac.isNotEmpty()) ".${p.frac}" else ""
    if (isZero(p)) return body
    return (if (p.neg) "−" else if (sign) "+" else "") + body
}

/** Signed percentage: "+0.43%", "−99.90%". */
fun formatPercent(value: String?, sign: Boolean = true, places: Int = 2): String =
    formatDecimal(value, places = places, sign = sign).let { if (it == DASH) DASH else "$it%" }

private data class CurrencyStyle(val symbol: String, val after: Boolean, val digits: Int)

/** Symbol and fraction digits of an ISO code; anything else is text after the amount. */
private fun currencyStyle(code: String): CurrencyStyle {
    if (!ISO_CODE.matches(code)) return CurrencyStyle(code, true, 2)
    val currency = runCatching { Currency.getInstance(code) }.getOrNull() ?: return CurrencyStyle(code, true, 2)
    val symbol = NARROW[code] ?: currency.getSymbol(Locale.US)
    return CurrencyStyle(symbol, false, currency.defaultFractionDigits.takeIf { it >= 0 } ?: 2)
}

// ponytail: desktop uses Intl's "en" narrowSymbol, which java.util.Currency lacks; this map
// covers the common codes where the two differ. Add a code when one shows wrongly.
private val NARROW = mapOf(
    "AUD" to "$", "CAD" to "$", "HKD" to "$", "MXN" to "$", "NZD" to "$", "SGD" to "$", "TWD" to "$",
    "CNY" to "¥", "DKK" to "kr", "NOK" to "kr", "SEK" to "kr", "PLN" to "zł", "CZK" to "Kč", "HUF" to "Ft",
    "TRY" to "₺", "ZAR" to "R", "RUB" to "₽", "KRW" to "₩", "ILS" to "₪", "INR" to "₹", "THB" to "฿", "PHP" to "₱",
)

/** Money in its currency: "€23,820.00", "+$3.23", "−€63.10". Fraction digits follow the
 *  currency (JPY 0, EUR 2) unless [places] is given. */
fun formatMoney(value: String?, currency: String?, sign: Boolean = false, places: Int? = null): String {
    val raw = parse(value) ?: return DASH
    val style = currencyStyle(currency.orEmpty().take(12))
    val p = round(raw, places ?: style.digits)
    val body = group(p.int) + if (p.frac.isNotEmpty()) ".${p.frac}" else ""
    val prefix = if (isZero(p)) "" else if (p.neg) "−" else if (sign) "+" else ""
    val spaced = style.symbol.length > 1 && style.symbol.all { it in 'A'..'Z' }
    return if (style.after || spaced) "$prefix$body ${style.symbol}".trimEnd() else "$prefix${style.symbol}$body"
}

/** A price: sub-cent prices keep their digits (up to 8), others follow the currency, so a
 *  $0.0004 token never shows as "$0.00". */
fun formatPrice(value: String?, currency: String?): String {
    val digits = value?.substringAfter('.', "")?.trimEnd('0')?.length ?: 0
    return formatMoney(value, currency, places = if (digits > 2) minOf(8, digits) else null)
}

/** A decimal string as a number, for chart drawing and bar widths only; amounts on screen
 *  always go through formatMoney/formatDecimal. */
fun chartNumber(value: String?): Double? = if (parse(value) == null) null else value!!.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

/** Simple moving average of the closes; null until [n] bars exist. */
fun movingAverage(closes: List<Double?>, n: Int): List<Double?> {
    var sum = 0.0
    var valid = 0
    return closes.mapIndexed { i, c ->
        if (c != null) { sum += c; valid++ }
        val drop = closes.getOrNull(i - n)
        if (i >= n && drop != null) { sum -= drop; valid-- }
        if (i >= n - 1 && valid == n) sum / n else null
    }
}

/** "IBKR · delayed 15 min", "IBKR · real time", or "IBKR · delay unknown" when Core did not
 *  say: never claim real time without data. */
fun sourceLabel(fresh: Freshness): String {
    val d = fresh.delayMinutes
    val delay = if (d == null) "delay unknown" else if (d > 0) "delayed ${d.roundToLong()} min" else "real time"
    return "${fresh.source.ifEmpty { "Unknown source" }} · $delay"
}

// ---- screen state -------------------------------------------------------

/** Core read is older than this: show it, but say it is stale. */
const val STALE_AFTER_MS = 10 * 60_000L

/** Why there is no trading data at all. NO_API: Core has no trading read API yet
 *  (404/405/501). NOT_SYNCED: IBKR is not configured, not synced yet or unreachable from Core
 *  (503). UNREACHABLE: no answer from the Home Node. */
enum class NotConnected { NO_API, NOT_SYNCED, UNREACHABLE }

/** NOT_HELD: the instrument is no longer in the portfolio (404 on bars). */
enum class TradingError { SIGNIN, FAILED, NOT_HELD }

sealed interface TradingState<out T> {
    data object Loading : TradingState<Nothing>
    data class Disconnected(val reason: NotConnected) : TradingState<Nothing>
    data class Failed(val reason: TradingError) : TradingState<Nothing>
    /** Data; [staleMs] is its age once Core's read is older than [STALE_AFTER_MS] (or its
     *  time is unreadable: Long.MAX_VALUE). */
    data class Ready<T>(val data: T, val empty: Boolean, val staleMs: Long? = null) : TradingState<T>
}

/** Classify a failed read. [instrument]: the read was /instruments/{conid}/bars, where 404
 *  means the instrument is not held, not a missing API. */
fun failureState(result: ApiResult<*>, instrument: Boolean = false): TradingState<Nothing> = when {
    result is ApiResult.Unreachable -> TradingState.Disconnected(NotConnected.UNREACHABLE)
    result == ApiResult.Unauthorized -> TradingState.Failed(TradingError.SIGNIN)
    result is ApiResult.HttpError -> when (result.status) {
        404 -> if (instrument) TradingState.Failed(TradingError.NOT_HELD) else TradingState.Disconnected(NotConnected.NO_API)
        405, 501 -> TradingState.Disconnected(NotConnected.NO_API)
        503 -> TradingState.Disconnected(NotConnected.NOT_SYNCED)
        401 -> TradingState.Failed(TradingError.SIGNIN)
        else -> TradingState.Failed(TradingError.FAILED)
    }
    else -> TradingState.Failed(TradingError.FAILED)
}

/** Epoch millis of an ISO-8601 time; null when it does not parse. */
fun isoMillis(iso: String?): Long? = iso?.let {
    runCatching { Instant.parse(it).toEpochMilli() }.recoverCatching { _ -> OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
}

/** State for a successful read: stale when Core's read is old or its time is unreadable
 *  (checked first: an old "nothing" is not a fresh "nothing"), keeping whether it is empty. */
fun <T> dataState(data: T, fresh: Freshness, empty: Boolean, now: Long): TradingState<T> {
    val age = isoMillis(fresh.asOf)?.let { now - it } ?: Long.MAX_VALUE
    return TradingState.Ready(data, empty, age.takeIf { it > STALE_AFTER_MS })
}

/** One trading read's answer as a state. */
fun <T> tradingState(result: ApiResult<T>, fresh: (T) -> Freshness, isEmpty: (T) -> Boolean, now: Long, instrument: Boolean = false): TradingState<T> =
    if (result is ApiResult.Success) dataState(result.value, fresh(result.value), isEmpty(result.value), now) else failureState(result, instrument)

/** What the owner sees, and must do, when there is no trading data. */
fun notConnectedText(reason: NotConnected): Pair<String, String> = when (reason) {
    NotConnected.NO_API -> "Not connected to IBKR yet" to
        "This Core has no trading data yet; it comes with the IBKR broker connection. Update Core on your Home Node and turn on the IBKR portfolio sync. Nothing is shown until then."
    NotConnected.NOT_SYNCED -> "IBKR not connected yet" to
        "Core has no IBKR data yet: the IBKR connection is not set up, the first sync has not finished, or Core cannot reach IBKR right now. Check the IBKR broker on your Home Node, then try again."
    NotConnected.UNREACHABLE -> "Home Node unreachable" to "No answer from your Home Node. Check the connection; trading data comes from Core."
}

fun errorText(reason: TradingError): String = when (reason) {
    TradingError.NOT_HELD -> "This instrument is no longer in your portfolio."
    TradingError.SIGNIN -> "Sign this device in again."
    TradingError.FAILED -> "Core returned an error. Try again later."
}

enum class LinkTone { IDLE, OK, WARN }

/** Tone and label of the IBKR link, read from the portfolio: a portfolio means connected;
 *  503 means not connected (yet). */
fun ibkrLinkState(state: TradingState<*>): Pair<LinkTone, String> = when (state) {
    TradingState.Loading -> LinkTone.IDLE to "Loading…"
    is TradingState.Ready -> if (state.staleMs != null) LinkTone.WARN to "Connected, sync is old" else LinkTone.OK to "Connected"
    is TradingState.Disconnected -> when (state.reason) {
        NotConnected.NO_API -> LinkTone.IDLE to "Requires newer Core"
        NotConnected.NOT_SYNCED -> LinkTone.WARN to "Not connected"
        NotConnected.UNREACHABLE -> LinkTone.WARN to "Unavailable"
    }
    is TradingState.Failed -> LinkTone.WARN to "Unavailable"
}

private val TODAY = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val OTHER_DAY = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
private val DAY_ONLY = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

/** "10:42" today, "5 Nov, 10:42" otherwise, in [zone]. */
fun asOfText(iso: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = isoMillis(iso) ?: return "an unknown time"
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (if (today) TODAY else OTHER_DAY).format(time)
}

/** "5 Nov" for a date or a time; "—" when it does not parse. */
fun dayText(iso: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = isoMillis(iso) ?: runCatching { java.time.LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
    return at?.let { DAY_ONLY.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: DASH
}

/** Percent change from the first to the last value as a decimal string ("4.80"); null when
 *  unknown. */
fun changeRatio(first: String?, last: String?): String? {
    val a = chartNumber(first)
    val b = chartNumber(last)
    if (a == null || a == 0.0 || b == null) return null
    return fixed2((b / a - 1) * 100).takeIf { parse(it) != null }
}

/** JavaScript's toFixed(2): the exact binary value, rounded half away from zero. */
internal fun fixed2(x: Double): String =
    if (x.isFinite()) java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() else "NaN"

/** "+4.80%" change from the first to the last value; "—" when unknown. */
fun changePercent(first: String?, last: String?): String = formatPercent(changeRatio(first, last))

/** "3 min", "2 h", "4 days" for a stale banner. */
fun ageText(ms: Long): String {
    if (ms == Long.MAX_VALUE) return "an unknown time"
    val min = maxOf(1L, (ms / 60_000.0).roundToLong())
    if (min < 60) return "$min min"
    val h = (min / 60.0).roundToLong()
    if (h < 48) return "$h h"
    return "${(h / 24.0).roundToLong()} days"
}

/** Allocation buckets within 2 points of target. */
fun onTarget(allocation: List<AllocationTarget>): Int = allocation.count { a ->
    val now = chartNumber(a.currentPct)
    val goal = chartNumber(a.targetPct)
    now != null && goal != null && abs(now - goal) < 2
}

/** "2 positions not shown (not in EUR): AAPL (USD), X (no currency)", or null when Core
 *  showed every position. */
fun skippedText(p: Portfolio): String? {
    if (p.skippedCount == 0L) return null
    val named = p.skippedPositions.map { "${it.symbol} (${it.currency ?: "no currency"})" }
    val more = p.skippedCount - named.size
    val what = if (p.skippedCount == 1L) "1 position" else "${p.skippedCount} positions"
    return "$what not shown (not in ${p.baseCurrency}): ${named.joinToString(", ")}${if (more > 0) " and $more more" else ""}"
}

/** "12 pt above target" for a bucket 2 points or more off; null when on target or unknown.
 *  The flag is true when above. */
fun offTarget(a: AllocationTarget): Pair<String, Boolean>? {
    val now = chartNumber(a.currentPct)
    val goal = chartNumber(a.targetPct)
    if (now == null || goal == null || abs(now - goal) < 2) return null
    return "${abs(now - goal).roundToLong()} pt ${if (now > goal) "above" else "below"} target" to (now > goal)
}

/** Stocks and ETFs: what the Stocks page lists and opens. */
fun isStock(p: Position): Boolean = p.assetClass == "STK" || p.assetClass == "ETF"

private val CHART_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val CHART_TIME_FULL = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
private val CHART_MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK)
private val CHART_DAY_FULL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

/** A chart time: hours within a span under two days, months over a span of 400 days, else
 *  days; [full] for the values table. */
fun chartTime(iso: String, bars: List<Bar>, full: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = isoMillis(iso) ?: return DASH
    val span = (isoMillis(bars.lastOrNull()?.t) ?: 0) - (isoMillis(bars.firstOrNull()?.t) ?: 0)
    val format = when {
        span < 2 * 86_400_000L -> if (full) CHART_TIME_FULL else CHART_TIME
        span > 400 * 86_400_000L && !full -> CHART_MONTH
        else -> if (full) CHART_DAY_FULL else DAY_ONLY
    }
    return format.format(Instant.ofEpochMilli(at).atZone(zone))
}

/** A typed amount as Core takes it: trimmed, a single decimal comma read as a dot (a Dutch
 *  keyboard); null when it is still not a decimal number. */
fun holdingNumber(text: String): String? = if (holdingIssue(text) == null) normalized(text) else null

/** Why a typed amount cannot be sent; null when it can. "1,234" and "1.234" look like
 *  thousands in one convention and decimals in the other, so both are refused. */
fun holdingIssue(text: String): String? = when {
    THOUSANDS.matches(text.trim()) || LEADING_ZERO.containsMatchIn(text.trim()) -> HOLDING_AMBIGUOUS
    !DECIMAL.matches(normalized(text)) -> HOLDING_NUMBER_RULE
    else -> null
}

private val THOUSANDS = Regex("^-?[1-9][0-9]{0,2}[.,][0-9]{3}$")
private val LEADING_ZERO = Regex("^-?0[0-9]+[.,]")
private fun normalized(text: String) = text.trim().let { if (it.count { c -> c == ',' } == 1 && '.' !in it) it.replace(',', '.') else it }

const val HOLDING_NUMBER_RULE = "Quantity and average cost must be numbers, such as 12.5."
const val HOLDING_AMBIGUOUS = "Ambiguous amount: write 1234, or 1.2340 for a decimal."

/** The notice after a failed holdings write. A 2xx with an oversized answer, or no answer at
 *  all, may have landed: the list reloads to check. */
fun holdingsWriteText(result: ApiResult<*>): String = when (result) {
    is ApiResult.InvalidResponse -> "Not confirmed; reloaded to check."
    is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check."
    else -> holdingsErrorText(result)
}

/** The notice after a failed holdings read or write. */
fun holdingsErrorText(result: ApiResult<*>): String = when (result) {
    is ApiResult.Unreachable -> "No answer from your Home Node. Check the connection."
    ApiResult.Unauthorized -> "Your session is not valid. Sign this device in again."
    is ApiResult.HttpError -> "Core refused this (HTTP ${result.status})."
    else -> "Core returned an answer this app does not understand."
}

// ---- response guards ----------------------------------------------------
// Core's answer is checked before any screen sees it: an older or broken Core yields an error
// state. Lists are capped; a bar or history point without finite prices is dropped rather
// than drawn as 0.

object TradingLimits {
    const val POSITIONS = 1000
    const val BARS = 5000
    const val POINTS = 5000
    const val NOTES = 50
    const val SOURCES = 20
    const val ALLOCATION = 50
    const val DIVIDENDS = 200
    const val SKIPPED = 50
    const val HOLDINGS = 1000
}

private const val MAX_SAFE = 9_007_199_254_740_991L

/** A conid from a route or a response: a positive safe integer, else null. */
fun parseConid(value: String): Long? = value.takeIf { CONID.matches(it) }?.toLongOrNull()?.takeIf { it in 1..MAX_SAFE }

/** A conid in an answer: a positive safe integer, as a number or (like desktop) its digits. */
private fun JsonObject.conid(key: String): Long {
    val p = this[key] as? JsonPrimitive ?: throw IllegalArgumentException()
    val n = if (p.isString) parseConid(p.content) else p.content.toLongOrNull()?.takeIf { it in 1..MAX_SAFE }
    return n ?: throw IllegalArgumentException()
}

/** An optional list: absent or null is none; anything else must be a list of at most [max]. */
private fun JsonObject.optArray(key: String, max: Int): JsonArray? =
    if (this[key] == null || this[key] == JsonNull) null else array(key, max)

private fun JsonObject.strings(key: String, max: Int): List<String>? = optArray(key, max)?.map {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException()
}

/** source and as_of are required; delay_minutes is absent or a known delay (0 or more). */
private fun freshness(o: JsonObject): Freshness {
    val delay = if ("delay_minutes" !in o) null
    else (o["delay_minutes"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        ?: throw IllegalArgumentException()
    return Freshness(o.str("source"), delay, o.str("as_of"))
}

private fun parsePosition(x: JsonElement): Position {
    val p = x.obj()
    return Position(
        p.conid("conid"), p.str("symbol"), p.optStr("name"), p.str("asset_class"), p.optStr("exchange"), p.str("currency"),
        p.str("quantity"), p.str("avg_cost"), p.str("last_price"), p.str("market_value"),
        p.optStr("day_pnl"), p.optStr("day_change_pct"), p.optStr("unrealized_pnl"), p.optStr("weight_pct"),
    )
}

private fun skipped(o: JsonObject): Pair<Long, List<SkippedPosition>> {
    if ("skipped_count" !in o && "skipped_positions" !in o) return 0L to emptyList()
    val count = o.int("skipped_count").also { require(it in 1..TradingLimits.POSITIONS) }
    val list = o.array("skipped_positions", minOf(count.toInt(), TradingLimits.SKIPPED)).also { require(it.isNotEmpty()) }.map {
        val s = it.obj()
        // Core counts code points, not UTF-16 units.
        val symbol = s.str("symbol").also { sym -> require(sym.codePointCount(0, sym.length) in 1..32) }
        val currency = if ("currency" !in s) null else s.str("currency").also { c -> require(ISO_CODE.matches(c)) }
        SkippedPosition(symbol, currency)
    }
    return count to list
}

fun readPortfolio(x: JsonElement): Portfolio? = guarded {
    val o = x.obj()
    val fresh = freshness(o)
    val deposit = o["monthly_deposit"].takeUnless { it == null || it == JsonNull }?.obj()?.let { m ->
        val day = (m["day_of_month"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw IllegalArgumentException()
        MonthlyDeposit(m.str("amount"), day, m.str("next_date"))
    }
    val (count, named) = skipped(o)
    Portfolio(
        fresh, o.str("base_currency"), o.str("net_liquidation"), o.str("cash"),
        o.optStr("day_pnl"), o.optStr("day_change_pct"), o.optStr("total_pnl"), o.optStr("total_return_pct"), o.optStr("dividends_12m"),
        o.array("positions", TradingLimits.POSITIONS).map(::parsePosition),
        o.optArray("allocation", TradingLimits.ALLOCATION)?.map { val a = it.obj(); AllocationTarget(a.str("label"), a.str("current_pct"), a.str("target_pct")) },
        deposit,
        o.optArray("upcoming_dividends", TradingLimits.DIVIDENDS)?.map {
            val d = it.obj()
            Dividend(d.conid("conid"), d.str("symbol"), d.str("ex_date"), d.optStr("pay_date"), d.optStr("amount"), d.str("currency"))
        },
        count, named,
    )
}

fun readValueHistory(x: JsonElement): PortfolioHistory? = guarded {
    val o = x.obj()
    val points = o.array("points", TradingLimits.POINTS).mapNotNull { p ->
        val t = ((p as? JsonObject)?.get("t") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val value = ((p as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (t != null && chartNumber(value) != null) HistoryPoint(t, value!!) else null
    }
    PortfolioHistory(freshness(o), o.str("base_currency"), points)
}

fun readBars(x: JsonElement): InstrumentBars? = guarded {
    val o = x.obj()
    fun price(b: JsonObject, key: String) = (b[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { chartNumber(it) != null }
    val bars = o.array("bars", TradingLimits.BARS).mapNotNull { e ->
        val b = e as? JsonObject ?: return@mapNotNull null
        val t = (b["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        Bar(t, price(b, "o") ?: return@mapNotNull null, price(b, "h") ?: return@mapNotNull null, price(b, "l") ?: return@mapNotNull null,
            price(b, "c") ?: return@mapNotNull null, price(b, "v"))
    }
    val notes = o.optArray("notes", TradingLimits.NOTES)?.map {
        val n = it.obj()
        val kind = n.optStr("kind").let { k -> NoteKind.entries.firstOrNull { e -> e.wire == k } } ?: throw IllegalArgumentException()
        InstrumentNote(n.str("t"), kind, n.str("text"), n.strings("sources", TradingLimits.SOURCES).orEmpty())
    }.orEmpty()
    InstrumentBars(freshness(o), o.conid("conid"), o.str("symbol"), o.optStr("name"), o.optStr("exchange"), o.str("currency"), bars, notes)
}

// ---- manual holdings (/v1/holdings) ----------------------------------------

data class Holding(
    val id: String, val symbol: String, val quantity: String, val avgCost: String, val currency: String,
    val costBasis: String, val weightPct: String,
)

data class Holdings(val holdings: List<Holding>, val totalCost: String)

private val HOLDING_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/** A holding id goes into a URL path: path-safe characters only. */
fun isHoldingId(id: String): Boolean = HOLDING_ID.matches(id)

/** A shown value: a string or a number as Core wrote it (desktop shows it as is). */
private fun JsonObject.shown(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.toDoubleOrNull() != null }?.content?.takeIf { it.length <= 200 }
        ?: throw IllegalArgumentException()

fun readHoldings(x: JsonElement): Holdings? = guarded {
    val o = x.obj()
    Holdings(
        o.array("holdings", TradingLimits.HOLDINGS).map {
            val h = it.obj()
            Holding(
                h.shown("id").also { id -> require(isHoldingId(id)) }, h.str("symbol", 200), h.shown("quantity"), h.shown("avg_cost"),
                h.optStr("currency", 12).orEmpty(), h.shown("cost_basis"), h.shown("weight_pct"),
            )
        },
        o.shown("total_cost"),
    )
}
