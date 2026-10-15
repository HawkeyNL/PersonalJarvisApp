import Combine
import Foundation
import SwiftUI
import UIKit
import UserNotifications

extension Notification.Name {
    /// An approval push arrived while Jarvis was in the foreground: fetch pending approvals now.
    static let jarvisApprovalPushed = Notification.Name("jarvis.approvalPushed")
    /// APNs answered `registerForRemoteNotifications`; `object` is the hex token, or nil on failure.
    static let jarvisPushToken = Notification.Name("jarvis.pushToken")
}

/// The APNs environment this installation was signed for. AltStore and SideStore
/// sign with the owner's development profile, whose `aps-environment` decides it;
/// without that entitlement (simulator, unsigned or not re-signed) there is none.
enum PushEnvironment {
    static func current(bundle: Bundle = .main) -> String? {
        guard let url = bundle.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        return fromProfile(data)
    }

    /// The profile is a signed CMS envelope around a plain XML property list.
    static func fromProfile(_ data: Data) -> String? {
        guard data.count <= 1_000_000,
              let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(from: data[start.lowerBound..<end.upperBound], format: nil) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any] else { return nil }
        switch entitlements["aps-environment"] as? String {
        case "development": return "sandbox"
        case "production": return "production"
        default: return nil
        }
    }
}

/// An APNs device token as Core accepts it: lowercase hex, 64-200 characters.
func apnsTokenHex(_ token: Data) -> String? {
    let hex = token.map { String(format: "%02x", $0) }.joined()
    return (64...200).contains(hex.count) ? hex : nil
}

/// Deep link of the last tapped approval push. A subject keeps it for RootView
/// when the tap launched the app before any view existed.
@MainActor let tappedPushLink = CurrentValueSubject<URL?, Never>(nil)

/// The deep link a tapped push may open: only the approval routes, never an action.
func approvalPushLink(_ userInfo: [AnyHashable: Any]) -> URL? {
    guard let link = userInfo["link"] as? String,
          link == "jarvis://approvals/unlock" || link == "jarvis://approvals/pairing" else { return nil }
    return URL(string: link)
}

/// Receives APNs callbacks. Foreground pushes show no banner; they only trigger
/// an immediate fetch. Notifications have no actions and there is no silent push.
final class JarvisAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        NotificationCenter.default.post(name: .jarvisPushToken, object: apnsTokenHex(deviceToken))
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NotificationCenter.default.post(name: .jarvisPushToken, object: nil)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        await MainActor.run { NotificationCenter.default.post(name: .jarvisApprovalPushed, object: nil) }
        return []
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let link = approvalPushLink(response.notification.request.content.userInfo)
        await MainActor.run {
            if let link { tappedPushLink.send(link) }
            NotificationCenter.default.post(name: .jarvisApprovalPushed, object: nil)
        }
    }
}

/// The "Approval notifications" setting. Asks for permission only when the owner
/// turns it on, then registers this device's APNs token with Core; the push itself
/// carries no details.
@MainActor
final class ApprovalPush: ObservableObject {
    private static let enabledKey = "jarvis.push.approvals"
    @Published private(set) var enabled = UserDefaults.standard.bool(forKey: ApprovalPush.enabledKey)
    @Published private(set) var status: String?
    let environment = PushEnvironment.current()
    weak var owner: JarvisAppModel?
    private var token: String?
    /// Token Core last accepted in this session; avoids a PUT on every unlock.
    private var registered: String?
    private var observer: NSObjectProtocol?

    init() {
        observer = NotificationCenter.default.addObserver(forName: .jarvisPushToken, object: nil, queue: .main) { [weak self] note in
            let token = note.object as? String
            MainActor.assumeIsolated { self?.received(token) }
        }
    }

    func setEnabled(_ on: Bool) async {
        status = nil
        guard on else {
            enabled = false
            UserDefaults.standard.set(false, forKey: Self.enabledKey)
            await removeTarget()
            return
        }
        guard environment != nil else {
            status = "This installation has no push entitlement. Enable Push Notifications for its app ID, then refresh it in AltStore or SideStore."
            return
        }
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
        guard granted else {
            status = "Notifications are off for Jarvis. Allow them in iOS Settings."
            return
        }
        enabled = true
        UserDefaults.standard.set(true, forKey: Self.enabledKey)
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// After sign-in: ask APNs for the current token; it goes to Core when it arrives.
    func signedIn() {
        guard enabled, environment != nil else { return }
        UIApplication.shared.registerForRemoteNotifications()
    }

    /// Before logout or reset, while the session still works. Best effort and
    /// capped at a few seconds so an unreachable Home Node never delays it;
    /// revoking the device on the Home Node also deletes its token. A reset
    /// turns the setting off: a new identity must opt in again.
    func signingOut(reset: Bool) async {
        guard enabled else { return }
        if reset {
            enabled = false
            UserDefaults.standard.removeObject(forKey: Self.enabledKey)
        }
        await removeTarget()
    }

    private func removeTarget() async {
        token = nil
        registered = nil
        guard let owner, owner.isAuthenticated else { return }
        let delete = Task { try? await owner.setPushTarget(nil) }
        let deadline = Task {
            try? await Task.sleep(for: .seconds(3))
            delete.cancel()
        }
        _ = await delete.value
        deadline.cancel()
    }

    private func received(_ token: String?) {
        guard enabled else { return }
        guard let token else {
            status = "Jarvis could not register for notifications."
            return
        }
        self.token = token
        Task { await upload() }
    }

    private func upload() async {
        guard enabled, let token, token != registered, let environment, let owner, owner.isAuthenticated else { return }
        do {
            try await owner.setPushTarget((token, environment))
            // Turned off while the PUT was in flight: take it back.
            guard enabled else { await removeTarget(); return }
            registered = token
            status = nil
        } catch {
            status = (error as? LocalizedError)?.errorDescription ?? "Your Home Node did not accept this device for notifications."
        }
    }
}

struct ApprovalNotificationsView: View {
    @ObservedObject var push: ApprovalPush
    @State private var busy = false

    var body: some View {
        Form {
            Section {
                Toggle("Approval notifications", isOn: Binding(get: { push.enabled }, set: { on in
                    busy = true
                    Task { await push.setEnabled(on); busy = false }
                }))
                .disabled(busy)
            } footer: {
                Text("Your Home Node tells this iPhone when an approval is waiting. The notification only says \"Approval waiting\"; Jarvis loads the request after you open it, and approving still needs Face ID or Touch ID.")
            }
            if let status = push.status { Section("Status") { Text(verbatim: status) } }
        }
        .navigationTitle("Notifications")
        .scrollContentBackground(.hidden)
        .background(JarvisTheme.background)
        .toolbarBackground(JarvisTheme.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
    }
}
