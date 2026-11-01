import SwiftUI

/// Every screen reachable from the Core hub.
enum HubRoute: Hashable, CaseIterable {
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

extension HubRoute {
    var title: String {
        switch self {
        case .chat: "Chat"
        case .voice: "Voice"
        case .settings: "Settings"
        case .models: "Models"
        case .conversations: "Conversations"
        case .agents: "Agents"
        case .tasks: "Tasks"
        case .integrations: "Integrations"
        case .health: "System health"
        case .healthUsage: "Usage"
        case .memory: "Memory"
        case .context: "Devices and context"
        case .pairingApprovals: "Pairing requests"
        }
    }

    var symbol: String {
        switch self {
        case .chat: "bubble.left"
        case .voice: "mic"
        case .settings: "gearshape"
        case .models: "square.stack.3d.up"
        case .conversations: "bubble.left.and.bubble.right"
        case .agents: "person.2"
        case .tasks: "checkmark.circle"
        case .integrations: "powerplug"
        case .health: "waveform.path.ecg"
        case .healthUsage: "eurosign.circle"
        case .memory: "brain"
        case .context: "desktopcomputer"
        case .pairingApprovals: "person.badge.key"
        }
    }
}

/// Jarvis tab (I-Home): what waits on you, the update notice and the Jarvis
/// pages, with the ask bar at the bottom. Every number comes from Core.
struct HomeView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var model: JarvisAppModel
    let unlocks: [UnlockRequestItem]
    let openActivity: () -> Void
    @State private var pending: Availability<[PendingAction]> = .loading
    @State private var now = Date()
    /// Result of the last /readyz probe; nil until the first one returns.
    @State private var ready: Bool?

    private var online: Bool? {
        switch model.connectionState {
        case .reachable: ready
        case .unreachable: false
        case .checking, .unconfigured: nil
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                waitingCard
                UpdateNotice(model: model)
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Jarvis")
                    // Chat and Voice open from the ask bar.
                    RouteList(routes: AppTab.jarvis.links.filter { $0 != .chat && $0 != .voice }) { route in
                        switch route {
                        case .conversations: return conversationsCard(model.conversations.map(\.updatedAt), now: now).line1
                        case .memory: return "Not yet available"
                        default: return nil
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(JV.bgPage)
        .safeAreaInset(edge: .bottom, spacing: 0) { askBar }
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
        let status = statusPill(mood: model.mood, online: online)
        return VStack(alignment: .leading, spacing: 2) {
            Text(now.formatted(.dateTime.weekday(.wide).day().month(.wide)))
                .font(JV.font(13, relativeTo: .subheadline))
                .foregroundStyle(JV.textSecondary)
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text("Jarvis")
                    .font(JV.font(30, .semibold, relativeTo: .largeTitle))
                    .foregroundStyle(JV.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                StatusLabel(tone: status.tone, text: online == true ? "Core online" : status.label)
            }
        }
        .padding(.horizontal, 4)
    }

    /// Unlock requests and agent approvals; pairing requests are read on
    /// Activity only (they share Core's pairing rate budget).
    @ViewBuilder private var waitingCard: some View {
        let actions = pending.value ?? []
        let lines = unlocks.map { "\"\(PairingFingerprint.displayName($0.device_name, max: 40))\" asks to unlock Jarvis" }
            + actions.map(pendingTitle)
        if !lines.isEmpty {
            Button(action: openActivity) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 10) {
                        IconBadge(symbol: "checkmark.shield", size: 30)
                        Text("Waiting on you").font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary)
                        Spacer(minLength: 0)
                        Text(verbatim: "\(lines.count)")
                            .font(JV.number(13, .semibold))
                            .foregroundStyle(JV.accentText)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(JV.accentSubtle, in: Capsule())
                    }
                    ForEach(Array(lines.prefix(3).enumerated()), id: \.offset) { _, line in
                        Text(verbatim: line)
                            .font(JV.font(13))
                            .foregroundStyle(JV.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .jvCard(padding: 16, border: JV.accentBorder)
                .contentShape(RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Opens Activity")
        }
    }

    private var askBar: some View {
        HStack(spacing: 8) {
            NavigationLink(value: HubRoute.chat) {
                Text("Ask Jarvis anything…")
                    .font(JV.font(16))
                    .foregroundStyle(JV.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .padding(.leading, 14)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Ask Jarvis anything")
            NavigationLink(value: HubRoute.voice) {
                Image(systemName: "mic")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(JV.textOnAccent)
                    .frame(width: 44, height: 44)
                    .background(JV.accentSolid, in: Circle())
                    .contentShape(Circle())
            }
            .accessibilityLabel("Voice")
        }
        .buttonStyle(.plain)
        .padding(4)
        .background(JV.bgSunken, in: Capsule())
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(JV.bgSurface)
        .overlay(alignment: .top) { Rectangle().fill(JV.separator).frame(height: 1) }
    }

    private func load() async {
        async let pending = model.coreRead("/v1/agent/pending", as: PendingActionList.self)
        async let probe = model.probe("/readyz")
        let result = await pending
        let readiness = await probe
        // A cancelled load (page pushed, tab left, app inactive) keeps the last values.
        guard !Task.isCancelled else { return }
        self.pending = result.map(\.pending)
        ready = readiness != nil
        now = Date()
    }
}

/// New iOS version notice; informational only, the app never installs.
struct UpdateNotice: View {
    @Environment(\.openURL) private var openURL
    @ObservedObject var model: JarvisAppModel

    var body: some View {
        if let update = model.iosUpdate {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Jarvis \(update.version) is available")
                        .font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary)
                    Spacer()
                    Button { model.dismissAppUpdate() } label: {
                        Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                    }
                    .foregroundStyle(JV.textSecondary)
                    .accessibilityLabel("Dismiss update notice")
                }
                // One row when it fits, stacked otherwise (narrow width, large text).
                ViewThatFits {
                    HStack(spacing: 8) { links(update) }
                    VStack(alignment: .leading, spacing: 8) { links(update) }
                }
                // Large: 48 pt targets (regular is 40 pt, under the 44 pt minimum).
                .buttonStyle(JVButtonStyle(kind: .secondary, size: .large))
            }
            .jvCard(padding: 12)
        }
    }

    @ViewBuilder private func links(_ update: IOSUpdateNotice) -> some View {
        Button("Open in AltStore") { openURL(update.altstoreURL) }
        Button("Open in SideStore") { openURL(update.sidestoreURL) }
        Button("Downloads page") { openURL(update.downloadsURL) }
    }
}
