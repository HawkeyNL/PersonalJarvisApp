import Foundation

// Memecoin PAPER trader (/v1/trading/meme/*), as in desktop memecoin.ts and
// memeStrategies.ts: the reads, their checks, the status wording and the outcomes of the
// two tightening actions (pause entries, disable a strategy). It is a simulation: no
// wallet, no signer, no execution path. Resuming and enabling need the Home Node or a
// device signature on a Mac, so this app has neither. Required fields are checked
// strictly and anything that is not "paper" fails closed.

struct MemeOverview: Decodable {
    struct Agent: Decodable {
        let calling: Bool
        let calls_last_hour: Int
        let calls_last_day: Int
        let max_calls_per_hour: Int
        let max_calls_per_day: Int
        let last_call_at: String?
        /// Three invalid replies in a row pause decisions.
        let paused_until: String?
        /// A plan limit backs every call off.
        let backoff_until: String?
    }

    struct Position: Decodable {
        let mint: String
        let strategy_version: Int
        let tokens: String
        let cost_usd: String
        /// Executable value along the pool curve; nil without a fresh mark.
        let value_usd: String?
        let opened_at: String
        let exit_failures: Int
        /// Exits stopped after repeated failures; the position keeps its slot.
        let stuck: Bool
    }

    struct Gate: Decodable {
        let closed_trades: Int
        let min_closed_trades: Int
        let duration_days: Int
        let min_duration_days: Int
        let trading_days: Int
        let min_trading_days: Int
        let net_result_usd: String
        let net_expectancy_usd: String
        let enough_trades: Bool
        let enough_time: Bool
        let enough_days: Bool
        let positive_expectancy: Bool
        let passed: Bool
    }

    struct Goal: Decodable {
        let multiple_target: String
        let multiple: String?
        let fraction_to_goal: String?
        let remaining_usd: String?
        let reached: Bool?
    }

    struct Pnl: Decodable {
        let realised_usd: String
        let unrealised_usd: String
        let failed_tx_fees_usd: String
        let ai_cost_usd: String
        let net_usd: String
    }

    /// The trader's report; Core sends its fields together once a tick produced one.
    struct Report: Decodable {
        let strategy_version: Int
        let as_of: String
        let stake_usd: String
        let equity_usd: String
        let pnl: Pnl
        let open_positions: [Position]
        let gate: Gate
        let goal: Goal
    }

    let mode: String
    let enabled: Bool
    let running: Bool
    var paused: Bool
    let paused_at: String?
    /// A fixed reason from Core, never error detail.
    let last_error: String?
    /// Nil: Claude does not decide.
    let agent: Agent?
    let report: Report?

    private enum Keys: String, CodingKey { case mode, enabled, running, paused, paused_at, last_error, agent, as_of, strategy_version }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        mode = try container.decode(String.self, forKey: .mode)
        enabled = try container.decode(Bool.self, forKey: .enabled)
        running = try container.decode(Bool.self, forKey: .running)
        paused = try container.decode(Bool.self, forKey: .paused)
        paused_at = try container.decodeIfPresent(String.self, forKey: .paused_at)
        last_error = try container.decodeIfPresent(String.self, forKey: .last_error)
        agent = try container.decodeIfPresent(Agent.self, forKey: .agent)
        report = container.contains(.as_of) || container.contains(.strategy_version) ? try Report(from: decoder) : nil
    }

    var stuck: Int { report?.open_positions.filter(\.stuck).count ?? 0 }
}

struct MemeCandidate: Decodable {
    enum Verdict: String, Decodable { case accept, reject }

    let mint: String
    let observed_at: String
    let strategy_version: Int
    let verdict: Verdict
    let reason: String?
    let price_usd: String?
    let liquidity_usd: String?
}

struct MemeCandidates: Decodable { let candidates: [MemeCandidate] }

struct MemeTrade: Decodable {
    let mint: String
    let strategy_version: Int
    let tokens: String
    let cost_usd: String
    let opened_at: String
    let closed_at: String
    let usd_out: String
    let exit_fees_usd: String
    let net_pnl_usd: String
    let exit_reason: String
}

