import Foundation

// Pure presentation helpers for the hub and node pages, mirroring the desktop
// hubModel.ts / nodeModels.ts. No SwiftUI, so unit tests exercise them directly.

/// Status color of a dot/label. Every tone is always paired with text.
enum Tone: Equatable { case ok, idle, warn, error }

/// What Jarvis is doing (the Home status line).
enum Mood: Equatable { case idle, listening, thinking }

/// Why an optional Core read failed.
/// `unavailable`: Core answered 503, it cannot serve this right now (e.g. IBKR not connected yet).
enum LoadFailure: Equatable { case signin, forbidden, network, unavailable, failed }

/// Result of an optional Core read. `unsupported` means the route does not
/// exist on this Core (older version) and is shown as "Requires newer Core".
enum Availability<Value> {
    case loading
    case ok(Value)
    case unsupported
    case error(LoadFailure)

    var value: Value? {
        if case let .ok(value) = self { return value }
        return nil
    }

    /// 404/405 is an older Core, 401 a session to renew, 403 a feature Core
    /// refuses, 503 something Core cannot serve right now; only a request
    /// without an answer is a connection problem.
    init(error: Error) {
        switch error as? JarvisAPIError {
        case .unauthorized?: self = .error(.signin)
        case .unreachable?, .timedOut?: self = .error(.network)
        case let .rejected(status, _)?:
            if status == 404 || status == 405 { self = .unsupported }
            else { self = .error(status == 403 ? .forbidden : status == 503 ? .unavailable : .failed) }
        default: self = .error(.failed)
        }
    }

    func map<T>(_ transform: (Value) -> T) -> Availability<T> {
        switch self {
        case .loading: .loading
        case let .ok(value): .ok(transform(value))
        case .unsupported: .unsupported
        case let .error(reason): .error(reason)
        }
    }
}

/// One-line explanation of a failed read; `forbidden` names what Core turned off.
func failureText(_ reason: LoadFailure, forbidden: String = "Turned off in Core.") -> String {
    switch reason {
    case .signin: "Sign in again from Settings."
    case .forbidden: forbidden
    case .network: "Check the connection to your Home Node."
    case .unavailable: "Core cannot provide this right now. Try again later."
    case .failed: "Core returned an error. Try again later."
    }
}

/// Generic status of a satellite whose source did not load.
func sourceStatus<T>(_ source: Availability<T>, ok: (T) -> (Tone, String)) -> (Tone, String) {
    switch source {
    case let .ok(value): return ok(value)
    case .loading: return (.idle, "Loading…")
    case .unsupported: return (.idle, "Requires newer Core")
    case .error: return (.warn, "Unavailable")
    }
}

// MARK: - Time

private let isoFractional: ISO8601DateFormatter = {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter
}()
private let isoPlain = ISO8601DateFormatter()

/// RFC 3339 timestamp with or without fractional seconds.
func parseCoreDate(_ text: String) -> Date? {
    isoFractional.date(from: text) ?? isoPlain.date(from: text)
}

/// "just now", "4m ago", "2h ago", "3d ago"; "" for an unparseable time.
func relativeTime(_ text: String, now: Date) -> String {
    guard let date = parseCoreDate(text) else { return "" }
    return relativeTime(date, now: now)
}

func relativeTime(_ date: Date, now: Date) -> String {
    let seconds = max(0, now.timeIntervalSince(date))
    if seconds < 60 { return "just now" }
    if seconds < 3600 { return "\(Int(seconds / 60))m ago" }
    if seconds < 86_400 { return "\(Int(seconds / 3600))h ago" }
    return "\(Int(seconds / 86_400))d ago"
}

/// Number of timestamps within the last `days` days.
func countSince(_ timestamps: [String], days: Int, now: Date) -> Int {
    let since = now.addingTimeInterval(-Double(days) * 86_400)
    return timestamps.filter { text in parseCoreDate(text).map { $0 >= since } ?? false }.count
}

