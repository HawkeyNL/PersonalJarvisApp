import SwiftUI

// The pushed pages (canvas D-Home, D-Runs, D-Memory, D-Devices, D-Approvals in
// the I-* mobile patterns). Each one shows what Core really reports; features
// Core lacks are listed under Later, never as sample data.

/// Skeleton while `source` loads, the error or "Requires newer Core" card when
/// it did not load, otherwise `content`.
struct Loaded<Value, Content: View>: View {
    let source: Availability<Value>
    let title: String
    var symbol = "sparkles"
    var forbidden = "Turned off in Core."
    var rows = 3
    var retry: (() async -> Void)? = nil
    @ViewBuilder let content: (Value) -> Content

    var body: some View {
        switch source {
        case .loading: LoadingState(label: "\(title), loading", rows: rows)
        case let .ok(value): content(value)
        case .unsupported, .error:
            UnavailableState.forSource(source, title: title, symbol: symbol, forbidden: forbidden, retry: retry)
        }
    }
}

// MARK: - Conversations

struct ConversationsPage: View {
    @ObservedObject var model: JarvisAppModel
    let navigate: (HubRoute) -> Void
    @State private var selected = "all"
    @State private var query = ""
    @State private var now = Date()

    private var timestamps: [String] { model.conversations.map(\.updatedAt) }
    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var views: [NodeItem] {
        let total = model.conversations.count
        let week = countSince(timestamps, days: 7, now: now)
        return [
            NodeItem(id: "all", label: "All", title: "All conversations", icon: "bubble.left",
                     detail: "Every conversation is stored on your Home Node and follows you across devices.",
                     tone: total > 0 ? .ok : .idle, status: "\(total) total"),
            NodeItem(id: "week", label: "This week", title: "This week", icon: "calendar",
                     detail: "Conversations you or Jarvis added to in the last seven days.",
                     tone: week > 0 ? .ok : .idle, status: "\(week) active"),
        ]
    }

    private static let later: [NodeItem] = [
        .planned("channels", "Channels", "Channels", icon: "iphone",
                 detail: "Core does not record where a conversation took place yet, so threads cannot be split by voice, desktop or mobile."),
        .planned("summaries", "Summaries", "Summaries", icon: "doc.text",
                 detail: "Core does not write summaries of conversations yet."),
        .planned("actions", "Action items", "Action items", icon: "checkmark.circle",
                 detail: "Core does not extract action items from conversations yet."),
        .planned("memories", "Memories", "Linked memories", icon: "brain",
                 detail: "Core has no browsable memory store yet, so nothing can be linked to a conversation."),
    ]

    private var rows: [ActivityRow] {
        let q = trimmedQuery
        let since = selected == "week" ? now.addingTimeInterval(-7 * 86_400) : nil
        return model.conversations.filter { item in
            if !q.isEmpty && !item.title.localizedCaseInsensitiveContains(q) { return false }
            guard let since else { return true }
            guard let date = parseCoreDate(item.updatedAt) else { return false }
            return date >= since
        }.map {
            ActivityRow(id: $0.id.uuidString, symbol: "bubble.left", title: $0.title.isEmpty ? "Untitled" : $0.title,
                        time: relativeTime($0.updatedAt, now: now), tone: .ok, toneLabel: "Stored on Core", badge: false)
        }
    }

    var body: some View {
        PageScaffold(title: "Conversations", subtitle: "Everything you and Jarvis said") {
            Button {
                model.newConversation()
                navigate(.chat)
            } label: {
                Label("New conversation", systemImage: "square.and.pencil").frame(maxWidth: .infinity)
            }
            .buttonStyle(JVButtonStyle(kind: .quiet, size: .large))
            TileGrid(tiles: [
                TileData(label: "Conversations", value: "\(model.conversations.count)"),
                TileData(label: "Active this week", value: "\(countSince(timestamps, days: 7, now: now))"),
                TileData(label: "Active today", value: "\(countSince(timestamps, days: 1, now: now))"),
                TileData(label: "Last activity", value: lastActivity),
            ])
            SegmentedControl(label: "Show", items: views, selection: $selected)
            if let current = views.first(where: { $0.id == selected }) {
                Text(verbatim: current.detail)
                    .font(JV.font(13))
                    .foregroundStyle(JV.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
            }
            searchField
            list
            LaterList(items: Self.later)
        }
        .task {
            await model.refreshConversationList()
            now = Date()
        }
    }

    private var searchField: some View {
        let shape = RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous)
        return HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(JV.textSecondary).accessibilityHidden(true)
            TextField("Search conversations", text: $query)
                .font(JV.font(15))
                .foregroundStyle(JV.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(JV.bgSurface, in: shape)
        .overlay(shape.strokeBorder(JV.borderStrong, lineWidth: 1))
    }

    @ViewBuilder private var list: some View {
        if model.conversations.isEmpty {
            UnavailableState(title: "No conversations yet", kind: .empty,
                             detail: "Start one with New conversation, or ask from the Jarvis tab.", symbol: "bubble.left")
        } else if rows.isEmpty {
            let q = trimmedQuery
            UnavailableState(title: q.isEmpty ? "Nothing this week" : "Nothing found", kind: .empty,
                             detail: q.isEmpty ? "No conversation was active in the last seven days."
                                 : "No conversation title contains \u{201C}\(q.prefix(40))\u{201D}.",
                             symbol: "magnifyingglass",
                             action: q.isEmpty ? nil : UnavailableState.Action(label: "Clear search") { query = "" })
        } else {
            ActivityList(rows: rows, onSelect: open)
        }
    }

    private var lastActivity: String {
        guard let newest = timestamps.compactMap(parseCoreDate).max() else { return "—" }
        return relativeTime(newest, now: now)
    }

    private func open(_ row: ActivityRow) {
        guard let id = UUID(uuidString: row.id) else { return }
        Task { await model.openConversation(id) }
        navigate(.chat)
    }
}

// MARK: - Agents

/// Agents per group (D-Runs list pattern); the selected agent's card, figures
/// and tools sit on top.
struct AgentsPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var source: Availability<AgentsResponse> = .loading
    @State private var selected = ""
    @State private var sub = "overview"

    private var response: AgentsResponse? { source.value }
    private var groups: [(group: String, agents: [AgentInfo])] { groupAgents(response?.agents ?? []) }
    private var ordered: [AgentInfo] { groups.flatMap { $0.agents } }