struct MemeTrades: Decodable { let trades: [MemeTrade] }

struct MemeStrategy: Decodable, Identifiable {
    enum Family: String, Decodable {
        case a, b, c
        var label: String {
            switch self {
            case .a: "A · survivor momentum"
            case .b: "B · base reclaim"
            case .c: "C · established trend"
            }
        }
    }

    struct Performance: Decodable {
        let trades: Int
        let net_usd: String
        let costs_usd: String
        let expectancy_usd: String
        let win_rate_bps: Int
        let max_drawdown_usd: String
        let profit_factor: String?
    }

    let id: String
    let family: Family?
    let active_version: Int
    let enabled: Bool
    /// Only a device signature enables it again.
    let disabled_by_owner: Bool
    let changed_at: String
    let changed_by: String
    let performance: Performance
}

struct MemeStrategies: Decodable { let strategies: [MemeStrategy] }

/// Any JSON value, for parameters shown as compact JSON text.
indirect enum JSONValue: Decodable, Equatable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(Bool.self) { self = .bool(value) }
        else if let value = try? container.decode(Double.self) { self = .number(value) }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode([JSONValue].self) { self = .array(value) }
        else { self = .object(try container.decode([String: JSONValue].self)) }
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        return encoder
    }()

    /// Compact JSON, keys sorted.
    var text: String {
        switch self {
        case .null: return "null"
        case let .bool(value): return value ? "true" : "false"
        case let .number(value):
            return value == value.rounded() && abs(value) < 1e15 ? String(Int64(value)) : String(value)
        case let .string(value):
            return (try? Self.encoder.encode(value)).map { String(decoding: $0, as: UTF8.self) } ?? "\"\""
        case let .array(values): return "[" + values.map(\.text).joined(separator: ",") + "]"
        case let .object(values):
            return "{" + values.keys.sorted().map { key in JSONValue.string(key).text + ":" + (values[key]?.text ?? "null") }.joined(separator: ",") + "}"
        }
    }
}

struct MemeStrategyDetail: Decodable {
    struct DiffRow: Decodable {
        let field: String
        /// Nil when the field is absent on that side.
        let from: JSONValue?
        let to: JSONValue?

        private enum Keys: String, CodingKey { case field, from, to }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: Keys.self)
            field = try container.decode(String.self, forKey: .field)
            from = container.contains(.from) ? try container.decode(JSONValue.self, forKey: .from) : nil
            to = container.contains(.to) ? try container.decode(JSONValue.self, forKey: .to) : nil
        }
    }

    struct Backtest: Decodable {
        enum Status: String, Decodable { case ok, unbacktested, failed }
        let status: Status
        let mints: Int
        let points: Int
        let trades: Int?
        let net_usd: String?
        let expectancy_usd: String?
        let win_rate_bps: Int?
        let max_drawdown_usd: String?
        let profit_factor: String?
    }

    struct Version: Decodable {
        enum Author: String, Decodable { case seed, claude }
        let version: Int
        let parent_version: Int?
        let author: Author
        let created_at: String
        let config: [String: JSONValue]?
        /// Core's per-field diff to the parent; nil without a parent.
        let diff: [DiffRow]?
        /// Written by Claude; display only, through `Memecoin.plain`.
        let rationale: String?
        let backtest: Backtest?

        /// Parameter fields as compact JSON text, without the identity fields.
        var parameters: [(field: String, value: String)] {
            (config ?? [:]).keys.sorted().filter { !["id", "version", "parent", "family"].contains($0) }
                .map { ($0, config?[$0]?.text ?? "null") }
        }
    }

    struct Gate: Decodable {
        let at: String
        let closed_trades: Int
        let min_closed_trades: Int
        let duration_days: Int
        let min_duration_days: Int
        let trading_days: Int
        let min_trading_days: Int
        let net_result_usd: String
        let net_expectancy_usd: String
    }

    let strategy: MemeStrategy
    let active_config_sha256: String?
    let versions: [Version]
    let gate: Gate?

    private enum Keys: String, CodingKey { case active_config_sha256, versions, gate }

    init(from decoder: Decoder) throws {
        strategy = try MemeStrategy(from: decoder)
        let container = try decoder.container(keyedBy: Keys.self)
        active_config_sha256 = try container.decodeIfPresent(String.self, forKey: .active_config_sha256)
        versions = try container.decode([Version].self, forKey: .versions)
        gate = try container.decodeIfPresent(Gate.self, forKey: .gate)
    }
}

