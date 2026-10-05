import SwiftUI

@main
struct JarvisApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = JarvisAppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .overlay {
                    if scenePhase != .active {
                        JarvisTheme.background.ignoresSafeArea().accessibilityLabel("Jarvis locked")
                    }
                }
                .tint(JarvisTheme.accent)
                .preferredColorScheme(.dark)
                .task { await model.start() }
        }
        .onChange(of: scenePhase) { _, phase in
            // LocalAuthentication itself temporarily makes the scene inactive.
            // Only a real background transition invalidates an unlock attempt.
            switch lockTransition(for: phase) {
            case .lock: model.lockWhenBackgrounded()
            case .unlock: Task { await model.unlockOnForeground() }
            case .privacyOnly: break
            }
        }
    }
}

enum LockTransition: Equatable { case lock, unlock, privacyOnly }

func lockTransition(for phase: ScenePhase) -> LockTransition {
    switch phase {
    case .background: .lock
    case .active: .unlock
    case .inactive: .privacyOnly
    @unknown default: .lock
    }
}