/// "14d 2h", "3h 12m", "5m" from seconds of uptime.
func formatUptime(_ seconds: Int64) -> String {
    let s = max(0, seconds)
    let days = s / 86_400, hours = (s % 86_400) / 3600, minutes = (s % 3600) / 60
    if days > 0 { return "\(days)d \(hours)h" }
    if hours > 0 { return "\(hours)h \(minutes)m" }
    return "\(minutes)m"
}

/// "850 ms", "1.2 s"; "—" when Core has no measurement.
func formatMs(_ ms: Double?) -> String {
    guard let ms, ms.isFinite else { return "—" }
    return ms < 1000 ? "\(Int(ms.rounded())) ms" : String(format: "%.1f s", ms / 1000)
}

func formatGiB(_ bytes: Int64) -> String {
    String(format: "%.1f GiB", Double(bytes) / 1_073_741_824)
}

func formatEuro(_ value: Double) -> String { String(format: "€%.2f", value) }

/// "1.2K", "3.4M" token counts.
func formatCompact(_ value: Int) -> String {
    let n = Double(value)
    if n >= 1_000_000 { return String(format: "%.1fM", n / 1_000_000) }
    if n >= 1000 { return String(format: "%.1fK", n / 1000) }
    return String(value)
}

private func plural(_ n: Int, _ one: String, _ many: String? = nil) -> String {
    "\(n) \(n == 1 ? one : (many ?? one + "s"))"
}

// MARK: - Hub cards

/// What a hub card shows: two short lines and a status tone.
struct CardSummary: Equatable {
    let line1: String
    let line2: String
    let tone: Tone
}

func summarize<T>(_ source: Availability<T>, ok: (T) -> CardSummary) -> CardSummary {
    switch source {
    case .loading: CardSummary(line1: "Loading…", line2: "", tone: .idle)
    case .unsupported: CardSummary(line1: "Requires newer Core", line2: "", tone: .idle)
    case let .error(reason):
        CardSummary(line1: "Unavailable", line2: cardHint(reason), tone: .warn)
    case let .ok(value): ok(value)
    }
}

private func cardHint(_ reason: LoadFailure) -> String {
    switch reason {
    case .signin: "Sign in again"
    case .forbidden: "Turned off in Core"
    case .network: "Check the connection"
    case .unavailable: "Not available now"
    case .failed: "Core error"
    }
}

func conversationsCard(_ updatedAt: [String], now: Date) -> CardSummary {
    guard !updatedAt.isEmpty else { return CardSummary(line1: "No conversations yet", line2: "Start one above", tone: .idle) }
    let newest = updatedAt.compactMap(parseCoreDate).max()
    let last = newest.map { relativeTime($0, now: now) } ?? ""
    return CardSummary(line1: "\(countSince(updatedAt, days: 7, now: now)) this week",
                       line2: last.isEmpty ? "" : "Last: \(last)", tone: .ok)
}

func tasksCard(sessions: Availability<[CodingSession]>, pending: Availability<[PendingAction]>) -> CardSummary {
    summarize(sessions) { list in
        let active = list.filter { $0.state == "active" }.count
        let waiting = pending.value?.count
        let tone: Tone = (waiting ?? 0) > 0 ? .warn : active > 0 ? .ok : .idle
        return CardSummary(line1: "\(active) active",
                           line2: waiting.map { "\($0) awaiting approval" } ?? "Approvals unavailable", tone: tone)
    }
}

/// Status line of the Home header.
func statusPill(mood: Mood, online: Bool?) -> (label: String, hint: String, tone: Tone) {
    guard let online else { return ("Connecting", "Reaching your Home Node…", .idle) }
    guard online else { return ("Offline", "Home Node unreachable", .error) }
    switch mood {
    case .thinking: return ("Thinking", "Working on your request…", .ok)
    case .listening: return ("Listening", "Speak now…", .ok)
    case .idle: return ("Ready", "Type to begin", .ok)
    }
}

// MARK: - Shell

/// The four iPhone tabs (canvas N-Structure), labelled and ordered as on Android.
enum AppTab: String, CaseIterable, Hashable {
    case jarvis, trading, activity, settings

    var title: String {
        switch self {
        case .jarvis: "Jarvis"
        case .trading: "Trading"
        case .activity: "Activity"
        case .settings: "Settings"
        }
    }

