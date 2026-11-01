import SwiftUI

/// Every screen reachable from the Core hub.
enum HubRoute: Hashable {
    case chat, voice, settings, models
    case conversations, agents, tasks, integrations, health, memory, context
    /// Pending new-device pairing requests (Context › Devices).
    case pairingApprovals
    /// System Health opened on its Usage node.
    case healthUsage
}

/// Route for a notification deep link. Links only navigate, never act; any
/// other URL returns nil and is ignored.
func deepLinkRoute(_ url: URL) -> HubRoute? {
    guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
          components.scheme?.lowercased() == "jarvis" else { return nil }
    switch (components.host?.lowercased(), components.path) {
    case ("approvals", "/pairing"): return .pairingApprovals
    case ("health", "/usage"): return .healthUsage
    default: return nil
    }
}

/// What the root shows. The hub, and every route behind it, needs an unlocked
/// app, a reachable Home Node and a signed-in session; anything else enrolls.
enum RootScreen: Equatable { case lock, enrollment, hub }

func rootScreen(lock: AppLockState, connection: ConnectionState, authenticated: Bool) -> RootScreen {
    if lock != .unlocked { return .lock }
    return connection == .reachable && authenticated ? .hub : .enrollment
}

/// Core hub: the orb with its seven modules and a two-column card grid.
/// Every number comes from Core; a route an older Core lacks says so.
struct HubView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @ObservedObject var model: JarvisAppModel
    @State private var devices: Availability<[LinkedDevice]> = .loading
    @State private var pending: Availability<[PendingAction]> = .loading
    @State private var sessions: Availability<[CodingSession]> = .loading
    @State private var registry: Availability<HomeNodeRegistry> = .loading
    @State private var agentCount: Availability<Int> = .loading
    @State private var now = Date()
    /// Result of the last /readyz probe; nil until the first one returns.
    @State private var ready: Bool?

    private struct Module {
        let route: HubRoute
        let title: String
        let symbol: String
        /// Satellite offset from the orb centre on the 124 pt ring.
        let offset: CGSize
    }

    private static let modules: [Module] = [
        Module(route: .conversations, title: "Conversations", symbol: "bubble.left", offset: CGSize(width: 0, height: -124)),
        Module(route: .memory, title: "Memory", symbol: "brain", offset: CGSize(width: 98, height: -76)),
        Module(route: .integrations, title: "Integrations", symbol: "powerplug", offset: CGSize(width: 120, height: 30)),
        Module(route: .health, title: "System health", symbol: "waveform.path.ecg", offset: CGSize(width: 62, height: 107)),
        Module(route: .context, title: "Context", symbol: "folder", offset: CGSize(width: -62, height: 107)),
        Module(route: .tasks, title: "Tasks", symbol: "checkmark.circle", offset: CGSize(width: -120, height: 30)),
        Module(route: .agents, title: "Agents", symbol: "person.2", offset: CGSize(width: -98, height: -76)),
    ]

    private var online: Bool? {
        switch model.connectionState {
        case .reachable: ready
        case .unreachable: false
        case .checking, .unconfigured: nil
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                commandBar
                orbCluster
                pill
                updateBanner
                VStack(alignment: .leading, spacing: 10) {
                    Eyebrow(text: "Your system")
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                        ForEach(Self.modules.filter { $0.route != .agents }, id: \.title) { module in
                            card(module)
                        }
                    }
                    if let agents = Self.modules.first(where: { $0.route == .agents }) { card(agents) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(JV.bgPage)
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await load() }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            await model.checkForAppUpdate()
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Jarvis")
                    .font(JV.font(28, .semibold, relativeTo: .largeTitle))
                    .foregroundStyle(JV.textPrimary)
                Text("Your second mind")
                    .font(JV.font(13, relativeTo: .subheadline))
                    .foregroundStyle(JV.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Spacer()
            let status = statusPill(mood: model.mood, online: online)
            StatusLabel(tone: status.tone, text: online == true ? "Core Online" : status.label)
            ProfileButton()
        }
        .padding(.top, 8)
    }

    private var commandBar: some View {
        HStack(spacing: 0) {
            NavigationLink(value: HubRoute.chat) {
                HStack(spacing: 14) {
                    Image(systemName: "waveform")
                        .font(.system(size: 18))
                        .foregroundStyle(JV.accentText)
                        .accessibilityHidden(true)
                    Text("Ask Jarvis anything…")
                        .font(JV.font(15))
                        .foregroundStyle(JV.textSecondary)
                    Spacer(minLength: 0)
                }
                .padding(.leading, 16)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
            }
            .accessibilityLabel("Ask Jarvis anything")
            NavigationLink(value: HubRoute.voice) {
                Image(systemName: "mic")
                    .font(.system(size: 17))
                    .foregroundStyle(JV.accentText)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Voice")
        }
        .buttonStyle(.plain)
        .frame(minHeight: 48)
        .jvCard(padding: 0)
    }

    private var orbCluster: some View {
        ZStack {
            Circle().stroke(JV.border, lineWidth: 1)
                .frame(width: 248, height: 248)
            Circle().stroke(JV.borderSubtle, style: StrokeStyle(lineWidth: 1, dash: [1, 6]))
                .frame(width: 316, height: 316)
            JarvisOrb(size: 182, mood: model.mood)
            ForEach(Self.modules, id: \.title) { module in
                NavigationLink(value: module.route) {
                    Image(systemName: module.symbol)
                        .font(.system(size: 18))
                        .foregroundStyle(JV.accentText)
                        .frame(width: 46, height: 46)
                        .background(JV.bgSurface, in: Circle())
                        .overlay(Circle().strokeBorder(JV.accentBorder, lineWidth: 1))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(module.title)
                .offset(module.offset)
            }
        }
        .frame(height: 340)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private var updateBanner: some View {
        if let update = model.iosUpdate {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Jarvis \(update.version) is available")
                        .font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary)
                    Spacer()
                    Button { model.dismissAppUpdate() } label: {
                        Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44)
                    }
                    .foregroundStyle(JV.textSecondary)
                    .accessibilityLabel("Dismiss update notice")
                }
                // One row when it fits, stacked otherwise (narrow width, large text).
                ViewThatFits {
                    HStack(spacing: 8) { updateLinks(update) }
                    VStack(alignment: .leading, spacing: 8) { updateLinks(update) }
                }
                .buttonStyle(JVButtonStyle(kind: .secondary, size: .small))
            }
            .jvCard(padding: 12)
        }
    }

    @ViewBuilder private func updateLinks(_ update: IOSUpdateNotice) -> some View {
        Button("Open in AltStore") { openURL(update.altstoreURL) }
        Button("Open in SideStore") { openURL(update.sidestoreURL) }
        Button("Downloads page") { openURL(update.downloadsURL) }
    }

    private var pill: some View {
        let status = statusPill(mood: model.mood, online: online)
        return HStack(spacing: 14) {
            StatusDot(tone: status.tone)
            Text(status.label)
                .font(JV.font(14, .medium))
                .foregroundStyle(status.tone.color)
            Rectangle().fill(JV.separator).frame(width: 1, height: 16)
            Text(status.hint).font(JV.font(13)).foregroundStyle(JV.textSecondary)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 44)
        .background(JV.bgSurface, in: Capsule())
        .overlay(Capsule().strokeBorder(JV.shadowRing, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private func card(_ module: Module) -> some View {
        NavigationLink(value: module.route) {
            ModuleCard(title: module.title, symbol: module.symbol, summary: summary(for: module.route))
        }
        .buttonStyle(.plain)
    }

    private func summary(for route: HubRoute) -> CardSummary {
        switch route {
        case .conversations: conversationsCard(model.conversations.map(\.updatedAt), now: now)
        case .memory: CardSummary(line1: "Not yet available", line2: "", tone: .idle)
        case .integrations: integrationsCard(registry.map(\.software))
        case .health: healthCard(online: online, registry: registry)
        case .context: contextCard(devices)
        case .tasks: tasksCard(sessions: sessions, pending: pending)
        case .agents: agentsCard(agentCount)
        default: CardSummary(line1: "", line2: "", tone: .idle)
        }
    }

    private func load() async {
        async let devices = model.coreRead("/v1/devices", as: DeviceListResponse.self)
        async let pending = model.coreRead("/v1/agent/pending", as: PendingActionList.self)
        async let sessions = model.coreRead("/v1/coding/sessions", as: CodingSessionList.self)
        async let registry = model.coreRead("/v1/system/registry", as: HomeNodeRegistry.self)
        async let agents = model.coreRead("/v1/agents", as: AgentsResponse.self)
        async let probe = model.probe("/readyz")
        let results = (await devices, await pending, await sessions, await registry, await agents)
        let readiness = await probe
        // A cancelled load (page pushed, app inactive) keeps the last values.
        guard !Task.isCancelled else { return }
        self.devices = results.0.map(\.devices)
        self.pending = results.1.map(\.pending)
        self.sessions = results.2.map(\.sessions)
        self.registry = results.3
        self.agentCount = results.4.map { $0.agent_count ?? $0.agents.count }
        ready = readiness != nil
        now = Date()
    }
}
