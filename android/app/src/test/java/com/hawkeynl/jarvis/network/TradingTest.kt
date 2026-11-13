package com.hawkeynl.jarvis.network

import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/trading.test.mjs (fixtures aside). Neutral test data only.
class TradingTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun with(base: String, field: String, value: String?): String =
        JsonObject(if (value == null) json(base).jsonObject - field else json(base).jsonObject + (field to json(value))).toString()

    private val meta = """"source":"IBKR","delay_minutes":15,"as_of":"2026-11-01T12:00:00Z""""
    private val position = """{"conid":1,"symbol":"A","asset_class":"STK","currency":"EUR","quantity":"1","avg_cost":"1","last_price":"1","market_value":"1"}"""
    private val portfolio = """{$meta,"base_currency":"EUR","net_liquidation":"1","cash":"0","positions":[$position]}"""

    @Test fun `decimal strings format exactly, without floats`() {
        assertEquals("1,234,567.891", formatDecimal("1234567.891"))
        assertEquals("0.10", formatDecimal("0.1", places = 2))
        assertEquals("1.01", formatDecimal("1.005", places = 2))
        assertEquals("10,000,000,000,000,000.00", formatDecimal("9999999999999999.995", places = 2))
        assertEquals("0.00", formatDecimal("-0.004", places = 2))
        assertEquals("+12.50", formatDecimal("12.50", sign = true))
        assertEquals("−3", formatDecimal("-3", sign = true))
        assertEquals("0.0001", formatDecimal("0.00010000", maxPlaces = 8))
        assertEquals("7", formatDecimal("007"))
        for (bad in listOf(null, "", "1e5", "1,000", "NaN", " -", "--1")) assertEquals(bad.toString(), DASH, formatDecimal(bad))
    }

    @Test fun `money follows the currency and keeps its sign`() {
        assertEquals("€23,820.00", formatMoney("23820", "EUR"))
        assertEquals("−€63.10", formatMoney("-63.1", "EUR", sign = true))
        assertEquals("+€101.60", formatMoney("101.6", "EUR", sign = true))
        assertEquals("€0.00", formatMoney("0.00", "EUR", sign = true))
        assertEquals("$176.40", formatMoney("176.4", "USD"))
        assertEquals("¥2,850", formatMoney("2850.4", "JPY"))
        assertEquals("€0.000000412", formatMoney("0.000000412", "EUR", places = 9))
        assertEquals("€128,456,789,012.34", formatMoney("128456789012.34", "EUR"))
        assertEquals("10.00 CHF", formatMoney("10", "CHF"))
        assertEquals("10.00 not-a-code", formatMoney("10", "not-a-code"))
        assertEquals("10.00", formatMoney("10", ""))
        assertEquals("1.00", formatMoney("1", null))
        assertEquals(DASH, formatMoney(null, "EUR"))
        assertEquals("$0.000412", formatPrice("0.00041200", "USD"))
        assertEquals("$176.40", formatPrice("176.40", "USD"))
        assertEquals("¥2,850", formatPrice("2850.00", "JPY"))
    }

    @Test fun `percentages, signs and chart numbers`() {
        assertEquals("+0.43%", formatPercent("0.43"))
        assertEquals("−99.90%", formatPercent("-99.9"))
        assertEquals(DASH, formatPercent(null))
        assertEquals(listOf(-1, 0, 1, null), listOf("-1", "0.000", "3", "x").map(::decimalSign))
        assertEquals(176.4, chartNumber("176.40")!!, 0.0)
        assertNull(chartNumber("abc"))
        assertEquals("+4.80%", changePercent("100", "104.8"))
        assertEquals(DASH, changePercent("0", "1"))
        assertEquals("-5.00", changeRatio("100", "95"))
        assertNull(changeRatio(null, "1"))
    }

    @Test fun `moving average waits for n bars and skips gaps`() {
        assertEquals(listOf(null, 1.5, 2.5, 3.5), movingAverage(listOf(1.0, 2.0, 3.0, 4.0), 2))
        assertEquals(listOf(null, null, null, 4.0), movingAverage(listOf(1.0, null, 3.0, 5.0), 2))
        assertEquals(listOf<Double?>(null, null), movingAverage(listOf(1.0, 2.0), 50))
    }

    @Test fun `labels name the source and the delay`() {
        assertEquals("IBKR · delayed 15 min", sourceLabel(Freshness("IBKR", 15.0, "x")))
        assertEquals("IBKR · real time", sourceLabel(Freshness("IBKR", 0.0, "x")))
        assertEquals("IBKR · delay unknown", sourceLabel(Freshness("IBKR", null, "x")))
        assertEquals("3 h", ageText(3 * 3_600_000L))
        assertEquals("an unknown time", ageText(Long.MAX_VALUE))
        // Missing or nonsense delay is never read as real time: the guard refuses it.
        for (delay in listOf("null", "-1", "\"15\"")) assertNull(delay, readPortfolio(json(with(portfolio, "delay_minutes", delay))))
        assertNull(readPortfolio(json(with(portfolio, "delay_minutes", null)))!!.fresh.delayMinutes)
    }

    @Test fun `failed reads - not connected for 404, 405, 501, 503 and no answer, else an error`() {
        fun http(status: Int) = ApiResult.HttpError(status, null)
        assertEquals(TradingState.Disconnected(NotConnected.NO_API), failureState(http(404)))
        assertEquals(TradingState.Disconnected(NotConnected.NO_API), failureState(http(405)))
        assertEquals(TradingState.Disconnected(NotConnected.NO_API), failureState(http(501)))
        assertEquals(TradingState.Disconnected(NotConnected.NOT_SYNCED), failureState(http(503)))
        assertEquals(TradingState.Disconnected(NotConnected.UNREACHABLE), failureState(ApiResult.Unreachable(UnreachableReason.DNS)))
        assertEquals(TradingState.Failed(TradingError.SIGNIN), failureState(ApiResult.Unauthorized))
        assertEquals(TradingState.Failed(TradingError.FAILED), failureState(http(500)))
        assertEquals(TradingState.Failed(TradingError.FAILED), failureState(ApiResult.InvalidResponse("x")))
        for (reason in NotConnected.entries) {
            val (title, detail) = notConnectedText(reason)
            assertTrue(reason.name, detail.length > 40)
            assertTrue(reason.name, !Regex("gateway|client portal", RegexOption.IGNORE_CASE).containsMatchIn(title + detail))
        }
        // Bars: 404 means the instrument is no longer held.
        assertEquals(TradingState.Failed(TradingError.NOT_HELD), failureState(http(404), instrument = true))
        assertTrue(errorText(TradingError.NOT_HELD).contains("no longer in your portfolio"))
        assertEquals(TradingState.Disconnected(NotConnected.NOT_SYNCED), failureState(http(503), instrument = true))
        assertEquals(TradingState.Failed(TradingError.FAILED), failureState(http(400), instrument = true))
    }

    @Test fun `IBKR link state comes from the portfolio read`() {
        assertEquals(LinkTone.IDLE to "Loading…", ibkrLinkState(TradingState.Loading))
        assertEquals(LinkTone.OK to "Connected", ibkrLinkState(TradingState.Ready(1, empty = false)))
        assertEquals(LinkTone.OK to "Connected", ibkrLinkState(TradingState.Ready(1, empty = true)))
        assertEquals(LinkTone.WARN to "Connected, sync is old", ibkrLinkState(TradingState.Ready(1, false, 3_600_000)))
        assertEquals(LinkTone.WARN to "Not connected", ibkrLinkState(TradingState.Disconnected(NotConnected.NOT_SYNCED)))
        assertEquals(LinkTone.IDLE to "Requires newer Core", ibkrLinkState(TradingState.Disconnected(NotConnected.NO_API)))
        assertEquals(LinkTone.WARN to "Unavailable", ibkrLinkState(TradingState.Disconnected(NotConnected.UNREACHABLE)))
        assertEquals(LinkTone.WARN to "Unavailable", ibkrLinkState(TradingState.Failed(TradingError.FAILED)))
    }

    @Test fun `successful reads - empty, data or stale`() {
        val now = isoMillis("2026-11-01T12:00:00Z")!!
        fun at(ms: Long) = Freshness("IBKR", 15.0, java.time.Instant.ofEpochMilli(now - ms).toString())
        assertEquals(TradingState.Ready("d", empty = false), dataState("d", at(60_000), empty = false, now))
        assertEquals(TradingState.Ready("d", empty = true), dataState("d", at(0), empty = true, now))
        // Stale wins over empty, and keeps the emptiness for the screen.
        assertEquals(TradingState.Ready("d", true, STALE_AFTER_MS + 1), dataState("d", at(STALE_AFTER_MS + 1), empty = true, now))
        assertEquals(Long.MAX_VALUE, (dataState("d", Freshness("IBKR", null, "garbage"), false, now) as TradingState.Ready).staleMs)
        assertEquals("12:00", asOfText("2026-11-01T12:00:00Z", now, ZoneOffset.UTC))
        assertEquals("31 Oct, 09:05", asOfText("2026-10-31T09:05:00Z", now, ZoneOffset.UTC))
        assertEquals("an unknown time", asOfText("garbage", now))
        assertEquals("5 Nov", dayText("2026-11-05", ZoneOffset.UTC))
    }

    @Test fun `allocation counts buckets within 2 points of target`() {
        assertEquals(1, onTarget(listOf(AllocationTarget("a", "57", "60"), AllocationTarget("b", "9", "10"), AllocationTarget("c", "x", "10"))))
    }

    @Test fun `off-target buckets, stock classes and chart times`() {
        assertEquals("3 pt below target" to false, offTarget(AllocationTarget("a", "57", "60")))
        assertEquals("12 pt above target" to true, offTarget(AllocationTarget("a", "52", "40")))
        assertNull(offTarget(AllocationTarget("b", "9", "10")))
        assertNull(offTarget(AllocationTarget("c", "x", "10")))
        val day = listOf(Bar("2026-11-01T09:00:00Z", "1", "1", "1", "1"), Bar("2026-11-01T17:00:00Z", "1", "1", "1", "1"))
        assertEquals("09:00", chartTime("2026-11-01T09:00:00Z", day, zone = ZoneOffset.UTC))
        assertEquals("1 Nov, 09:00", chartTime("2026-11-01T09:00:00Z", day, full = true, zone = ZoneOffset.UTC))
        val years = listOf(Bar("2021-11-01T00:00:00Z", "1", "1", "1", "1"), Bar("2026-11-01T00:00:00Z", "1", "1", "1", "1"))
        assertEquals("Nov 2021", chartTime("2021-11-01T00:00:00Z", years, zone = ZoneOffset.UTC))
        assertEquals("1 Nov 2021", chartTime("2021-11-01T00:00:00Z", years, full = true, zone = ZoneOffset.UTC))
        assertEquals(DASH, chartTime("x", years))
        assertTrue(holdingsErrorText(ApiResult.HttpError(400, null)).contains("HTTP 400"))
    }

    @Test fun `parseConid accepts positive safe integers only`() {
        assertEquals(265598L, parseConid("265598"))
        for (bad in listOf("0", "-1", "12a", "1.5", "", "9999999999999")) assertNull(bad, parseConid(bad))
        assertEquals(265598L, readPortfolio(json(with(portfolio, "positions", "[${with(position, "conid", "\"265598\"")}]")))!!.positions[0].conid)
    }

    @Test fun `portfolio guard checks required fields and element shapes`() {
        assertNotNull(readPortfolio(json(portfolio)))
        val bad = listOf(
            "null", "[]", "\"x\"", with(portfolio, "base_currency", null), with(portfolio, "net_liquidation", "1"), with(portfolio, "as_of", null),
            with(portfolio, "delay_minutes", "-5"), with(portfolio, "positions", "\"none\""),
            with(portfolio, "positions", "[${with(position, "symbol", "7")}]"), with(portfolio, "positions", "[${with(position, "conid", "\"abc\"")}]"),
            with(portfolio, "positions", "[${with(position, "currency", null)}]"), with(portfolio, "positions", "[null]"),
            with(portfolio, "allocation", """[{"label":"x","current_pct":5,"target_pct":"5"}]"""),
            with(portfolio, "upcoming_dividends", """[{"conid":1,"symbol":"A","ex_date":"2026-11-05"}]"""),
            with(portfolio, "monthly_deposit", """{"amount":"500"}"""),
            with(portfolio, "positions", "[${List(TradingLimits.POSITIONS + 1) { position }.joinToString(",")}]"),
        )
        for (x in bad) assertNull(x.take(80), readPortfolio(json(x)))
    }

    @Test fun `positions Core left out are guarded and named`() {
        assertNull(skippedText(readPortfolio(json(portfolio))!!))
        val skipped = with(with(portfolio, "skipped_count", "2"), "skipped_positions", """[{"symbol":"AAPL","currency":"USD"},{"symbol":"NOCUR"}]""")
        assertEquals("2 positions not shown (not in EUR): AAPL (USD), NOCUR (no currency)", skippedText(readPortfolio(json(skipped))!!))
        val one = with(with(portfolio, "skipped_count", "1"), "skipped_positions", """[{"symbol":"AAPL","currency":"USD"}]""")
        assertEquals("1 position not shown (not in EUR): AAPL (USD)", skippedText(readPortfolio(json(one))!!))
        val fifty = List(TradingLimits.SKIPPED) { """{"symbol":"A","currency":"USD"}""" }.joinToString(",")
        val many = with(with(portfolio, "skipped_count", "60"), "skipped_positions", "[$fifty]")
        assertTrue(Regex("^60 positions not shown .* and 10 more$").matches(skippedText(readPortfolio(json(many))!!)!!))
        // 32 astral characters are 32 code points (Core's bound) but 64 UTF-16 units.
        assertNotNull(readPortfolio(json(with(one, "skipped_positions", """[{"symbol":"${"😀".repeat(32)}"}]"""))))
        val bad = listOf(
            with(portfolio, "skipped_count", "1"),
            with(portfolio, "skipped_positions", """[{"symbol":"A"}]"""),
            with(with(skipped, "skipped_count", "0"), "skipped_positions", "[]"),
            with(skipped, "skipped_count", "\"2\""), with(skipped, "skipped_count", "1.5"),
            with(skipped, "skipped_count", "${TradingLimits.POSITIONS + 1}"), with(skipped, "skipped_count", "1"),
            with(skipped, "skipped_positions", "[]"), with(skipped, "skipped_positions", "\"AAPL\""), with(skipped, "skipped_positions", "[null]"),
            with(skipped, "skipped_positions", """[{"symbol":""}]"""), with(skipped, "skipped_positions", """[{"symbol":"${"X".repeat(33)}"}]"""),
            with(skipped, "skipped_positions", """[{"symbol":"${"😀".repeat(33)}"}]"""),
            with(skipped, "skipped_positions", """[{"symbol":7}]"""), with(skipped, "skipped_positions", """[{"symbol":"A","currency":"usd"}]"""),
            with(skipped, "skipped_positions", """[{"symbol":"A","currency":null}]"""),
            with(many, "skipped_positions", "[${List(TradingLimits.SKIPPED + 1) { """{"symbol":"A"}""" }.joinToString(",")}]"),
        )
        for (x in bad) assertNull(x.takeLast(120), readPortfolio(json(x)))
    }

    @Test fun `bars guard drops bars without finite prices and rejects bad shapes`() {
        val bar = """{"t":"2026-11-01T12:00:00Z","o":"1","h":"2","l":"0.5","c":"1.5","v":"100"}"""
        val base = """{$meta,"conid":1,"symbol":"A","currency":"USD","range":"1M","bars":[$bar]}"""
        val read = readBars(json(with(base, "bars", "[$bar,${with(bar, "o", null)},${with(bar, "c", "\"NaN\"")},${with(bar, "h", "1")},null,${with(bar, "v", "\"x\"")}]")))!!
        assertEquals(2, read.bars.size)
        assertNull(read.bars[1].v)
        assertNull(readBars(json(with(base, "symbol", null))))
        assertNull(readBars(json(with(base, "currency", "3"))))
        assertNull(readBars(json(with(base, "bars", "[${List(TradingLimits.BARS + 1) { bar }.joinToString(",")}]"))))
        assertNull(readBars(json(with(base, "notes", """[{"t":"x","kind":"rumour","text":"y"}]"""))))
        assertNull(readBars(json(with(base, "notes", """[{"t":"x","kind":"fact","text":"y","sources":[1]}]"""))))
        val note = """{"t":"x","kind":"fact","text":"y"}"""
        assertNull(readBars(json(with(base, "notes", "[${List(TradingLimits.NOTES + 1) { note }.joinToString(",")}]"))))
        assertEquals(NoteKind.FACT, readBars(json(with(base, "notes", "[$note]")))!!.notes.single().kind)
    }

    @Test fun `history guard drops unreadable points and caps the list`() {
        val base = """{$meta,"base_currency":"EUR","range":"3M"}"""
        assertEquals(1, readValueHistory(json(with(base, "points", """[{"t":"a","value":"1"},{"t":"b","value":2},{"value":"3"}]""")))!!.points.size)
        assertNull(readValueHistory(json(with(base, "points", "[${List(TradingLimits.POINTS + 1) { """{"t":"a","value":"1"}""" }.joinToString(",")}]"))))
        assertNull(readValueHistory(json(with(with(base, "base_currency", "null"), "points", "[]"))))
    }

    @Test fun `narrow currency symbols and toFixed rounding match desktop`() {
        assertEquals("$10.00", formatMoney("10", "CAD"))
        assertEquals("$10.00", formatMoney("10", "HKD"))
        assertEquals("kr10.00", formatMoney("10", "SEK"))
        // toFixed(2) rounds the exact binary value: 1.005 is just below, 0.125 exactly half.
        assertEquals("1.00", fixed2(1.005))
        assertEquals("0.13", fixed2(0.125))
        assertEquals("-0.13", fixed2(-0.125))
        assertEquals("4.80", changeRatio("100", "104.8"))
    }

    @Test fun `typed holding amounts accept a decimal comma`() {
        assertEquals("12.5", holdingNumber(" 12,5 "))
        assertEquals("12.5", holdingNumber("12.5"))
        assertEquals("3", holdingNumber("3"))
        for (bad in listOf("", "1.000,50", "1,2,3", "12,5.0", "abc", "1e3", "12.")) assertNull(bad, holdingNumber(bad))
        assertEquals("Not confirmed; reloaded to check.", holdingsWriteText(ApiResult.InvalidResponse("x")))
        assertTrue(holdingsWriteText(ApiResult.Unreachable(UnreachableReason.NETWORK)).contains("reloaded to check"))
    }

    @Test fun `holdings read strings or numbers and only path-safe ids`() {
        val h = """{"id":"h1","symbol":"AAPL","quantity":"2","avg_cost":150.5,"currency":"USD","cost_basis":"301.00","weight_pct":"100.00"}"""
        val read = readHoldings(json("""{"holdings":[$h],"total_cost":"301.00"}"""))!!
        assertEquals("150.5", read.holdings.single().avgCost)
        for (broken in listOf(with(h, "id", "\"../x\""), with(h, "symbol", "1"), with(h, "quantity", "true"), with(h, "cost_basis", "null"))) {
            assertNull(broken, readHoldings(json("""{"holdings":[$broken],"total_cost":"0"}""")))
        }
        assertNull(readHoldings(json("""{"holdings":[]}""")))
        assertTrue(isHoldingId("0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71"))
    }
}
