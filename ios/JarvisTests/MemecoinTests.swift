import XCTest
@testable import Jarvis

/// Mirrors desktop/tests/memecoin.test.mjs and meme-strategies.test.mjs. Neutral test
/// data only: the mints are plausible base58, not real tokens.
final class MemecoinTests: XCTestCase {
    private let mint = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private let id = "a-survivor-momentum"
    private let at = Date(timeIntervalSince1970: 1_793_529_738) // 2026-11-01T10:42:18Z

    private func json(_ object: Any) -> Data { try! JSONSerialization.data(withJSONObject: object) }

    private func decode<T: Decodable>(_ type: T.Type, _ object: Any) -> T? { try? JSONDecoder().decode(type, from: json(object)) }

    private var position: [String: Any] {
        ["mint": mint, "strategy_version": 3, "tokens": "184211.775", "cost_usd": "25", "value_usd": "27.31",
         "opened_at": "2026-11-01T09:00:00Z", "exit_failures": 0, "stuck": false]
    }

    private var overview: [String: Any] {
        ["mode": "paper", "enabled": true, "running": true, "paused": false, "paused_at": NSNull(), "last_error": NSNull(),
         "agent": ["calling": false, "calls_last_hour": 2, "calls_last_day": 17, "max_calls_per_hour": 4, "max_calls_per_day": 48,
                   "last_call_at": NSNull(), "paused_until": NSNull(), "backoff_until": NSNull()] as [String: Any],
         "strategy_version": 3, "as_of": "2026-11-01T10:40:00Z", "stake_usd": "250", "equity_usd": "270.1158",
         "pnl": ["realised_usd": "18.4", "unrealised_usd": "2.31", "failed_tx_fees_usd": "0.38", "ai_cost_usd": "0.2142", "net_usd": "20.1158"],
         "open_positions": [position],
         "gate": ["closed_trades": 63, "min_closed_trades": 100, "duration_days": 19, "min_duration_days": 28, "trading_days": 14,
                  "min_trading_days": 7, "net_result_usd": "20.1158", "net_expectancy_usd": "0.3193", "enough_trades": false,
                  "enough_time": false, "enough_days": true, "positive_expectancy": true, "passed": false] as [String: Any],
         "goal": ["multiple_target": "2", "multiple": "1.0805", "fraction_to_goal": "0.0805", "remaining_usd": "229.8842", "reached": false] as [String: Any]]
    }

    private func read(_ object: [String: Any]) -> MemeOverview? {
        guard let value = decode(MemeOverview.self, object), Memecoin.valid(value) else { return nil }
        return value
    }

    private func merged(_ base: [String: Any], _ overrides: [String: Any]) -> [String: Any] { base.merging(overrides) { $1 } }

    func testOverviewGuardFailsClosedAndNeverShowsAnythingButPaper() throws {
        XCTAssertNotNil(read(overview))
        let off = try XCTUnwrap(read(["mode": "paper", "enabled": false, "running": false, "paused": false, "resume": "x"]))
        XCTAssertNil(off.report)
        XCTAssertEqual(Memecoin.status(off).kind, .disabled)
        var halfReport = overview
        halfReport.removeValue(forKey: "as_of")
        var noMode = overview
        noMode.removeValue(forKey: "mode")
        let gate = try XCTUnwrap(overview["gate"] as? [String: Any])
        let goal = try XCTUnwrap(overview["goal"] as? [String: Any])
        let pnl = try XCTUnwrap(overview["pnl"] as? [String: Any])
        let broken: [[String: Any]] = [
            merged(overview, ["mode": "live"]), noMode, merged(overview, ["enabled": "true"]), merged(overview, ["paused_at": "yesterday"]),
            merged(overview, ["last_error": String(repeating: "x", count: 201)]), merged(overview, ["equity_usd": 270.11]),
            merged(overview, ["equity_usd": "1e3"]), halfReport, merged(overview, ["pnl": merged(pnl, ["ai_cost_usd": NSNull()])]),
            merged(overview, ["gate": merged(gate, ["closed_trades": -1])]), merged(overview, ["gate": merged(gate, ["passed": "no"])]),
            merged(overview, ["goal": merged(goal, ["multiple": 1.08])]),
            merged(overview, ["open_positions": [merged(position, ["mint": "not base58 0OIl"])]]),
            merged(overview, ["open_positions": [position.filter { $0.key != "stuck" }]]),
            merged(overview, ["open_positions": Array(repeating: position, count: 101)]),
        ]
        for object in broken { XCTAssertNil(read(object), "\(object["mode"] ?? "no mode")") }
        // Optional fields read as nil.
        var raw = overview
        raw.removeValue(forKey: "paused_at")
        raw["goal"] = goal.filter { $0.key != "multiple" }
        raw["open_positions"] = [position.filter { $0.key != "value_usd" }]
        let o = try XCTUnwrap(read(raw))
        XCTAssertNil(o.paused_at)
        XCTAssertNil(o.report?.goal.multiple)
        XCTAssertNil(o.report?.open_positions[0].value_usd)
    }

