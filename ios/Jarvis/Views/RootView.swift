import SwiftUI

struct RootView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var path: [HubRoute] = []

    var body: some View {
        Group {
            if model.lockState != .unlocked {
                LockView(model: model)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(JarvisTheme.background)
                    .ignoresSafeArea()
            } else {
                NavigationStack(path: $path) {
                    Group {
                        if model.connectionState == .reachable && model.isAuthenticated {
                            HubView(model: model)
                        } else {
                            // Connecting, enrolling or signed out: Settings stays
                            // reachable to configure the Home Node address.
                            EnrollmentView(model: model)
                                .navigationTitle("Jarvis")
                                .navigationBarTitleDisplayMode(.inline)
                                .toolbarBackground(JarvisTheme.background, for: .navigationBar)
                                .toolbarBackground(.visible, for: .navigationBar)
                                .toolbar { ToolbarItem(placement: .topBarTrailing) { ProfileButton() } }
                        }
                    }
                    .navigationDestination(for: HubRoute.self) { route in destination(route) }
                }
                // Owner pages must not stay mounted after logout, reset or a 401.
                .onChange(of: model.isAuthenticated) { _, authenticated in
                    if !authenticated { path.removeAll() }
                }
            }
        }
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
        case .memory: MemoryPage(model: model)
        case .context: ContextPage(model: model)
        }
    }

    private func navigate(_ route: HubRoute) { path.append(route) }
}
