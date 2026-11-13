import Foundation

// Trading: Core's read-only IBKR reads (/v1/trading/*) and the manual holdings
// (/v1/holdings), as in desktop trading.ts and portfolio.ts. Money, prices and quantities
// are decimal strings ("-1234.5600"), never JSON numbers; they become Doubles only to draw
// a chart. Required fields are checked strictly (fail closed); optional display values of
// another type read as missing.

/// Where the numbers come from and how old they are. On every trading answer.
protocol TradingFreshness {
    var source: String { get }
    /// Price delay of the market-data subscription; 0 is real time, nil unknown.
    var delay_minutes: Double? { get }
    var as_of: String { get }
}

struct TradingPosition: Decodable, Identifiable {
    /// IBKR contract id; the key for the instrument's bars.
    let conid: Int
    let symbol: String
    let name: LenientText?
    /// IBKR asset class, e.g. "STK", "ETF", "FUT", "CASH".
    let asset_class: String
    let exchange: LenientText?
    /// Currency of the instrument's prices (last_price, avg_cost).
    let currency: String
    let quantity: String
    let avg_cost: String
    let last_price: String
    /// The value and result fields are in the portfolio's base currency.
    let market_value: String
    let day_pnl: LenientText?
    let day_change_pct: LenientText?
    let unrealized_pnl: LenientText?
    /// Share of net liquidation, 0..100.
    let weight_pct: LenientText?

    var id: Int { conid }
    var isStock: Bool { asset_class == "STK" || asset_class == "ETF" }
}

struct Portfolio: Decodable, TradingFreshness {
    /// One allocation bucket against the owner's target, both 0..100.
    struct Allocation: Decodable { let label: String; let current_pct: String; let target_pct: String }
    struct Deposit: Decodable { let amount: String; let day_of_month: Int; let next_date: String }
    struct Dividend: Decodable {
        let conid: Int
        let symbol: String
        let ex_date: String
        let pay_date: LenientText?
        let amount: LenientText?
        let currency: String
    }
    /// A position Core left out because it is not in the base currency.
    struct Skipped: Decodable { let symbol: String; let currency: String? }

    let source: String
    let delay_minutes: Double?
    let as_of: String
    let base_currency: String
    let net_liquidation: String
    let cash: String
    let day_pnl: LenientText?
    let day_change_pct: LenientText?
    let total_pnl: LenientText?
    let total_return_pct: LenientText?
    let dividends_12m: LenientText?
    let positions: [TradingPosition]
    /// Nil: the owner has not set targets.
    let allocation: [Allocation]?
    let monthly_deposit: Deposit?
    let upcoming_dividends: [Dividend]?
    /// Both nil when Core showed every position; older Cores never send them.
    let skipped_count: Int?
    let skipped_positions: [Skipped]?

    var stocks: [TradingPosition] { positions.filter(\.isStock) }
}

struct PortfolioHistory: Decodable, TradingFreshness {
    struct Point: Decodable { let t: String; let value: String }

    let source: String
    let delay_minutes: Double?
    let as_of: String
    let base_currency: String
    /// Oldest first; a point that does not decode is skipped.
    let points: [Lenient<Point>]

    /// The points with a readable value; others are dropped rather than drawn as 0.
    var readable: [Point] { points.compactMap(\.value).filter { Trading.chartNumber($0.value) != nil } }
}

struct InstrumentBars: Decodable, TradingFreshness {
    struct Bar: Decodable {
        let t: String
        let o: String
        let h: String
        let l: String
        let c: String
        let v: LenientText?

        /// The volume when it is a decimal string.
        var volume: Double? { Trading.chartNumber(v?.value) }
    }

    /// fact = from a source; calculation = computed by code; inference = an AI estimate.
    struct Note: Decodable {
        enum Kind: String, Decodable {
            case fact, calculation, inference
            var label: String {
                switch self {
                case .fact: "Fact"
                case .calculation: "Calculation"
                case .inference: "AI inference"
                }
            }
        }

        let t: String
        let kind: Kind
        let text: String
        let sources: [String]?
    }

    let source: String
    let delay_minutes: Double?
    let as_of: String
    let conid: Int
    let symbol: String
    let name: LenientText?
    let exchange: LenientText?
    let currency: String
    /// Oldest first; a bar that does not decode is skipped.
    let bars: [Lenient<Bar>]
    let notes: [Note]?

