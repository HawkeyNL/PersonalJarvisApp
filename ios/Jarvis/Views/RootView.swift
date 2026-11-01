import Combine
import SwiftUI

struct RootView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var tab = AppTab.jarvis
    /// Pushed pages per tab; kept while locked, cleared on sign-out.
    @State private var paths: [AppTab: [HubRoute]] = [:]
    @State private var enrollmentPath: [HubRoute] = []
    /// Deep link waiting for unlock and sign-in.
    @State private var pendingLink: HubRoute?

    private var screen: RootScreen {
        rootScreen(lock: model.lockState, connection: model.connectionState, authenticated: model.isAuthenticated)
    }

    var body: some View {
        Group {
            if screen == .lock {
                LockView(model: model)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(JV.bgPage)
                    .ignoresSafeArea()
            } else {
                Group {
                    if screen == .hub {
                        MainTabs(model: model, tab: $tab, paths: $paths)
                    } else {
                        // Connecting, enrolling or signed out: only Settings is reachable,
                        // to configure the Home Node address.
                        NavigationStack(path: $enrollmentPath) {
                            EnrollmentView(model: model)
                                .navigationTitle("Jarvis")
                                .navigationBarTitleDisplayMode(.inline)
                                .toolbarBackground(JV.bgPage, for: .navigationBar)
                                .toolbarBackground(.visible, for: .navigationBar)
                                .toolbar { ToolbarItem(placement: .topBarTrailing) { ProfileButton() } }
                                .navigationDestination(for: HubRoute.self) { route in
                                    if route == .settings { SettingsView(model: model) }
                                }
                        }
                    }
                }
                .onChange(of: pendingLink, initial: true) { _, _ in openPendingLink() }
            }
        }
        // Owner pages must not stay mounted after logout, reset or a 401, also
        // when that happens while locked.
        .onChange(of: model.isAuthenticated) { _, authenticated in
            if authenticated {
                openPendingLink()
            } else {
                paths = [:]
                enrollmentPath = []
                tab = .jarvis
            }
        }
        .onOpenURL { url in pendingLink = deepLinkRoute(url) }
        // A tapped approval push uses the same router; the link was checked by the app delegate.
        .onReceive(tappedPushLink.compactMap { $0 }) { url in
            tappedPushLink.send(nil)
            pendingLink = deepLinkRoute(url)
        }
    }

    /// Links only navigate, and only once the app is unlocked and signed in.
    private func openPendingLink() {
        guard model.lockState == .unlocked, model.isAuthenticated, let route = pendingLink else { return }
        pendingLink = nil
        let destination = tabDestination(route)
        tab = destination.tab
        paths[destination.tab] = destination.path
    }
}

/// The one sheet the shell shows at a time. SwiftUI cannot present two, so
/// agent approvals and unlock requests share this presenter.
enum ShellSheet: Identifiable {
    case unlock(UnlockRequestItem)
    case agent(PendingAction, decided: () async -> Void)

    var id: String {
        switch self {
        case let .unlock(item): "unlock-\(item.id)"
        case let .agent(item, _): "agent-\(item.pending_id)"
        }
    }
}

private struct OpenAgentApprovalKey: EnvironmentKey {
    static let defaultValue: (PendingAction, @escaping () async -> Void) -> Void = { _, _ in }
}

extension EnvironmentValues {
    /// Opens the agent approval sheet; `decided` runs after a decision.
    var openAgentApproval: (PendingAction, @escaping () async -> Void) -> Void {
        get { self[OpenAgentApprovalKey.self] }
        set { self[OpenAgentApprovalKey.self] = newValue }
    }
}

/// The signed-in app: four tabs, each with its own navigation stack. Unlock
/// requests are polled here (foreground only) and open as a sheet over any tab.
struct MainTabs: View {
    @ObservedObject var model: JarvisAppModel
    @Binding var tab: AppTab
    @Binding var paths: [AppTab: [HubRoute]]
    @Environment(\.scenePhase) private var scenePhase
    @State private var unlocks: [UnlockRequestItem] = []
    @State private var sheet: ShellSheet?
    /// Requests already offered as a sheet; a dismissed one stays on Activiteit.
    @State private var offered: Set<UUID> = []

    var body: some View {
        TabView(selection: $tab) {
            ForEach(AppTab.allCases, id: \.self) { item in
                NavigationStack(path: path(item)) {
                    root(item)
                        .navigationDestination(for: HubRoute.self) { route in destination(route, in: item) }
                }
                .toolbarBackground(JV.bgSurface, for: .tabBar)
                .toolbarBackground(.visible, for: .tabBar)
                .tabItem { Label(item.title, systemImage: item.symbol) }
                .tag(item)
            }
        }
        .environment(\.openAgentApproval, openAgentApproval)
        .sheet(item: $sheet, onDismiss: offerUnlock) { current in
            switch current {
            case let .unlock(item):
                UnlockSheet(model: model, item: item) { id in unlocks.removeAll { $0.id == id } }
            case let .agent(item, decided):
                AgentApprovalSheet(model: model, item: item, time: relativeTime(item.created_at, now: Date()), decided: decided)
            }
        }
        .onChange(of: unlocks) { _, _ in offerUnlock() }
        .task(id: scenePhase == .active) {
            guard scenePhase == .active else { unlocks = []; return }
            while !Task.isCancelled {
                let result = await model.pendingUnlocks()
                guard !Task.isCancelled else { break }
                unlocks = result ?? []
                // An answer with requests or an error returns at once: never spin.
                try? await Task.sleep(for: .seconds(result == nil || !unlocks.isEmpty ? 10 : 2))
            }
        }
    }

    private func openAgentApproval(_ item: PendingAction, decided: @escaping () async -> Void) {
        if sheet == nil { sheet = .agent(item, decided: decided) }
    }

    /// Shows the next request not offered yet, only while no other sheet is up.
    private func offerUnlock() {
        guard sheet == nil, let next = unlocks.first(where: { !offered.contains($0.id) }) else { return }
        offered.insert(next.id)
        sheet = .unlock(next)
    }

    private func path(_ item: AppTab) -> Binding<[HubRoute]> {
        Binding(get: { paths[item] ?? [] }, set: { paths[item] = $0 })
    }

    @ViewBuilder private func root(_ item: AppTab) -> some View {
        switch item {
        case .jarvis: HomeView(model: model, unlocks: unlocks) { tab = .activity }
        case .trading: TradingPage(model: model)
        case .activity:
            ActivityView(model: model, unlocks: unlocks) { item in
                guard sheet == nil else { return }
                offered.insert(item.id)
                sheet = .unlock(item)
            }
        case .settings: SettingsView(model: model, inTabs: true)
        }
    }

    @ViewBuilder private func destination(_ route: HubRoute, in item: AppTab) -> some View {
        let navigate: (HubRoute) -> Void = { paths[item, default: []].append($0) }
        switch route {
        case .chat: ChatView(model: model)
        case .voice: VoicePage()
        case .settings: SettingsView(model: model, inTabs: true)
        case .models: ModelsView(model: model)
        case .conversations: ConversationsPage(model: model, navigate: navigate)
        case .agents: AgentsPage(model: model)
        case .tasks: TasksPage(model: model)
        case .integrations: IntegrationsPage(model: model, navigate: navigate)
        case .health: HealthPage(model: model, navigate: navigate)
        case .healthUsage: HealthPage(model: model, navigate: navigate, node: "usage")
        case .memory: MemoryPage(model: model)
        case .context: ContextPage(model: model)
        case .pairingApprovals: PairingApprovalsPage(model: model)
        }
    }
}
