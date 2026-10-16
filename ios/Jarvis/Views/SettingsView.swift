import SwiftUI

struct SettingsView: View {
    @ObservedObject var model: JarvisAppModel

    var body: some View {
        List {
            Section("Home Node") {
                NavigationLink { HomeNodeSettingsView(model: model) } label: { Label("Connection", systemImage: "network") }
                if model.isAuthenticated {
                    NavigationLink { ResourcesSettingsView(model: model) } label: { Label("Resources", systemImage: "cpu") }
                    NavigationLink { ModelsView(model: model) } label: { Label("Models", systemImage: "square.stack.3d.up") }
                }
            }
            Section("This device") {
                NavigationLink { VoiceSettingsView(model: model) } label: { Label("Voice", systemImage: "waveform") }
                NavigationLink { SecuritySettingsView(model: model) } label: { Label("Security", systemImage: "lock.shield") }
            }
            if let notice = model.notice { Section("Status") { Text(notice) } }
        }
        .navigationTitle("Settings")
        .settingsBackground()
    }
}

private struct HomeNodeSettingsView: View {
    @ObservedObject var model: JarvisAppModel

    var body: some View {
        Form {
            Section("Home Node address") {
                TextField("https://jarvis.local", text: $model.endpointText)
                    .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                Button("Save and test connection") { Task { _ = await model.saveEndpoint() } }
                connectionLabel
            }
            if let notice = model.notice { Section("Status") { Text(notice) } }
        }
        .navigationTitle("Connection")
        .settingsBackground()
    }

    @ViewBuilder private var connectionLabel: some View {
        switch model.connectionState {
        case .unconfigured: Label("Not configured", systemImage: "circle")
        case .checking: Label("Checking", systemImage: "clock")
        case .reachable: Label("Reachable", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
        case let .unreachable(message): Label(message, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
        }
    }
}

private struct VoiceSettingsView: View {
    @ObservedObject var model: JarvisAppModel

    var body: some View {
        Form {
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
        }
        .navigationTitle("Voice")
        .settingsBackground()
        .task { model.refreshLocalVoices() }
    }
}

private struct SecuritySettingsView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var showResetConfirmation = false
    @State private var showLogoutConfirmation = false

    var body: some View {
        Form {
            Section("Device security") {
                Text("The device key, pairing nonce, device ID, and session token are stored in the device-only Keychain and are never synchronized through iCloud.").font(.footnote)
                if model.isAuthenticated {
                    NavigationLink { PasswordChangeView(model: model) } label: { Text("Change account password") }
                    Button("Lock now") { model.lockWhenBackgrounded() }
                    Button("Log out") { showLogoutConfirmation = true }
                }
                Button("Reset this device", role: .destructive) { showResetConfirmation = true }
            }
        }
        .navigationTitle("Security")
        .settingsBackground()
        .confirmationDialog("Log out of Jarvis?", isPresented: $showLogoutConfirmation, titleVisibility: .visible) {
            Button("Log out", role: .destructive) { Task { await model.logout() } }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This ends your session. Your Home Node address and device identity are kept so you can sign in again without resetting this device.")
        }
        .confirmationDialog("Remove this device identity?", isPresented: $showResetConfirmation, titleVisibility: .visible) {
            Button("Revoke and require re-enrollment", role: .destructive) { Task { await model.resetDevice() } }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Jarvis asks your Home Node to revoke this device, confirmed with Face ID or Touch ID, then removes its local key and session. If the Home Node does not confirm, nothing is erased until you choose.")
        }
        .alert("Erase locally only?", isPresented: Binding(get: { model.localEraseOffer != nil },
                                                          set: { if !$0 { model.localEraseOffer = nil } })) {
            Button("Erase locally only", role: .destructive) { Task { await model.eraseLocally() } }
            Button("Keep this device", role: .cancel) { model.localEraseOffer = nil }
        } message: {
            Text(verbatim: "\(model.localEraseOffer ?? "")\n\nThis device may stay active on your Home Node until you revoke it from another device. Erase locally only if you will do that.")
        }
    }
}

/// Change the account password with the current one; the change is signed with the device key.
private struct PasswordChangeView: View {
    @ObservedObject var model: JarvisAppModel
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var new = ""
    @State private var repeated = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                SecureField("Current password", text: $current).textContentType(.password)
                SecureField("New password (at least 15 characters)", text: $new).textContentType(.newPassword)
                SecureField("Repeat new password", text: $repeated).textContentType(.newPassword)
            } footer: {
                Text("Confirmed with Face ID or Touch ID. Every device is signed out after the change; sign in again with the new password.")
            }
            if let error { Section { Text(verbatim: error).foregroundStyle(JarvisTheme.danger) } }
            Section {
                Button(busy ? "Verifying…" : "Change password") { Task { await change() } }
                    .disabled(busy || current.isEmpty || new.isEmpty)
            }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .navigationTitle("Account password")
        .settingsBackground()
    }

    private func change() async {
        guard new == repeated else { error = "The new passwords do not match."; return }
        busy = true
        error = nil
        do {
            try await model.changePassword(current: current, new: new)
            current = ""; new = ""; repeated = ""
            dismiss()
        } catch {
            self.error = PairingFingerprint.displayName((error as? LocalizedError)?.errorDescription ?? "The password was not changed.", max: 160)
        }
        busy = false
    }
}

