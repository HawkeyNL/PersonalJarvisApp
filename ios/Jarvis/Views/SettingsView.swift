import SwiftUI

struct SettingsView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var showResetConfirmation = false
    @State private var showLogoutConfirmation = false

    var body: some View {
        NavigationStack {
            Form {
                if model.isAuthenticated {
                    Section("Home Node models") {
                        NavigationLink("Model access") { ModelsView(model: model) }
                    }
                }
                Section("Local voice") {
                    Toggle("Speak replies on this active device", isOn: $model.voiceEnabled)
                    Picker("System voice", selection: $model.selectedVoice) {
                        Text("Local system default").tag("")
                        if !model.selectedVoice.isEmpty && !model.availableVoices.contains(where: { $0.id == model.selectedVoice }) {
                            Text("Saved voice unavailable").tag(model.selectedVoice)
                        }
                        ForEach(model.availableVoices) { voice in Text(voice.label).tag(voice.id) }
                    }
                    Button("Refresh available system voices") { model.refreshLocalVoices() }
                    Text("Speech rate: \(model.voiceRate, specifier: "%.2f")×")
                    Slider(value: $model.voiceRate, in: 0.5...2, step: 0.25)
                        .accessibilityLabel("Speech rate for subsequent phrases")
                    Button("Stop current speech") { model.stopSpeaking() }
                }
                Section("Home Node") {
                    TextField("https://jarvis.local", text: $model.endpointText)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    Button("Save and test connection") { Task { _ = await model.saveEndpoint() } }
                    connectionLabel
                }
                Section("Security") {
                    Text("The device key, pairing nonce, device ID, and session token are stored in the device-only Keychain and are never synchronized through iCloud.")
                        .font(.footnote)
                    if model.isAuthenticated {
                        Button("Lock now") { model.lockWhenBackgrounded() }
                        Button("Log out") { showLogoutConfirmation = true }
                    }
                    Button("Reset this device", role: .destructive) { showResetConfirmation = true }
                }
                if let notice = model.notice {
                    Section("Status") { Text(notice) }
                }
            }
            .navigationTitle("Settings")
            .scrollContentBackground(.hidden)
            .background(JarvisTheme.background)
            .toolbarBackground(JarvisTheme.panel, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .task { model.refreshLocalVoices() }
            .confirmationDialog(
                "Log out of Jarvis?",
                isPresented: $showLogoutConfirmation,
                titleVisibility: .visible
            ) {
                Button("Log out", role: .destructive) { Task { await model.logout() } }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("This ends your session. Your Home Node address and device identity are kept so you can sign in again without resetting this device.")
            }
            .confirmationDialog(
                "Remove this device identity?",
                isPresented: $showResetConfirmation,
                titleVisibility: .visible
            ) {
                Button("Reset and require re-enrollment", role: .destructive) {
                    Task { await model.resetDevice() }
                }
                Button("Cancel", role: .cancel) { }
            } message: {
                Text("Jarvis will attempt to revoke this device, then remove its local key and session even if the Home Node is offline.")
            }
        }
    }

    @ViewBuilder private var connectionLabel: some View {
        switch model.connectionState {
        case .unconfigured: Label("Not configured", systemImage: "circle")
        case .checking: Label("Checking", systemImage: "clock")
        case .reachable: Label("Reachable", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
        case let .unreachable(message):
            Label(message, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
        }
    }
}