    private func symbol(_ agent: AgentInfo) -> String { isTrader(agent) ? "chart.line.uptrend.xyaxis" : "person.2" }

    private func item(_ agent: AgentInfo) -> NodeItem {
        let (tone, status) = agentStatus(agent.usage, unavailableReason: response?.usage_unavailable_reason)
        return NodeItem(id: agent.id, label: agent.name, title: agent.name, icon: symbol(agent),
                        detail: agent.description ?? "", tone: tone, status: status)
    }

    var body: some View {
        PageScaffold(title: "Agents", subtitle: "Your personal AI system", top: selected) {
            Loaded(source: source, title: "Agents", symbol: "person.2", forbidden: "Agents are turned off in Core.", retry: load) { response in
                if response.agents.isEmpty {
                    UnavailableState(title: "No agents", kind: .empty, detail: response.unavailable_reason == nil
                                     ? "The installed agent bundle has no agents."
                                     : "Core has no agent bundle installed, so there are no agents to show.", symbol: "person.2")
                } else if let agent = ordered.first(where: { $0.id == selected }) {
                    NodePanel(item: item(agent)) {
                        if isTrader(agent) {
                            Text("Trading opens in the desktop app.")
                                .font(JV.font(13))
                                .foregroundStyle(JV.textSecondary)
                        }
                    }
                    SegmentedControl(label: "Agent details", pairs: [("overview", "Overview"), ("tools", "Tools")], selection: $sub)
                    if sub == "overview" {
                        TileGrid(tiles: tiles(agent))
                    } else {
                        ActivityList(rows: (agent.allowed_tools ?? []).map {
                            ActivityRow(id: $0, symbol: "terminal", title: $0, tone: .ok, toneLabel: "Allowed", badge: false)
                        }, emptyText: "This agent may not use any tools.")
                    }
                    ForEach(groups, id: \.group) { group in
                        ListSection(title: group.group) {
                            ActivityList(rows: group.agents.map(row), onSelect: { selected = $0.id })
                        }
                    }
                }
            }
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
    }

    private func row(_ agent: AgentInfo) -> ActivityRow {
        let (tone, label) = agentStatus(agent.usage, unavailableReason: response?.usage_unavailable_reason)
        return ActivityRow(id: agent.id, symbol: symbol(agent), title: agent.name,
                           detail: agent.model_policy, tone: tone, toneLabel: label, selected: agent.id == selected)
    }

    private func tiles(_ agent: AgentInfo) -> [TileData] {
        let usage = agent.usage
        let notMeasured = response?.usage_unavailable_reason == agentUsageNotInstrumented ? "Not measured yet" : "—"
        return [
            TileData(label: "Model policy", value: agent.model_policy ?? "—"),
            TileData(label: "Group", value: agent.group ?? "Other"),
            TileData(label: "Requests this month", value: usage.map { "\($0.requests)" } ?? notMeasured),
            TileData(label: "Tokens this month", value: usage?.total_tokens.map(formatCompact) ?? notMeasured),
            TileData(label: "Spend this month", value: usage?.spent_eur.map(formatEuro) ?? notMeasured),
            TileData(label: "Latency p95", value: formatMs(usage?.latency_p95_ms)),
        ]
    }

    private func load() async {
        let result = await model.coreRead("/v1/agents", as: AgentsResponse.self)
        guard !Task.isCancelled else { return }
        source = result
        if !ordered.contains(where: { $0.id == selected }) { selected = ordered.first?.id ?? "" }
    }
}

// MARK: - Tasks

/// Coding sessions and agent actions (D-Runs): Active, Waiting and Completed.
struct TasksPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var sessions: Availability<[CodingSession]> = .loading
    @State private var pending: Availability<[PendingAction]> = .loading
    @State private var audit: Availability<[AgentAuditEntry]> = .loading
    @State private var selected = "active"
    @State private var sub = "overview"
    @State private var now = Date()
    @Environment(\.openAgentApproval) private var openAgentApproval

    private var split: (open: [CodingSession], finished: [CodingSession]) { splitSessions(sessions.value ?? []) }

    private var views: [NodeItem] {
        let running = split.open.filter { $0.state == "active" }.count
        let finished = split.finished.count
        let active = sourceStatus(sessions) { _ in (running > 0 ? Tone.ok : Tone.idle, "\(running) running") }
        let wait = sourceStatus(pending) { list in
            list.isEmpty ? (Tone.idle, "Nothing waiting") : (Tone.warn, "\(list.count) \(list.count == 1 ? "approval" : "approvals")")
        }
        let done = sourceStatus(sessions) { _ in (finished == 0 ? Tone.idle : Tone.ok, "\(finished) finished") }
        return [
            NodeItem(id: "active", label: "Active", title: "Active", icon: "play.circle",
                     detail: "Coding sessions Jarvis is running or has paused on your Home Node.", tone: active.0, status: active.1),
            NodeItem(id: "waiting", label: "Waiting", title: "Waiting on you", icon: "exclamationmark.triangle",
                     detail: "Changes an agent proposed. Nothing runs until you approve it with this iPhone's key or on a trusted desktop.", tone: wait.0, status: wait.1),
            NodeItem(id: "completed", label: "Completed", title: "Completed", icon: "checkmark.circle",
                     detail: "Coding sessions that ended, and every agent action Core recorded.", tone: done.0, status: done.1),
        ]
    }

    private static let later: [NodeItem] = [
        .planned("scheduled", "Scheduled", "Scheduled", icon: "calendar", detail: "Core cannot run tasks at a set time yet."),
        .planned("recurring", "Recurring", "Recurring", icon: "repeat", detail: "Core cannot repeat tasks on a schedule yet."),
        .planned("goals", "Goals", "Goals", icon: "target", detail: "Core does not track goals or split them into tasks yet."),
    ]

