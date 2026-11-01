import SwiftUI

struct EnrollmentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var model: JarvisAppModel
    @State private var password = ""
    @State private var activationCode = ""
    @State private var ownFingerprint: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Label {
                    Text(title).foregroundStyle(JV.textPrimary)
                } icon: {
                    Image(systemName: symbol).foregroundStyle(JV.accentText)
                }
                .font(JV.font(22, .semibold, relativeTo: .title2))
                .accessibilityAddTraits(.isHeader)
                Text(detail).font(JV.font(15)).foregroundStyle(JV.textSecondary)
                actions
            }
            .frame(maxWidth: 480, alignment: .leading)
            .jvCard(padding: 24)
            .frame(maxWidth: .infinity)
            .padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .background(JV.bgPage)
        .onDisappear { password = ""; activationCode = "" }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { password = ""; activationCode = "" }
        }
    }

    private var title: String {
        switch model.connectionState {
        case .unconfigured: "Connect a Home Node"
        case .checking: "Checking Home Node"
        case .unreachable: "Home Node unavailable"
        case .reachable:
            switch model.enrollmentState {
            case .awaitingApproval: "Approval required"
            case .authenticated: "Connected"
            case .needsActivation: "Activate your first device"
            case .needsPassword: "Account password required"
            default: "Enroll this iPhone or iPad"
            }
        }
    }

    private var symbol: String {
        model.connectionState == .reachable ? "key.horizontal" : "network.slash"
    }

    private var detail: String {
        switch model.connectionState {
        case .unconfigured: "Set your Home Node address in Settings."
        case .checking: "Waiting for a readiness response."
        case let .unreachable(message): message
        case .reachable:
            switch model.enrollmentState {
            case let .awaitingApproval(expiresAt):
                "Approve this unique device identity from an existing trusted Jarvis device before \(expiresAt.formatted())."
            case .signedOut: "Your session is signed out. Sign in again with this device identity."
            case .needsActivation: "Use the one-time code from your Home Node and choose a password of at least 15 characters."
            case .needsPassword: "Your password and this device's signature are both required."
            case let .failed(message): message
            default: "This creates a unique Ed25519 identity in this device's Keychain."
            }
        }
    }

    @ViewBuilder private var actions: some View {
        switch model.connectionState {
        case .unconfigured:
            Text("Open Settings with the profile button to configure the address.")
        case .checking:
            ProgressView()
        case .unreachable:
            Button("Retry") { Task { _ = await model.checkConnection() } }
                .buttonStyle(JVButtonStyle(size: .large))
        case .reachable:
            switch model.enrollmentState {
            case .awaitingApproval:
                if let ownFingerprint {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Check that the approving device shows this key fingerprint:")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Text(verbatim: ownFingerprint)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                Button("Check approval") { Task { await model.refreshEnrollment() } }
                    .buttonStyle(JVButtonStyle(size: .large))
                    .task { ownFingerprint = await model.ownKeyFingerprint() }
            case .requesting, .authenticating:
                ProgressView()
            case .authenticated:
                EmptyView()
            default:
                EnrollmentCredentialsForm(
                    requiresActivation: model.enrollmentState == .needsActivation,
                    password: $password,
                    activationCode: $activationCode
                ) {
                    let suppliedPassword = password
                    let suppliedCode = model.enrollmentState == .needsActivation ? activationCode : nil
                    password = ""
                    activationCode = ""
                    Task { await model.requestEnrollment(password: suppliedPassword, activationCode: suppliedCode) }
                }
            }
        }
    }
}

// Keep text inputs outside ContentUnavailableView's action presentation.
// This independently hostable form lets simulator tests exercise real UIKit
// hit testing, keyboard focus and text entry without credentials or networking.
struct EnrollmentCredentialsForm: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var passwordVisible = false
    let requiresActivation: Bool
    @Binding var password: String
    @Binding var activationCode: String
    let submit: () -> Void
    private enum Field: Hashable { case activation, password }
    @FocusState private var focused: Field?

    private var canSubmit: Bool {
        !password.isEmpty && (!requiresActivation || !activationCode.isEmpty)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if requiresActivation {
                Text("One-time activation code").font(.headline)
                TextField("One-time activation code", text: $activationCode)
                    .textContentType(.oneTimeCode)
                    .keyboardType(.asciiCapable)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focused, equals: .activation)
                    .submitLabel(.next)
                    .onSubmit { focused = .password }
                    .accessibilityIdentifier("activation-code")
                    .privacySensitive()
            }
            Text("Account password").font(.headline)
            HStack {
                Group {
                    if passwordVisible {
                        TextField("Account password", text: $password)
                    } else {
                        SecureField("Account password", text: $password)
                    }
                }
                .textContentType(requiresActivation ? .newPassword : .password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused, equals: .password)
                .submitLabel(.go)
                .onSubmit { if canSubmit { passwordVisible = false; focused = nil; submit() } }
                .accessibilityIdentifier("account-password")
                .privacySensitive()
                Button {
                    passwordVisible.toggle()
                    focused = .password
                } label: {
                    Image(systemName: passwordVisible ? "eye.slash" : "eye")
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(passwordVisible ? "Hide password" : "Show password")
                .accessibilityIdentifier("password-visibility")
            }
            Button("Continue") { passwordVisible = false; focused = nil; submit() }
                .disabled(!canSubmit)
                .buttonStyle(JVButtonStyle(size: .large))
        }
        .textFieldStyle(.roundedBorder)
        .onChange(of: scenePhase) { _, phase in if phase != .active { passwordVisible = false } }
        .onChange(of: requiresActivation) { _, _ in passwordVisible = false }
        .onChange(of: password) { _, value in if value.isEmpty { passwordVisible = false } }
        .onDisappear { passwordVisible = false }
    }
}
