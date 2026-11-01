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
                // Owner pages must not stay mounted after logout, reset or a 401.
                .onChange(of: model.isAuthenticated) { _, authenticated in
                    if authenticated {
                        openPendingLink()
                    } else {
                        paths = [:]
                        tab = .jarvis
                    }
                }
                .onChange(of: pendingLink, initial: true) { _, _ in openPendingLink() }
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

/// The signed-in app: four tabs, each with its own navigation stack. Unlock
/// requests are polled here (foreground only) and open as a sheet over any tab.
struct MainTabs: View {
    @ObservedObject var model: JarvisAppModel
    @Binding var tab: AppTab
    @Binding var paths: [AppTab: [HubRoute]]
    @Environment(\.scenePhase) private var scenePhase
    @State private var unlocks: [UnlockRequestItem] = []
    @State private var unlockSheet: UnlockRequestItem?
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
        .sheet(item: $unlockSheet) { item in
            UnlockSheet(model: model, item: item) { id in unlocks.removeAll { $0.id == id } }
        }
        .onChange(of: unlocks) { _, list in
            guard unlockSheet == nil, let next = list.first(where: { !offered.contains($0.id) }) else { return }
            offered.insert(next.id)
            unlockSheet = next
        }
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

    private func path(_ item: AppTab) -> Binding<[HubRoute]> {
        Binding(get: { paths[item] ?? [] }, set: { paths[item] = $0 })
    }

    @ViewBuilder private func root(_ item: AppTab) -> some View {
        switch item {
        case .jarvis: HomeView(model: model, unlocks: unlocks) { tab = .activity }
        case .trading: TradingPage(model: model)
        case .activity: ActivityView(model: model, unlocks: unlocks) { unlockSheet = $0 }
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
