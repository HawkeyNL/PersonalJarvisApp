import Combine
import SwiftUI

struct RootView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var path: [HubRoute] = []
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
                NavigationStack(path: $path) {
                    Group {
                        if screen == .hub {
                            HubView(model: model)
                        } else {
                            // Connecting, enrolling or signed out: Settings stays
                            // reachable to configure the Home Node address.
                            EnrollmentView(model: model)
                                .navigationTitle("Jarvis")
                                .navigationBarTitleDisplayMode(.inline)
                                .toolbarBackground(JV.bgPage, for: .navigationBar)
                                .toolbarBackground(.visible, for: .navigationBar)
                                .toolbar { ToolbarItem(placement: .topBarTrailing) { ProfileButton() } }
                        }
                    }
                    .navigationDestination(for: HubRoute.self) { route in destination(route) }
                }
                // Polls only while unlocked and signed in: this branch is gone once locked.
                .safeAreaInset(edge: .top, spacing: 0) {
                    if model.isAuthenticated { UnlockRequestsBanner(model: model) }
                }
                // Owner pages must not stay mounted after logout, reset or a 401.
                .onChange(of: model.isAuthenticated) { _, authenticated in
                    if authenticated { openPendingLink() } else { path.removeAll() }
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
        path = [route]
    }

    @ViewBuilder private func destination(_ route: HubRoute) -> some View {
        switch route {
        case .chat: ChatView(model: model)
        case .voice: VoicePage()
        case .settings: SettingsView(model: model)
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

    private func navigate(_ route: HubRoute) { path.append(route) }
}