struct MemeDecision: Decodable {
    let at: String
    let call_id: String
    let kind: String
    let strategy_version: Int?
    let mint: String?
    let size_bps: Int?
    let reason_code: String?
    /// Written by Claude; display only, through `Memecoin.plain`.
    let rationale: String?
    let model: String?
}

struct MemeDecisions: Decodable { let decisions: [MemeDecision] }

/// Core's answer to the pause and the disable: only an exact confirmation counts.
struct MemeAnswer: Decodable {
    let paused: Bool?
    let strategy_id: String?
    let enabled: Bool?

    private enum Keys: String, CodingKey { case paused, strategy_id, enabled }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        paused = try? container.decodeIfPresent(Bool.self, forKey: .paused)
        strategy_id = try? container.decodeIfPresent(String.self, forKey: .strategy_id)
        enabled = try? container.decodeIfPresent(Bool.self, forKey: .enabled)
    }
}

enum Memecoin {
    /// Rows asked for per list; Core allows 1..100.
    static let listLimit = 50
    static let overviewPath = "/v1/trading/meme/overview"
    static let candidatesPath = "/v1/trading/meme/candidates?limit=\(listLimit)"
    static let tradesPath = "/v1/trading/meme/trades?limit=\(listLimit)"
    static let strategiesPath = "/v1/trading/meme/strategies"
    static let decisionsPath = "/v1/trading/meme/decisions?limit=\(listLimit)"

    static func strategyPath(_ id: String) -> String? { isStrategyId(id) ? "\(strategiesPath)/\(id)" : nil }

    // MARK: Response checks: anything unexpected reads as failed (fail closed).

    private static func text(_ value: String?, _ max: Int = 200) -> Bool { (value?.count ?? 0) <= max }
    private static func time(_ value: String) -> Bool { value.count <= 64 && parseCoreDate(value) != nil }
    private static func optTime(_ value: String?) -> Bool { value.map(time) ?? true }
    private static func dec(_ value: String) -> Bool { value.count <= 64 && Trading.parse(value) != nil }
    private static func optDec(_ value: String?) -> Bool { value.map(dec) ?? true }
    private static func counts(_ values: Int...) -> Bool { values.allSatisfy { $0 >= 0 } }
    private static func validVersion(_ value: Int) -> Bool { (1...0xFFFF_FFFF).contains(value) }

    static func isMint(_ value: String) -> Bool {
        (1...64).contains(value.count) && value.unicodeScalars.allSatisfy {
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".unicodeScalars.contains($0)
        }
    }

