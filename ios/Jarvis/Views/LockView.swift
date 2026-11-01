import SwiftUI

struct LockView: View {
    @ObservedObject var model: JarvisAppModel

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 54))
                .foregroundStyle(JV.accentText)
                .accessibilityHidden(true)
            Text("Jarvis is locked")
                .font(JV.font(22, .semibold, relativeTo: .title2))
                .foregroundStyle(JV.textPrimary)
                .accessibilityAddTraits(.isHeader)
            switch model.lockState {
            case let .unavailable(message):
                Text(message).multilineTextAlignment(.center).foregroundStyle(JV.textSecondary)
            case .denied:
                Text("Authentication was not accepted.").foregroundStyle(JV.textSecondary)
            default:
                Text("Authenticate on this device to continue.").foregroundStyle(JV.textSecondary)
            }
            Button("Unlock") { Task { await model.unlock() } }
                .disabled(model.isUnlocking)
                .buttonStyle(JVButtonStyle(size: .large))
            if model.isUnlocking { ProgressView("Unlocking…") }
        }
        .padding(32)
    }
}
