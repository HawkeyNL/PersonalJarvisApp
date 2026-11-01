import SwiftUI

@main
struct JarvisApp: App {
    @UIApplicationDelegateAdaptor(JarvisAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = JarvisAppModel()
    @AppStorage(Appearance.storageKey) private var appearance = Appearance.system

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .overlay {
                    if scenePhase != .active {
                        JV.bgPage.ignoresSafeArea().accessibilityLabel("Jarvis locked")
                    }
                }
                .tint(JV.accentText)
                .onChange(of: appearance, initial: true) { _, value in Appearance.apply(value) }
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

extension View {
    /// The privacy cover for a sheet: sheets present above the root overlay,
    /// so each one covers itself the same way while the scene is not active.
    func privacyCover() -> some View { modifier(PrivacyCover()) }
}

struct PrivacyCover: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.overlay {
            if scenePhase != .active {
                JV.bgPage.ignoresSafeArea().accessibilityLabel("Jarvis locked")
            }
        }
    }
}
