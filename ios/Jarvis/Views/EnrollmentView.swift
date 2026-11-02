import SwiftUI

/// The onboarding steps that exist on iOS (D-Onboarding): connect to the Home
/// Node, sign in (the first device also activates), approve this device on a
/// trusted one, then finish signing in. Display only; the states drive it.
enum OnboardingStep: Int, CaseIterable {
    case connect, signIn, approve, finish

    init(connection: ConnectionState, enrollment: EnrollmentState) {
        guard connection == .reachable else { self = .connect; return }
        switch enrollment {
        case .awaitingApproval: self = .approve
        case .authenticating, .authenticated: self = .finish
        default: self = .signIn
        }
    }

    var label: String {
        switch self {
        case .connect: "Connect"
        case .signIn: "Sign in"
        case .approve: "Approve"
        case .finish: "Finish"
        }
    }

    var accessibilityLabel: String { "Step \(rawValue + 1) of \(Self.allCases.count), \(label)" }
}

/// Before sign-in (D-Onboarding): one Instrument card with step progress.
struct EnrollmentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var model: JarvisAppModel
    @State private var password = ""
    @State private var activationCode = ""
    @State private var ownFingerprint: String?
    @FocusState private var addressFocused: Bool
    @State private var connecting = false
    @State private var addressError: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: JV.Space.xl) {
                StepProgress(current: OnboardingStep(connection: model.connectionState, enrollment: model.enrollmentState))
                VStack(alignment: .leading, spacing: 6) {
                    Text(title)
                        .font(JV.font(24, .semibold, relativeTo: .title2))
                        .foregroundStyle(JV.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                    Text(detail).font(JV.font(15)).foregroundStyle(JV.textSecondary)
                }
                .fixedSize(horizontal: false, vertical: true)
                actions
            }
            .frame(maxWidth: 480, alignment: .leading)
            .jvCard(padding: JV.Space.xl)
            .frame(maxWidth: .infinity)
            .padding(JV.Space.md)
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
            case .authenticating: "Signing in"
            case .authenticated: "Connected"
            case .needsActivation: "Activate your first device"
            case .needsPassword: "Account password required"
            case .signedOut: "Sign in again"
            default: "Enroll this iPhone or iPad"
            }
        }
    }

    private var detail: String {
        switch model.connectionState {
        case .unconfigured: "Enter the address of your Home Node."
        case .checking: "Waiting for a readiness response."
        case let .unreachable(message): message
        case .reachable:
            switch model.enrollmentState {
            case let .awaitingApproval(expiresAt):
                "Approve this unique device identity from an existing trusted Jarvis device before \(expiresAt.formatted())."
            case .signedOut: "Your session is signed out. Sign in again with this device identity."
            case .needsActivation: "Use the one-time code from your Home Node and choose a password of at least 15 characters."
            case .needsPassword: "Your password and this device's signature are both required."
            case .authenticating: "Checking this device with your Home Node."
            case let .failed(message): message
            default: "This creates a unique Ed25519 identity in this device's Keychain."
            }
        }
    }

    @ViewBuilder private var actions: some View {
        switch model.connectionState {
        case .unconfigured:
            // First setup only: nothing is paired yet. Changing a saved address
            // clears the pairing, so that stays a deliberate step in Settings.
            VStack(alignment: .leading, spacing: 6) {
                Text("Home Node address")
                    .font(JV.font(13, .medium))
                    .foregroundStyle(JV.textPrimary)
                    .accessibilityHidden(true)
                TextField("https://jarvis.local", text: $model.endpointText)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                    .focused($addressFocused)
                    .submitLabel(.go)
                    .onSubmit(connect)
                    .jvInput(focused: addressFocused) { addressFocused = true }
                    .accessibilityLabel("Home Node address")
                    .accessibilityIdentifier("home-node-address")
                    .onChange(of: model.endpointText) { _, _ in addressError = nil }
                if let addressError {
                    Text(verbatim: addressError)
                        .font(JV.font(13, relativeTo: .footnote))
                        .foregroundStyle(JV.dangerText)
                }
            }
            Button("Connect", action: connect)
                .disabled(connecting || model.endpointText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .buttonStyle(JVButtonStyle(size: .large))
        case .checking:
            BusyLine(label: "Checking…")
        case .unreachable:
            Button("Retry") { Task { _ = await model.checkConnection() } }
                .buttonStyle(JVButtonStyle(size: .large))
            Text("To change the address, open Settings with the profile button.")
                .font(JV.font(13, relativeTo: .footnote))
                .foregroundStyle(JV.textSecondary)
        case .reachable:
            switch model.enrollmentState {
            case .awaitingApproval:
                if let ownFingerprint {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Check that the approving device shows this key fingerprint:")
                            .font(JV.font(13, relativeTo: .footnote))
                            .foregroundStyle(JV.textSecondary)
                        Text(verbatim: ownFingerprint)
                            .font(JV.mono(15, .medium))
                            .foregroundStyle(JV.textPrimary)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(JV.Space.sm)
                            .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
                Button("Check approval") { Task { await model.refreshEnrollment() } }
                    .buttonStyle(JVButtonStyle(size: .large))
                    .task { ownFingerprint = await model.ownKeyFingerprint() }
            case .requesting:
                BusyLine(label: "Requesting…")
            case .authenticating:
                BusyLine(label: "Signing in…")
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

    private func connect() {
        guard !connecting, !model.endpointText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        // An invalid address stays on this card with its reason instead of
        // leaving for the unreachable state, which has no address field.
        do { _ = try EndpointNormalizer.normalize(model.endpointText) } catch {
            addressError = (error as? LocalizedError)?.errorDescription ?? "Enter a valid Home Node URL."
            return
        }
        addressFocused = false
        connecting = true
        Task {
            _ = await model.saveEndpoint()
            connecting = false
        }
    }
}

/// A calm busy line: small spinner and what is happening.
private struct BusyLine: View {
    let label: String

    var body: some View {
        HStack(spacing: JV.Space.xs) {
            ProgressView().tint(JV.textSecondary)
            Text(label).font(JV.font(15)).foregroundStyle(JV.textSecondary)
        }
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }
}

/// Step bars with labels (D-Onboarding); done and current steps are accent.
/// At the largest text sizes one line names the current step instead.
private struct StepProgress: View {
    let current: OnboardingStep
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: JV.Space.xs) {
                ForEach(OnboardingStep.allCases, id: \.self) { step in
                    let reached = step.rawValue <= current.rawValue
                    VStack(alignment: .leading, spacing: 6) {
                        Capsule()
                            .fill(reached ? JV.accentSolid : contrast == .increased ? JV.borderStrong : JV.bgSunken)
                            .frame(height: 4)
                        if !compact {
                            Text(step.label)
                                .font(JV.font(12, step == current ? .semibold : .regular, relativeTo: .caption))
                                .foregroundStyle(reached ? JV.textPrimary : JV.textSecondary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if compact {
                Text(current.accessibilityLabel)
                    .font(JV.font(12, .semibold, relativeTo: .caption))
                    .foregroundStyle(JV.textPrimary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(current.accessibilityLabel)
    }

    /// Four labels no longer fit a phone row from xxxLarge up.
    private var compact: Bool { typeSize >= .xxxLarge }
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
        VStack(alignment: .leading, spacing: JV.Space.md) {
            if requiresActivation {
                VStack(alignment: .leading, spacing: 6) {
                    fieldLabel("One-time activation code")
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
                        .jvInput(focused: focused == .activation) { focused = .activation }
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                fieldLabel("Account password")
                HStack(spacing: 0) {
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
                            .foregroundStyle(JV.textSecondary)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(passwordVisible ? "Hide password" : "Show password")
                    .accessibilityIdentifier("password-visibility")
                }
                .jvInput(focused: focused == .password) { focused = .password }
            }
            Button("Continue") { passwordVisible = false; focused = nil; submit() }
                .disabled(!canSubmit)
                .buttonStyle(JVButtonStyle(size: .large))
        }
        .onChange(of: scenePhase) { _, phase in if phase != .active { passwordVisible = false } }
        .onChange(of: requiresActivation) { _, _ in passwordVisible = false }
        .onChange(of: password) { _, value in if value.isEmpty { passwordVisible = false } }
        .onDisappear { passwordVisible = false }
    }

    /// Visible field label; the field itself carries the same accessibility label.
    private func fieldLabel(_ text: String) -> some View {
        Text(text).font(JV.font(13, .medium)).foregroundStyle(JV.textPrimary).accessibilityHidden(true)
    }
}