    func testCandidateAndTradeGuardsAreDeepAndCapped() {
        let c: [String: Any] = ["mint": mint, "observed_at": "2026-11-01T10:00:00Z", "strategy_version": 3, "verdict": "reject",
                                "reason": "lp_not_secured", "price_usd": "0.0004", "liquidity_usd": "12000"]
        func candidates(_ list: [Any]) -> Bool { decode(MemeCandidates.self, ["candidates": list]).map(Memecoin.valid) ?? false }
        XCTAssertTrue(candidates([c]))
        XCTAssertTrue(candidates([]))
        XCTAssertFalse(candidates([merged(c, ["verdict": "maybe"])]))
        XCTAssertFalse(candidates([merged(c, ["reason": NSNull()])]))
        XCTAssertFalse(candidates([merged(c, ["verdict": "accept"])]))
        XCTAssertFalse(candidates([merged(c, ["price_usd": 0.02])]))
        XCTAssertFalse(candidates(Array(repeating: c, count: 101)))
        let t: [String: Any] = ["mint": mint, "strategy_version": 3, "tokens": "1", "cost_usd": "25", "opened_at": "2026-11-01T09:00:00Z",
                                "closed_at": "2026-11-01T10:00:00Z", "usd_out": "20", "exit_fees_usd": "0.1", "net_pnl_usd": "-4.92",
                                "exit_reason": "stop_loss"]
        func trades(_ list: [Any]) -> Bool { decode(MemeTrades.self, ["trades": list]).map(Memecoin.valid) ?? false }
        XCTAssertTrue(trades([t]))
        XCTAssertFalse(trades([merged(t, ["net_pnl_usd": -4.92])]))
        XCTAssertFalse(trades([merged(t, ["closed_at": ""])]))
        XCTAssertNil(decode(MemeTrades.self, ["trades": "none"]))
    }

    func testStatusPicksTheMostRestrictiveStateAndNeverSaysLive() throws {
        let o = try XCTUnwrap(read(overview))
        func with(_ overrides: [String: Any]) throws -> MemeOverview { try XCTUnwrap(read(merged(overview, overrides))) }
        XCTAssertEqual(Memecoin.status(o).kind, .running)
        XCTAssertEqual(Memecoin.status(try with(["enabled": false, "paused": true])).kind, .disabled)
        let stopped = Memecoin.status(try with(["running": false, "last_error": "paper trader did not start; see the Core log"]))
        XCTAssertEqual(stopped.kind, .stopped)
        XCTAssertTrue(stopped.detail.contains("did not start"))
        XCTAssertTrue(Memecoin.status(try with(["paused": true])).detail.contains("still valued and exited"))
        let pausedFailing = Memecoin.status(try with(["paused": true, "last_error": "tick timed out"]))
        XCTAssertEqual(pausedFailing.kind, .paused)
        XCTAssertTrue(pausedFailing.detail.contains("tick timed out"))
        XCTAssertFalse(pausedFailing.detail.contains("still valued"))
        XCTAssertEqual(Memecoin.status(try with(["last_error": "tick timed out"])).kind, .halted)
        var starting = overview
        for key in ["strategy_version", "as_of"] { starting.removeValue(forKey: key) }
        XCTAssertEqual(Memecoin.status(try XCTUnwrap(read(starting))).kind, .starting)
        for state in try [o, with(["enabled": false]), with(["paused": true]), with(["running": false])] {
            let status = Memecoin.status(state)
            XCTAssertNil((status.detail + status.label).range(of: "\\blive\\b", options: [.regularExpression, .caseInsensitive]))
        }
    }

