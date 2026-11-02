import SwiftUI

/// App lock (Face ID or Touch ID): a quiet centred column on the page, no
/// app content behind it.
struct LockView: View {
    @ObservedObject var model: JarvisAppModel

    var body: some View {
        VStack(spacing: JV.Space.md) {
            IconBadge(symbol: "lock", size: 48)
            VStack(spacing: 6) {
                Text("Jarvis is locked")
                    .font(JV.font(22, .semibold, relativeTo: .title2))
                    .foregroundStyle(JV.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text(message)
                    .font(JV.font(15))
                    .foregroundStyle(JV.textSecondary)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            Button("Unlock") { Task { await model.unlock() } }
                .disabled(model.isUnlocking)
                .buttonStyle(JVButtonStyle(size: .large))
                .padding(.top, JV.Space.xs)
            if model.isUnlocking {
                HStack(spacing: JV.Space.xs) {
                    ProgressView().tint(JV.textSecondary)
                    Text("Unlocking…").font(JV.font(13, relativeTo: .footnote)).foregroundStyle(JV.textSecondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .frame(maxWidth: 400)
        .padding(JV.Space.xxl)
    }

    private var message: String {
        switch model.lockState {
        case let .unavailable(message): message
        case .denied: "Authentication was not accepted."
        default: "Authenticate on this device to continue."
        }
    }
}
