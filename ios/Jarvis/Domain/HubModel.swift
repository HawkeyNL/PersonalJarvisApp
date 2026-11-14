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

/// The future side of `relativeTime`: "in 4m", "in 2h", "in 3d"; "now" within a minute or once it has passed.
func untilTime(_ date: Date, now: Date) -> String {
    let seconds = date.timeIntervalSince(now)
    if seconds < 60 { return "now" }
    if seconds < 3600 { return "in \(Int(seconds / 60))m" }
    if seconds < 86_400 { return "in \(Int(seconds / 3600))h" }
    return "in \(Int(seconds / 86_400))d"
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
        case .jarvis: [.chat, .voice, .conversations, .memory, .roadmaps]
        case .trading: []
        case .activity: [.tasks, .agents, .jobs]
        case .settings: [.health, .healthUsage, .integrations, .models, .context]
        }
    }
}

/// Where a route (deep link) opens: its tab and the pages pushed there.
func tabDestination(_ route: HubRoute) -> (tab: AppTab, path: [HubRoute]) {
    switch route {
    case .settings: (.settings, [])
    case .pairingApprovals: (.activity, [])
    case .chat, .voice, .conversations, .memory, .roadmaps: (.jarvis, [route])
    case .tasks, .agents, .jobs: (.activity, [route])
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

/// Tone and label of the IBKR link: from the status route, or on an older Core
/// (404/405) from the portfolio read as before.
func ibkrHeader(_ status: Availability<IbkrStatus>, fallback: Availability<IbkrPortfolio>, now: Date) -> (Tone, String) {
    if case .unsupported = status { return ibkrState(fallback, now: now) }
    return sourceStatus(status, ok: ibkrOverall)
}

/// Header of the IBKR status from `overall`; info only needs attention when there is a problem.
func ibkrOverall(_ status: IbkrStatus) -> (Tone, String) {
    switch status.overall {
    case "ok": (.ok, "Connected")
    case "info": status.problems.isEmpty ? (.ok, "Connected") : (.idle, "Needs attention")
    case "warn": (.warn, "Needs attention")
    case "error": (.error, "Not working")
    case "off": (.idle, "Off")
    default: (.idle, "Unknown")
    }
}

/// One row of the IBKR status; `mono` for a code this app does not know.
struct IbkrRow: Equatable {
    let label: String
    let value: String
    var mono = false
}

/// The IBKR status rows. Last error and Rate limited until only appear when set,
/// Brokerage session only when the broker answered.
func ibkrRows(_ status: IbkrStatus, now: Date) -> [IbkrRow] {
    let lastSuccess = status.sync?.lastSuccessAt.map { relativeTime($0, now: now) }
    var rows = [
        IbkrRow(label: "Broker", value: ibkrBrokerLabel(status.broker)),
        IbkrRow(label: "Portfolio sync", value: ibkrSyncLabel(status.sync)),
        IbkrRow(label: "Session token", value: ibkrTokenLabel(status.token, now: now)),
        IbkrRow(label: "Last successful sync", value: lastSuccess.map { $0.isEmpty ? "Unknown" : $0 } ?? "Never"),
    ]
    if let error = ibkrLastError(status) {
        let label = error.code.map { ibkrErrorLabels[$0] ?? $0 } ?? "Unknown"
        let at = error.at.map { relativeTime($0, now: now) } ?? ""
        rows.append(IbkrRow(label: "Last error", value: [label, at].filter { !$0.isEmpty }.joined(separator: " · "),
                            mono: error.code.map { ibkrErrorLabels[$0] == nil } ?? false))
    }
    if let ibkr = status.ibkr {
        rows.append(IbkrRow(label: "Brokerage session", value: ibkrBrokerageLabel(ibkr.brokerageSession)))
    }
    if let until = status.ibkr?.rateLimitedUntil.flatMap(parseCoreDate) {
        rows.append(IbkrRow(label: "Rate limited until",
                            value: "\(until.formatted(date: .omitted, time: .shortened)) · \(untilTime(until, now: now))"))
    }
    return rows
}

/// Shown under the rows whenever the Brokerage session row is.
let ibkrBrokerageNote = "The brokerage session is only needed for charts."

/// The more recent of the sync's and the broker's last error; one with a
/// readable time beats one without, and the sync's wins a tie.
func ibkrLastError(_ status: IbkrStatus) -> IbkrStatus.Failure? {
    let sync = status.sync?.lastError, broker = status.ibkr?.lastError
    guard let broker else { return sync }
    guard let sync else { return broker }
    guard let brokerAt = broker.at.flatMap(parseCoreDate) else { return sync }
    guard let syncAt = sync.at.flatMap(parseCoreDate) else { return broker }
    return brokerAt > syncAt ? broker : sync
}

func ibkrBrokerLabel(_ broker: IbkrStatus.Broker?) -> String {
    if broker?.credentials == "missing" { return "Credentials missing" }
    switch broker?.service {
    case "active", "activating":
        // Not asked while the sync is off or refused: say only that it runs.
        switch broker?.reachable {
        case true?: return "Running, reachable"
        case false?: return "Running, not reachable"
        case nil: return "Running"
        }
    case "inactive", "deactivating": return "Stopped"
    case "failed": return "Failed"
    case "not_found": return "Not installed"
    default: return "Unknown"
    }
}

func ibkrSyncLabel(_ sync: IbkrStatus.Sync?) -> String {
    switch sync?.state {
    case "off": "Off"
    case "refused": "Refused"
    case "ok": "On · Syncing"
    case "starting": "On · Starting"
    case "failing": "On · Failing"
    case "waiting_for_broker": "On · Waiting for the broker"
    default: sync?.enabled == false ? "Off" : sync?.enabled == true ? "On · Unknown" : "Unknown"
    }
}

/// "Valid · refreshed 3h ago · expires in 21h · usable"; "Unknown" when the broker did not answer.
func ibkrTokenLabel(_ token: IbkrStatus.Token?, now: Date) -> String {
    guard let token else { return "Unknown" }
    let state = switch token.state {
    case "none": "Not created yet"
    case "valid": "Valid"
    case "refresh_due": "Refreshing soon"
    case "expired": "Expired"
    case "rejected": "Rejected"
    default: "Unknown"
    }
    let refreshed = token.refreshedAt.map { relativeTime($0, now: now) } ?? ""
    let expires = token.expiresAt.flatMap(parseCoreDate).map {
        $0 > now ? "expires \(untilTime($0, now: now))" : "expired \(relativeTime($0, now: now))"
    }
    return [state,
            refreshed.isEmpty ? nil : "refreshed \(refreshed)",
            expires,
            token.usable.map { $0 ? "usable" : "not usable" }]
        .compactMap { $0 }.joined(separator: " · ")
}

func ibkrBrokerageLabel(_ session: String?) -> String {
    switch session {
    case "open": "Open"
    case "closed": "Closed"
    case "competing": "In use elsewhere"
    default: "Unknown"
    }
}

/// Labels of the sync, refusal and broker error codes (the sets do not
/// overlap); any other code is shown as it is.
let ibkrErrorLabels: [String: String] = [
    "broker_not_running": "Broker not running",
    "broker_unavailable": "Broker unavailable",
    "broker_busy": "Broker busy",
    "broker_timeout": "Broker timed out",
    "ibkr_auth": "IBKR refused the login",
    "ibkr_rate_limited": "Rate limited by IBKR",
    "ibkr_failed": "IBKR request failed",
    "broker_protocol": "Unreadable broker answer",
    "account_unsupported": "Account not supported",
    "data_refused": "IBKR data refused",
    "store_failed": "Could not store the portfolio",
    "socket_path_invalid": "Broker socket path invalid",
    "broker_account_missing": "Broker account missing",
    "broker_account_unsafe": "Broker account unsafe",
    "auth_rejected": "Rejected by IBKR",
    "not_authenticated": "Not signed in to IBKR",
    "rate_limited": "Rate limited by IBKR",
    "upstream": "IBKR request failed",
    "busy": "Broker busy",
    "invalid_input": "Invalid request",
    "unknown": "Unknown",
]

/// Tone and badge text of a problem's severity.
func ibkrSeverity(_ severity: String?) -> (Tone, String) {
    switch severity {
    case "info": (.idle, "Info")
    case "warn": (.warn, "Warning")
    case "error": (.error, "Error")
    default: (.idle, "Unknown")
    }
}

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

/// One resource bar; `percent` is nil when Core has no reading.
struct Meter: Equatable, Identifiable {
    let id: String
    let label: String
    let percent: Int?
    let tone: Tone
    let detail: String
}

private func loadTone(_ percent: Double) -> Tone { percent >= 90 ? .error : percent >= 80 ? .warn : .ok }
private func spaceDetail(used: Int64, total: Int64) -> String {
    "\(formatGiB(used)) used · \(formatGiB(total - used)) free of \(formatGiB(total))"
}

/// CPU, memory and disk bars for the Home Node, in that order; each warns at 80% and 90%.
func resourceMeters(live: HomeNodeRegistry.LiveHost?, disks: [DiskStatus]) -> [Meter] {
    var meters: [Meter] = []
    if let live {
        if let cpu = live.cpuPercent {
            meters.append(Meter(id: "cpu", label: "CPU", percent: Int(cpu.rounded()), tone: loadTone(cpu), detail: String(format: "%.1f%% in use", cpu)))
        } else {
            meters.append(Meter(id: "cpu", label: "CPU", percent: nil, tone: .idle, detail: "First sample…"))
        }
        let used = live.memoryUsedBytes, total = live.memoryTotalBytes
        if total > 0 {
            let pct = Double(used) * 100 / Double(total)
            meters.append(Meter(id: "memory", label: "Memory", percent: Int(pct.rounded()), tone: loadTone(pct), detail: spaceDetail(used: used, total: total)))
        } else {
            meters.append(Meter(id: "memory", label: "Memory", percent: nil, tone: .idle, detail: "Unknown"))
        }
    }
    for (i, disk) in disks.enumerated() {
        let id = "disk-\(disk.label)-\(i)", label = "Disk · \(disk.label)"
        let tone = diskState(disk).0
        if tone != .idle, let total = disk.total_bytes, let free = disk.free_bytes, let used = disk.used_percent {
            meters.append(Meter(id: id, label: label, percent: Int(used.rounded()), tone: tone, detail: spaceDetail(used: total - free, total: total)))
        } else {
            meters.append(Meter(id: id, label: label, percent: nil, tone: .idle, detail: "Unknown"))
        }
    }
    return meters
}

// MARK: - Devices

/// Devices that may be offered for revoking: active ones other than this
/// device. Nothing is offered until this device's own id (lowercased) is known.
func revocableDevices(_ list: [LinkedDevice], thisDevice: String?) -> [LinkedDevice] {
    guard let thisDevice else { return [] }
    return list.filter { $0.status == "active" && $0.id.lowercased() != thisDevice }
}