    func testStaleStuckAndTheAgent() throws {
        let o = try XCTUnwrap(read(overview))
        let now = try XCTUnwrap(parseCoreDate("2026-11-01T10:41:00Z"))
        XCTAssertNil(Memecoin.reportAge(o, now: now))
        XCTAssertNotNil(Memecoin.reportAge(o, now: now.addingTimeInterval(Trading.staleAfter + 60)))
        XCTAssertEqual(o.stuck, 0)
        let stuck = try XCTUnwrap(read(merged(overview, ["open_positions": [merged(position, ["stuck": true, "exit_failures": 10]), position]])))
        XCTAssertEqual(stuck.stuck, 1)
        XCTAssertEqual(Memecoin.agentStatus(o, now: now).kind, .active)
        XCTAssertTrue(Memecoin.agentStatus(o, now: now).detail.contains("2 of 4 calls this hour, 17 of 48"))
        XCTAssertEqual(Memecoin.agentStatus(try XCTUnwrap(read(overview.filter { $0.key != "agent" })), now: now).kind, .off)
        XCTAssertEqual(Memecoin.agentStatus(try XCTUnwrap(read(merged(overview, ["paused": true]))), now: now).kind, .stopped)
        var agent = try XCTUnwrap(overview["agent"] as? [String: Any])
        agent["paused_until"] = "2026-11-01T11:00:00Z"
        agent["backoff_until"] = "2026-11-01T12:00:00Z"
        let paused = Memecoin.agentStatus(try XCTUnwrap(read(merged(overview, ["agent": agent]))), now: now)
        XCTAssertEqual(paused.kind, .paused)
        XCTAssertEqual(paused.until, "2026-11-01T11:00:00Z")
        agent["paused_until"] = NSNull()
        XCTAssertEqual(Memecoin.agentStatus(try XCTUnwrap(read(merged(overview, ["agent": agent]))), now: now).kind, .backoff)
    }

    func testPauseSucceedsOnlyOnCoresConfirmation() throws {
        func answer(_ object: Any) -> Result<MemeAnswer, Error> {
            (try? JSONSerialization.data(withJSONObject: object)).flatMap { try? JSONDecoder().decode(MemeAnswer.self, from: $0) }
                .map { .success($0) } ?? .failure(JarvisAPIError.invalidResponse)
        }
        XCTAssertEqual(Memecoin.pauseOutcome(answer(["paused": true]), now: at), Memecoin.Outcome(ok: true, at: "10:42:18 UTC",
                       message: "Paused at 10:42:18 UTC. New paper entries stopped; exits keep running."))
        for object in [["paused": false], [:], ["paused": "true"]] as [[String: Any]] {
            let outcome = Memecoin.pauseOutcome(answer(object), now: at)
            XCTAssertFalse(outcome.ok)
            XCTAssertTrue(outcome.message.contains("did not confirm"))
        }
        XCTAssertTrue(Memecoin.pauseOutcome(.failure(JarvisAPIError.invalidResponse), now: at).message.contains("did not confirm"))
        let refused = Memecoin.pauseOutcome(.failure(JarvisAPIError.unauthorized), now: at).message
        for part in ["10:42:18 UTC", "HTTP 401", "nothing changed", "Sign in"] { XCTAssertTrue(refused.contains(part), part) }
        let unsaved = Memecoin.pauseOutcome(.failure(JarvisAPIError.rejected(status: 503, message: nil)), now: at)
        XCTAssertTrue(unsaved.unsaved && unsaved.message.contains("restart would undo it"))
        XCTAssertTrue(Memecoin.pauseOutcome(.failure(JarvisAPIError.rejected(status: 404, message: nil)), now: at).message.contains("no memecoin pause"))
        XCTAssertTrue(Memecoin.pauseOutcome(.failure(JarvisAPIError.rejected(status: 500, message: "raw")), now: at).message.contains("HTTP 500"))
        XCTAssertTrue(Memecoin.pauseOutcome(.failure(JarvisAPIError.unreachable), now: at).message.contains("unknown whether the pause arrived"))
        XCTAssertTrue(Memecoin.pauseOutcome(.failure(JarvisAPIError.invalidConfiguration), now: at).message.contains("not confirmed"))
    }

