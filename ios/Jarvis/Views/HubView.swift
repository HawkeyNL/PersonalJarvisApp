import SwiftUI

/// Every screen reachable from the Core hub.
enum HubRoute: Hashable {
    case chat, voice, settings, models
    case conversations, agents, tasks, integrations, health, memory, context
}

/// Core hub: the orb with its seven modules and a two-column card grid.
/// Every number comes from Core; a route an older Core lacks says so.
struct HubView: View {
    @Environment(\.scenePhase) private var scenePhase
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
        Module(route: .health, title: "System Health", symbol: "waveform.path.ecg", offset: CGSize(width: 62, height: 107)),
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
        .background { JarvisBackdrop() }
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await load() }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Circle()
                .strokeBorder(JarvisTheme.accent, lineWidth: 3)
                .frame(width: 28, height: 28)
                .shadow(color: JarvisTheme.accent.opacity(0.7), radius: 6)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text("JARVIS")
                    .font(JarvisFont.display(18, semibold: false))
                    .tracking(7.5)
                    .foregroundStyle(Color(hex: 0xEAF7F1))
                Text("YOUR SECOND MIND")
                    .font(JarvisFont.body(9))
                    .tracking(3)
                    .foregroundStyle(JarvisTheme.text6)
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
                        .foregroundStyle(JarvisTheme.accent)
                        .accessibilityHidden(true)
                    Text("Ask Jarvis anything…")
                        .font(JarvisFont.body(13))
                        .tracking(1.8)
                        .foregroundStyle(JarvisTheme.text6)
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
                    .foregroundStyle(JarvisTheme.accent)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Voice")
        }
        .buttonStyle(.plain)
        .frame(height: 48)
        .background(Color(red: 8 / 255.0, green: 26 / 255.0, blue: 22 / 255.0).opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(red: 160 / 255.0, green: 1, blue: 215 / 255.0).opacity(0.16), lineWidth: 1))
    }

    private var orbCluster: some View {
        ZStack {
            Circle().stroke(Color(red: 150 / 255.0, green: 1, blue: 210 / 255.0).opacity(0.5), lineWidth: 1.3)
                .frame(width: 248, height: 248)
            Circle().stroke(JarvisTheme.accent.opacity(0.18), lineWidth: 1)
                .frame(width: 268, height: 268)
            Circle().stroke(JarvisTheme.accent.opacity(0.2), style: StrokeStyle(lineWidth: 1, dash: [1, 6]))
                .frame(width: 316, height: 316)
            JarvisOrb(size: 182, mood: model.mood)
            ForEach(Self.modules, id: \.title) { module in
                NavigationLink(value: module.route) {
                    Image(systemName: module.symbol)
                        .font(.system(size: 18))
                        .foregroundStyle(JarvisTheme.accent)
                        .frame(width: 46, height: 46)
                        .background(Color(red: 3 / 255.0, green: 24 / 255.0, blue: 19 / 255.0).opacity(0.95), in: Circle())
                        .overlay(Circle().stroke(JarvisTheme.accent.opacity(0.6), lineWidth: 1.5))
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

    private var pill: some View {
        let status = statusPill(mood: model.mood, online: online)
        return HStack(spacing: 14) {
            StatusDot(tone: status.tone)
                .frame(width: 26, height: 26)
                .overlay(Circle().stroke(status.tone.color.opacity(0.6), lineWidth: 1.5))
            Text(status.label)
                .font(JarvisFont.body(13, 600))
                .tracking(1)
                .foregroundStyle(status.tone.labelColor)
            Rectangle().fill(Color(red: 160 / 255.0, green: 1, blue: 215 / 255.0).opacity(0.3)).frame(width: 1, height: 16)
            Text(status.hint).font(JarvisFont.body(11)).foregroundStyle(JarvisTheme.text5)
        }
        .padding(.leading, 10)
        .padding(.trailing, 18)
        .frame(height: 44)
        .background(Color(red: 6 / 255.0, green: 22 / 255.0, blue: 18 / 255.0).opacity(0.75), in: Capsule())
        .overlay(Capsule().stroke(Color(red: 160 / 255.0, green: 1, blue: 215 / 255.0).opacity(0.16), lineWidth: 1))
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