    /// Bars with readable open, high, low and close; others are dropped rather than drawn as 0.
    var readable: [Bar] {
        bars.compactMap(\.value).filter { bar in [bar.o, bar.h, bar.l, bar.c].allSatisfy { Trading.chartNumber($0) != nil } }
    }
}

/// One bar as numbers, for drawing only.
struct ChartBar: Equatable {
    let open: Double
    let high: Double
    let low: Double
    let close: Double
    var volume: Double?
}

/// A chart's data, worked out once per read: the bars thinned to about one per 2 points of
/// a phone-wide chart, and the moving averages over all bars, sampled at each bucket's end.
struct ChartSeries {
    static let maxPoints = 180

    let bars: [ChartBar]
    let averages: [Int: [Double?]]
    /// Bars before thinning.
    let count: Int
    let hasVolume: Bool
    let low: Double?
    let high: Double?

    init(_ all: [ChartBar], averages lengths: [Int] = [], maxPoints: Int = ChartSeries.maxPoints) {
        let ranges = Trading.buckets(all.count, max: maxPoints)
        bars = Trading.thin(all, max: maxPoints)
        let closes = all.map { Optional($0.close) }
        averages = Dictionary(uniqueKeysWithValues: lengths.map { n in
            let full = Trading.movingAverage(closes, n)
            return (n, ranges.map { full[$0.upperBound - 1] })
        })
        count = all.count
        hasVolume = all.contains { $0.volume != nil }
        low = all.map(\.low).min()
        high = all.map(\.high).max()
    }
}

/// A position entered by hand (/v1/holdings).
struct Holding: Decodable, Identifiable {
    let id: String
    let symbol: String
    let quantity: String
    let avg_cost: String
    let currency: String
    let cost_basis: String
    let weight_pct: String
}

struct HoldingList: Decodable {
    let holdings: [Holding]
    let total_cost: String
}

struct NewHolding: Encodable, Equatable {
    let symbol: String
    let quantity: String
    let avg_cost: String
}

/// Why there is no trading data at all. noApi: Core has no trading reads yet; notSynced:
/// IBKR is not set up, not synced yet or unreachable from Core (503); unreachable: no
/// answer from the Home Node.
enum TradingNotConnected: Equatable { case noApi, notSynced, unreachable }

/// notHeld: the instrument is no longer in the portfolio (404 on its bars).
enum TradingError: Equatable { case signin, failed, notHeld }

enum TradingState<Value> {
    case loading
    case notConnected(TradingNotConnected)
    case error(TradingError)
    /// `stale`: the age of Core's read when it is older than 10 minutes or unreadable.
    case ok(Value, empty: Bool, stale: TimeInterval?)

    var value: Value? {
        if case let .ok(value, _, _) = self { return value }
        return nil
    }
}

enum Trading {
    static let historyRanges = ["1M", "3M", "1Y", "ALL"]
    static let barRanges = ["1D", "1W", "1M", "3M", "1Y", "5Y"]
    /// Core's read is older than this: show it, but say it is stale.
    static let staleAfter: TimeInterval = 10 * 60
    static let dash = "—"

    static func historyPath(_ range: String) -> String? {
        historyRanges.contains(range) ? "/v1/trading/portfolio/history?range=\(range)" : nil
    }

    static func barsPath(_ conid: Int, _ range: String) -> String? {
        validConid(conid) && barRanges.contains(range) ? "/v1/trading/instruments/\(conid)/bars?range=\(range)" : nil
    }

    // MARK: Decimal strings

    struct Parsed: Equatable { var neg: Bool; var int: String; var frac: String }