    func testDisableSucceedsOnlyOnConfirmationForThatStrategy() throws {
        func answer(_ object: [String: Any]) throws -> Result<MemeAnswer, Error> {
            .success(try JSONDecoder().decode(MemeAnswer.self, from: json(object)))
        }
        XCTAssertTrue(Memecoin.disableOutcome(id, try answer(["strategy_id": id, "enabled": false]), now: at).ok)
        for object in [["strategy_id": "other", "enabled": false], ["strategy_id": id, "enabled": true], [:]] as [[String: Any]] {
            XCTAssertTrue(Memecoin.disableOutcome(id, try answer(object), now: at).message.contains("did not confirm"))
        }
        let fail = { (status: Int) in Memecoin.disableOutcome(self.id, .failure(JarvisAPIError.rejected(status: status, message: nil)), now: self.at) }
        XCTAssertTrue(fail(503).unsaved)
        XCTAssertTrue(fail(404).message.contains("no strategy"))
        XCTAssertTrue(fail(429).message.contains("Wait a minute"))
        XCTAssertTrue(Memecoin.disableOutcome(id, .failure(JarvisAPIError.unauthorized), now: at).message.contains("nothing changed"))
        XCTAssertTrue(Memecoin.disableOutcome(id, .failure(JarvisAPIError.timedOut), now: at).message.contains("unknown whether"))
    }

    func testDisplayHelpers() {
        XCTAssertEqual(Memecoin.shortMint(mint), "7xKX…gAsU")
        XCTAssertEqual(Memecoin.shortMint("short"), "short")
        XCTAssertEqual(Memecoin.usd("270.1158"), "$270.12")
        XCTAssertEqual(Memecoin.usd("-12.5", sign: true), "−$12.50")
        XCTAssertEqual(Memecoin.usd("0.0042"), "$0.0042")
        XCTAssertEqual(Memecoin.usd("0"), "$0.00")
        XCTAssertEqual(Memecoin.usd(nil), "—")
        XCTAssertEqual(Memecoin.progressPct(63, 100), 63)
        XCTAssertEqual(Memecoin.progressPct(150, 100), 100)
        XCTAssertEqual(Memecoin.progressPct(0, 0), 100)
        XCTAssertEqual(Memecoin.goalPct("0.0805") ?? 0, 8.05, accuracy: 0.0001)
        XCTAssertEqual(Memecoin.goalPct("1.5"), 100)
        XCTAssertNil(Memecoin.goalPct(nil))
        XCTAssertEqual(Memecoin.reasonLabel(Memecoin.rejectReasons, "lp_not_secured"), "Liquidity not locked or burned")
        XCTAssertEqual(Memecoin.reasonLabel(Memecoin.rejectReasons, "toString"), "Other (toString)")
        XCTAssertEqual(Memecoin.reasonLabel(Memecoin.rejectReasons, nil), "—")
        XCTAssertEqual(Memecoin.bpsPct(4125), "41.25%")
        XCTAssertEqual(Memecoin.bpsPct(6000), "60%")
        XCTAssertEqual(Memecoin.bpsPct(4250), "42.5%")
        XCTAssertEqual(Memecoin.plain("a\u{202E}b\u{200B}c\nd"), "abc d")
        XCTAssertNil(Memecoin.plain("\u{200B}"))
    }

    private var strategy: [String: Any] {
        ["id": id, "family": "a", "active_version": 1_000_004, "enabled": true, "disabled_by_owner": false,
         "changed_at": "2026-11-01T09:00:00Z", "changed_by": "claude",
         "performance": ["trades": 12, "net_usd": "14.82", "costs_usd": "1.2", "expectancy_usd": "1.235", "win_rate_bps": 4125,
                         "max_drawdown_usd": "6.4", "profit_factor": "1.8"] as [String: Any]]
    }