    /// Core's strategy id rule; checked before an id reaches a path.
    static func isStrategyId(_ value: String) -> Bool {
        (1...32).contains(value.utf8.count) && value.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }
    }

    private static func isCode(_ value: String, _ max: Int = 32) -> Bool {
        guard let first = value.utf8.first, (97...122).contains(first), value.utf8.count <= max else { return false }
        return value.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 95 }
    }

    private static func matches(_ value: String, _ allowed: String, max: Int) -> Bool {
        (1...max).contains(value.utf8.count) && value.unicodeScalars.allSatisfy { allowed.unicodeScalars.contains($0) }
    }

    private static let alphanumerics = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    static func valid(_ o: MemeOverview) -> Bool {
        guard o.mode == "paper", optTime(o.paused_at), text(o.last_error) else { return false }
        if let a = o.agent {
            guard counts(a.calls_last_hour, a.calls_last_day, a.max_calls_per_hour, a.max_calls_per_day),
                  optTime(a.last_call_at), optTime(a.paused_until), optTime(a.backoff_until) else { return false }
        }
        guard let r = o.report else { return true }
        let p = r.pnl, g = r.gate
        return r.strategy_version >= 0 && time(r.as_of) && dec(r.stake_usd) && dec(r.equity_usd)
            && [p.realised_usd, p.unrealised_usd, p.failed_tx_fees_usd, p.ai_cost_usd, p.net_usd].allSatisfy(dec)
            && r.open_positions.count <= 100 && r.open_positions.allSatisfy { position in
                isMint(position.mint) && counts(position.strategy_version, position.exit_failures) && dec(position.tokens)
                    && dec(position.cost_usd) && optDec(position.value_usd) && time(position.opened_at)
            }
            && counts(g.closed_trades, g.min_closed_trades, g.duration_days, g.min_duration_days, g.trading_days, g.min_trading_days)
            && dec(g.net_result_usd) && dec(g.net_expectancy_usd)
            && dec(r.goal.multiple_target) && optDec(r.goal.multiple) && optDec(r.goal.fraction_to_goal) && optDec(r.goal.remaining_usd)
    }

    static func valid(_ list: MemeCandidates) -> Bool {
        list.candidates.count <= 100 && list.candidates.allSatisfy { c in
            isMint(c.mint) && time(c.observed_at) && c.strategy_version >= 0 && optDec(c.price_usd) && optDec(c.liquidity_usd)
                && (c.verdict == .accept ? c.reason == nil : c.reason.map { $0.count <= 64 } ?? false)
        }
    }

    static func valid(_ list: MemeTrades) -> Bool {
        list.trades.count <= 100 && list.trades.allSatisfy { t in
            isMint(t.mint) && t.strategy_version >= 0 && time(t.opened_at) && time(t.closed_at)
                && [t.tokens, t.cost_usd, t.usd_out, t.exit_fees_usd, t.net_pnl_usd].allSatisfy(dec) && t.exit_reason.count <= 64
        }
    }

    static func valid(_ s: MemeStrategy) -> Bool {
        let p = s.performance
        return isStrategyId(s.id) && validVersion(s.active_version) && time(s.changed_at) && text(s.changed_by, 64)
            && p.trades >= 0 && (0...10_000).contains(p.win_rate_bps)
            && [p.net_usd, p.costs_usd, p.expectancy_usd, p.max_drawdown_usd].allSatisfy(dec) && optDec(p.profit_factor)
    }

    static func valid(_ list: MemeStrategies) -> Bool { list.strategies.count <= 20 && list.strategies.allSatisfy(valid) }

    /// One strategy; also invalid when Core answers for another id.
    static func valid(_ d: MemeStrategyDetail, id: String) -> Bool {
        guard valid(d.strategy), d.strategy.id == id, d.versions.count <= 20 else { return false }
        if let hash = d.active_config_sha256, !(hash.count == 64 && hash.allSatisfy { $0.isHexDigit && !$0.isUppercase }) { return false }
        if let g = d.gate {
            guard time(g.at), counts(g.closed_trades, g.min_closed_trades, g.duration_days, g.min_duration_days, g.trading_days, g.min_trading_days),
                  dec(g.net_result_usd), dec(g.net_expectancy_usd) else { return false }
        }
        return d.versions.allSatisfy { v in
            validVersion(v.version) && (v.parent_version.map(validVersion) ?? true) && time(v.created_at)
                && (v.config.map { JSONValue.object($0).text.count <= 8192 } ?? true)
                && (v.diff.map { rows in
                    rows.count <= 64 && rows.allSatisfy { $0.field.count <= 64 && ($0.from?.text.count ?? 0) <= 2048 && ($0.to?.text.count ?? 0) <= 2048 }
                } ?? true)
                && (v.rationale?.unicodeScalars.count ?? 0) <= 600 && (v.backtest.map(valid) ?? true)
        }
    }

    private static func valid(_ b: MemeStrategyDetail.Backtest) -> Bool {
        guard counts(b.mints, b.points) else { return false }
        guard b.status == .ok else { return true }
        guard let trades = b.trades, let rate = b.win_rate_bps, let net = b.net_usd, let expectancy = b.expectancy_usd,
              let drawdown = b.max_drawdown_usd else { return false }
        return trades >= 0 && rate >= 0 && [net, expectancy, drawdown].allSatisfy(dec) && optDec(b.profit_factor)
    }

    static func valid(_ list: MemeDecisions) -> Bool {
        list.decisions.count <= 100 && list.decisions.allSatisfy { d in
            time(d.at) && matches(d.call_id, alphanumerics + "_-", max: 64) && isCode(d.kind)
                && (d.strategy_version.map(validVersion) ?? true) && (d.mint.map(isMint) ?? true)
                && (d.size_bps.map { (1...10_000).contains($0) } ?? true) && (d.reason_code.map { isCode($0) } ?? true)
                && (d.rationale?.unicodeScalars.count ?? 0) <= 300 && (d.model.map { matches($0, alphanumerics + "_.:-", max: 64) } ?? true)
        }
    }

    // MARK: Status

    struct Status: Equatable {
        enum Kind: Equatable { case disabled, stopped, paused, halted, starting, running }
        let kind: Kind
        let label: String
        let style: Badge.Style
        let detail: String
    }

    /// One honest headline for the trader, most restrictive first: off, not running,
    /// paused, last tick stopped, no report yet, running.
    static func status(_ o: MemeOverview) -> Status {
        if !o.enabled {
            return Status(kind: .disabled, label: "Off", style: .neutral,
                          detail: "Paper trading is off, which is the default. Only the owner can turn it on, in Core's configuration on the Home Node; this app cannot.")
        }
        if !o.running {
            return Status(kind: .stopped, label: "Not running", style: .negative,
                          detail: o.last_error.map { "\($0)." } ?? "Paper trading is turned on, but the trader is not running. The Core log says why.")
        }
        if o.paused {
            // A failing tick also stops exits, so do not claim they run.
            return Status(kind: .paused, label: "Paused", style: .negative, detail: o.last_error.map {
                "No new paper entries. The last tick also stopped: \($0). Open paper positions may not be valued or exited until it recovers; the Core log has the details."
            } ?? "No new paper entries. Open paper positions are still valued and exited.")
        }
        if let error = o.last_error {
            return Status(kind: .halted, label: "Tick stopped", style: .negative,
                          detail: "The last tick stopped: \(error). Core tries again every minute; the Core log has the details.")
        }
        if o.report == nil {
            return Status(kind: .starting, label: "Starting", style: .neutral,
                          detail: "The trader is running but has not reported yet. A tick runs every minute.")
        }
        return Status(kind: .running, label: "Running · paper", style: .accent,
                      detail: "Simulating on read-only market data. Exits and entries run every minute.")
    }

    struct AgentStatus: Equatable {
        enum Kind: Equatable { case off, stopped, paused, backoff, calling, active }
        let kind: Kind
        let label: String
        let style: Badge.Style
        let detail: String
        let until: String?
    }

    private static func later(_ time: String?, _ now: Date) -> Bool { time.flatMap(parseCoreDate).map { $0 > now } ?? false }

    /// Claude's state as the strategy agent, most restrictive first. Code exits run in every state.
    static func agentStatus(_ o: MemeOverview, now: Date) -> AgentStatus {
        guard let a = o.agent else {
            return AgentStatus(kind: .off, label: "Off", style: .neutral,
                               detail: "Claude does not decide. Each enabled strategy enters on its own signal. The paper profile's agent section on the Home Node turns Claude on.",
                               until: nil)
        }
        let exits = "Code exits (stop loss, trailing stop, take profit, liquidity drop, time stop) keep running."
        if !o.enabled || !o.running || o.paused {
            return AgentStatus(kind: .stopped, label: "Not deciding", style: .neutral,
                               detail: "The trader is \(o.paused ? "paused" : "not running"), so Claude makes no new calls. \(exits)", until: nil)
        }
        if later(a.paused_until, now) {
            return AgentStatus(kind: .paused, label: "Paused", style: .negative,
                               detail: "Three invalid replies in a row paused Claude's decisions. \(exits)", until: a.paused_until)
        }
        if later(a.backoff_until, now) {
            return AgentStatus(kind: .backoff, label: "Backing off", style: .negative, detail: "A plan limit was hit, so calls wait. \(exits)",
                               until: a.backoff_until)
        }
        let budget = "\(a.calls_last_hour) of \(a.max_calls_per_hour) calls this hour, \(a.calls_last_day) of \(a.max_calls_per_day) in the last day."
        if a.calling { return AgentStatus(kind: .calling, label: "Deciding now", style: .accent, detail: "A call is running. \(budget)", until: nil) }
        return AgentStatus(kind: .active, label: "Active", style: .accent,
                           detail: "Claude decides paper entries and early exits, at most every 15 minutes. \(budget)", until: nil)
    }

    /// How old the report is when that is past the stale limit; else nil.
    static func reportAge(_ o: MemeOverview, now: Date) -> TimeInterval? {
        guard let report = o.report else { return nil }
        let age = parseCoreDate(report.as_of).map { now.timeIntervalSince($0) } ?? .infinity
        return age > Trading.staleAfter ? age : nil
    }

    static let resumeText = "Resuming is configuration-only, on the Home Node: copy the paused-at time into JARVIS_MEMECOIN_PAPER_RESUME_PAUSED_AT in core.env and restart Core. This app cannot resume."
    static let noEdgeText = "Paper results are a filter, not proof. Even 100 closed trades cannot prove an edge: memecoin results hang on a few outliers, and paper fills are simulated."
    /// Enabling is signed with Touch ID on a Mac; this app cannot sign it.
    static let enableElsewhere = "Enable from your Mac"

    /// Why a read failed, in desktop's words.
    static func failureText(_ reason: LoadFailure?) -> String {
        switch reason {
        case nil: "This Core has no memecoin paper trader yet. Update Core on your Home Node."
        case .network?: "No answer from your Home Node. Check the connection."
        case .signin?: "Your session is not valid. Sign in again from Settings."
        case .unavailable?: "Core could not read the paper trader's data just now. Try again in a minute."
        default: "Core returned an answer this app does not understand, or an error. Try again later."
        }
    }

    // MARK: Pause and disable outcomes

    struct Outcome: Equatable {
        let ok: Bool
        let at: String
        let message: String
        /// 503: Core acted in memory but could not store it.
        var unsaved = false
    }

    private static let clock: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "HH:mm:ss 'UTC'"
        return formatter
    }()

    /// The pause's outcome for the owner. Every message says when, what Core answered and
    /// whether the pause is in effect, so it matches Core's audit log.
    static func pauseOutcome(_ result: Result<MemeAnswer, Error>, now: Date = Date()) -> Outcome {
        let at = clock.string(from: now)
        switch result {
        case let .success(answer):
            return answer.paused == true ? Outcome(ok: true, at: at, message: "Paused at \(at). New paper entries stopped; exits keep running.")
                : Outcome(ok: false, at: at, message: "Core answered at \(at), but did not confirm the pause. Reload the status to check it.")
        case let .failure(error):
            if let known = error as? JarvisAPIError, known == .invalidResponse || known == .responseTooLarge {
                return Outcome(ok: false, at: at, message: "Core answered at \(at), but did not confirm the pause. Reload the status to check it.")
            }
            if Jobs.isNetwork(error) {
                return Outcome(ok: false, at: at, message: "No answer from the Home Node at \(at). It is unknown whether the pause arrived; reload the status before trying again.")
            }
            switch Jobs.status(error) {
            case 401?:
                return Outcome(ok: false, at: at, message: "Pause refused at \(at) (HTTP 401): your session is not valid, so nothing changed. Sign in again from Settings, then pause again.")
            case 503?:
                return Outcome(ok: false, at: at, message: "Pause not saved at \(at) (HTTP 503): Core stopped new entries, but could not store the pause, so a restart would undo it. Pause again until it is confirmed.", unsaved: true)
            case let status? where [404, 405, 501].contains(status):
                return Outcome(ok: false, at: at, message: "Pause not available at \(at) (HTTP \(status)): this Core has no memecoin pause, so nothing changed.")
            case let status?:
                return Outcome(ok: false, at: at, message: "Pause failed at \(at) (HTTP \(status)). The pause is not confirmed; try again.")
            case nil:
                return Outcome(ok: false, at: at, message: "Pause not confirmed at \(at). Reload the status before trying again.")
            }
        }
    }

    /// The disable's outcome; every message says when and whether it holds.
    static func disableOutcome(_ id: String, _ result: Result<MemeAnswer, Error>, now: Date = Date()) -> Outcome {
        let at = clock.string(from: now)
        switch result {
        case let .success(answer):
            return answer.strategy_id == id && answer.enabled == false
                ? Outcome(ok: true, at: at, message: "Disabled at \(at). No new paper entries; open positions keep exiting.")
                : Outcome(ok: false, at: at, message: "Core answered at \(at), but did not confirm the disable. Reload to check it.")
        case let .failure(error):
            if let known = error as? JarvisAPIError, known == .invalidResponse || known == .responseTooLarge {
                return Outcome(ok: false, at: at, message: "Core answered at \(at), but did not confirm the disable. Reload to check it.")
            }
            if Jobs.isNetwork(error) {
                return Outcome(ok: false, at: at, message: "No answer from the Home Node at \(at). It is unknown whether the disable arrived; reload before trying again.")
            }
            switch Jobs.status(error) {
            case 401?:
                return Outcome(ok: false, at: at, message: "Disable refused at \(at) (HTTP 401): your session is not valid, so nothing changed. Sign in again from Settings.")
            case 503?:
                return Outcome(ok: false, at: at, message: "Disable not saved at \(at) (HTTP 503): Core stopped this strategy's entries, but a restart would undo it. Disable again until it is confirmed.", unsaved: true)
            case 404?:
                return Outcome(ok: false, at: at, message: "Core has no strategy \(id) (HTTP 404), so nothing changed.")
            case 429?:
                return Outcome(ok: false, at: at, message: "Too many strategy changes at \(at) (HTTP 429). Wait a minute, then try again.")
            case let status?:
                return Outcome(ok: false, at: at, message: "Disable failed at \(at) (HTTP \(status)). It is not confirmed; try again.")
            case nil:
                return Outcome(ok: false, at: at, message: "Disable not confirmed at \(at). Reload before trying again.")
            }
        }
    }

    // MARK: Display

    /// "7xKX…gAsU": a mint is long and unreadable.
    static func shortMint(_ mint: String) -> String { mint.count > 12 ? "\(mint.prefix(4))…\(mint.suffix(4))" : mint }

    /// Dollars; amounts below a cent keep their digits so a fee never reads $0.00.
    static func usd(_ value: String?, sign: Bool = false) -> String {
        var fraction = value?.components(separatedBy: ".").dropFirst().first ?? ""
        while fraction.hasSuffix("0") { fraction.removeLast() }
        let tiny = Trading.chartNumber(value).map { $0 != 0 && abs($0) < 0.01 } ?? false
        return Trading.formatMoney(value, "USD", sign: sign, places: tiny ? min(8, max(2, fraction.count)) : 2)
    }

    /// Share of a minimum reached, 0..100, for a meter.
    static func progressPct(_ have: Int, _ need: Int) -> Double { need <= 0 ? 100 : min(100, max(0, Double(have) / Double(need) * 100)) }

    /// fraction_to_goal (0..1 as a decimal string) as 0..100; nil when unknown.
    static func goalPct(_ fraction: String?) -> Double? { Trading.chartNumber(fraction).map { min(100, max(0, $0 * 100)) } }

    /// Basis points as a percentage: 4250 -> "42.5%".
    static func bpsPct(_ bps: Int) -> String {
        var text = String(format: "%.2f", Double(bps) / 100)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return "\(text)%"
    }

    /// Claude's text as it reads: control characters become spaces, format characters
    /// (bidi overrides, zero-width, tags) are dropped. Nil when nothing is left.
    static func plain(_ text: String?) -> String? {
        guard let text else { return nil }
        var out = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .control: out.append(" ")
            case .format: continue
            default: out.append(scalar.value == 0x2028 || scalar.value == 0x2029 ? " " : scalar)
            }
        }
        return out.isEmpty ? nil : String(out)
    }

    static let rejectReasons: [String: String] = [
        "data_unavailable": "Market data unavailable", "stale_snapshot": "Market data too old", "invalid_snapshot": "Market data inconsistent",
        "denied": "On the deny list", "mint_authority_active": "Mint authority still active",
        "freeze_authority_active": "Freeze authority still active", "unknown_extension": "Unknown token extension",
        "extension_not_allowed": "Token extension not allowed", "extension_authority_active": "Extension authority active",
        "lp_not_secured": "Liquidity not locked or burned", "lp_unlocks_too_soon": "Liquidity unlocks too soon",
        "liquidity_too_low": "Liquidity too low", "pool_too_young": "Pool too young",
        "holder_concentration_too_high": "Top holders own too much", "no_sell_route": "No route to sell",
        "sell_impact_too_high": "Selling moves the price too much",
    ]

    static let exitReasons: [String: String] = [
        "liquidity_drop": "Liquidity dropped", "stop_loss": "Stop loss", "take_profit": "Take profit", "time_stop": "Time stop",
        "trailing_stop": "Trailing stop", "agent_exit": "Exited by Claude",
    ]

    /// A known code's label; an unknown one is shown as its code, marked as such.
    static func reasonLabel(_ labels: [String: String], _ code: String?) -> String {
        guard let code else { return Trading.dash }
        return labels[code] ?? "Other (\(code))"
    }

    private static let kinds: [String: String] = [
        "enter": "Enter", "exit": "Exit", "hold": "Hold", "skip": "Skipped", "propose": "Proposal",
        "worker_unavailable": "Worker busy", "invalid_output": "Invalid reply",
    ]

    private static let reasons: [String: String] = rejectReasons.merging([
        "accepted": "Accepted", "strategy_limit": "Strategy limit reached", "strategy_disabled": "Strategy disabled",
        "strategy_changed": "Strategy changed", "no_slot": "No free slot", "no_price": "No price", "apply_deadline": "Too late to apply",
        "book_unavailable": "Paper ledger unavailable", "plan_limit": "Plan limit", "malformed_reply": "Malformed reply",
        "out_of_bounds": "Outside the bounds", "loosening": "Would loosen the filters", "parent_mismatch": "Wrong parent version",
        "family_changed": "Family changed", "unchanged": "No change", "version_cap": "Daily version limit", "strategy_cap": "Strategy limit",
    ]) { $1 }

    /// A decision row for display. skip / call_started only marks that a call began, so it
    /// reads as "Claude called" and is de-emphasised. Unknown kinds and codes stay visible.
    static func decisionView(_ d: MemeDecision) -> (kind: String, reason: String, muted: Bool) {
        if d.kind == "skip" && d.reason_code == "call_started" { return ("Claude called", "", true) }
        return (kinds[d.kind] ?? "Other (\(d.kind))", d.reason_code.map { reasons[$0] ?? $0 } ?? "", false)
    }

    /// Which strategy a version belongs to, when it is a strategy's active one.
    static func versionOwner(_ strategies: [MemeStrategy], _ version: Int?) -> String? {
        version.flatMap { version in strategies.first { $0.active_version == version }?.id }
    }

    /// Strategy state, most restrictive first.
    static func state(_ s: MemeStrategy) -> (label: String, style: Badge.Style) {
        if s.enabled { return ("Enabled", .accent) }
        return s.disabled_by_owner ? ("Disabled by you", .negative) : ("Disabled", .neutral)
    }

    static func changedBy(_ by: String) -> String { by == "seed" ? "Seed" : by == "claude" ? "Claude" : "You" }
}
