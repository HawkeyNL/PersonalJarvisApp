package com.hawkeynl.jarvis.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/memecoin.test.mjs and tests/meme-strategies.test.mjs (fixtures,
// realtime hints and the native enable aside). Neutral test data only; mints are plausible
// base58, not real tokens.
class MemecoinTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun with(base: String, field: String, value: String?): String =
        JsonObject(if (value == null) json(base).jsonObject - field else json(base).jsonObject + (field to json(value))).toString()

    private val now = isoMillis("2026-11-01T12:00:00Z")!!
    private val mint = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private val position = """{"mint":"$mint","strategy_version":3,"tokens":"184211.775","cost_usd":"25","value_usd":"27.31",
        "opened_at":"2026-11-01T10:00:00Z","exit_failures":0,"stuck":false}"""
    private val gate = """{"closed_trades":63,"min_closed_trades":100,"duration_days":19,"min_duration_days":28,"trading_days":14,
        "min_trading_days":7,"net_result_usd":"20.1158","net_expectancy_usd":"0.3193","enough_trades":false,"enough_time":false,
        "enough_days":true,"positive_expectancy":true,"passed":false}"""
    private val goal = """{"multiple_target":"2","multiple":"1.0805","fraction_to_goal":"0.0805","remaining_usd":"229.8842","reached":false}"""
    private val pnl = """{"realised_usd":"18.4","unrealised_usd":"2.31","failed_tx_fees_usd":"0.38","ai_cost_usd":"0.2142","net_usd":"20.1158"}"""
    private val agent = """{"calling":false,"calls_last_hour":2,"calls_last_day":17,"max_calls_per_hour":4,"max_calls_per_day":48,
        "last_call_at":"2026-11-01T11:51:00Z","paused_until":null,"backoff_until":null}"""
    private val overview = """{"mode":"paper","enabled":true,"running":true,"paused":false,"paused_at":null,"last_error":null,"resume":"x",
        "agent":$agent,"strategy_version":3,"as_of":"2026-11-01T11:59:30Z","stake_usd":"250","equity_usd":"270.1158","pnl":$pnl,
        "open_positions":[$position],"gate":$gate,"goal":$goal}"""

    private fun read(text: String = overview) = readOverview(json(text))!!

    @Test fun `overview without a report is the off or starting shape`() {
        val off = read("""{"mode":"paper","enabled":false,"running":false,"paused":false,"paused_at":null,"last_error":null,"resume":"x"}""")
        assertNull(off.report)
        assertEquals(MemeStatusKind.DISABLED, memeStatus(off).kind)
        assertEquals(AgentStatusKind.OFF, agentStatus(off, now).kind)
    }

    @Test fun `overview guard fails closed`() {
        val broken = listOf(
            "null", "[]", "\"paper\"",
            with(overview, "mode", "\"live\""), with(overview, "mode", null), with(overview, "enabled", "\"true\""),
            with(overview, "paused_at", "\"yesterday\""), with(overview, "last_error", "\"${"x".repeat(MemeLimits.TEXT + 1)}\""),
            with(overview, "equity_usd", "270.11"), with(overview, "equity_usd", "\"1e3\""), with(overview, "as_of", null),
            with(overview, "pnl", with(pnl, "ai_cost_usd", "null")), with(overview, "gate", with(gate, "closed_trades", "-1")),
            with(overview, "gate", with(gate, "passed", "\"no\"")), with(overview, "goal", with(goal, "multiple", "1.08")),
            with(overview, "open_positions", "[${with(position, "mint", "\"not base58 0OIl\"")}]"),
            with(overview, "open_positions", "[${with(position, "stuck", null)}]"),
            with(overview, "open_positions", "[${List(MemeLimits.POSITIONS + 1) { position }.joinToString(",")}]"),
            with(overview, "agent", with(agent, "calling", "\"no\"")), with(overview, "agent", with(agent, "calls_last_hour", "-1")),
            with(overview, "agent", with(agent, "paused_until", "\"soon\"")), with(overview, "agent", "\"on\""),
        )
        for (x in broken) assertNull(x.take(80), readOverview(json(x)))
        // An older Core without the agent reads as off.
        assertNull(read(with(overview, "agent", null)).agent)
    }

    @Test fun `optional fields read as null`() {
        val raw = with(with(with(overview, "paused_at", null), "goal", with(goal, "multiple", null)), "open_positions", "[${with(position, "value_usd", null)}]")
        val o = read(raw)
        assertNull(o.pausedAt)
        assertNull(o.report!!.goal.multiple)
        assertNull(o.report.openPositions[0].valueUsd)
    }

    @Test fun `candidate and trade guards are deep and capped`() {
        val c = """{"mint":"$mint","observed_at":"2026-11-01T11:00:00Z","strategy_version":3,"verdict":"reject","reason":"lp_not_secured",
            "price_usd":"0.0213","liquidity_usd":"41250"}"""
        assertEquals("lp_not_secured", readCandidates(json("""{"candidates":[$c]}"""))!!.single().reason)
        assertTrue(readCandidates(json("""{"candidates":[${with(with(c, "verdict", "\"accept\""), "reason", "null")}]}"""))!!.single().accepted)
        for (bad in listOf(
            with(c, "verdict", "\"maybe\""), with(c, "reason", "null"), with(c, "verdict", "\"accept\""), with(c, "price_usd", "0.02"),
        )) assertNull(bad, readCandidates(json("""{"candidates":[$bad]}""")))
        assertNull(readCandidates(json("""{"candidates":[${List(MemeLimits.LIST + 1) { c }.joinToString(",")}]}""")))
        assertEquals(emptyList<Candidate>(), readCandidates(json("""{"candidates":[]}""")))
        assertNull(readCandidates(json("{}")))
        val t = """{"mint":"$mint","strategy_version":3,"tokens":"120544.1","cost_usd":"25","opened_at":"2026-11-01T08:00:00Z",
            "closed_at":"2026-11-01T09:00:00Z","usd_out":"37.87","exit_fees_usd":"0.16","net_pnl_usd":"12.71","exit_reason":"take_profit"}"""
        assertNotNull(readTrades(json("""{"trades":[$t]}""")))
        assertNull(readTrades(json("""{"trades":[${with(t, "net_pnl_usd", "-4.92")}]}""")))
        assertNull(readTrades(json("""{"trades":[${with(t, "closed_at", "\"\"")}]}""")))
        assertNull(readTrades(json("""{"trades":"none"}""")))
    }

    @Test fun `status picks the most restrictive state`() {
        val o = read()
        assertEquals(MemeStatusKind.RUNNING, memeStatus(o).kind)
        assertEquals(MemeStatusKind.DISABLED, memeStatus(o.copy(enabled = false, paused = true)).kind)
        val stopped = memeStatus(o.copy(running = false, lastError = "paper trader did not start; see the Core log"))
        assertEquals(MemeStatusKind.STOPPED, stopped.kind)
        assertTrue(stopped.detail.contains("did not start"))
        assertTrue(memeStatus(o.copy(paused = true)).detail.contains("still valued and exited"))
        // A failing tick is not hidden behind the pause, and exits are not claimed.
        val pausedFailing = memeStatus(o.copy(paused = true, lastError = "tick timed out"))
        assertEquals(MemeStatusKind.PAUSED, pausedFailing.kind)
        assertTrue(pausedFailing.detail.contains("tick timed out"))
        assertFalse(pausedFailing.detail.contains("still valued"))
        assertEquals(MemeStatusKind.HALTED, memeStatus(o.copy(lastError = "tick timed out")).kind)
        assertEquals(MemeStatusKind.STARTING, memeStatus(o.copy(report = null)).kind)
        // Nothing says "live" except to deny it.
        for (s in listOf(o, o.copy(enabled = false), o.copy(paused = true), o.copy(running = false))) {
            assertFalse(Regex("\\blive\\b", RegexOption.IGNORE_CASE).containsMatchIn(memeStatus(s).detail + memeStatus(s).label))
        }
    }

    @Test fun `stale and stuck are flagged separately from the status`() {
        val o = read()
        assertNull(reportAge(o, now))
        assertTrue(reportAge(o.copy(report = o.report!!.copy(asOf = "2026-11-01T11:49:00Z")), now)!! > STALE_AFTER_MS)
        assertNull(reportAge(o.copy(report = null), now))
        assertEquals(0, stuckCount(o))
        val stuck = o.report.openPositions[0].copy(stuck = true)
        assertEquals(2, stuckCount(o.copy(report = o.report.copy(openPositions = listOf(stuck, stuck)))))
    }

    @Test fun `read failures map to what the owner can do`() {
        assertEquals(MemeFailure.UNREACHABLE, memeFailure(ApiResult.Unreachable(UnreachableReason.NETWORK)))
        assertEquals(MemeFailure.NO_API, memeFailure(ApiResult.HttpError(404, null)))
        assertEquals(MemeFailure.SIGNIN, memeFailure(ApiResult.Unauthorized))
        assertEquals(MemeFailure.UNAVAILABLE, memeFailure(ApiResult.HttpError(503, null)))
        assertEquals(MemeFailure.FAILED, memeFailure(ApiResult.HttpError(500, null)))
        assertEquals(MemeFailure.FAILED, memeFailure(ApiResult.InvalidResponse("x")))
        assertTrue(memeFailureText(MemeFailure.SIGNIN).contains("Sign this device in"))
    }

    private val at = clock(isoMillis("2026-11-01T10:42:18Z")!!)

    @Test fun `pause succeeds only on Core's confirmation`() {
        assertEquals("10:42:18 UTC", at)
        assertEquals(WriteOutcome(true, at), pauseOutcome(ApiResult.Success(json("""{"paused":true}""")), at))
        for (answer in listOf("""{"paused":false}""", "{}", "null", "\"ok\"", """{"paused":"true"}""")) {
            val r = pauseOutcome(ApiResult.Success(json(answer)), at)
            assertFalse(answer, r.ok)
            assertTrue(r.message!!.contains("did not confirm"))
        }
    }

    @Test fun `pause errors say when, what Core answered and what holds`() {
        fun fail(result: ApiResult<JsonElement>) = pauseOutcome(result, at)
        val r401 = fail(ApiResult.Unauthorized).message!!
        assertTrue(r401.contains("10:42:18 UTC") && r401.contains("HTTP 401") && r401.contains("nothing changed") && r401.contains("Sign this device in"))
        val r503 = fail(ApiResult.HttpError(503, null))
        assertTrue(r503.message!!.contains("restart would undo it"))
        assertEquals(503, r503.status)
        assertTrue(fail(ApiResult.HttpError(404, null)).message!!.contains("no memecoin pause"))
        assertTrue(Regex("HTTP 500.*not confirmed").containsMatchIn(fail(ApiResult.HttpError(500, null)).message!!))
        assertTrue(fail(ApiResult.Unreachable(UnreachableReason.TIMEOUT)).message!!.contains("unknown whether the pause arrived"))
        assertTrue(fail(ApiResult.InvalidResponse("x")).message!!.contains("could not be sent"))
    }

    @Test fun `display helpers`() {
        assertEquals("7xKX…gAsU", shortMint(mint))
        assertEquals("short", shortMint("short"))
        assertEquals("$270.12", usd("270.1158"))
        assertEquals("−$12.50", usd("-12.5", sign = true))
        assertEquals("$0.0042", usd("0.0042"))
        assertEquals("$0.00", usd("0"))
        assertEquals(DASH, usd(null))
        assertEquals(63.0, progressPct(63, 100), 0.0)
        assertEquals(100.0, progressPct(150, 100), 0.0)
        assertEquals(100.0, progressPct(0, 0), 0.0)
        assertEquals(8.05, goalPct("0.0805")!!, 1e-9)
        assertEquals(100.0, goalPct("1.5")!!, 0.0)
        assertNull(goalPct(null))
        assertEquals("Liquidity not locked or burned", reasonLabel(REJECT_REASONS, "lp_not_secured"))
        assertEquals("Other (toString)", reasonLabel(REJECT_REASONS, "toString"))
        assertEquals(DASH, reasonLabel(REJECT_REASONS, null))
    }

    // ---- strategies ----------------------------------------------------------------

    private val id = "a-survivor-momentum"
    private val perf = """{"trades":41,"net_usd":"14.82","costs_usd":"0.38","expectancy_usd":"0.3615","win_rate_bps":4125,"max_drawdown_usd":"6.4","profit_factor":"1.31"}"""
    private val strategy = """{"id":"$id","family":"a","active_version":1000004,"enabled":true,"disabled_by_owner":false,
        "changed_at":"2026-11-01T02:00:00Z","changed_by":"claude","performance":$perf}"""
    private val version = """{"version":1000004,"parent_version":1000001,"author":"claude","created_at":"2026-11-01T02:00:00Z",
        "config":{"id":"$id","version":1000004,"parent":1000001,"family":"a","take_profit_bps":7500,"trailing_stop_bps":1500},
        "diff":[{"field":"take_profit_bps","from":5000,"to":7500},{"field":"trailing_stop_bps","from":null,"to":1500}],
        "rationale":"Wider take profit.","backtest":{"status":"unbacktested","mints":3,"points":120}}"""
    private val seed = """{"version":1000001,"parent_version":null,"author":"seed","created_at":"2026-10-01T02:00:00Z","config":null,"diff":null,"rationale":null,"backtest":null}"""
    private val detail = with(with(with(strategy, "active_config_sha256", "\"${"a1".repeat(32)}\""), "versions", "[$version,$seed]"), "gate",
        """{"at":"2026-11-01T11:00:00Z","closed_trades":41,"min_closed_trades":100,"duration_days":9,"min_duration_days":28,"trading_days":7,
        "min_trading_days":7,"net_result_usd":"14.82","net_expectancy_usd":"0.3615"}""")
    private val decision = """{"id":"d-2","at":"2026-11-01T11:30:00Z","call_id":"call_1","kind":"enter","strategy_version":1000004,"mint":"$mint",
        "size_bps":5000,"reason_code":"fresh_high_breakout","rationale":"Momentum.","model":"claude-opus","input_tokens":10,"output_tokens":5}"""

    @Test fun `strategy ids follow Core's rule`() {
        assertTrue(isStrategyId("claude-1000005"))
        for (bad in listOf("", "A-b", "a_b", "a/b", "..x", "a".repeat(33))) assertFalse(bad, isStrategyId(bad))
    }

    @Test fun `strategy list guard fails closed`() {
        assertEquals(id, readStrategies(json("""{"strategies":[$strategy]}"""))!!.single().id)
        for (s in listOf(
            with(strategy, "id", "\"Bad\""), with(strategy, "family", "\"d\""), with(strategy, "active_version", "0"),
            with(strategy, "enabled", "\"true\""), with(strategy, "changed_by", "\"${"x".repeat(65)}\""),
            with(strategy, "performance", with(perf, "net_usd", "14.82")), with(strategy, "performance", with(perf, "win_rate_bps", "10001")),
            with(strategy, "performance", null),
        )) assertNull(s.take(80), readStrategies(json("""{"strategies":[$s]}""")))
        assertNull(readStrategies(json("""{"strategies":[${List(StrategyLimits.STRATEGIES + 1) { strategy }.joinToString(",")}]}""")))
        assertNull(readStrategies(json("{}")))
        val read = readStrategies(json("""{"strategies":[${with(with(strategy, "family", "null"), "performance", with(perf, "profit_factor", null))}]}"""))!!
        assertNull(read[0].family)
        assertNull(read[0].performance.profitFactor)
    }

    @Test fun `strategy detail guard fails closed and only for the asked id`() {
        assertNotNull(readStrategy(json(detail), id))
        assertNull(readStrategy(json(detail), "b-base-reclaim"))
        for (x in listOf(
            with(detail, "active_config_sha256", "\"XYZ\""),
            with(detail, "versions", "[${with(version, "author", "\"owner\"")}]"),
            with(detail, "versions", "[${with(version, "rationale", "\"${"x".repeat(601)}\"")}]"),
            with(detail, "versions", "[${with(version, "diff", """[{"field":"x","from":"${"a".repeat(StrategyLimits.VALUE)}","to":1}]""")}]"),
            with(detail, "versions", "[${with(version, "diff", "\"none\"")}]"),
            with(detail, "versions", "[${with(version, "backtest", """{"status":"great","mints":1,"points":2}""")}]"),
            with(detail, "versions", "[${with(version, "backtest", """{"status":"ok","mints":1,"points":2}""")}]"),
            with(detail, "versions", "[${List(StrategyLimits.VERSIONS + 1) { version }.joinToString(",")}]"),
            with(detail, "gate", """{"at":"2026-11-01T11:00:00Z","closed_trades":1,"min_closed_trades":1,"duration_days":1,"min_duration_days":1,
                "trading_days":1,"min_trading_days":1,"net_result_usd":1,"net_expectancy_usd":"1"}"""),
        )) assertNull(x.takeLast(120), readStrategy(json(x), id))
    }

    @Test fun `versions read as diff rows, plain rationale and a parameter list`() {
        val s = readStrategy(json(detail), id)!!
        val (top, first) = s.versions
        assertEquals(listOf(StrategyDiffRow("take_profit_bps", "5000", "7500"), StrategyDiffRow("trailing_stop_bps", "null", "1500")), top.diff)
        assertTrue(top.byClaude)
        assertEquals("unbacktested", top.backtest!!.status)
        assertNull(first.diff)
        assertFalse(first.byClaude)
        // Identity fields are in the header, not the parameters.
        assertEquals(listOf("take_profit_bps" to "7500", "trailing_stop_bps" to "1500"), top.config)
        // A field missing on one side reads as a dash.
        val oneSided = with(detail, "versions", "[${with(version, "diff", """[{"field":"trailing_stop_bps","to":1500}]""")}]")
        assertEquals(listOf(StrategyDiffRow("trailing_stop_bps", "—", "1500")), readStrategy(json(oneSided), id)!!.versions[0].diff)
    }

    @Test fun `decision guard fails closed`() {
        assertEquals("d-2-2026-11-01T11:30:00Z-0", readDecisions(json("""{"decisions":[$decision]}"""))!!.single().key)
        for (d in listOf(
            with(decision, "kind", "\"Enter\""), with(decision, "call_id", "\"a b\""), with(decision, "mint", "\"not base58 0OIl\""),
            with(decision, "size_bps", "0"), with(decision, "size_bps", "10001"), with(decision, "reason_code", "\"Bad-Code\""),
            with(decision, "rationale", "\"${"x".repeat(301)}\""), with(decision, "model", "\"claude <script>\""), with(decision, "at", "\"yesterday\""),
        )) assertNull(d.take(80), readDecisions(json("""{"decisions":[$d]}""")))
        assertNull(readDecisions(json("""{"decisions":[${List(101) { decision }.joinToString(",")}]}""")))
    }

    @Test fun `call_started rows read as a muted 'Claude called'`() {
        val enter = readDecisions(json("""{"decisions":[$decision]}"""))!!.single()
        assertEquals(DecisionView("Claude called", "", true), decisionView(enter.copy(kind = "skip", reasonCode = "call_started")))
        assertEquals(DecisionView("Enter", "fresh_high_breakout", false), decisionView(enter))
        assertEquals(DecisionView("Skipped", "No free slot", false), decisionView(enter.copy(kind = "skip", reasonCode = "no_slot")))
        assertEquals("Other (a_new_kind)", decisionView(enter.copy(kind = "a_new_kind")).kind)
        assertEquals("constructor", decisionView(enter.copy(reasonCode = "constructor")).reason)
    }

    @Test fun `strategy display helpers`() {
        val list = readStrategies(json("""{"strategies":[$strategy,${with(with(with(strategy, "id", "\"b-base-reclaim\""), "enabled", "false"), "disabled_by_owner", "true")},
            ${with(with(strategy, "id", "\"c\""), "enabled", "false")}]}"""))!!
        assertEquals(id, versionOwner(list, 1000004))
        assertNull(versionOwner(list, 999))
        assertNull(versionOwner(list, null))
        assertEquals("41.25%", bpsPct(4125))
        assertEquals("60%", bpsPct(6000))
        assertEquals("0%", bpsPct(0))
        assertEquals("Enabled", strategyState(list[0]).first)
        assertEquals("Disabled by you", strategyState(list[1]).first)
        assertEquals("Disabled", strategyState(list[2]).first)
    }

    @Test fun `disable succeeds only on Core's confirmation for that strategy`() {
        assertEquals(WriteOutcome(true, at), disableOutcome(id, ApiResult.Success(json("""{"strategy_id":"$id","enabled":false}""")), at))
        for (answer in listOf("""{"strategy_id":"other","enabled":false}""", """{"strategy_id":"$id","enabled":true}""", "{}", "null")) {
            val r = disableOutcome(id, ApiResult.Success(json(answer)), at)
            assertFalse(r.ok)
            assertTrue(r.message!!.contains("did not confirm"))
        }
        fun fail(result: ApiResult<JsonElement>) = disableOutcome(id, result, at)
        assertEquals(503, fail(ApiResult.HttpError(503, null)).status)
        assertTrue(fail(ApiResult.HttpError(503, null)).message!!.contains("restart would undo it"))
        assertTrue(fail(ApiResult.Unauthorized).message!!.contains("nothing changed"))
        assertTrue(fail(ApiResult.HttpError(404, null)).message!!.contains("no strategy"))
        assertTrue(fail(ApiResult.HttpError(429, null)).message!!.contains("Wait a minute"))
        assertTrue(fail(ApiResult.Unreachable(UnreachableReason.NETWORK)).message!!.contains("unknown whether"))
    }

    @Test fun `agent status - off, stopped, paused until, backoff, calling, active`() {
        val o = read()
        assertEquals(AgentStatusKind.ACTIVE, agentStatus(o, now).kind)
        assertTrue(agentStatus(o, now).detail.contains("2 of 4 calls this hour, 17 of 48"))
        assertEquals(AgentStatusKind.OFF, agentStatus(o.copy(agent = null), now).kind)
        assertEquals(AgentStatusKind.STOPPED, agentStatus(o.copy(paused = true), now).kind)
        val soon = "2026-11-01T17:00:00Z"
        val paused = agentStatus(o.copy(agent = o.agent!!.copy(pausedUntil = soon, backoffUntil = soon)), now)
        assertEquals(AgentStatusKind.PAUSED, paused.kind)
        assertEquals(soon, paused.until)
        assertEquals(AgentStatusKind.BACKOFF, agentStatus(o.copy(agent = o.agent.copy(backoffUntil = soon)), now).kind)
        // A pause or backoff in the past no longer applies.
        val past = "2026-11-01T11:59:00Z"
        assertEquals(AgentStatusKind.ACTIVE, agentStatus(o.copy(agent = o.agent.copy(pausedUntil = past, backoffUntil = past)), now).kind)
        assertEquals(AgentStatusKind.CALLING, agentStatus(o.copy(agent = o.agent.copy(calling = true)), now).kind)
    }

    @Test fun `Claude's text drops control and format characters instead of failing the record`() {
        val scotland = "🏴󠁧󠁢󠁳󠁣󠁴󠁿"
        val evil = with(detail, "versions", "[${with(version, "rationale", Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive("evil‮text next $scotland")))}]")
        val s = readStrategy(json(evil), id)
        assertNotNull("a rationale never hides the strategy or its Disable button", s)
        assertEquals("eviltext next 🏴", s!!.versions[0].rationale)
        assertEquals("line break", plain("line\nbreak"))
        assertEquals("zerowidth end", plain("zero​width end"))
        assertNull("nothing left reads as none", plain("​‮"))
        assertNull(readStrategy(json(with(detail, "versions", "[${with(version, "rationale", "\"${"x".repeat(StrategyLimits.RATIONALE + 1)}\"")}]")), id))
    }
}