    var body: some View {
        PageScaffold(title: "Tasks", subtitle: "What Jarvis is working on") {
            SegmentedControl(label: "Tasks", items: views, selection: $selected)
            if let current = views.first(where: { $0.id == selected }) {
                NodePanel(item: current)
                switch selected {
                case "active": active
                case "waiting": waiting
                default: completed
                }
            }
            LaterList(items: Self.later)
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
    }

    private var active: some View {
        Loaded(source: sessions, title: "Coding sessions", symbol: "play.circle", retry: load) { _ in
            TileGrid(tiles: [
                TileData(label: "Running", value: "\(split.open.filter { $0.state == "active" }.count)"),
                TileData(label: "Paused", value: "\(split.open.filter { $0.state == "suspended" }.count)"),
                TileData(label: "Awaiting approval", value: pending.value.map { "\($0.count)" } ?? "—"),
                TileData(label: "Last update", value: lastUpdate),
            ])
            ListSection(title: "Open sessions") {
                ActivityList(rows: sessionRows(split.open), emptyText: "No coding sessions are running.")
            }
        }
    }

    @ViewBuilder private var waiting: some View {
        Text("Read the whole action first. Approving signs exactly what is shown with this iPhone's device key; anything this app cannot show in full can only be denied here.")
            .font(JV.font(13))
            .foregroundStyle(JV.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
        Loaded(source: pending, title: "Approvals", symbol: "exclamationmark.triangle",
               forbidden: "Agent actions are turned off in Core.", rows: 2, retry: load) { list in
            if list.isEmpty {
                UnavailableState(title: "Nothing waiting for you", kind: .empty,
                                 detail: "When an agent needs a yes, it shows up here and on Activity.", symbol: "checkmark.shield")
            }
            ForEach(list) { action in
                PendingActionRow(item: action, time: relativeTime(action.created_at, now: now)) {
                    openAgentApproval(action) { await load() }
                }
            }
        }
    }

    @ViewBuilder private var completed: some View {
        SegmentedControl(label: "Completed", pairs: [("overview", "Sessions"), ("history", "History")], selection: $sub)
        if sub == "overview" {
            Loaded(source: sessions, title: "Coding sessions", symbol: "checkmark.circle", retry: load) { _ in
                ActivityList(rows: sessionRows(split.finished), emptyText: "No finished sessions yet.")
            }
        } else {
            Loaded(source: audit, title: "Agent history", symbol: "shield",
                   forbidden: "Agent actions are turned off in Core.", retry: load) { entries in
                ActivityList(rows: auditRows(entries, now: now), emptyText: "Core recorded no agent actions yet.")
            }
        }
    }

    private var lastUpdate: String {
        guard let first = sessions.value?.first?.updated_at else { return "—" }
        let text = relativeTime(first, now: now)
        return text.isEmpty ? "—" : text
    }

    private func sessionRows(_ list: [CodingSession]) -> [ActivityRow] {
        list.map { session in
            let (tone, label) = sessionState(session.state)
            return ActivityRow(id: session.id, symbol: "chevron.left.forwardslash.chevron.right",
                               title: session.objective.flatMap { $0.isEmpty ? nil : $0 } ?? "Coding session",
                               detail: session.repository,
                               time: session.updated_at.map { relativeTime($0, now: now) }, tone: tone, toneLabel: label)
        }
    }

    private func load() async {
        async let sessions = model.coreRead("/v1/coding/sessions", as: CodingSessionList.self)
        async let pending = model.coreRead("/v1/agent/pending", as: PendingActionList.self)
        async let audit = model.coreRead("/v1/agent/audit", as: AgentAuditResponse.self)
        let results = (await sessions, await pending, await audit)
        guard !Task.isCancelled else { return }
        self.sessions = results.0.map(\.sessions)
        self.pending = results.1.map(\.pending)
        self.audit = results.2.map(\.entries)
        now = Date()
    }
}

/// Agent history rows from Core's audit log.
func auditRows(_ entries: [AgentAuditEntry], now: Date) -> [ActivityRow] {
    entries.enumerated().map { index, entry in
        ActivityRow(id: "\(entry.ts)-\(index)", symbol: "shield", title: entry.action,
                    detail: [entry.risk, entry.note].compactMap { $0 }.joined(separator: " · "),
                    time: relativeTime(entry.ts, now: now), tone: outcomeTone(entry.outcome), toneLabel: entry.outcome)
    }
}

// MARK: - Integrations

struct IntegrationsPage: View {
    @ObservedObject var model: JarvisAppModel
    let navigate: (HubRoute) -> Void
    @State private var registry: Availability<HomeNodeRegistry> = .loading
    @State private var policy: Availability<ModelPolicySnapshot> = .loading
    @State private var ibkr: Availability<IbkrPortfolio> = .loading
    @State private var loadedAt = Date()
    @State private var selected = "brains"
    @State private var sub = "overview"

    private var views: [NodeItem] {
        let brains = registry.value?.brains ?? []
        let ready = brains.filter(\.available).count
        let allowedCount = (policy.value?.models ?? []).filter(\.enabled).count
        let b = sourceStatus(registry) { _ in (ready > 0 ? Tone.ok : Tone.idle, "\(ready) of \(brains.count) ready") }
        let m = sourceStatus(policy) { _ in (allowedCount == 0 ? Tone.idle : Tone.ok, "\(allowedCount) allowed") }
        return [
            NodeItem(id: "brains", label: "Brains", title: "AI brains", icon: "sparkles",
                     detail: "The brains Core found on your Home Node. Ready means it is set up, not that it is in use.", tone: b.0, status: b.1),
            NodeItem(id: "models", label: "Models", title: "Models", icon: "square.stack.3d.up",
                     detail: "Allowing a model lets Jarvis choose it; it never selects a model for a running request.", tone: m.0, status: m.1),
            ibkrItem(ibkr, now: loadedAt),
        ]
    }

    private static let later: [NodeItem] = [
        .planned("codex", "Codex", "Codex", icon: "terminal", detail: "Core does not report the Codex broker status to the app yet."),
        .planned("github", "GitHub", "GitHub", icon: "arrow.triangle.branch", detail: "Core does not report a GitHub connection to the app yet."),
        .planned("sandbox", "Sandbox", "OpenSandbox", icon: "shippingbox", detail: "Core does not report OpenSandbox status to the app yet."),
    ]

    var body: some View {
        PageScaffold(title: "Integrations", subtitle: "Brains, models and tools") {
            SegmentedControl(label: "Integrations", items: views, selection: $selected)
            if let current = views.first(where: { $0.id == selected }) {
                switch selected {
                case "brains":
                    NodePanel(item: current)
                    SegmentedControl(label: "Brains", pairs: [("overview", "Overview"), ("tools", "Host tools")], selection: $sub)
                    Loaded(source: registry, title: "Home Node registry", symbol: "sparkles", retry: load) { value in
                        if sub == "overview" {
                            ActivityList(rows: (value.brains ?? []).map { brain in
                                ActivityRow(id: brain.id, symbol: "sparkles", title: brain.label,
                                            detail: [costLabel(brain.cost), brain.note ?? ""].filter { !$0.isEmpty }.joined(separator: " · "),
                                            tone: brain.available ? .ok : .idle, toneLabel: brain.available ? "Ready" : "Not set up")
                            }, emptyText: value.brains == nil ? "This Core does not report brains yet." : "No brains found.")
                        } else {
                            ActivityList(rows: softwareRows(value.software), emptyText: "No host tools reported.")
                        }
                    }
                case "models":
                    NodePanel(item: current) {
                        PanelButton(title: "Manage models", symbol: "slider.horizontal.3") { navigate(.models) }
                    }
                    Loaded(source: policy, title: "Model policy", symbol: "square.stack.3d.up", retry: load) { value in
                        ListSection(title: "Allowed models") {
                            ActivityList(rows: value.models.filter(\.enabled).map {
                                ActivityRow(id: $0.id, symbol: "square.stack.3d.up", title: $0.model, detail: $0.provider,
                                            tone: .ok, toneLabel: "Allowed", badge: false)
                            }, emptyText: "No models are allowed.")
                        }
                    }
                default:
                    IbkrPanel(source: ibkr, loadedAt: loadedAt, retry: load)
                }
            }
            LaterList(items: Self.later)
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        async let registry = model.coreRead("/v1/system/registry", as: HomeNodeRegistry.self)
        async let policy = model.coreRead("/v1/system/models", as: ModelPolicySnapshot.self)
        async let ibkr = model.coreRead("/v1/trading/portfolio", as: IbkrPortfolio.self)
        let results = (await registry, await policy, await ibkr)
        guard !Task.isCancelled else { return }
        self.registry = results.0
        self.policy = results.1
        self.ibkr = results.2
        loadedAt = Date()
    }
}

func ibkrItem(_ source: Availability<IbkrPortfolio>, now: Date) -> NodeItem {
    let state = ibkrState(source, now: now)
    return NodeItem(id: "ibkr", label: "IBKR", title: "Interactive Brokers", icon: "chart.line.uptrend.xyaxis",
                    detail: "Core's read-only link to Interactive Brokers. Jarvis only reads; it never places orders.",
                    tone: state.0, status: state.1)
}

/// IBKR link state from Core's read-only portfolio read (Integrations and Trading).
struct IbkrPanel: View {
    let source: Availability<IbkrPortfolio>
    let loadedAt: Date
    var retry: (() async -> Void)? = nil

    var body: some View {
        let item = ibkrItem(source, now: loadedAt)
        NodePanel(item: item)
        if case .error(.unavailable) = source {
            UnavailableState(title: "Interactive Brokers", kind: .error, detail: ibkrNotSynced, symbol: "chart.line.uptrend.xyaxis",
                             action: retry.map { UnavailableState.Action(label: "Try again", run: $0) })
        } else {
            Loaded(source: source, title: "Interactive Brokers", symbol: "chart.line.uptrend.xyaxis", rows: 1, retry: retry) { portfolio in
                let synced = relativeTime(portfolio.asOf, now: loadedAt)
                TileGrid(tiles: [
                    TileData(label: "Connection", value: item.status),
                    TileData(label: "Last sync", value: synced.isEmpty ? "—" : synced),
                    TileData(label: "Positions", value: String(portfolio.positions.count)),
                    TileData(label: "Orders", value: "Read-only"),
                ])
            }
        }
    }
}

// MARK: - Trading

/// Trading tab. The iPhone app only has Core's read-only IBKR link so far;
/// the dashboard, pots and proposals of the design are not built yet.
struct TradingPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var ibkr: Availability<IbkrPortfolio> = .loading
    @State private var loadedAt = Date()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                IbkrPanel(source: ibkr, loadedAt: loadedAt, retry: load)
                UnavailableState(title: "Trading dashboard",
                                 detail: "Pots, proposals and mandates are coming to the iPhone app. Nothing here can place an order.",
                                 symbol: "chart.line.uptrend.xyaxis")
            }
            .padding(16)
        }
        .background(JV.bgPage)
        .navigationTitle(AppTab.trading.title)
        .toolbarBackground(JV.bgPage, for: .navigationBar)
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        let result = await model.coreRead("/v1/trading/portfolio", as: IbkrPortfolio.self)
        guard !Task.isCancelled else { return }
        ibkr = result
        loadedAt = Date()
    }
}