    /// Optional "-", digits, optional "." and digits; anything else is nil.
    static func parse(_ value: String?) -> Parsed? {
        guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              let match = text.wholeMatch(of: #/(-)?([0-9]+)(?:\.([0-9]+))?/#) else { return nil }
        var int = String(match.output.2)
        while int.count > 1, int.hasPrefix("0") { int.removeFirst() }
        return Parsed(neg: match.output.1 != nil, int: int, frac: match.output.3.map(String.init) ?? "")
    }

    /// Round half away from zero to `places` fraction digits, on the digits.
    private static func round(_ p: Parsed, _ places: Int) -> Parsed {
        if p.frac.count <= places { return Parsed(neg: p.neg, int: p.int, frac: p.frac.padding(toLength: places, withPad: "0", startingAt: 0)) }
        var digits = (p.int + p.frac.prefix(places)).compactMap(\.wholeNumberValue)
        if (Array(p.frac)[places].wholeNumberValue ?? 0) >= 5 {
            var i = digits.count - 1
            while i >= 0 && digits[i] == 9 { digits[i] = 0; i -= 1 }
            if i < 0 { digits.insert(1, at: 0) } else { digits[i] += 1 }
        }
        let all = digits.map { String($0) }.joined()
        let cut = all.count - places
        return Parsed(neg: p.neg, int: cut > 0 ? String(all.prefix(cut)) : "0", frac: String(all.suffix(places)))
    }

    private static func isZero(_ p: Parsed) -> Bool { (p.int + p.frac).allSatisfy { $0 == "0" } }

    private static func group(_ int: String) -> String {
        var out = ""
        for (index, digit) in int.enumerated() {
            if index > 0 && (int.count - index) % 3 == 0 { out.append(",") }
            out.append(digit)
        }
        return out
    }

    private static func body(_ p: Parsed) -> String { group(p.int) + (p.frac.isEmpty ? "" : ".\(p.frac)") }

    /// -1, 0 or 1; nil when it is not a decimal string.
    static func sign(_ value: String?) -> Int? { parse(value).map { isZero($0) ? 0 : $0.neg ? -1 : 1 } }

    /// "," grouping; `places` fixed (rounded) fraction digits, `maxPlaces` keeps up to that
    /// many without trailing zeros, `sign` puts "+" on positive values. Not a decimal: "—".
    static func formatDecimal(_ value: String?, places: Int? = nil, maxPlaces: Int? = nil, sign: Bool = false) -> String {
        guard let raw = parse(value) else { return dash }
        var p = round(raw, places ?? maxPlaces ?? raw.frac.count)
        if places == nil && maxPlaces != nil { while p.frac.hasSuffix("0") { p.frac.removeLast() } }
        if isZero(p) { return body(p) }
        return (p.neg ? "−" : sign ? "+" : "") + body(p)
    }

    /// Signed percentage: "+0.43%", "−99.90%".
    static func formatPercent(_ value: String?, sign: Bool = true, places: Int = 2) -> String {
        let text = formatDecimal(value, places: places, sign: sign)
        return text == dash ? dash : "\(text)%"
    }

    /// Symbol, position and fraction digits of a currency, as English narrow symbols.
    // ponytail: no cache; add one if long lists ever scroll slowly.
    private static func currencyStyle(_ code: String) -> (symbol: String, after: Bool, digits: Int) {
        guard code.wholeMatch(of: #/[A-Z]{3}/#) != nil else { return (code, true, 2) }
        let text = Decimal(1).formatted(.currency(code: code).presentation(.narrow).locale(Locale(identifier: "en")))
        guard let one = text.firstIndex(of: "1") else { return (code, true, 2) }
        let before = text[..<one].trimmingCharacters(in: .whitespaces)
        let tail = text[text.index(after: one)...]
        let digits = tail.hasPrefix(".") ? tail.dropFirst().prefix(while: \.isNumber).count : 0
        let after = tail.drop { $0 == "." || $0.isNumber }.trimmingCharacters(in: .whitespaces)
        return before.isEmpty ? (after.isEmpty ? code : after, true, digits) : (before, false, digits)
    }

    /// Money in its currency: "€23,820.00", "+$3.23", "−€63.10", "10.00 CHF". Fraction
    /// digits follow the currency (JPY 0, EUR 2) unless `places` is given.
    static func formatMoney(_ value: String?, _ currency: String?, sign: Bool = false, places: Int? = nil) -> String {
        guard let raw = parse(value) else { return dash }
        let style = currencyStyle(String((currency ?? "").prefix(12)))
        let p = round(raw, places ?? style.digits)
        let prefix = isZero(p) ? "" : p.neg ? "−" : sign ? "+" : ""
        let spaced = style.symbol.count > 1 && style.symbol.allSatisfy { ("A"..."Z").contains($0) }
        if style.after || spaced {
            let text = "\(prefix)\(body(p)) \(style.symbol)"
            return text.hasSuffix(" ") ? String(text.dropLast()) : text
        }
        return "\(prefix)\(style.symbol)\(body(p))"
    }

    /// A price: sub-cent prices keep their digits (up to 8), others follow the currency,
    /// so a $0.0004 token never shows as "$0.00".
    static func formatPrice(_ value: String?, _ currency: String?) -> String {
        var fraction = value?.components(separatedBy: ".").dropFirst().first ?? ""
        while fraction.hasSuffix("0") { fraction.removeLast() }
        return formatMoney(value, currency, places: fraction.count > 2 ? min(8, fraction.count) : nil)
    }

    /// A decimal string as a number, for chart drawing and bar widths only.
    static func chartNumber(_ value: String?) -> Double? {
        guard parse(value) != nil, let number = Double(value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "") else { return nil }
        return number.isFinite ? number : nil
    }

    /// Percent change from the first to the last value as a decimal string ("4.80"); nil
    /// when unknown.
    static func changeRatio(_ first: String?, _ last: String?) -> String? {
        guard let a = chartNumber(first), a != 0, let b = chartNumber(last),
              let exact = parse(String(format: "%.15f", (b / a - 1) * 100)) else { return nil }
        // Rounded once, on the digits, half away from zero, like desktop's toFixed(2).
        let p = round(exact, 2)
        return (p.neg && !isZero(p) ? "-" : "") + p.int + "." + p.frac
    }

    /// The source ranges a series of `count` bars is drawn as: one per bar, or `max` buckets.
    static func buckets(_ count: Int, max: Int) -> [Range<Int>] {
        guard count > max, max > 0 else { return (0..<count).map { $0..<($0 + 1) } }
        return (0..<max).map { (($0 * count) / max)..<((($0 + 1) * count) / max) }
    }

    /// Bars as drawn: at most `max`, each bucket's open, high, low, close and summed volume.
    static func thin(_ bars: [ChartBar], max: Int) -> [ChartBar] {
        buckets(bars.count, max: max).compactMap { range in
            let slice = bars[range]
            guard let first = slice.first, let last = slice.last else { return nil }
            let volumes = slice.compactMap(\.volume)
            return ChartBar(open: first.open, high: slice.map(\.high).max() ?? first.high, low: slice.map(\.low).min() ?? first.low,
                            close: last.close, volume: volumes.isEmpty ? nil : volumes.reduce(0, +))
        }
    }

    /// Simple moving average of the closes; nil until `n` bars exist.
    static func movingAverage(_ closes: [Double?], _ n: Int) -> [Double?] {
        var sum = 0.0
        var valid = 0
        return closes.indices.map { i -> Double? in
            if let close = closes[i] { sum += close; valid += 1 }
            if i >= n, let drop = closes[i - n] { sum -= drop; valid -= 1 }
            return i >= n - 1 && valid == n ? sum / Double(n) : nil
        }
    }

    /// "IBKR · delayed 15 min", "IBKR · real time", or "IBKR · delay unknown" when Core did
    /// not say: never claim real time without data.
    static func sourceLabel(_ meta: TradingFreshness) -> String {
        let delay: String
        if let minutes = meta.delay_minutes, minutes.isFinite, minutes >= 0 {
            delay = minutes > 0 ? "delayed \(Int(minutes.rounded())) min" : "real time"
        } else {
            delay = "delay unknown"
        }
        return "\(meta.source.isEmpty ? "Unknown source" : meta.source) · \(delay)"
    }

    /// "3 min", "2 h", "4 days" for a stale banner.
    static func ageText(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite else { return "an unknown time" }
        let minutes = max(1, Int((seconds / 60).rounded()))
        if minutes < 60 { return "\(minutes) min" }
        let hours = Int((Double(minutes) / 60).rounded())
        if hours < 48 { return "\(hours) h" }
        return "\(Int((Double(hours) / 24).rounded())) days"
    }

    private static func formatter(_ format: String, utc: Bool = false) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.dateFormat = format
        if utc { formatter.timeZone = TimeZone(identifier: "UTC") }
        return formatter
    }

    private static let timeOnly = formatter("HH:mm")
    private static let dayAndTime = formatter("d MMM, HH:mm")
    private static let dayOnly = formatter("d MMM")
    private static let plainDay = formatter("yyyy-MM-dd", utc: true)
    private static let plainDayText = formatter("d MMM", utc: true)

    /// "10:42" today, "5 Nov, 10:42" otherwise.
    static func asOfText(_ iso: String, now: Date = Date()) -> String {
        guard let date = parseCoreDate(iso) else { return "an unknown time" }
        return Calendar.current.isDate(date, inSameDayAs: now) ? timeOnly.string(from: date) : dayAndTime.string(from: date)
    }

    /// "5 Nov"; "—" when unreadable. A plain date ("2026-11-05") is a calendar day, read and
    /// shown as such.
    static func dayText(_ iso: String) -> String {
        if let date = parseCoreDate(iso) { return dayOnly.string(from: date) }
        return plainDay.date(from: iso).map(plainDayText.string(from:)) ?? dash
    }

    /// "2 positions not shown (not in EUR): AAPL (USD), X (no currency)", or nil when Core
    /// showed every position.
    static func skippedText(_ p: Portfolio) -> String? {
        let count = p.skipped_count ?? 0
        guard count > 0 else { return nil }
        let named = (p.skipped_positions ?? []).map { "\($0.symbol) (\($0.currency ?? "no currency"))" }
        let more = count - named.count
        let what = count == 1 ? "1 position" : "\(count) positions"
        return "\(what) not shown (not in \(p.base_currency)): \(named.joined(separator: ", "))\(more > 0 ? " and \(more) more" : "")"
    }

    /// The owner's allocation buckets that are within 2 points of target.
    static func onTarget(_ allocation: [Portfolio.Allocation]) -> Int {
        allocation.filter { offTarget($0) == nil && chartNumber($0.current_pct) != nil && chartNumber($0.target_pct) != nil }.count
    }

    /// "3 pt above target" when a bucket is 2 points or more off; nil otherwise.
    static func offTarget(_ a: Portfolio.Allocation) -> (text: String, over: Bool)? {
        guard let now = chartNumber(a.current_pct), let goal = chartNumber(a.target_pct), abs(now - goal) >= 2 else { return nil }
        return ("\(Int(abs(now - goal).rounded())) pt \(now > goal ? "above" : "below") target", now > goal)
    }

    // MARK: Screen state

    /// The screen state of a read: a failed read says whether Core is not connected or
    /// failed; a successful one is stale when Core's read is old or its time unreadable
    /// (an old "nothing" is not a fresh "nothing"). `instrument`: a 404 on an instrument's
    /// bars means it is not held, not a missing API.
    static func state<Value: TradingFreshness>(_ source: Availability<Value>, instrument: Bool = false, now: Date = Date(),
                                               isEmpty: (Value) -> Bool) -> TradingState<Value> {
        switch source {
        case .loading: return .loading
        case .unsupported: return instrument ? .error(.notHeld) : .notConnected(.noApi)
        case .error(.network): return .notConnected(.unreachable)
        case .error(.unavailable): return .notConnected(.notSynced)
        case .error(.signin): return .error(.signin)
        case .error: return .error(.failed)
        case let .ok(value):
            let age = parseCoreDate(value.as_of).map { now.timeIntervalSince($0) } ?? .infinity
            return .ok(value, empty: isEmpty(value), stale: age > staleAfter ? age : nil)
        }
    }

    /// What the owner sees, and must do, when there is no trading data.
    static func notConnectedText(_ reason: TradingNotConnected) -> (title: String, detail: String) {
        switch reason {
        case .noApi:
            ("Not connected to IBKR yet",
             "This Core has no trading data yet; it comes with the IBKR broker connection. Update Core on your Home Node and turn on the IBKR portfolio sync. Nothing is shown until then.")
        case .notSynced:
            ("IBKR not connected yet",
             "Core has no IBKR data yet: the IBKR connection is not set up, the first sync has not finished, or Core cannot reach IBKR right now. Check the IBKR broker on your Home Node, then try again.")
        case .unreachable:
            ("Home Node unreachable", "No answer from your Home Node. Check the connection; trading data comes from Core.")
        }
    }

    static func errorText(_ reason: TradingError) -> String {
        switch reason {
        case .notHeld: "This instrument is no longer in your portfolio."
        case .signin: "Sign in again from Settings."
        case .failed: "Core returned an error. Try again later."
        }
    }

    /// Tone and label of the IBKR link, read from the portfolio.
    static func linkState<Value>(_ state: TradingState<Value>) -> (Tone, String) {
        switch state {
        case .loading: (.idle, "Loading…")
        case let .ok(_, _, stale): stale == nil ? (.ok, "Connected") : (.warn, "Connected, sync is old")
        case .notConnected(.noApi): (.idle, "Requires newer Core")
        case .notConnected(.notSynced): (.warn, "Not connected")
        case .notConnected(.unreachable), .error: (.warn, "Unavailable")
        }
    }

    // MARK: Response checks: anything unexpected reads as failed (fail closed).

    /// A conid: a positive safe integer.
    static func validConid(_ conid: Int) -> Bool { conid > 0 && conid <= 9_007_199_254_740_991 }

    private static func validDelay(_ meta: TradingFreshness) -> Bool { meta.delay_minutes.map { $0.isFinite && $0 >= 0 } ?? true }

    /// Both absent, or a count of 1 to 1000 naming 1 to min(count, 50) positions.
    private static func validSkipped(_ count: Int?, _ list: [Portfolio.Skipped]?) -> Bool {
        guard let count, let list else { return count == nil && list == nil }
        return (1...1000).contains(count) && (1...min(count, 50)).contains(list.count) && list.allSatisfy { skipped in
            (1...32).contains(skipped.symbol.unicodeScalars.count) && (skipped.currency.map { $0.wholeMatch(of: #/[A-Z]{3}/#) != nil } ?? true)
        }
    }

    static func valid(_ p: Portfolio) -> Bool {
        validDelay(p) && p.positions.count <= 1000 && p.positions.allSatisfy { validConid($0.conid) }
            && (p.allocation?.count ?? 0) <= 50 && (p.upcoming_dividends?.count ?? 0) <= 200
            && (p.upcoming_dividends ?? []).allSatisfy { validConid($0.conid) } && validSkipped(p.skipped_count, p.skipped_positions)
    }

    static func valid(_ history: PortfolioHistory) -> Bool { validDelay(history) && history.points.count <= 5000 }

    static func valid(_ bars: InstrumentBars) -> Bool {
        validDelay(bars) && validConid(bars.conid) && bars.bars.count <= 5000 && (bars.notes?.count ?? 0) <= 50
            && (bars.notes ?? []).allSatisfy { ($0.sources?.count ?? 0) <= 20 }
    }

    static func valid(_ list: HoldingList) -> Bool { list.holdings.count <= 1000 && list.holdings.allSatisfy { isSafePathSegment($0.id) } }

    // MARK: Holdings

    /// The notice after a refused add or remove, in fixed words; never Core's own text.
    /// A manual holding as typed: trimmed, the symbol in capitals, and a single decimal
    /// comma read as a point ("12,5" is 12.5). Nil when the symbol is empty or an amount
    /// is not a plain decimal number.
    static func newHolding(symbol: String, quantity: String, avgCost: String) -> NewHolding? {
        func amount(_ text: String) -> String? {
            guard !ambiguousAmount(text) else { return nil }
            var value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if value.filter({ $0 == "," }).count == 1 && !value.contains(".") { value = value.replacingOccurrences(of: ",", with: ".") }
            return parse(value) != nil && value.count <= 64 ? value : nil
        }
        let symbol = symbol.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !symbol.isEmpty, symbol.count <= 32, let quantity = amount(quantity), let avgCost = amount(avgCost) else { return nil }
        return NewHolding(symbol: symbol, quantity: quantity, avg_cost: avgCost)
    }

    /// "1,234" or "1.234": a thousands group or a decimal, so it is refused both ways.
    /// "01,234" (a leading zero before more digits) reads the same way, so it is refused too.
    static func ambiguousAmount(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).wholeMatch(of: #/-?(?:[1-9][0-9]{0,2}|0[0-9]{1,2})[.,][0-9]{3}/#) != nil
    }

    static let ambiguousAmountText = "Ambiguous amount: write 1234, or 1.2340 for a decimal."

    static let holdingInputText = "Enter a symbol, and the quantity and average cost as numbers, like 12 or 101,50."

    static func holdingFailureText(_ error: Error, adding: Bool) -> String {
        if let known = error as? JarvisAPIError, known == .invalidResponse || known == .responseTooLarge {
            return "Not confirmed; reloaded to check."
        }
        let what = adding ? "Not added." : "Not removed."
        if Jobs.isNetwork(error) {
            return "\(what) No answer from your Home Node. It is unknown whether the change arrived; reloaded to check."
        }
        switch Jobs.status(error) {
        case 400, 422: return "\(what) Core refused this as invalid. Check the symbol, quantity and average cost."
        case 401: return "\(what) Your session is not valid. Sign in again from Settings."
        case 404: return "\(what) This holding no longer exists on your Home Node."
        case 405, 501: return "\(what) This Core has no manual holdings. Update Core on your Home Node."
        default: return "\(what) Core returned an error. Try again later."
        }
    }
}
