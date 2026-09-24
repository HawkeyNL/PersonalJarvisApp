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
    @State private var provider = ""
    @State private var access = "all"
    @State private var pricing = "all"
    private var catalog: ModelCatalogPage {
        ModelCatalogPage(models: policy?.models ?? [], search: search, provider: provider,
                         access: access, pricing: pricing, requestedPage: page)
    }
    private var providers: [String] {
        Set((policy?.models ?? []).map(\.provider)).sorted()
    }
    var body: some View {
        List {
            Section {
                Text("Changes apply to every device. Face ID, Touch ID or your passcode authorizes model changes for five minutes, until lock or logout. Each change still needs confirmation. Budget limits remain in effect.")
                    .font(.footnote)
                if let notice { Text(notice).foregroundStyle(JarvisTheme.accent) }
                Button("Refresh") { Task { await reload() } }.disabled(busy)
                if let policy, !policy.mutable { Text(policy.unavailableMessage) }
            }
            Section("Filters") {
                Picker("Provider", selection: $provider) {
                    Text("All providers").tag("")
                    ForEach(providers, id: \.self) { Text($0).tag($0) }
                }
                Picker("Access", selection: $access) {
                    Text("All models").tag("all")
                    Text("Enabled").tag("enabled")
                    Text("Disabled").tag("disabled")
                }
                Picker("Pricing", selection: $pricing) {
                    Text("All prices").tag("all")
                    Text("Price available").tag("priced")
                    Text("Unknown price").tag("unknown")
                }
            }
            Section("Models") {
                ForEach(catalog.entries) { entry in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(entry.model).font(.headline)
                        Text(entry.provider).font(.caption).foregroundStyle(.secondary)
                        if entry.hasPrice {
                            Text("USD / 1M tokens · \(entry.price_status ?? "known")").font(.caption)
                            Text("Input \(price(entry.input_per_million_usd)) · Output \(price(entry.output_per_million_usd))")
                                .font(.caption).foregroundStyle(.secondary)
                            if entry.cache_read_per_million_usd != nil {
                                Text("Cached input \(price(entry.cache_read_per_million_usd))").font(.caption)
                            }
                            if let notes = entry.pricing_notes { Text(notes).font(.caption).foregroundStyle(.secondary) }
                            if let long = entry.long_context {
                                Text("From \(long.from_input_tokens) input tokens: input \(price(long.input_per_million_usd)) · output \(price(long.output_per_million_usd)) per 1M")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            if let date = entry.pricing_updated_at { Text("Pricing updated \(date)").font(.caption).foregroundStyle(.secondary) }
                        } else {
                            Text("Price unknown — not free").font(.caption).foregroundStyle(.secondary)
                        }
                        HStack {
                            Text(entry.enabled ? "Enabled" : "Disabled")
                            Spacer()
                            Button(entry.enabled ? "Disable" : "Enable") { selected = entry; confirming = true }
                                .buttonStyle(.borderless)
                                .disabled(busy || policy?.mutable != true)
                        }
                    }
                    .padding(.vertical, 4)
                }
                if catalog.total == 0 { Text("No models found.") }
                HStack {
                    Button("Previous") { page = catalog.index - 1 }.disabled(catalog.index == 0 || busy)
                    Spacer()
                    Text("\(catalog.index + 1) / \(catalog.count)")
                    Spacer()
                    Button("Next") { page = catalog.index + 1 }.disabled(catalog.index + 1 >= catalog.count || busy)
                }
                .buttonStyle(.borderless)
                Text("\(catalog.total) models · up to 25 per page").font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Models")
        .searchable(text: $search)
        .onChange(of: search) { _, _ in page = 0 }
        .onChange(of: provider) { _, _ in page = 0 }
        .onChange(of: access) { _, _ in page = 0 }
        .onChange(of: pricing) { _, _ in page = 0 }
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
        do {
            policy = try await model.loadModelPolicy()
            if !provider.isEmpty && !providers.contains(provider) { provider = "" }
            page = catalog.index
        }
        catch { notice = error.localizedDescription; policy = nil }
    }

    private func price(_ value: Double?) -> String {
        guard let value, value.isFinite, value >= 0 else { return "unknown" }
        return value.formatted(.currency(code: "USD").precision(.fractionLength(0...6)))
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