    var symbol: String {
        switch self {
        case .jarvis: "bubble.left"
        case .trading: "chart.line.uptrend.xyaxis"
        case .activity: "checkmark.shield"
        case .settings: "slider.horizontal.3"
        }
    }

    /// The route this tab's root screen is itself, shown without a push.
    var root: HubRoute? {
        switch self {
        case .settings: .settings
        // Activity lists pairing requests first.
        case .activity: .pairingApprovals
        case .jarvis, .trading: nil
        }
    }

    /// Routes the root screen links to; each opens as a pushed page. The root
    /// screens draw their rows from this list.
    var links: [HubRoute] {
        switch self {
        case .jarvis: [.chat, .voice, .conversations, .memory]
        case .trading: []
        case .activity: [.tasks, .agents]
        case .settings: [.health, .healthUsage, .integrations, .models, .context]
        }
    }
}

/// Where a route (deep link) opens: its tab and the pages pushed there.
func tabDestination(_ route: HubRoute) -> (tab: AppTab, path: [HubRoute]) {
    switch route {
    case .settings: (.settings, [])
    case .pairingApprovals: (.activity, [])
    case .chat, .voice, .conversations, .memory: (.jarvis, [route])
    case .tasks, .agents: (.activity, [route])
    case .health, .healthUsage, .integrations, .models, .context: (.settings, [route])
    }
}

/// I-Approve: Approve stays off until the end of the details has been on
/// screen once. `endY` is the top of the end marker in the scroll view's
/// visible coordinates, `viewport` the visible height. Once reached it stays
/// reached for this review; a new review starts closed.
struct ReviewGate: Equatable {
    private(set) var reachedEnd = false

    mutating func observe(endY: CGFloat, viewport: CGFloat) {
        // Before the first layout pass sizes are zero; never open on that.
        guard viewport > 0, endY.isFinite, viewport.isFinite else { return }
        if endY <= viewport { reachedEnd = true }
    }
}

// MARK: - Tasks

/// Tone and label of a coding session state; unknown states stay neutral.
func sessionState(_ state: String) -> (Tone, String) {
    switch state {
    case "active": return (.ok, "Running")
    case "suspended": return (.warn, "Paused")
    case "completed": return (.ok, "Completed")
    case "cancelled": return (.idle, "Cancelled")
    case "archived": return (.idle, "Archived")
    default: return (.idle, state.isEmpty ? "Unknown" : state)
    }
}

/// Open (running or paused) vs finished coding sessions, order kept.
func splitSessions(_ list: [CodingSession]) -> (open: [CodingSession], finished: [CodingSession]) {
    let isOpen: (CodingSession) -> Bool = { $0.state == "active" || $0.state == "suspended" }
    return (list.filter(isOpen), list.filter { !isOpen($0) })
}

/// Tone of an agent audit outcome ("ok", "denied", "error", …).
func outcomeTone(_ outcome: String) -> Tone {
    switch outcome {
    case "ok": .ok
    case "error": .error
    case "denied": .warn
    default: .idle
    }
}

/// Server-provided ids are only placed in a URL path when they are plain
/// identifiers, so they can never add path segments or query parameters.
func isSafePathSegment(_ text: String) -> Bool {
    let allowed = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")
    return (1...128).contains(text.count) && text.allSatisfy { allowed.contains($0) }
}

// MARK: - Integrations

/// Core's last IBKR sync is older than this: still connected, but say so.
let ibkrStaleAfter: TimeInterval = 10 * 60

/// Tone and label of the IBKR link, from GET /v1/trading/portfolio: a
/// portfolio means connected, 503 means not connected or not synced yet.
func ibkrState(_ source: Availability<IbkrPortfolio>, now: Date) -> (Tone, String) {
    if case .error(.unavailable) = source { return (.warn, "Not connected") }
    return sourceStatus(source) { portfolio in
        guard let at = parseCoreDate(portfolio.asOf), now.timeIntervalSince(at) <= ibkrStaleAfter else {
            return (.warn, "Connected, sync is old")
        }
        return (.ok, "Connected")
    }
}

