import SwiftUI

struct SettingsView: View {
    @ObservedObject var model: JarvisAppModel

    var body: some View {
        NavigationStack {
            List {
                Section("Home Node") {
                    NavigationLink { HomeNodeSettingsView(model: model) } label: { Label("Connection", systemImage: "network") }
                    if model.isAuthenticated {
                        NavigationLink { ResourcesSettingsView(model: model) } label: { Label("Bronnen", systemImage: "cpu") }
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
            Button("Reset and require re-enrollment", role: .destructive) { Task { await model.resetDevice() } }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Jarvis will attempt to revoke this device, then remove its local key and session even if the Home Node is offline.")
        }
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
        .navigationTitle("Bronnen")
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
            .toolbarBackground(JarvisTheme.panel, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}
