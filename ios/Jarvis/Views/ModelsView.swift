import SwiftUI

struct ModelsView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var policy: ModelPolicySnapshot?
    @State private var selected: ModelAccessEntry?
    @State private var confirming = false
    @State private var busy = false
    @State private var notice: String?
    @State private var search = ""
    @State private var page = 0
    private var filtered: [ModelAccessEntry] {
        (policy?.models ?? []).filter { search.isEmpty || $0.id.localizedCaseInsensitiveContains(search) }
    }
    var body: some View {
        List {
            Section {
                Text("Changes apply to every device. Face ID, Touch ID or your passcode authorizes model changes for five minutes, until lock or logout. Each change still needs confirmation. Budget limits remain in effect.")
                    .font(.footnote)
                if let notice { Text(notice).foregroundStyle(JarvisTheme.accent) }
                Button("Refresh") { Task { await reload() } }.disabled(busy)
                if policy != nil && policy?.mutable != true { Text("Model controls are unavailable. Update Core or ask the owner to verify its model policy.") }
            }
            Section("Models") {
                ForEach(Array(filtered.dropFirst(page * 25).prefix(25))) { entry in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(entry.model).font(.headline)
                        Text(entry.provider).font(.caption).foregroundStyle(.secondary)
                        HStack {
                            Text(entry.enabled ? "Enabled" : "Disabled")
                            Spacer()
                            Button(entry.enabled ? "Disable" : "Enable") { selected = entry; confirming = true }
                                .disabled(busy || policy?.mutable != true)
                        }
                    }
                    .padding(.vertical, 4)
                }
                if filtered.isEmpty { Text("No models found.") }
                HStack {
                    Button("Previous") { page -= 1 }.disabled(page == 0 || busy)
                    Spacer()
                    Text("\(page + 1) / \(max(1, (filtered.count + 24) / 25))")
                    Spacer()
                    Button("Next") { page += 1 }.disabled((page + 1) * 25 >= filtered.count || busy)
                }
            }
        }
        .navigationTitle("Models")
        .searchable(text: $search)
        .onChange(of: search) { _, _ in page = 0 }
        .task { await reload() }
        .confirmationDialog("Change model access?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Confirm model change") { Task { await change() } }
            Button("Cancel", role: .cancel) { selected = nil }
        } message: {
            if let selected { Text("\(selected.enabled ? "Disable" : "Enable") \(selected.provider)/\(selected.model)? Device authentication is remembered for five minutes, until lock or logout. Running requests will not be cancelled.") }
        }
    }

    private func reload() async {
        busy = true
        defer { busy = false }
        do { policy = try await model.loadModelPolicy(); page = min(page, max(0, (filtered.count - 1) / 25)) }
        catch { notice = error.localizedDescription; policy = nil }
    }

    private func change() async {
        guard !busy, let entry = selected, let hash = policy?.policy_sha256 else { return }
        selected = nil
        busy = true
        do { try await model.setModelEnabled(entry, policyHash: hash); notice = "Model change verified and active." }
        catch { notice = error.localizedDescription }
        await reload()
    }
}