let ibkrNotSynced = "Core has no IBKR data yet: the IBKR connection is not set up, the first sync has not finished, "
    + "or Core cannot reach IBKR right now. Check the IBKR broker on your Home Node, then refresh."

func costLabel(_ cost: String) -> String {
    switch cost {
    case "plan": "Subscription"
    case "metered": "Pay per use"
    case "local": "Local"
    case "cheap": "Cheap"
    case "mid": "Mid"
    case "pricey": "Pricey"
    default: cost
    }
}

// MARK: - Agents

/// Core's reason for `usage: null` when it does not record per-agent usage yet.
let agentUsageNotInstrumented = "agent_usage_not_instrumented"

/// Agents grouped by `group` (alphabetical, ungrouped last as "Other"), names sorted.
func groupAgents(_ agents: [AgentInfo]) -> [(group: String, agents: [AgentInfo])] {
    let sorted = agents.sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
    var groups: [String: [AgentInfo]] = [:]
    for agent in sorted {
        groups[agent.group?.trimmingCharacters(in: .whitespaces) ?? "", default: []].append(agent)
    }
    return groups.keys.sorted { a, b in
        if a.isEmpty { return false }
        if b.isEmpty { return true }
        return a.localizedCompare(b) == .orderedAscending
    }.map { (group: $0.isEmpty ? "Other" : $0, agents: groups[$0] ?? []) }
}

/// What an agent's status line may honestly say: its usage this month, not a
/// live Active/Idle state (Core does not report one).
func agentStatus(_ usage: AgentUsage?, unavailableReason: String?) -> (Tone, String) {
    guard let usage else {
        return (.idle, unavailableReason == agentUsageNotInstrumented ? "Not measured yet" : "Usage unavailable")
    }
    if usage.requests == 0 { return (.idle, "Not used this month") }
    return (.ok, "\(plural(usage.requests, "request")) this month")
}

func isTrader(_ agent: AgentInfo) -> Bool {
    let pattern = "trad(er|ing)"
    return agent.id.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil ||
        agent.name.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
}

// MARK: - Health

/// Tone and label of a systemd unit state as Core reports it.
func serviceState(_ state: String) -> (Tone, String) {
    switch state {
    case "active": return (.ok, "Running")
    case "failed": return (.error, "Failed")
    case "not_found": return (.idle, "Not installed")
    case "", "unknown": return (.idle, "Unknown")
    default: return (.warn, state.prefix(1).uppercased() + state.dropFirst())
    }
}

func servicesSummary(_ services: [ServiceStatus]) -> (Tone, String) {
    guard !services.isEmpty else { return (.idle, "No services reported") }
    let failed = services.filter { $0.state == "failed" }.count
    if failed > 0 { return (.error, "\(failed) failed") }
    let running = services.filter { $0.state == "active" }.count
    return (running == services.count ? .ok : .warn, "\(running) of \(services.count) running")
}

/// Short badge text for a disk's tone (`diskState`); the summary line says how full.
func diskBadge(_ tone: Tone) -> String {
    switch tone {
    case .ok: "OK"
    case .warn: "Filling up"
    case .error: "Nearly full"
    case .idle: "Unknown"
    }
}

/// Tone and one-line summary of a disk; full disks warn early.
func diskState(_ disk: DiskStatus) -> (Tone, String) {
    guard disk.state == "ok", let used = disk.used_percent, let free = disk.free_bytes, let total = disk.total_bytes else {
        return (.idle, "Unknown")
    }
    let tone: Tone = used >= 90 ? .error : used >= 80 ? .warn : .ok
    return (tone, "\(Int(used.rounded()))% used · \(formatGiB(free)) free of \(formatGiB(total))")
}

// MARK: - Devices

/// Devices that may be offered for revoking: active ones other than this
/// device. Nothing is offered until this device's own id (lowercased) is known.
func revocableDevices(_ list: [LinkedDevice], thisDevice: String?) -> [LinkedDevice] {
    guard let thisDevice else { return [] }
    return list.filter { $0.status == "active" && $0.id.lowercased() != thisDevice }
}