    func testStrategyIdsAndListGuard() throws {
        XCTAssertTrue(Memecoin.isStrategyId("claude-1000005"))
        for bad in ["", "A-b", "a_b", "a/b", "..", String(repeating: "a", count: 33)] { XCTAssertFalse(Memecoin.isStrategyId(bad), bad) }
        func list(_ items: [Any]) -> MemeStrategies? {
            guard let value = decode(MemeStrategies.self, ["strategies": items]), Memecoin.valid(value) else { return nil }
            return value
        }
        XCTAssertNotNil(list([strategy]))
        let performance = try XCTUnwrap(strategy["performance"] as? [String: Any])
        let broken: [[String: Any]] = [
            merged(strategy, ["id": "Bad"]), merged(strategy, ["family": "d"]), merged(strategy, ["active_version": 0]),
            merged(strategy, ["enabled": "true"]), merged(strategy, ["changed_by": String(repeating: "x", count: 65)]),
            merged(strategy, ["performance": merged(performance, ["net_usd": 14.82])]),
            merged(strategy, ["performance": merged(performance, ["win_rate_bps": 10001])]), strategy.filter { $0.key != "performance" },
        ]
        for object in broken { XCTAssertNil(list([object]), "\(object["id"] ?? "")") }
        XCTAssertNil(list(Array(repeating: strategy, count: 21)))
        let read = try XCTUnwrap(list([merged(strategy, ["family": NSNull(), "performance": performance.filter { $0.key != "profit_factor" }])]))
        XCTAssertNil(read.strategies[0].family)
        XCTAssertNil(read.strategies[0].performance.profit_factor)
        XCTAssertEqual(Memecoin.state(read.strategies[0]).label, "Enabled")
        XCTAssertEqual(Memecoin.versionOwner(read.strategies, 1_000_004), id)
        XCTAssertNil(Memecoin.versionOwner(read.strategies, 999))
        XCTAssertNil(Memecoin.versionOwner(read.strategies, nil))
    }

    func testStrategyDetailGuardReadsDiffsAndOnlyTheAskedId() throws {
        let version: [String: Any] = [
            "version": 1_000_004, "parent_version": 1_000_003, "author": "claude", "created_at": "2026-11-01T09:00:00Z",
            "config": ["id": id, "version": 1_000_004, "take_profit_bps": 7500, "name": "a/b"] as [String: Any],
            "diff": [["field": "take_profit_bps", "from": 5000, "to": 7500], ["field": "trailing_stop_bps", "from": NSNull(), "to": 1500],
                     ["field": "added", "to": 1.5]] as [[String: Any]],
            "rationale": "Wider\u{202E} target", "backtest": ["status": "unbacktested", "mints": 3, "points": 40] as [String: Any],
        ]
        let good = merged(strategy, ["active_config_sha256": String(repeating: "ab", count: 32), "versions": [version], "gate": NSNull()])
        func detail(_ object: [String: Any], _ asked: String? = nil) -> MemeStrategyDetail? {
            guard let value = decode(MemeStrategyDetail.self, object), Memecoin.valid(value, id: asked ?? id) else { return nil }
            return value
        }
        let read = try XCTUnwrap(detail(good))
        let diff = try XCTUnwrap(read.versions[0].diff)
        XCTAssertEqual(diff.map { $0.from?.text ?? "—" }, ["5000", "null", "—"])
        XCTAssertEqual(diff.map { $0.to?.text ?? "—" }, ["7500", "1500", "1.5"])
        XCTAssertEqual(read.versions[0].parameters.map(\.field), ["name", "take_profit_bps"])
        XCTAssertEqual(read.versions[0].parameters.first?.value, "\"a/b\"")
        XCTAssertEqual(Memecoin.plain(read.versions[0].rationale), "Wider target")
        XCTAssertNil(detail(good, "b-base-reclaim"))
        let broken: [[String: Any]] = [
            merged(good, ["active_config_sha256": "XYZ"]), merged(good, ["versions": [merged(version, ["author": "owner"])]]),
            merged(good, ["versions": [merged(version, ["rationale": String(repeating: "x", count: 601)])]]),
            merged(good, ["versions": [merged(version, ["diff": [["field": "x", "from": String(repeating: "a", count: 2048), "to": 1]]])]]),
            merged(good, ["versions": [merged(version, ["diff": "none"])]]),
            merged(good, ["versions": [merged(version, ["backtest": ["status": "great", "mints": 1, "points": 2]])]]),
            merged(good, ["versions": [merged(version, ["backtest": ["status": "ok", "mints": 1, "points": 2]])]]),
            merged(good, ["versions": Array(repeating: version, count: 21)]),
        ]
        for object in broken { XCTAssertNil(detail(object)) }
    }