func softwareRows(_ software: [HomeNodeRegistry.Software]) -> [ActivityRow] {
    software.map { item in
        ActivityRow(id: item.name, symbol: "shippingbox", title: item.name + (item.version.map { " \($0)" } ?? ""),
                    detail: item.detail, tone: item.present ? .ok : .idle, toneLabel: item.present ? "Present" : "Missing")
    }
}

// MARK: - System Health

struct HealthPage: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var model: JarvisAppModel
    let navigate: (HubRoute) -> Void
    @State private var livez: HealthProbe?
    @State private var readyz: HealthProbe?
    @State private var probed = false
    @State private var checkedAt: Date?
    @State private var registry: Availability<HomeNodeRegistry> = .loading
    @State private var usage: Availability<UsageSummary> = .loading
    @State private var services: Availability<ServicesResponse> = .loading
    @State private var audit: Availability<[SystemAuditEntry]> = .loading
    @State private var selected: String
    @State private var sub = "overview"

    init(model: JarvisAppModel, navigate: @escaping (HubRoute) -> Void, node: String = "core") {
        self.model = model
        self.navigate = navigate
        _selected = State(initialValue: node)
    }

    private var views: [NodeItem] {
        let core: (Tone, String) = !probed ? (Tone.idle, "Checking…") : readyz != nil ? (Tone.ok, "Running") : (Tone.error, "Unreachable")
        let node = sourceStatus(registry) { value in
            value.liveHost.map { live in (Tone.ok, "Up \(formatUptime(live.uptimeSeconds))") } ?? (Tone.idle, "No live vitals")
        }
        let spend = sourceStatus(usage) { u in
            u.over_budget ? (Tone.error, "Budget reached")
                : u.above_soft_budget == true ? (Tone.warn, "Above soft budget")
                : (Tone.ok, "\(formatEuro(u.spent_eur)) of \(formatEuro(u.budget_eur))")
        }
        var svc = sourceStatus(services) { servicesSummary($0.services) }
        let fullDisk = (services.value?.disks ?? []).contains { disk in diskState(disk).0 == .warn || diskState(disk).0 == .error }
        if fullDisk && svc.0 == .ok { svc = (.warn, "\(svc.1) · disk nearly full") }
        return [
            NodeItem(id: "core", label: "Core", title: "Jarvis Core", icon: "cpu",
                     detail: "The heart of Jarvis on your Home Node — everything else talks through it.", tone: core.0, status: core.1),
            NodeItem(id: "node", label: "Home Node", title: "Home Node", icon: "house",
                     detail: "The machine Jarvis runs on: hardware inventory, live load and installed tools.", tone: node.0, status: node.1),
            NodeItem(id: "usage", label: "Usage", title: "Usage", icon: "chart.line.uptrend.xyaxis",
                     detail: "Monthly spend against the hard budget, with token use per backend.", tone: spend.0, status: spend.1),
            NodeItem(id: "services", label: "Services", title: "Services and disks", icon: "internaldrive",
                     detail: "The Jarvis services on your Home Node and the free space on its disks.", tone: svc.0, status: svc.1),
        ]
    }

    var body: some View {
        PageScaffold(title: "System health", subtitle: "Core, Home Node and usage") {
            SegmentedControl(label: "System health", items: views, selection: $selected)
            if let current = views.first(where: { $0.id == selected }) {
                NodePanel(item: current)
                switch selected {
                case "core":
                    TileGrid(tiles: [
                        TileData(label: "Liveness", value: probeLabel(livez)),
                        TileData(label: "Readiness", value: probeLabel(readyz)),
                        TileData(label: "Environment", value: readyz?.environment ?? "—"),
                        TileData(label: "Checked", value: checkedAt.map { $0.formatted(date: .omitted, time: .shortened) } ?? "—"),
                    ])
                    ListSection(title: "Security events") {
                        Loaded(source: audit, title: "Security events", symbol: "shield", retry: load) { entries in
                            ActivityList(rows: securityRows(entries), emptyText: "No security events recorded.")
                        }
                    }
                case "node":
                    SegmentedControl(label: "Home Node", pairs: [("overview", "Overview"), ("software", "Software")], selection: $sub)
                    Loaded(source: registry, title: "Home Node", symbol: "house", retry: load) { value in
                        if sub == "overview" { TileGrid(tiles: nodeTiles(value)) }
                        else { ActivityList(rows: softwareRows(value.software), emptyText: "No software reported.") }
                    }
                case "usage":
                    Loaded(source: usage, title: "Usage", symbol: "chart.line.uptrend.xyaxis", retry: load) { u in
                        TileGrid(tiles: [
                            TileData(label: "Spent this month", value: formatEuro(u.spent_eur)),
                            TileData(label: "Budget", value: formatEuro(u.budget_eur)),
                            TileData(label: "Remaining", value: formatEuro(u.remaining_eur ?? max(0, u.budget_eur - u.spent_eur))),
                            TileData(label: "Reserved", value: u.reserved_eur.map(formatEuro) ?? "—"),
                            TileData(label: "Requests", value: u.requests.map { "\($0)" } ?? "—"),
                            TileData(label: "Tokens", value: u.total_tokens.map(formatCompact) ?? "—"),
                            TileData(label: "Latency p95", value: formatMs(u.latency_p95_ms)),
                        ])
                        ListSection(title: "By backend") {
                            ActivityList(rows: (u.by_backend ?? []).map { backend in
                                ActivityRow(id: backend.backend, symbol: "sparkles", title: backend.backend,
                                            detail: backend.total_tokens.map { "\(formatCompact($0)) tokens" },
                                            time: formatEuro(backend.spent_eur), tone: .ok, toneLabel: "Used this month", badge: false)
                            }, emptyText: "No usage this month.")
                        }
                    }
                default:
                    Loaded(source: services, title: "Services and disks", symbol: "internaldrive", retry: load) { value in
                        ListSection(title: "Services") {
                            ActivityList(rows: value.services.map { service in
                                let (tone, label) = serviceState(service.state)
                                return ActivityRow(id: service.unit, symbol: "cpu", title: service.label, detail: service.unit,
                                                   tone: tone, toneLabel: label)
                            }, emptyText: "No services reported.")
                        }
                        ListSection(title: "Disks") {
                            ActivityList(rows: value.disks.map { disk in
                                let (tone, label) = diskState(disk)
                                return ActivityRow(id: disk.label, symbol: "internaldrive", title: disk.label, detail: label,
                                                   tone: tone, toneLabel: diskBadge(tone))
                            }, emptyText: "No disks reported.")
                        }
                    }
                }
            }
            ListSection(title: "More") {
                Button { navigate(.models) } label: {
                    ListRow(title: HubRoute.models.title, symbol: HubRoute.models.symbol,
                            detail: "Owner-controlled model access. Allowing a model never selects it for a running request.")
                }
                .buttonStyle(.plain)
                .jvCard(padding: 0)
            }
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
        .task(id: selected == "node" && scenePhase == .active) {
            // Live vitals refresh while the Home Node view is open and the app is active.
            guard selected == "node", scenePhase == .active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                let result = await model.coreRead("/v1/system/registry", as: HomeNodeRegistry.self)
                if !Task.isCancelled { registry = result }
            }
        }
    }

    private func probeLabel(_ probe: HealthProbe?) -> String {
        guard probed else { return "Checking…" }
        return probe == nil ? "Failed" : "OK"
    }

    private func nodeTiles(_ value: HomeNodeRegistry) -> [TileData] {
        var tiles: [TileData] = []
        if let live = value.liveHost {
            tiles.append(TileData(label: "CPU usage", value: live.cpuPercent.map { String(format: "%.1f%%", $0) } ?? "First reading…"))
            tiles.append(TileData(label: "Memory in use", value: "\(formatGiB(live.memoryUsedBytes)) / \(formatGiB(live.memoryTotalBytes))"))
            tiles.append(TileData(label: "Uptime", value: formatUptime(live.uptimeSeconds)))
        }
        tiles.append(TileData(label: "Processor", value: "\(value.host.cpu) · \(value.host.cpuCores) cores"))
        tiles.append(TileData(label: "GPU", value: value.host.gpu))
        tiles.append(TileData(label: "System", value: "\(value.host.os) · \(value.host.arch)"))
        return tiles
    }

    private func securityRows(_ entries: [SystemAuditEntry]) -> [ActivityRow] {
        let now = Date()
        return entries.prefix(20).enumerated().map { index, entry in
            let good = ["ok", "success", "approved", "allowed"].contains(entry.outcome.lowercased())
            return ActivityRow(id: "\(entry.ts)-\(index)", symbol: entry.event.hasPrefix("auth") ? "key" : "shield",
                               title: entry.event, time: relativeTime(entry.ts, now: now),
                               tone: good ? .ok : .warn, toneLabel: entry.outcome)
        }
    }

    private func load() async {
        async let live = model.probe("/livez")
        async let ready = model.probe("/readyz")
        async let registry = model.coreRead("/v1/system/registry", as: HomeNodeRegistry.self)
        async let usage = model.coreRead("/v1/system/usage", as: UsageSummary.self)
        async let services = model.coreRead("/v1/system/services", as: ServicesResponse.self)
        async let audit = model.coreRead("/v1/system/audit", as: SystemAuditResponse.self)
        let probes = (await live, await ready)
        let results = (await registry, await usage, await services, await audit)
        guard !Task.isCancelled else { return }
        livez = probes.0
        readyz = probes.1
        probed = true
        checkedAt = Date()
        self.registry = results.0
        self.usage = results.1
        self.services = results.2
        self.audit = results.3.map(\.entries)
    }
}

