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

// Shared desktop/Core Admin palette: #050a08, #0a1410, #34f5a0.
enum JarvisTheme {
    static let background = Color(red: 5 / 255.0, green: 10 / 255.0, blue: 8 / 255.0)
    static let panel = Color(red: 10 / 255.0, green: 20 / 255.0, blue: 16 / 255.0)
    static let accent = Color("AccentColor")
}
