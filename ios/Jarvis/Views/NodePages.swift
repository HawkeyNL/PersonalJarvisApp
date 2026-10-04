import SwiftUI

// The module pages reached from the Core hub. Each one shows what Core really
// reports; features Core lacks are honest "Not yet available" states.

// MARK: - Conversations

struct ConversationsPage: View {
    @ObservedObject var model: JarvisAppModel
    let navigate: (HubRoute) -> Void
    @State private var selected = "all"
    @State private var sub = "overview"
    @State private var query = ""
    @State private var now = Date()

    private var timestamps: [String] { model.conversations.map(\.updatedAt) }

    private var items: [NodeItem] {
        let total = model.conversations.count
        let week = countSince(timestamps, days: 7, now: now)
        return [
            NodeItem(id: "all", label: "All", title: "All conversations", icon: "bubble.left",
                     detail: "Every conversation is stored on your Home Node and follows you across devices.",
                     tone: total > 0 ? .ok : .idle, status: "\(total) total"),
            NodeItem(id: "week", label: "This week", title: "This week", icon: "calendar",
                     detail: "Conversations you or Jarvis added to in the last seven days.",
                     tone: week > 0 ? .ok : .idle, status: "\(week) active"),
            .planned("channels", "Channels", "Channels", icon: "iphone",
                     detail: "Core does not record where a conversation took place yet, so threads cannot be split by voice, desktop or mobile."),
            .planned("summaries", "Summaries", "Summaries", icon: "doc.text",
                     detail: "Core does not write summaries of conversations yet."),
            .planned("actions", "Action items", "Action items", icon: "checkmark.circle",
                     detail: "Core does not extract action items from conversations yet."),
            .planned("memories", "Memories", "Linked memories", icon: "brain",
                     detail: "Core has no browsable memory store yet, so nothing can be linked to a conversation."),
        ]
    }