private struct ResourcesSettingsView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var model: JarvisAppModel
    @State private var registry: HomeNodeRegistry?
    @State private var error: String?
    @State private var loading = false

    var body: some View {
        List {
            if let registry {
                Section("Live Home Node") {
                    if let live = registry.liveHost {
                        LabeledContent("CPU usage", value: live.cpuPercent.map { String(format: "%.1f%%", $0) } ?? "First reading…")
                        LabeledContent("Memory in use", value: "\(gib(live.memoryUsedBytes)) / \(gib(live.memoryTotalBytes))")
                        LabeledContent("Uptime", value: "\(live.uptimeSeconds / 3600) h \((live.uptimeSeconds % 3600) / 60) min")
                        LabeledContent("Last reading", value: Date(timeIntervalSince1970: TimeInterval(live.sampledAt)).formatted(date: .omitted, time: .standard))
                    } else {
                        Text("This Home Node version does not provide live hardware readings.").foregroundStyle(.secondary)
                    }
                }
                Section("Hardware") {
                    LabeledContent("Processor", value: registry.host.cpu)
                    LabeledContent("Cores", value: String(registry.host.cpuCores))
                    LabeledContent("Memory", value: "\(registry.host.memTotalGB.formatted()) GB")
                    LabeledContent("GPU", value: registry.host.gpu)
                    LabeledContent("Operating system", value: registry.host.os)
                    LabeledContent("Architecture", value: registry.host.arch)
                }
                Section("Software") {
                    ForEach(registry.software, id: \.name) { item in
                        LabeledContent(item.name, value: item.present ? (item.version ?? "Available") : "Unavailable")
                    }
                }
            } else if loading { ProgressView("Loading resources…") }
            if let error { Section("Status") { Text(error).foregroundStyle(.secondary) } }
        }
        .navigationTitle("Resources")
        .settingsBackground()
        .refreshable { await reload() }
        .task {
            while !Task.isCancelled {
                if scenePhase == .active { await reload() }
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    private func reload() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            registry = try await model.loadHomeNodeRegistry()
            error = nil
        } catch is CancellationError {
            // Leaving this page cancels polling.
        } catch {
            registry = nil
            self.error = (error as? LocalizedError)?.errorDescription ?? "Could not load Home Node resources."
        }
    }

    private func gib(_ bytes: Int64) -> String { String(format: "%.1f GiB", Double(bytes) / 1_073_741_824) }
}

private extension View {
    func settingsBackground() -> some View {
        scrollContentBackground(.hidden)
            .background(JarvisTheme.background)
            .toolbarBackground(JarvisTheme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}