    func testDecisionGuardAndView() throws {
        let good: [String: Any] = ["id": 7, "at": "2026-11-01T09:00:00Z", "call_id": "call_1", "kind": "enter", "strategy_version": 1_000_004,
                                   "mint": mint, "size_bps": 5000, "reason_code": "fresh_high_breakout", "rationale": "Breakout",
                                   "model": "claude-sonnet-4.5"]
        func decisions(_ list: [Any]) -> MemeDecisions? {
            guard let value = decode(MemeDecisions.self, ["decisions": list]), Memecoin.valid(value) else { return nil }
            return value
        }
        let enter = try XCTUnwrap(decisions([good])?.decisions.first)
        XCTAssertTrue(Memecoin.decisionView(enter) == ("Enter", "fresh_high_breakout", false))
        let broken: [[String: Any]] = [
            merged(good, ["kind": "Enter"]), merged(good, ["call_id": "a b"]), merged(good, ["mint": "not base58 0OIl"]),
            merged(good, ["size_bps": 0]), merged(good, ["size_bps": 10001]), merged(good, ["reason_code": "Bad-Code"]),
            merged(good, ["rationale": String(repeating: "x", count: 301)]), merged(good, ["model": "claude <script>"]),
            merged(good, ["at": "yesterday"]),
        ]
        for object in broken { XCTAssertNil(decisions([object]), "\(object)") }
        XCTAssertNil(decisions(Array(repeating: good, count: 101)))
        let started = try XCTUnwrap(decisions([merged(good, ["kind": "skip", "reason_code": "call_started"])])?.decisions.first)
        XCTAssertTrue(Memecoin.decisionView(started) == ("Claude called", "", true))
        let skipped = try XCTUnwrap(decisions([merged(good, ["kind": "skip", "reason_code": "no_slot"])])?.decisions.first)
        XCTAssertTrue(Memecoin.decisionView(skipped) == ("Skipped", "No free slot", false))
        let unknown = try XCTUnwrap(decisions([merged(good, ["kind": "a_new_kind"])])?.decisions.first)
        XCTAssertEqual(Memecoin.decisionView(unknown).kind, "Other (a_new_kind)")
    }
}

/// The pause and the disable through the app model against a fixture Core: the lock gate
/// and the id check.
@MainActor
final class MemecoinWriteModelTests: XCTestCase {
    private func signedIn() -> JarvisAppModel {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MemeCoreProtocol.self]
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: URLSession(configuration: configuration))
        return JarvisAppModel(signedInChat: ChatService(api: api, tokenLoader: { "fixture-session-not-a-secret" }), api: api, realtime: false)
    }

    func testTheTighteningActionsGoToTheirRoutesAndReadCoresAnswer() async throws {
        MemeCoreProtocol.reset(status: 200, body: #"{"paused":true}"#)
        let model = signedIn()
        let paused = try await model.pauseMemecoin()
        XCTAssertEqual(paused.paused, true)
        MemeCoreProtocol.reset(status: 200, body: #"{"strategy_id":"a-survivor-momentum","enabled":false}"#)
        let disabled = try await model.disableMemeStrategy("a-survivor-momentum")
        XCTAssertTrue(Memecoin.disableOutcome("a-survivor-momentum", .success(disabled)).ok)
        do {
            _ = try await model.disableMemeStrategy("../pause")
            XCTFail("sent an unsafe id")
        } catch {}
        XCTAssertEqual(MemeCoreProtocol.requests(), ["POST /v1/trading/meme/strategies/a-survivor-momentum/disable"])
    }

    func testALockedAppSendsNoPause() async {
        MemeCoreProtocol.reset(status: 200, body: #"{"paused":true}"#)
        let model = signedIn()
        model.lockWhenBackgrounded()
        do {
            _ = try await model.pauseMemecoin()
            XCTFail("sent while locked")
        } catch {}
        XCTAssertEqual(MemeCoreProtocol.requests(), [])
    }
}

private final class MemeCoreProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var log: [String] = []
    private static var status = 200
    private static var body = "{}"

    static func reset(status: Int, body: String) {
        lock.withLock { log = []; self.status = status; self.body = body }
    }
    static func requests() -> [String] { lock.withLock { log } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, body) = Self.lock.withLock {
            Self.log.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")")
            return (Self.status, Self.body)
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