    private var rows: [ActivityRow] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let since = selected == "week" ? now.addingTimeInterval(-7 * 86_400) : nil
        return model.conversations.filter { item in
            if !q.isEmpty && !item.title.localizedCaseInsensitiveContains(q) { return false }
            guard let since else { return true }
            guard let date = parseCoreDate(item.updatedAt) else { return false }
            return date >= since
        }.map {
            ActivityRow(id: $0.id.uuidString, symbol: "bubble.left", title: $0.title.isEmpty ? "Untitled" : $0.title,
                        time: relativeTime($0.updatedAt, now: now), tone: .ok, toneLabel: "Stored on Core")
        }
    }

    var body: some View {
        NodeScaffold(title: "Conversations", subtitle: "Everything you and Jarvis said", items: items,
                     selection: $selected, mood: model.mood) {
            if let current = items.first(where: { $0.id == selected }) {
                if selected == "all" || selected == "week" {
                    NodePanel(item: current) {
                        PanelButton(title: "New conversation", symbol: "square.and.pencil") {
                            model.newConversation()
                            navigate(.chat)
                        }
                    }
                    ChipRow(chips: [ChipRow.Chip(id: "overview", label: "Overview", symbol: "doc.text"),
                                    ChipRow.Chip(id: "threads", label: "Threads", symbol: "text.alignleft")],
                            selection: $sub, compact: true)
                    if sub == "overview" {
                        TileGrid(tiles: [
                            TileData(label: "Conversations", value: "\(model.conversations.count)", symbol: "bubble.left"),
                            TileData(label: "Active this week", value: "\(countSince(timestamps, days: 7, now: now))", symbol: "calendar"),
                            TileData(label: "Active today", value: "\(countSince(timestamps, days: 1, now: now))", symbol: "sun.max"),
                            TileData(label: "Last activity", value: lastActivity, symbol: "clock"),
                        ])
                        Eyebrow(text: "Recent")
                        ActivityList(rows: Array(rows.prefix(5)), emptyText: "No conversations yet.", onSelect: open)
                    } else {
                        TextField("Search conversations", text: $query)
                            .font(JarvisFont.body(13))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(JarvisTheme.control, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(JarvisTheme.accent.opacity(0.3), lineWidth: 1))
                        ActivityList(rows: rows, emptyText: "No conversations match.", onSelect: open)
                    }
                } else {
                    NodePanel(item: current)
                    UnavailableState(title: current.title, detail: current.detail, symbol: current.icon)
                }
            }
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .task {
            await model.refreshConversationList()
            now = Date()
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

struct AgentsPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var source: Availability<AgentsResponse> = .loading
    @State private var selected = ""
    @State private var sub = "overview"

    private var response: AgentsResponse? { source.value }
    private var ordered: [AgentInfo] { groupAgents(response?.agents ?? []).flatMap { $0.agents } }

    private var items: [NodeItem] {
        ordered.map { agent in
            let (tone, status) = agentStatus(agent.usage, unavailableReason: response?.usage_unavailable_reason)
            return NodeItem(id: agent.id, label: agent.name, title: agent.name,
                            icon: isTrader(agent) ? "chart.line.uptrend.xyaxis" : "person.2",
                            detail: agent.description ?? "", tone: tone, status: status)
        }
    }

    var body: some View {
        NodeScaffold(title: "Agents", subtitle: "Your personal AI system", items: items, selection: $selected, mood: model.mood) {
            if let state = UnavailableState.forSource(source, title: "Agents", symbol: "person.2", forbidden: "Agents are turned off in Core.") {
                state
            } else if let response, response.agents.isEmpty {
                UnavailableState(title: "No agents", detail: response.unavailable_reason == nil
                                 ? "The installed agent bundle has no agents."
                                 : "Core has no agent bundle installed, so there are no agents to show.", symbol: "person.2")
            } else if let agent = ordered.first(where: { $0.id == selected }),
                      let current = items.first(where: { $0.id == selected }) {
                NodePanel(item: current) {
                    if isTrader(agent) {
                        Text("Trading opens in the desktop app.")
                            .font(JarvisFont.italic(12))
                            .foregroundStyle(JarvisTheme.text5)
                            .padding(.top, 6)
                    }
                }
                ChipRow(chips: [ChipRow.Chip(id: "overview", label: "Overview", symbol: "doc.text"),
                                ChipRow.Chip(id: "tools", label: "Tools", symbol: "terminal")],
                        selection: $sub, compact: true)
                if sub == "overview" {
                    TileGrid(tiles: tiles(agent))
                    Eyebrow(text: "All agents")
                    ActivityList(rows: ordered.map { other in
                        let (tone, label) = agentStatus(other.usage, unavailableReason: response?.usage_unavailable_reason)
                        return ActivityRow(id: other.id, symbol: isTrader(other) ? "chart.line.uptrend.xyaxis" : "person.2",
                                           title: other.name, detail: [other.group, other.model_policy].compactMap { $0 }.joined(separator: " · "),
                                           tone: tone, toneLabel: label)
                    }, onSelect: { selected = $0.id })
                } else {
                    ActivityList(rows: (agent.allowed_tools ?? []).map {
                        ActivityRow(id: $0, symbol: "terminal", title: $0, tone: .ok, toneLabel: "Allowed")
                    }, emptyText: "This agent may not use any tools.")
                }
            }
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
    }

    private func tiles(_ agent: AgentInfo) -> [TileData] {
        let usage = agent.usage
        let notMeasured = response?.usage_unavailable_reason == agentUsageNotInstrumented ? "Not measured yet" : "—"
        return [
            TileData(label: "Model policy", value: agent.model_policy ?? "—", symbol: "square.stack.3d.up"),
            TileData(label: "Group", value: agent.group ?? "Other", symbol: "folder"),
            TileData(label: "Requests this month", value: usage.map { "\($0.requests)" } ?? notMeasured, symbol: "arrow.up.arrow.down"),
            TileData(label: "Tokens this month", value: usage?.total_tokens.map(formatCompact) ?? notMeasured, symbol: "number"),
            TileData(label: "Spend this month", value: usage?.spent_eur.map(formatEuro) ?? notMeasured, symbol: "eurosign.circle"),
            TileData(label: "Latency p95", value: formatMs(usage?.latency_p95_ms), symbol: "timer"),
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

struct TasksPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var sessions: Availability<[CodingSession]> = .loading
    @State private var pending: Availability<[PendingAction]> = .loading
    @State private var audit: Availability<[AgentAuditEntry]> = .loading
    @State private var selected = "active"
    @State private var sub = "overview"
    @State private var now = Date()
    @State private var denying: PendingAction?
    @State private var busy = false
    @State private var decisionError: String?

    private var split: (open: [CodingSession], finished: [CodingSession]) { splitSessions(sessions.value ?? []) }
    private var waiting: [PendingAction] { pending.value ?? [] }

    private var items: [NodeItem] {
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
            .planned("scheduled", "Scheduled", "Scheduled", icon: "calendar", detail: "Core cannot run tasks at a set time yet."),
            NodeItem(id: "waiting", label: "Waiting", title: "Waiting on you", icon: "exclamationmark.triangle",
                     detail: "Changes an agent proposed. Nothing runs until you approve it on a trusted device.", tone: wait.0, status: wait.1),
            NodeItem(id: "completed", label: "Completed", title: "Completed", icon: "checkmark.circle",
                     detail: "Coding sessions that ended, and every agent action Core recorded.", tone: done.0, status: done.1),
            .planned("recurring", "Recurring", "Recurring", icon: "repeat", detail: "Core cannot repeat tasks on a schedule yet."),
            .planned("goals", "Goals", "Goals", icon: "target", detail: "Core does not track goals or split them into tasks yet."),
        ]
    }

    var body: some View {
        NodeScaffold(title: "Tasks", subtitle: "What Jarvis is working on", items: items, selection: $selected, mood: model.mood) {
            if let current = items.first(where: { $0.id == selected }) {
                NodePanel(item: current)
                switch selected {
                case "active":
                    if let state = UnavailableState.forSource(sessions, title: "Coding sessions", symbol: "play.circle") { state }
                    TileGrid(tiles: [
                        TileData(label: "Running", value: "\(split.open.filter { $0.state == "active" }.count)", symbol: "play.circle"),
                        TileData(label: "Paused", value: "\(split.open.filter { $0.state == "suspended" }.count)", symbol: "pause.circle"),
                        TileData(label: "Awaiting approval", value: pending.value.map { "\($0.count)" } ?? "—", symbol: "exclamationmark.triangle"),
                        TileData(label: "Last update", value: lastUpdate, symbol: "clock"),
                    ])
                    Eyebrow(text: "Open sessions")
                    ActivityList(rows: sessionRows(split.open), emptyText: "No coding sessions are running.")
                case "waiting":
                    if let state = UnavailableState.forSource(pending, title: "Approvals", symbol: "exclamationmark.triangle",
                                                              forbidden: "Agent actions are turned off in Core.") { state }
                    Text("Approving needs this device's signature for the agent-approval message, which only the desktop app provides. Approve on desktop; you can deny here.")
                        .font(JarvisFont.body(12))
                        .foregroundStyle(JarvisTheme.text5)
                        .fixedSize(horizontal: false, vertical: true)
                    if let decisionError {
                        Text(decisionError).font(JarvisFont.body(12)).foregroundStyle(JarvisTheme.warn)
                    }
                    if waiting.isEmpty {
                        ActivityList(rows: [], emptyText: "Nothing is waiting for you.")
                    }
                    ForEach(waiting) { action in pendingCard(action) }
                case "completed":
                    ChipRow(chips: [ChipRow.Chip(id: "overview", label: "Sessions", symbol: "chevron.left.forwardslash.chevron.right"),
                                    ChipRow.Chip(id: "history", label: "History", symbol: "clock")],
                            selection: $sub, compact: true)
                    if sub == "overview" {
                        if let state = UnavailableState.forSource(sessions, title: "Coding sessions", symbol: "checkmark.circle") { state }
                        ActivityList(rows: sessionRows(split.finished), emptyText: "No finished sessions yet.")
                    } else {
                        if let state = UnavailableState.forSource(audit, title: "Agent history", symbol: "shield",
                                                                  forbidden: "Agent actions are turned off in Core.") { state }
                        ActivityList(rows: auditRows, emptyText: "Core recorded no agent actions yet.")
                    }
                default:
                    UnavailableState(title: current.title, detail: current.detail, symbol: current.icon)
                }
            }
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
        .confirmationDialog("Deny this agent action?", isPresented: Binding(
            get: { denying != nil }, set: { if !$0 { denying = nil } }),
                            titleVisibility: .visible, presenting: denying) { action in
            Button("Deny", role: .destructive) { Task { await deny(action) } }
            Button("Cancel", role: .cancel) {}
        } message: { action in
            Text("\(action.action) will be cancelled and will not run.")
        }
    }

    private func pendingCard(_ action: PendingAction) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ActivityRowView(row: ActivityRow(id: action.pending_id, symbol: "exclamationmark.triangle", title: action.action,
                                             detail: action.preview, time: relativeTime(action.created_at, now: now),
                                             tone: .warn, toneLabel: "Waiting for approval"))
            HStack(spacing: 10) {
                Label("Approve on desktop", systemImage: "desktopcomputer")
                    .font(JarvisFont.body(12))
                    .foregroundStyle(JarvisTheme.text5)
                Spacer()
                Button("Deny", role: .destructive) { denying = action }
                    .font(JarvisFont.body(13, 500))
                    .buttonStyle(.bordered)
                    .disabled(busy)
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 10)
        }
        .background(JarvisTheme.tile, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(JarvisTheme.warn.opacity(0.4), lineWidth: 1))
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
                               detail: [label, session.repository].compactMap { $0 }.joined(separator: " · "),
                               time: session.updated_at.map { relativeTime($0, now: now) }, tone: tone, toneLabel: label)
        }
    }

    private var auditRows: [ActivityRow] {
        (audit.value ?? []).enumerated().map { index, entry in
            ActivityRow(id: "\(entry.ts)-\(index)", symbol: "shield", title: entry.action,
                        detail: [entry.risk, entry.note].compactMap { $0 }.joined(separator: " · "),
                        time: relativeTime(entry.ts, now: now), tone: outcomeTone(entry.outcome), toneLabel: entry.outcome)
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

    private func deny(_ action: PendingAction) async {
        guard !busy else { return }
        busy = true
        decisionError = nil
        denying = nil
        do { try await model.denyAgentAction(action.pending_id) }
        catch { decisionError = "Denial failed: \((error as? LocalizedError)?.errorDescription ?? "Core did not confirm.")" }
        busy = false
        await load()
    }
}

// MARK: - Integrations

struct IntegrationsPage: View {
    @ObservedObject var model: JarvisAppModel
    let navigate: (HubRoute) -> Void
    @State private var registry: Availability<HomeNodeRegistry> = .loading
    @State private var policy: Availability<ModelPolicySnapshot> = .loading
    @State private var ibkr: Availability<IbkrStatus> = .loading
    @State private var selected = "brains"
    @State private var sub = "overview"

    private var brains: [HomeNodeRegistry.Brain] { registry.value?.brains ?? [] }
    private var allowed: [ModelAccessEntry] { (policy.value?.models ?? []).filter(\.enabled) }

    private var items: [NodeItem] {
        let ready = brains.filter(\.available).count
        let total = brains.count
        let allowedCount = allowed.count
        let b = sourceStatus(registry) { _ in (ready > 0 ? Tone.ok : Tone.idle, "\(ready) of \(total) ready") }
        let m = sourceStatus(policy) { _ in (allowedCount == 0 ? Tone.idle : Tone.ok, "\(allowedCount) allowed") }
        let i = sourceStatus(ibkr, ok: ibkrState)
        return [
            NodeItem(id: "brains", label: "Brains", title: "AI brains", icon: "sparkles",
                     detail: "The brains Core found on your Home Node. Ready means it is set up, not that it is in use.", tone: b.0, status: b.1),
            NodeItem(id: "models", label: "Models", title: "Models", icon: "square.stack.3d.up",
                     detail: "Allowing a model lets Jarvis choose it; it never selects a model for a running request.", tone: m.0, status: m.1),
            NodeItem(id: "ibkr", label: "IBKR", title: "Interactive Brokers", icon: "chart.line.uptrend.xyaxis",
                     detail: "Gateway status for Interactive Brokers. Jarvis only reads; it never places orders.", tone: i.0, status: i.1),
            .planned("codex", "Codex", "Codex", icon: "terminal", detail: "Core does not report the Codex broker status to the app yet."),
            .planned("github", "GitHub", "GitHub", icon: "arrow.triangle.branch", detail: "Core does not report a GitHub connection to the app yet."),
            .planned("sandbox", "Sandbox", "OpenSandbox", icon: "shippingbox", detail: "Core does not report OpenSandbox status to the app yet."),
        ]
    }

    var body: some View {
        NodeScaffold(title: "Integrations", subtitle: "Brains, models and tools", items: items, selection: $selected, mood: model.mood) {
            if let current = items.first(where: { $0.id == selected }) {
                switch selected {
                case "brains":
                    NodePanel(item: current)
                    ChipRow(chips: [ChipRow.Chip(id: "overview", label: "Overview", symbol: "doc.text"),
                                    ChipRow.Chip(id: "tools", label: "Host tools", symbol: "shippingbox")],
                            selection: $sub, compact: true)
                    if let state = UnavailableState.forSource(registry, title: "Home Node registry", symbol: "sparkles") { state }
                    if sub == "overview" {
                        ActivityList(rows: brains.map { brain in
                            ActivityRow(id: brain.id, symbol: "sparkles", title: brain.label,
                                        detail: [costLabel(brain.cost), brain.note ?? ""].filter { !$0.isEmpty }.joined(separator: " · "),
                                        tone: brain.available ? .ok : .idle, toneLabel: brain.available ? "Ready" : "Not set up")
                        }, emptyText: registry.value?.brains == nil ? "This Core does not report brains yet." : "No brains found.")
                    } else {
                        ActivityList(rows: softwareRows(registry.value?.software ?? []), emptyText: "No host tools reported.")
                    }
                case "models":
                    NodePanel(item: current) {
                        PanelButton(title: "Manage models", symbol: "slider.horizontal.3") { navigate(.models) }
                    }
                    if let state = UnavailableState.forSource(policy, title: "Model policy", symbol: "square.stack.3d.up") { state }
                    Eyebrow(text: "Allowed models")
                    ActivityList(rows: allowed.map {
                        ActivityRow(id: $0.id, symbol: "square.stack.3d.up", title: $0.model, detail: $0.provider, tone: .ok, toneLabel: "Allowed")
                    }, emptyText: "No models are allowed.")
                case "ibkr":
                    NodePanel(item: current)
                    if let state = UnavailableState.forSource(ibkr, title: "Interactive Brokers", symbol: "chart.line.uptrend.xyaxis") { state }
                    if let status = ibkr.value {
                        TileGrid(tiles: [
                            TileData(label: "Gateway", value: status.reachable ? "Reachable" : "Unreachable", symbol: "network"),
                            TileData(label: "Session", value: status.authenticated ? "Logged in" : "Not logged in", symbol: "key"),
                            TileData(label: "Connection", value: status.connected.map { $0 ? "Connected" : "Not connected" } ?? "Not reported",
                                     symbol: "link"),
                            TileData(label: "Orders", value: "Read-only", symbol: "lock"),
                        ])
                    }
                default:
                    NodePanel(item: current)
                    UnavailableState(title: current.title, detail: current.detail, symbol: current.icon)
                }
            }
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        async let registry = model.coreRead("/v1/system/registry", as: HomeNodeRegistry.self)
        async let policy = model.coreRead("/v1/system/models", as: ModelPolicySnapshot.self)
        async let ibkr = model.coreRead("/v1/broker/ibkr/status", as: IbkrStatus.self)
        let results = (await registry, await policy, await ibkr)
        guard !Task.isCancelled else { return }
        self.registry = results.0
        self.policy = results.1
        self.ibkr = results.2
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
    @State private var selected = "core"
    @State private var sub = "overview"

    private var items: [NodeItem] {
        let core: (Tone, String) = !probed ? (Tone.idle, "Checking…") : readyz != nil ? (Tone.ok, "Running") : (Tone.error, "Unreachable")
        let node = sourceStatus(registry) { value in
            value.liveHost.map { live in (Tone.ok, "Up \(formatUptime(live.uptimeSeconds))") } ?? (Tone.idle, "No live vitals")
        }
        let spend = sourceStatus(usage) { u in
            u.over_budget ? (Tone.error, "Budget reached") : (Tone.ok, "\(formatEuro(u.spent_eur)) of \(formatEuro(u.budget_eur))")
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
            NodeItem(id: "models", label: "Models", title: "Models", icon: "square.stack.3d.up",
                     detail: "Owner-controlled model access. Allowing a model never selects it for a running request.",
                     tone: .idle, status: "Owner policy"),
            NodeItem(id: "services", label: "Services", title: "Services & disk", icon: "internaldrive",
                     detail: "The Jarvis services on your Home Node and the free space on its disks.", tone: svc.0, status: svc.1),
        ]
    }

    var body: some View {
        NodeScaffold(title: "System Health", subtitle: "Core, Home Node and usage", items: items, selection: $selected, mood: model.mood) {
            if let current = items.first(where: { $0.id == selected }) {
                switch selected {
                case "core":
                    NodePanel(item: current)
                    TileGrid(tiles: [
                        TileData(label: "Liveness", value: probeLabel(livez), symbol: "heart"),
                        TileData(label: "Readiness", value: probeLabel(readyz), symbol: "checkmark.seal"),
                        TileData(label: "Environment", value: readyz?.environment ?? "—", symbol: "server.rack"),
                        TileData(label: "Checked", value: checkedAt.map { $0.formatted(date: .omitted, time: .shortened) } ?? "—", symbol: "clock"),
                    ])
                    Eyebrow(text: "Security events")
                    if let state = UnavailableState.forSource(audit, title: "Security events", symbol: "shield") { state }
                    ActivityList(rows: auditRows, emptyText: "No security events recorded.")
                case "node":
                    NodePanel(item: current)
                    ChipRow(chips: [ChipRow.Chip(id: "overview", label: "Overview", symbol: "doc.text"),
                                    ChipRow.Chip(id: "software", label: "Software", symbol: "square.stack.3d.up")],
                            selection: $sub, compact: true)
                    if let state = UnavailableState.forSource(registry, title: "Home Node", symbol: "house") { state }
                    if let value = registry.value {
                        if sub == "overview" { TileGrid(tiles: nodeTiles(value)) }
                        else { ActivityList(rows: softwareRows(value.software), emptyText: "No software reported.") }
                    }
                case "usage":
                    NodePanel(item: current)
                    if let state = UnavailableState.forSource(usage, title: "Usage", symbol: "chart.line.uptrend.xyaxis") { state }
                    if let u = usage.value {
                        TileGrid(tiles: [
                            TileData(label: "Spent this month", value: formatEuro(u.spent_eur), symbol: "eurosign.circle"),
                            TileData(label: "Budget", value: formatEuro(u.budget_eur), symbol: "gauge"),
                            TileData(label: "Remaining", value: formatEuro(u.remaining_eur ?? max(0, u.budget_eur - u.spent_eur)), symbol: "banknote"),
                            TileData(label: "Requests", value: u.requests.map { "\($0)" } ?? "—", symbol: "arrow.up.arrow.down"),
                            TileData(label: "Tokens", value: u.total_tokens.map(formatCompact) ?? "—", symbol: "number"),
                            TileData(label: "Latency p95", value: formatMs(u.latency_p95_ms), symbol: "timer"),
                        ])
                        Eyebrow(text: "By backend")
                        ActivityList(rows: (u.by_backend ?? []).map { backend in
                            ActivityRow(id: backend.backend, symbol: "sparkles", title: backend.backend,
                                        detail: backend.total_tokens.map { "\(formatCompact($0)) tokens" },
                                        time: formatEuro(backend.spent_eur), tone: .ok, toneLabel: "Used this month")
                        }, emptyText: "No usage this month.")
                    }
                case "models":
                    NodePanel(item: current) {
                        PanelButton(title: "Manage models", symbol: "slider.horizontal.3") { navigate(.models) }
                    }
                default:
                    NodePanel(item: current)
                    if let state = UnavailableState.forSource(services, title: "Services & disk", symbol: "internaldrive") { state }
                    if let value = services.value {
                        Eyebrow(text: "Services")
                        ActivityList(rows: value.services.map { service in
                            let (tone, label) = serviceState(service.state)
                            return ActivityRow(id: service.unit, symbol: "cpu", title: service.label, detail: service.unit,
                                               time: label, tone: tone, toneLabel: label)
                        }, emptyText: "No services reported.")
                        Eyebrow(text: "Disks")
                        ActivityList(rows: value.disks.map { disk in
                            let (tone, label) = diskState(disk)
                            return ActivityRow(id: disk.label, symbol: "internaldrive", title: disk.label, detail: label,
                                               tone: tone, toneLabel: label)
                        }, emptyText: "No disks reported.")
                    }
                }
            }
        }
        .onChange(of: selected) { _, _ in sub = "overview" }
        .refreshable { await load() }
        .task { await load() }
        .task(id: selected == "node" && scenePhase == .active) {
            // Live vitals refresh while the Home Node tab is open and the app is active.
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
            tiles.append(TileData(label: "CPU usage", value: live.cpuPercent.map { String(format: "%.1f%%", $0) } ?? "First reading…", symbol: "cpu"))
            tiles.append(TileData(label: "Memory in use", value: "\(formatGiB(live.memoryUsedBytes)) / \(formatGiB(live.memoryTotalBytes))",
                                  symbol: "memorychip"))
            tiles.append(TileData(label: "Uptime", value: formatUptime(live.uptimeSeconds), symbol: "clock"))
        }
        tiles.append(TileData(label: "Processor", value: "\(value.host.cpu) · \(value.host.cpuCores) cores", symbol: "cpu"))
        tiles.append(TileData(label: "GPU", value: value.host.gpu, symbol: "display"))
        tiles.append(TileData(label: "System", value: "\(value.host.os) · \(value.host.arch)", symbol: "server.rack"))
        return tiles
    }

    private var auditRows: [ActivityRow] {
        let now = Date()
        return (audit.value ?? []).prefix(20).enumerated().map { index, entry in
            let good = ["ok", "success", "approved", "allowed"].contains(entry.outcome.lowercased())
            return ActivityRow(id: "\(entry.ts)-\(index)", symbol: entry.event.hasPrefix("auth") ? "key" : "shield",
                               title: entry.event, detail: entry.outcome, time: relativeTime(entry.ts, now: now),
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

struct MemoryPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var selected = "people"

    private static let items: [NodeItem] = [
        .planned("people", "People", "People", icon: "person.2", detail: "Who matters and how you know them."),
        .planned("projects", "Projects", "Projects", icon: "folder", detail: "Everything about what you build."),
        .planned("preferences", "Preferences", "Preferences", icon: "heart", detail: "How you like things done."),
        .planned("knowledge", "Knowledge", "Knowledge", icon: "book", detail: "Notes, clips and saved insights."),
        .planned("routines", "Routines", "Routines", icon: "repeat", detail: "Your habits and recurring days."),
        .planned("ideas", "Ideas", "Ideas", icon: "lightbulb", detail: "Sparks worth coming back to."),
    ]

    var body: some View {
        NodeScaffold(title: "Memory", subtitle: "What Jarvis remembers", items: Self.items, selection: $selected, mood: model.mood) {
            if let current = Self.items.first(where: { $0.id == selected }) {
                NodePanel(item: current)
                UnavailableState(title: current.title,
                                 detail: "Core keeps your conversations but has no browsable memory store yet, so there is nothing to show here.",
                                 symbol: current.icon)
            }
        }
    }
}

// MARK: - Context

struct ContextPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var devices: Availability<[LinkedDevice]> = .loading
    @State private var thisDevice: String?
    @State private var selected = "devices"

    private var items: [NodeItem] {
        let summary = summarize(devices) { list in CardSummary(line1: "\(list.count) linked", line2: "", tone: list.isEmpty ? .idle : .ok) }
        return [
            .planned("work", "Work", "Work mode", icon: "briefcase", detail: "Focus on projects and code. Core does not support modes yet."),
            .planned("focus", "Focus", "Focus mode", icon: "moon", detail: "Only what really matters. Core does not support modes yet."),
            .planned("home", "Home", "Home mode", icon: "house", detail: "Personal life, no work pings. Core does not support modes yet."),
            .planned("sources", "Sources", "Sources", icon: "link", detail: "Where context comes from. Core does not report context sources yet."),
            .planned("schedule", "Schedule", "Schedule", icon: "calendar", detail: "When each mode switches on. Core does not schedule modes yet."),
            NodeItem(id: "devices", label: "Devices", title: "Devices", icon: "desktopcomputer",
                     detail: "The desktop and mobile apps linked to your Home Node.", tone: summary.tone, status: summary.line1),
        ]
    }

    var body: some View {
        NodeScaffold(title: "Context", subtitle: "Where and how Jarvis helps", items: items, selection: $selected, mood: model.mood) {
            if let current = items.first(where: { $0.id == selected }) {
                NodePanel(item: current)
                if selected == "devices" {
                    if let state = UnavailableState.forSource(devices, title: "Devices", symbol: "desktopcomputer") { state }
                    if let list = devices.value {
                        let platforms = Set(list.map(\.platform)).sorted().joined(separator: ", ")
                        TileGrid(tiles: [
                            TileData(label: "Linked devices", value: "\(list.count)", symbol: "desktopcomputer"),
                            TileData(label: "Platforms", value: platforms.isEmpty ? "—" : platforms, symbol: "square.grid.2x2"),
                            TileData(label: "This device", value: list.first { $0.id.lowercased() == thisDevice }?.name ?? "Unknown", symbol: "iphone"),
                            TileData(label: "Active", value: "\(list.filter { $0.status == "active" }.count)", symbol: "checkmark.circle"),
                        ])
                        Eyebrow(text: "Linked devices")
                        ActivityList(rows: list.map { device in
                            let mobile = device.platform.lowercased().contains("ios") || device.platform.lowercased().contains("android")
                            return ActivityRow(id: device.id, symbol: mobile ? "iphone" : "desktopcomputer",
                                               title: device.id.lowercased() == thisDevice ? "\(device.name) (this device)" : device.name,
                                               detail: device.platform,
                                               time: device.created_at.map { "linked \(relativeTime(Date(timeIntervalSince1970: $0), now: Date()))" },
                                               tone: device.status == "active" ? .ok : .idle, toneLabel: device.status)
                        }, emptyText: "No devices linked.")
                    }
                } else {
                    UnavailableState(title: current.title, detail: current.detail, symbol: current.icon)
                }
            }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        let result = await model.coreRead("/v1/devices", as: DeviceListResponse.self)
        let mine = await model.registeredDeviceID()?.uuidString.lowercased()
        guard !Task.isCancelled else { return }
        devices = result.map(\.devices)
        thisDevice = mine
    }
}

// MARK: - Voice

/// Voice input is not part of the iOS app yet; replies can already be spoken
/// (Settings › Voice).
struct VoicePage: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                JarvisOrb(size: 160, mood: .idle)
                    .padding(.top, 48)
                UnavailableState(title: "Push-to-talk", detail: "Push-to-talk arrives in milestone 2. This build does not request microphone access. Spoken replies can be turned on in Settings › Voice.", symbol: "mic")
            }
            .padding(16)
        }
        .background { JarvisBackdrop() }
        .jarvisNavigationBar(title: "Voice", subtitle: "Talk to Jarvis")
    }
}