// MARK: - Memory

/// D-Memory. Core has no browsable memory store yet: the page says so and lists
/// the categories under Later.
struct MemoryPage: View {
    @ObservedObject var model: JarvisAppModel

    private static let items: [NodeItem] = [
        .planned("people", "People", "People", icon: "person.2", detail: "Who matters and how you know them."),
        .planned("projects", "Projects", "Projects", icon: "folder", detail: "Everything about what you build."),
        .planned("preferences", "Preferences", "Preferences", icon: "heart", detail: "How you like things done."),
        .planned("knowledge", "Knowledge", "Knowledge", icon: "book", detail: "Notes, clips and saved insights."),
        .planned("routines", "Routines", "Routines", icon: "repeat", detail: "Your habits and recurring days."),
        .planned("ideas", "Ideas", "Ideas", icon: "lightbulb", detail: "Sparks worth coming back to."),
    ]

    var body: some View {
        PageScaffold(title: "Memory", subtitle: "What Jarvis remembers") {
            UnavailableState(title: "No memory store yet",
                             detail: "Core keeps your conversations but has no browsable memory store yet, so there is nothing to show here.",
                             symbol: "brain")
            LaterList(items: Self.items)
        }
    }
}

// MARK: - Devices and context

/// D-Devices: the devices linked to the Home Node, Revoke for the other active
/// ones, and the pairing requests page. Modes and sources are listed under Later.
struct ContextPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var devices: Availability<[LinkedDevice]> = .loading
    @State private var thisDevice: String?
    @State private var confirming: LinkedDevice?
    @State private var revoking: String?
    @State private var revokeError: String?

    private static let later: [NodeItem] = [
        .planned("work", "Work", "Work mode", icon: "briefcase", detail: "Focus on projects and code. Core does not support modes yet."),
        .planned("focus", "Focus", "Focus mode", icon: "moon", detail: "Only what really matters. Core does not support modes yet."),
        .planned("home", "Home", "Home mode", icon: "house", detail: "Personal life, no work pings. Core does not support modes yet."),
        .planned("sources", "Sources", "Sources", icon: "link", detail: "Where context comes from. Core does not report context sources yet."),
        .planned("schedule", "Schedule", "Schedule", icon: "calendar", detail: "When each mode switches on. Core does not schedule modes yet."),
    ]

    private var devicesItem: NodeItem {
        let summary = summarize(devices) { list in CardSummary(line1: "\(list.count) linked", line2: "", tone: list.isEmpty ? .idle : .ok) }
        return NodeItem(id: "devices", label: "Devices", title: "Devices", icon: "desktopcomputer",
                        detail: "The desktop and mobile apps linked to your Home Node.", tone: summary.tone, status: summary.line1)
    }

    var body: some View {
        PageScaffold(title: "Devices and context", subtitle: "Where and how Jarvis helps") {
            NodePanel(item: devicesItem)
            Loaded(source: devices, title: "Devices", symbol: "desktopcomputer", retry: load) { list in
                let platforms = Set(list.map(\.platform)).sorted().joined(separator: ", ")
                TileGrid(tiles: [
                    TileData(label: "Linked devices", value: "\(list.count)"),
                    TileData(label: "Platforms", value: platforms.isEmpty ? "—" : PairingFingerprint.displayName(platforms, max: 64)),
                    TileData(label: "This device",
                             value: list.first { $0.id.lowercased() == thisDevice }.map { PairingFingerprint.displayName($0.name, max: 64) } ?? "Unknown"),
                    TileData(label: "Active", value: "\(list.filter { $0.status == "active" }.count)"),
                ])
                RouteList(routes: [.pairingApprovals])
                ListSection(title: "Linked devices") { deviceList(list) }
                if let revokeError {
                    Text(verbatim: revokeError).font(JV.font(13)).foregroundStyle(JV.dangerText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            LaterList(items: Self.later)
        }
        .refreshable { await load() }
        .task { await load() }
        .confirmationDialog("Revoke this device?", isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
                            titleVisibility: .visible, presenting: confirming) { device in
            Button("Revoke", role: .destructive) { Task { await revoke(device) } }
            Button("Cancel", role: .cancel) { }
        } message: { device in
            let short = UUID(uuidString: device.id).map(AuthService.shortId) ?? "unknown"
            let linked = device.created_at.map { ", linked \(Date(timeIntervalSince1970: $0).formatted(date: .abbreviated, time: .omitted))" } ?? ""
            Text(verbatim: "\"\(PairingFingerprint.displayName(device.name, max: 40))\" (\(PairingFingerprint.displayName(device.platform, max: 16))), device \(short)\(linked), is signed out at once and must be paired again to use Jarvis.")
        }
    }

    @ViewBuilder private func deviceList(_ list: [LinkedDevice]) -> some View {
        // Nothing is offered until this device's own id is known.
        let revocable = Set(revocableDevices(list, thisDevice: thisDevice).map(\.id))
        if list.isEmpty {
            UnavailableState(title: "No devices linked", kind: .empty, symbol: "desktopcomputer")
        } else {
            VStack(spacing: 0) {
                ForEach(Array(list.enumerated()), id: \.element.id) { index, device in
                    if index > 0 { ListDivider(inset: 0) }
                    DeviceRow(device: device, isThis: device.id.lowercased() == thisDevice, revocable: revocable.contains(device.id),
                              revoking: revoking) { confirming = device }
                }
            }
            .jvCard(padding: 0)
        }
    }

    private func revoke(_ device: LinkedDevice) async {
        guard revoking == nil, let id = UUID(uuidString: device.id) else { return }
        revoking = device.id
        revokeError = nil
        do { try await model.revokeDevice(id) }
        catch {
            revokeError = "Not revoked: " + PairingFingerprint.displayName((error as? LocalizedError)?.errorDescription ?? "Core did not confirm.", max: 160)
        }
        revoking = nil
        await load()
    }

    private func load() async {
        let result = await model.coreRead("/v1/devices", as: DeviceListResponse.self)
        let mine = await model.registeredDeviceID()?.uuidString.lowercased()
        guard !Task.isCancelled else { return }
        devices = result.map(\.devices)
        thisDevice = mine
    }
}

/// One linked device: name, platform and link date, badges, and Revoke when
/// it may be revoked. Revoke asks for confirmation first (ContextPage).
private struct DeviceRow: View {
    let device: LinkedDevice
    let isThis: Bool
    let revocable: Bool
    let revoking: String?
    let revoke: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    private var name: String { PairingFingerprint.displayName(device.name, max: 64) }
    private var mobile: Bool {
        let platform = device.platform.lowercased()
        return platform.contains("ios") || platform.contains("android")
    }
    private var line: String {
        let linked = device.created_at.map { "linked \(relativeTime(Date(timeIntervalSince1970: $0), now: Date()))" }
        return [PairingFingerprint.displayName(device.platform, max: 32), linked].compactMap { $0 }.joined(separator: " · ")
    }

    var body: some View {
        let stacked = typeSize.isAccessibilitySize
        let layout = stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10)) : AnyLayout(HStackLayout(spacing: 12))
        layout {
            HStack(spacing: 12) {
                if !stacked { IconBadge(symbol: mobile ? "iphone" : "desktopcomputer", size: 36, style: .neutral) }
                VStack(alignment: .leading, spacing: 4) {
                    Text(verbatim: name).font(JV.font(15, .medium)).foregroundStyle(JV.textPrimary)
                    Text(verbatim: line).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 6) { badges }
                        VStack(alignment: .leading, spacing: 6) { badges }
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            if !stacked { Spacer(minLength: 4) }
            if revocable {
                Button(role: .destructive, action: revoke) {
                    Text(revoking == device.id ? "Revoking…" : "Revoke")
                }
                .buttonStyle(JVButtonStyle(kind: .secondary, size: .large))
                .disabled(revoking != nil)
                .accessibilityLabel(Text(verbatim: (revoking == device.id ? "Revoking " : "Revoke ") + name))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
    }

    @ViewBuilder private var badges: some View {
        if isThis { Badge(text: "This device", style: .accent, dot: false) }
        if device.status == "active" {
            Badge(text: "Active", style: .positive)
        } else {
            Badge(text: PairingFingerprint.displayName(device.status, max: 16))
        }
    }
}

// MARK: - Pairing approvals

/// Pending new-device pairing requests (Devices and context).
struct PairingApprovalsPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var refresh = 0

    var body: some View {
        PageScaffold(title: "Pairing requests", subtitle: "New devices waiting for you") {
            PairingRequestsSection(model: model, refresh: refresh)
        }
        .refreshable { refresh += 1 }
    }
}

/// Pending pairing requests, on their page and on Activity. Approve and Deny
/// need Face ID / Touch ID (never the passcode). The fingerprint is computed
/// here from the key the approval signs; candidate details are unverified
/// hints shown as plain text. `refresh` changes on pull-to-refresh.
struct PairingRequestsSection: View {
    @ObservedObject var model: JarvisAppModel
    var refresh = 0
    /// Activity shows its own empty state instead of this one.
    var showsEmpty = true
    /// Number of waiting requests once loaded, for the owner's empty state.
    var count: Binding<Int?>? = nil
    @State private var requests: Availability<[PairingApprovalItem]> = .loading
    @State private var biometrics = BiometricLock.biometricsAvailable
    @State private var busy: UUID?
    @State private var decisionError: String?
    @Environment(\.dynamicTypeSize) private var typeSize
    /// Shared by every instance (Activity and the page): list reads share Core's
    /// five-per-minute pairing budget per IP with candidates creating requests,
    /// so all reads stay at least 30 s apart, as on Android.
    private static var lastRead = Date.distantPast
    private static let minInterval: TimeInterval = 30

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if !biometrics {
                UnavailableState(title: "Biometrics required", kind: .error, detail: BiometricLock.biometricsRequired, symbol: "faceid",
                                 errorLabel: "Approve and Deny are off on this iPhone")
            }
            // No Try again: Core rate-limits this list and the 30-second poll retries.
            if let state = UnavailableState.forSource(requests, title: "Pairing requests", symbol: "person.badge.key") { state }
            // Reads are spaced 30 s apart, so the first one on a page can take a moment.
            if case .loading = requests {
                LoadingState(label: "Loading pairing requests", rows: 1)
            }
            if let decisionError {
                Text(verbatim: decisionError).font(JV.font(13)).foregroundStyle(JV.dangerText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let list = requests.value {
                if list.isEmpty && showsEmpty {
                    UnavailableState(title: "No devices waiting", kind: .empty,
                                     detail: "When a new device asks to pair, it shows up here.", symbol: "person.badge.key")
                }
                ForEach(list) { item in card(item) }
            }
        }
        .onChange(of: requests.value?.count, initial: true) { _, value in if let count { count.wrappedValue = value } }
        .onChange(of: refresh) { _, _ in
            if Date().timeIntervalSince(Self.lastRead) >= Self.minInterval { Task { await load() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: .jarvisApprovalPushed)) { _ in
            if Date().timeIntervalSince(Self.lastRead) >= Self.minInterval { Task { await load() } }
        }
        // Restarts on every tab switch and pop; the shared clock keeps the pace.
        .task {
            while !Task.isCancelled {
                let wait = Self.minInterval - Date().timeIntervalSince(Self.lastRead)
                if wait > 0 {
                    try? await Task.sleep(for: .seconds(wait))
                    continue
                }
                await load()
            }
        }
    }

    /// D-Approvals card: device and request, unverified hints, the fingerprint
    /// in a well, then Deny and Approve.
    private func card(_ item: PairingApprovalItem) -> some View {
        let checked = Result { try PairingFingerprint.verified(item) }
        let fingerprint = try? checked.get()
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                IconBadge(symbol: "person.badge.key", size: 36, style: .negative)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: PairingFingerprint.displayName(item.device_name, max: 128))
                        .font(JV.font(15, .semibold))
                        .foregroundStyle(JV.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Asks to pair with your Home Node").font(JV.font(13)).foregroundStyle(JV.textSecondary)
                }
            }
            .accessibilityElement(children: .combine)
            VStack(alignment: .leading, spacing: 6) {
                detail("Platform", PairingFingerprint.displayName(item.platform, max: 32))
                if let warning = PairingFingerprint.approverWarning(platform: item.platform) {
                    Text(verbatim: warning)
                        .font(JV.font(13, .medium))
                        .foregroundStyle(JV.negativeText)
                        .fixedSize(horizontal: false, vertical: true)
                }
                detail("Model", PairingFingerprint.displayName(item.model))
                detail("App version", PairingFingerprint.displayName(item.app_version, max: 32))
                detail("IP address", PairingFingerprint.displayName(item.ip))
                detail("User agent", PairingFingerprint.displayName(item.user_agent, max: 128))
                detail("Requested", Date(timeIntervalSince1970: TimeInterval(item.created_at)).formatted(date: .abbreviated, time: .standard))
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    detail("Expires in", pairingCountdown(item.expires_at, now: context.date))
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                Eyebrow(text: "Key fingerprint")
                switch checked {
                case let .success(value):
                    Text(verbatim: PairingFingerprint.grouped(value))
                        .font(JV.mono(13, relativeTo: .footnote))
                        .foregroundStyle(JV.textPrimary)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
                    Text("Compare it with the fingerprint the new device shows. Model, version, IP address and user agent are unverified hints.")
                        .font(JV.font(13))
                        .foregroundStyle(JV.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                case let .failure(error):
                    Text(verbatim: (error as? LocalizedError)?.errorDescription ?? "Invalid fingerprint.")
                        .font(JV.font(13))
                        .foregroundStyle(JV.dangerText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if biometrics {
                // Side by side when they fit, stacked otherwise (narrow width, large text).
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 10) {
                        denyButton(item, fill: false)
                        Spacer()
                        approveButton(item, enabled: fingerprint != nil, fill: false)
                    }
                    VStack(spacing: 10) {
                        denyButton(item, fill: true)
                        approveButton(item, enabled: fingerprint != nil, fill: true)
                    }
                }
                .disabled(busy != nil)
            }
        }
        .jvCard(padding: 16, border: JV.negativeSolid)
    }

    private func denyButton(_ item: PairingApprovalItem, fill: Bool) -> some View {
        Button(role: .destructive) { Task { await decide(item, approve: false) } } label: {
            Text("Deny").frame(maxWidth: fill ? .infinity : nil)
        }
        .buttonStyle(JVButtonStyle(kind: .secondary, size: .large))
    }

    private func approveButton(_ item: PairingApprovalItem, enabled: Bool, fill: Bool) -> some View {
        Button { Task { await decide(item, approve: true) } } label: {
            Text(busy == item.id ? "Verifying…" : "Approve").frame(maxWidth: fill ? .infinity : nil)
        }
        .buttonStyle(JVButtonStyle(size: .large))
        .disabled(!enabled)
    }

    /// Label and value side by side, stacked at accessibility text sizes.
    private func detail(_ label: String, _ value: String) -> some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 10))
        return layout {
            Text(label)
                .font(JV.font(13))
                .foregroundStyle(JV.textSecondary)
                .frame(width: typeSize.isAccessibilitySize ? nil : 96, alignment: .leading)
            Text(verbatim: value)
                .font(JV.font(13))
                .foregroundStyle(JV.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private func decide(_ item: PairingApprovalItem, approve: Bool) async {
        guard busy == nil else { return }
        busy = item.id
        decisionError = nil
        do {
            if approve { try await model.approvePairing(item) } else { try await model.denyPairing(item) }
            // No extra list read: the 30-second poll picks up any other change.
            requests = requests.map { list in list.filter { $0.id != item.id } }
        } catch {
            // Server text is shown sanitised and bounded.
            decisionError = PairingFingerprint.displayName((error as? LocalizedError)?.errorDescription ?? "The decision could not be completed.", max: 160)
        }
        busy = nil
        biometrics = BiometricLock.biometricsAvailable
    }

    private func load() async {
        // Claimed before the read, so a second instance does not read in parallel.
        Self.lastRead = Date()
        let result = await model.coreRead("/v1/auth/pairing/requests", as: PairingApprovalList.self)
        guard !Task.isCancelled else { return }
        requests = result.map(\.requests)
        biometrics = BiometricLock.biometricsAvailable
    }
}

/// "m:ss" until a pairing request expires, or "Expired".
func pairingCountdown(_ expiresAt: Int64, now: Date) -> String {
    let left = expiresAt - Int64(now.timeIntervalSince1970.rounded(.down))
    return left <= 0 ? "Expired" : String(format: "%lld:%02lld", left / 60, left % 60)
}

// MARK: - Voice

/// Voice input is not part of the iOS app yet; replies can already be spoken
/// (Settings › Voice).
struct VoicePage: View {
    var body: some View {
        PageScaffold(title: "Voice", subtitle: "Talk to Jarvis") {
            UnavailableState(title: "Push-to-talk", detail: "Push-to-talk arrives in milestone 2. This build does not request microphone access. Spoken replies can be turned on in Settings › Voice.", symbol: "mic")
        }
    }
}
