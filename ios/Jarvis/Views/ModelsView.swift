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
                NavigationLink("Model routing") { RoutingEditorView(model: model) }
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

/// Owner routing per tier. The signed bytes are built from the typed document in
/// `AuthService.setModelRouting`; this view only edits it.
struct RoutingEditorView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var policy: ModelPolicySnapshot?
    @State private var draft = RoutingDocument()
    @State private var loadFailed = false
    @State private var busy = false
    @State private var notice: String?
    @State private var confirming = false
    @State private var adding: [RoutingTier: String] = [:]

    private var mode: RoutingMode { policy?.routingMode ?? .unsupported }
    private var editable: Bool { mode == .editable && !busy }
    private var base: RoutingDocument { policy?.routingDocument ?? RoutingDocument() }
    private var dirty: Bool { draft != base }
    // An unusable routing file can be repaired by re-signing the shown document.
    private var canApply: Bool { dirty || policy?.routing_unavailable_reason != nil }
    private var issue: String? {
        guard let policy else { return nil }
        return RoutingRules.issue(draft, discovered: policy.models.map { RouteEntry($0.provider, $0.model) })
    }
    private var relaxed: Bool { RoutingRules.relaxation(current: policy?.currentRouting, next: draft).any }
    private var enabledIDs: Set<String> { Set((policy?.models ?? []).filter(\.enabled).map(\.id)) }

    var body: some View {
        List {
            if loadFailed {
                Section { Text("Model policy unreachable. Check the connection and session.").foregroundStyle(JarvisTheme.danger) }
            } else if let policy {
                if mode == .unsupported {
                    Section { Text("This Core cannot change routing from the app. Update Core to choose the model order per tier.") }
                } else {
                    intro(policy)
                    Section("Spending") {
                        Toggle(isOn: Binding(get: { draft.paidApi == .allowed }, set: { draft.paidApi = $0 ? .allowed : .off })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Paid APIs \(draft.paidApi == .allowed ? "allowed" : "off")")
                                Text("Off: only subscriptions and local models, in every tier and the built-in order.")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Toggle(isOn: $draft.researchWebSearch) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Research web search \(draft.researchWebSearch ? "on" : "off")")
                                Text("On: explicit Research requests may use the subscription's provider-hosted web search. Never a paid API.")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .disabled(!editable)
                    ForEach(RoutingTier.allCases) { tier in tierSection(tier, policy) }
                    Section {
                        if canApply, let issue { Text(issue).foregroundStyle(JarvisTheme.danger) }
                        Button("Apply routing") { confirming = true }.disabled(!editable || !canApply || issue != nil)
                        Button("Discard changes", role: .destructive) { draft = base }.disabled(!editable || !dirty)
                    }
                }
            } else {
                Section { ProgressView("Loading routing") }
            }
            if let notice { Section { Text(notice).foregroundStyle(JarvisTheme.accent) } }
            Section { Button("Refresh") { Task { await reload() } }.disabled(busy) }
        }
        .navigationTitle("Model routing")
        .task { await reload() }
        .confirmationDialog("Replace model routing?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Confirm change") { Task { await apply() } }
            Button("Cancel", role: .cancel) { }
        } message: { Text(summary) }
    }

    @ViewBuilder private func intro(_ policy: ModelPolicySnapshot) -> some View {
        Section {
            Text("Routing only orders models per tier. A model still has to be allowed, within budget and healthy; a tier on the built-in order keeps Core's own choice. Changes apply to all devices and need device authentication.")
                .font(.footnote)
            if let reason = policy.routingReasonText { Text(reason).foregroundStyle(JarvisTheme.warn) }
            if mode == .readonly {
                Text("Routing changes are unavailable on this Core right now (privileged broker unavailable or routing file unreadable).")
                    .foregroundStyle(JarvisTheme.warn)
            }
        }
    }

    @ViewBuilder private func tierSection(_ tier: RoutingTier, _ policy: ModelPolicySnapshot) -> some View {
        Section {
            if let route = draft.tiers[tier] {
                ForEach(Array(route.chain.enumerated()), id: \.element.id) { index, entry in
                    HStack(spacing: 10) {
                        Text("\(index + 1)").foregroundStyle(JarvisTheme.accent).monospacedDigit()
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.model).font(.headline)
                            Text("\(entry.provider) · \(kind(entry.provider))\(enabledIDs.contains(entry.id) ? "" : " · blocked")")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button { draft.tiers[tier]?.chain = RoutingRules.moved(route.chain, index, -1) } label: { Image(systemName: "arrow.up") }
                            .disabled(index == 0).accessibilityLabel("Move \(entry.id) up")
                        Button { draft.tiers[tier]?.chain = RoutingRules.moved(route.chain, index, 1) } label: { Image(systemName: "arrow.down") }
                            .disabled(index == route.chain.count - 1).accessibilityLabel("Move \(entry.id) down")
                        Button(role: .destructive) { draft.tiers[tier]?.chain.remove(at: index) } label: { Image(systemName: "xmark") }
                            .accessibilityLabel("Remove \(entry.id)")
                    }
                    .buttonStyle(.borderless)
                }
                if route.chain.isEmpty { Text("Add at least one model, or reset to the built-in order.").font(.caption).foregroundStyle(.secondary) }
                let options = RoutingRules.candidates(policy.models, excluding: route.chain)
                HStack {
                    Picker("Add a model", selection: Binding(get: { adding[tier] ?? "" }, set: { adding[tier] = $0 })) {
                        Text("Add a discovered model").tag("")
                        ForEach(options) { Text("\($0.model) (\($0.provider)\($0.enabled ? "" : ", blocked"))").tag($0.id) }
                    }
                    Button("Add") {
                        if let pick = options.first(where: { $0.id == adding[tier] }), route.chain.count < RoutingRules.maxChain {
                            draft.tiers[tier]?.chain.append(RouteEntry(pick.provider, pick.model))
                            adding[tier] = ""
                        }
                    }
                    .buttonStyle(.borderless).disabled((adding[tier] ?? "").isEmpty)
                }
                .disabled(route.chain.count >= RoutingRules.maxChain)
                if RoutingRules.meteredAfterSubscription(route.chain) || route.metered_after_subscription {
                    Toggle("Allow paid APIs after a subscription", isOn: Binding(
                        get: { draft.tiers[tier]?.metered_after_subscription ?? false },
                        set: { draft.tiers[tier]?.metered_after_subscription = $0 }))
                }
                if route.metered_after_subscription {
                    Text("When the subscription is unavailable or its plan is used up, this tier may fall back to a paid API and cost money.")
                        .font(.caption).foregroundStyle(JarvisTheme.warn)
                }
                if draft.paidApi == .off && route.chain.contains(where: { RoutingRules.isMetered($0.provider) }) {
                    Text("Paid APIs are off: paid entries in this tier are skipped.").font(.caption).foregroundStyle(.secondary)
                }
                Button("Reset to built-in order") { draft.tiers[tier] = nil }.buttonStyle(.borderless)
            } else {
                Text("Built-in order.").foregroundStyle(.secondary)
                Button("Customize") { draft.tiers[tier] = TierRoute() }.buttonStyle(.borderless)
            }
        } header: { Text("\(tier.title) · \(tier.detail)") }
        .disabled(!editable)
    }

    private func kind(_ provider: String) -> String {
        RoutingRules.isSubscription(provider) ? "Subscription" : RoutingRules.isMetered(provider) ? "Paid API" : "Local"
    }

    private var summary: String {
        var text = "This replaces the routing for all devices"
        if draft.paidApi == .off { text += " and turns paid APIs off" }
        if draft.researchWebSearch { text += ". Research may use web search" }
        text += ". Running requests are not cancelled."
        if relaxed { text += " This change can increase spending or use web search, so device authentication is requested again." }
        for tier in RoutingTier.allCases {
            let chain = draft.tiers[tier].map { $0.chain.map(\.id).joined(separator: " → ") } ?? "built-in order"
            text += "\n\(tier.title): \(chain)"
        }
        return text
    }

    private func reload(keepNotice: Bool = false) async {
        busy = true
        defer { busy = false }
        do {
            let result = try await model.loadModelPolicy()
            policy = result
            draft = result.routingDocument
            loadFailed = false
            if !keepNotice { notice = nil }
        } catch { loadFailed = true; policy = nil }
    }

    private func apply() async {
        guard !busy, let hash = policy?.routing_sha256, issue == nil else { return }
        busy = true
        do { try await model.setModelRouting(draft, routingHash: hash); notice = "Routing verified and updated." }
        catch { notice = error.localizedDescription }
        await reload(keepNotice: true)
    }
}
