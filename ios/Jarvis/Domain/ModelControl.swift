import CryptoKit
import Foundation

struct ModelAccessEntry: Decodable, Identifiable {
    let provider: String
    let model: String
    let enabled: Bool
    var price_status: String? = nil
    var input_per_million_usd: Double? = nil
    var output_per_million_usd: Double? = nil
    var cache_read_per_million_usd: Double? = nil
    var pricing_notes: String? = nil
    var pricing_updated_at: String? = nil
    var long_context: ModelLongContextPrice? = nil
    var id: String { provider + "/" + model }

    var hasPrice: Bool {
        guard let input = input_per_million_usd, let output = output_per_million_usd else { return false }
        return input.isFinite && output.isFinite && input >= 0 && output >= 0 && price_status != "unknown"
    }
}

struct ModelLongContextPrice: Decodable {
    let from_input_tokens: UInt32
    let input_per_million_usd: Double
    let output_per_million_usd: Double
}

/// Filtering and pagination operate on the same stable snapshot. No credentials
/// or signed mutation state belongs in this presentation model.
struct ModelCatalogPage {
    static let size = 25
    let entries: [ModelAccessEntry]
    let index: Int
    let count: Int
    let total: Int

    init(models: [ModelAccessEntry], search: String, provider: String,
         access: String, pricing: String, requestedPage: Int) {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = models.filter {
            (query.isEmpty || $0.id.localizedCaseInsensitiveContains(query)) &&
            (provider.isEmpty || $0.provider == provider) &&
            (access == "all" || $0.enabled == (access == "enabled")) &&
            (pricing == "all" || $0.hasPrice == (pricing == "priced"))
        }.sorted { $0.id < $1.id }
        total = filtered.count
        count = max(1, (total + Self.size - 1) / Self.size)
        index = min(max(0, requestedPage), count - 1)
        entries = Array(filtered.dropFirst(index * Self.size).prefix(Self.size))
    }
}

struct ModelPolicySnapshot: Decodable {
    let models: [ModelAccessEntry]
    let mutation: String
    let policy_sha256: String?
    let user_id: UUID
    let device_id: UUID
    let server_time: Int64
    var mutation_unavailable_reason: String? = nil
    // Absent on a Core without signed routing.
    var routing_mutation: String? = nil
    var routing_sha256: String? = nil
    var routing: LenientRouting? = nil
    var routing_unavailable_reason: String? = nil
    var mutable: Bool { mutation == "device-signed-model-toggle-v1" && policy_sha256 != nil }
    var unavailableMessage: String {
        switch mutation_unavailable_reason {
        case "policy_reload_required":
            return "Core's loaded catalog differs from the protected model policy. Ask the Home Node owner to reload Core; model changes remain locked for safety."
        case "broker_unavailable":
            return "The Home Node model-control broker is unavailable. Ask the owner to check Core health."
        case "policy_unavailable":
            return "Core cannot verify the protected model policy. Ask the owner to check Core health."
        default:
            return "Model controls are unavailable. Ask the owner to verify Core and its model policy."
        }
    }
}

/// Stable v1 broker signing format (client-core `approval_message`): the hashes bind
/// the operation payload and the policy snapshot the owner approved.
func privilegedApprovalMessage(action: String, payload: Data, request: UUID, nonce: Data, user: UUID, device: UUID,
                               issued: Int64, expires: Int64, state: Data) throws -> Data {
    guard !action.isEmpty, action.utf8.count <= 64, nonce.count == 32, issued >= 0, expires > issued,
          expires - issued <= 300, state.count == 32 else { throw JarvisAPIError.invalidResponse }
    var result = Data("jarvis-privileged-config-v1\0".utf8)
    result.append(contentsOf: [UInt8(action.utf8.count >> 8), UInt8(action.utf8.count & 0xff)])
    result.append(Data(action.utf8))
    result.append(Data(SHA256.hash(data: payload)))
    func uuid(_ value: UUID) -> Data { var bytes = value.uuid; return withUnsafeBytes(of: &bytes) { Data($0) } }
    func seconds(_ value: Int64) -> Data { var bytes = value.bigEndian; return withUnsafeBytes(of: &bytes) { Data($0) } }
    result.append(uuid(request)); result.append(nonce); result.append(uuid(user)); result.append(uuid(device))
    result.append(seconds(issued)); result.append(seconds(expires)); result.append(state)
    return result
}

/// Wire contract matches authoritative client-core model_control v1. No token,
/// private key or owner password belongs in these presentation/approval values.
struct ModelToggleApproval {
    let request: UUID
    let nonce: Data
    let user: UUID
    let device: UUID
    let issued: Int64
    let expires: Int64
    let provider: String
    let model: String
    let enabled: Bool
    let hash: String

    func operationBytes() throws -> Data {
        let providers = ["anthropic-api", "openai-api", "deepseek-api", "xai-api", "zai-api", "ollama", "ollama-cloud", "huggingface", "claude-cli", "codex-cli"]
        guard providers.contains(provider), !model.isEmpty, model.utf8.count <= 256,
              model.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }),
              Data(hexEncoded: hash)?.count == 32 else { throw JarvisAPIError.invalidResponse }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        func quote(_ text: String) throws -> String { String(decoding: try encoder.encode(text), as: UTF8.self) }
        return Data(try "{\"action\":\"model_set_enabled\",\"provider\":\(quote(provider)),\"model\":\(quote(model)),\"enabled\":\(enabled ? "true" : "false"),\"expected_policy_sha256\":\(quote(hash))}".utf8)
    }

    func message() throws -> Data {
        guard let state = Data(hexEncoded: hash) else { throw JarvisAPIError.invalidResponse }
        return try privilegedApprovalMessage(action: "model.set_enabled", payload: try operationBytes(), request: request,
            nonce: nonce, user: user, device: device, issued: issued, expires: expires, state: state)
    }

    func signed(_ signature: String) throws -> SignedModelToggle {
        _ = try message()
        guard Data(hexEncoded: signature)?.count == 64 else { throw JarvisAPIError.invalidResponse }
        let format = ISO8601DateFormatter()
        return SignedModelToggle(request_id: request, nonce_hex: nonce.hexEncodedString(), user_id: user, device_id: device,
            issued_at: format.string(from: Date(timeIntervalSince1970: TimeInterval(issued))),
            expires_at: format.string(from: Date(timeIntervalSince1970: TimeInterval(expires))),
            operation: .init(provider: provider, model: model, enabled: enabled, expected_policy_sha256: hash), signature_hex: signature)
    }
}

struct SignedModelToggle: Encodable {
    struct Operation: Encodable {
        let action = "model_set_enabled"
        let provider: String
        let model: String
        let enabled: Bool
        let expected_policy_sha256: String
    }
    let request_id: UUID
    let nonce_hex: String
    let user_id: UUID
    let device_id: UUID
    let issued_at: String
    let expires_at: String
    let operation: Operation
    let signature_hex: String
}

// MARK: - Owner model routing

enum RoutingTier: String, CaseIterable, Identifiable {
    case cheap, `default`, hard
    var id: String { rawValue }
    var title: String {
        switch self { case .cheap: "Cheap"; case .default: "Default"; case .hard: "Hard" }
    }
    var detail: String {
        switch self { case .cheap: "Quick, light requests"; case .default: "Most requests"; case .hard: "Complex or deep work" }
    }
}

enum PaidApiSetting: String, Codable { case allowed, off }
private enum OnOff: String, Codable { case on, off }

struct RouteEntry: Codable, Equatable, Hashable, Identifiable {
    let provider: String
    let model: String
    var id: String { provider + "/" + model }
    init(_ provider: String, _ model: String) { self.provider = provider; self.model = model }
    private enum CodingKeys: String, CodingKey { case provider, model }
    init(from decoder: Decoder) throws {
        try requireKnownKeys(decoder, ["provider", "model"])
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = try c.decode(String.self, forKey: .provider)
        model = try c.decode(String.self, forKey: .model)
    }
}

struct TierRoute: Codable, Equatable {
    var chain: [RouteEntry]
    var metered_after_subscription: Bool
    init(chain: [RouteEntry] = [], metered_after_subscription: Bool = false) {
        self.chain = chain; self.metered_after_subscription = metered_after_subscription
    }
    private enum CodingKeys: String, CodingKey { case chain, metered_after_subscription }
    init(from decoder: Decoder) throws {
        try requireKnownKeys(decoder, ["chain", "metered_after_subscription"])
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chain = try c.decode([RouteEntry].self, forKey: .chain)
        metered_after_subscription = try c.decodeIfPresent(Bool.self, forKey: .metered_after_subscription) ?? false
    }
}

/// Decoding rejects unknown fields like client-core (`deny_unknown_fields`), so a newer
/// routing shape reads as unknown (fail closed) instead of being silently reshaped.
/// The signed bytes never come from Codable: see `canonicalJSON()`.
struct RoutingDocument: Codable, Equatable {
    var version = 1
    var paidApi: PaidApiSetting = .allowed
    var tiers: [RoutingTier: TierRoute] = [:]
    var researchWebSearch = false

    init() {}
    private enum CodingKeys: String, CodingKey { case version, paid_api, tiers, research_web_search }
    init(from decoder: Decoder) throws {
        try requireKnownKeys(decoder, ["version", "paid_api", "tiers", "research_web_search"])
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decode(Int.self, forKey: .version)
        paidApi = try c.decodeIfPresent(PaidApiSetting.self, forKey: .paid_api) ?? .allowed
        if c.contains(.tiers), try !c.decodeNil(forKey: .tiers) {
            let t = try c.nestedContainer(keyedBy: AnyKey.self, forKey: .tiers)
            guard Set(t.allKeys.map(\.stringValue)).isSubset(of: Set(RoutingTier.allCases.map(\.rawValue))) else {
                throw DecodingError.dataCorruptedError(forKey: .tiers, in: c, debugDescription: "Unknown tier")
            }
            for tier in RoutingTier.allCases { tiers[tier] = try t.decodeIfPresent(TierRoute.self, forKey: AnyKey(stringValue: tier.rawValue)!) }
        }
        researchWebSearch = try c.decodeIfPresent(OnOff.self, forKey: .research_web_search) == .on
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(version, forKey: .version)
        try c.encode(paidApi, forKey: .paid_api)
        var t = c.nestedContainer(keyedBy: AnyKey.self, forKey: .tiers)
        for tier in RoutingTier.allCases { try t.encodeIfPresent(tiers[tier], forKey: AnyKey(stringValue: tier.rawValue)!) }
        if researchWebSearch { try c.encode(OnOff.on, forKey: .research_web_search) }
    }

    /// Exactly client-core's compact serde_json output for this document: fixed field
    /// order, absent tiers omitted, `paid_api` and `metered_after_subscription` always
    /// written, `research_web_search` last and only when on. Built by hand so it does
    /// not depend on JSONEncoder key order or escaping.
    func canonicalJSON() -> String {
        let q = JSONText.quote
        let tierJSON = RoutingTier.allCases.compactMap { tier -> String? in
            guard let route = tiers[tier] else { return nil }
            let chain = route.chain.map { "{\"provider\":\(q($0.provider)),\"model\":\(q($0.model))}" }.joined(separator: ",")
            return "\(q(tier.rawValue)):{\"chain\":[\(chain)],\"metered_after_subscription\":\(route.metered_after_subscription)}"
        }.joined(separator: ",")
        var out = "{\"version\":\(version),\"paid_api\":\(q(paidApi.rawValue)),\"tiers\":{\(tierJSON)}"
        if researchWebSearch { out += ",\"research_web_search\":\"on\"" }
        return out + "}"
    }
}

/// `routing` from the policy snapshot. A shape this app cannot parse yields a nil
/// `document`, which callers treat as unknown (never as "paid APIs allowed").
struct LenientRouting: Decodable {
    let document: RoutingDocument?
    init(from decoder: Decoder) throws { document = try? RoutingDocument(from: decoder) }
}

private struct AnyKey: CodingKey {
    let stringValue: String
    var intValue: Int? { nil }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}

private func requireKnownKeys(_ decoder: Decoder, _ allowed: Set<String>) throws {
    let keys = try decoder.container(keyedBy: AnyKey.self).allKeys.map(\.stringValue)
    guard Set(keys).isSubset(of: allowed) else {
        throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unknown field"))
    }
}

/// serde_json string escaping: `"` and `\` escaped, \b \f \n \r \t short forms, other
/// control characters as lowercase \u00xx, everything else (including `/` and non-ASCII)
/// raw UTF-8.
enum JSONText {
    static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case _ where scalar.value < 0x20: out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out + "\""
    }
}

enum RoutingMode { case editable, readonly, unsupported }

extension ModelPolicySnapshot {
    /// "unsupported": Core predates signed routing. "readonly": it cannot apply one now.
    var routingMode: RoutingMode {
        guard let routing_mutation else { return .unsupported }
        return routing_mutation == "device-signed-model-route-v1" && routing_sha256 != nil ? .editable : .readonly
    }
    /// Starting point for editing; defaults when there is no routing file.
    var routingDocument: RoutingDocument { routing?.document ?? RoutingDocument() }
    /// The routing Core runs now. nil when it cannot be known or Core failed closed
    /// (paid APIs off), so every paid permission then counts as new.
    var currentRouting: RoutingDocument? {
        if routing_unavailable_reason != nil { return nil }
        guard let routing else { return RoutingDocument() }  // no file: built-in order, paid APIs allowed
        return routing.document
    }
    var routingReasonText: String? { RoutingRules.reason(routing_unavailable_reason) }
}

/// Ways a routing change lets Jarvis spend more than the routing it replaces.
struct RoutingRelaxation: Equatable {
    var paidApi = false
    var paidFallback = false
    var paidModels = false
    var researchWebSearch = false
    var any: Bool { paidApi || paidFallback || paidModels || researchWebSearch }
    var prompt: String {
        var text = "Jarvis: replace model routing"
        if !any { text += "; allow model changes for five minutes" }
        if paidApi { text += "; allow paid APIs" }
        if paidFallback { text += "; allow paid fallback after subscription" }
        if paidModels { text += "; add paid API models" }
        if researchWebSearch { text += "; allow research web search" }
        return text
    }
}

/// Ports of desktop `modelRouting.ts` and `model_control.rs` routing rules.
enum RoutingRules {
    static let providers = ["anthropic-api", "openai-api", "deepseek-api", "xai-api", "zai-api",
                            "ollama", "ollama-cloud", "huggingface", "claude-cli", "codex-cli"]
    static let maxChain = 9
    /// Approval lifetime: covers the OS prompt (up to 120 s) and the post, within Core's 300 s maximum.
    static let approvalSeconds: Int64 = 240

    static func isSubscription(_ provider: String) -> Bool { provider == "claude-cli" || provider == "codex-cli" }
    /// Classified by provider id only, like Core's validator.
    static func isMetered(_ provider: String) -> Bool { !["ollama", "claude-cli", "codex-cli"].contains(provider) }

    static func meteredAfterSubscription(_ chain: [RouteEntry]) -> Bool {
        var afterSubscription = false
        for entry in chain {
            if afterSubscription && isMetered(entry.provider) { return true }
            afterSubscription = afterSubscription || isSubscription(entry.provider)
        }
        return false
    }

    /// First reason Core would refuse this document, or nil.
    static func issue(_ routing: RoutingDocument, discovered: [RouteEntry]) -> String? {
        if routing.version != 1 { return "Unsupported routing version" }
        let known = Set(discovered)
        for tier in RoutingTier.allCases {
            guard let route = routing.tiers[tier] else { continue }
            let name = tier.rawValue
            if route.chain.isEmpty || route.chain.count > maxChain { return "\(name): a routed tier needs 1 to \(maxChain) models" }
            var seen = Set<RouteEntry>()
            for entry in route.chain {
                if !providers.contains(entry.provider) { return "\(name): unknown provider \(entry.provider)" }
                let count = entry.model.unicodeScalars.count
                if count == 0 || count > 256 || entry.model.unicodeScalars.contains(where: { $0.properties.generalCategory == .control }) {
                    return "\(name): invalid model name"
                }
                if !seen.insert(entry).inserted { return "\(name): \(entry.id) is listed twice" }
                if !known.contains(entry) { return "\(name): \(entry.id) is not discovered on the Home Node" }
            }
            if meteredAfterSubscription(route.chain) && !route.metered_after_subscription {
                return "\(name): a paid API after a subscription needs explicit approval"
            }
        }
        return nil
    }

    /// Discovered routable models not yet in `chain`, enabled first.
    static func candidates(_ models: [ModelAccessEntry], excluding chain: [RouteEntry]) -> [ModelAccessEntry] {
        let used = Set(chain)
        return models.filter { providers.contains($0.provider) && !used.contains(RouteEntry($0.provider, $0.model)) }
            .sorted { ($1.enabled ? 1 : 0, $0.provider, $0.model) < ($0.enabled ? 1 : 0, $1.provider, $1.model) }
    }

    /// `chain` with entry `index` moved by `delta`; unchanged when out of range.
    static func moved(_ chain: [RouteEntry], _ index: Int, _ delta: Int) -> [RouteEntry] {
        let target = index + delta
        guard chain.indices.contains(index), chain.indices.contains(target) else { return chain }
        var next = chain
        next.swapAt(index, target)
        return next
    }

    /// Whether `next` lets Jarvis spend more, or use web search, than `current`.
    /// nil `current` (unknown or failed closed) counts every permission as new.
    static func relaxation(current: RoutingDocument?, next: RoutingDocument) -> RoutingRelaxation {
        // Web search sends research questions to a provider tool whatever the paid API
        // setting, so switching it on always needs a fresh prompt.
        let research = next.researchWebSearch && !(current?.researchWebSearch ?? false)
        // No metered backend can run at all.
        if next.paidApi == .off { return RoutingRelaxation(researchWebSearch: research) }
        var result = RoutingRelaxation(paidApi: current.map { $0.paidApi == .off } ?? true, researchWebSearch: research)
        for tier in RoutingTier.allCases {
            guard let route = next.tiers[tier] else {
                // Resetting a customised tier to the built-in order may put paid
                // APIs back after the subscriptions, so it always needs a prompt.
                if current?.tiers[tier] != nil { result.paidFallback = true; result.paidModels = true }
                continue
            }
            // A built-in (absent) tier counts as no paid fallback and no pinned paid models.
            let before = current?.tiers[tier]
            if route.metered_after_subscription && !(before?.metered_after_subscription ?? false) { result.paidFallback = true }
            if route.chain.contains(where: { isMetered($0.provider) && !(before?.chain.contains($0) ?? false) }) { result.paidModels = true }
        }
        return result
    }

    static func refused(status: Int) -> String {
        // A 503 can mean the broker is down or that Core failed closed; the refreshed
        // `routing_unavailable_reason` tells which.
        switch status {
        case 409: "Routing changed or names an undiscovered model; refresh and try again"
        case 503: "Routing not applied or not verified; refresh to see the current state"
        default: "Routing change refused; refresh and try again"
        }
    }

    private static let failClosed = " Until it is fixed, Core uses the built-in order without paid APIs."
    /// Owner-facing copy for `routing_unavailable_reason`; unknown codes stay visible.
    static func reason(_ code: String?) -> String? {
        guard let code else { return nil }
        switch code {
        case "routing_invalid": return "The routing file on the Home Node is invalid." + failClosed
        case "routing_unsafe": return "The routing file on the Home Node has unsafe ownership or permissions." + failClosed
        case "routing_unreadable": return "Core cannot read the routing file." + failClosed
        case "routing_too_large": return "The routing file on the Home Node is too large." + failClosed
        case "routing_unavailable": return "Core cannot use the routing file." + failClosed
        case "routing_activation_unverified": return "A signed routing change could not be verified. Paid APIs stay off until the owner verifies routing and restarts Core."
        case "routing_reload_required": return "The routing file on disk differs from what Core runs; restart Core to apply it."
        default: return "Routing unavailable (\(code))." + failClosed
        }
    }
}

/// Approval for `model.routing_set`: replace the whole routing document. Core and the
/// broker validate it; this type only produces the exact bytes to sign.
struct ModelRoutingApproval {
    let request: UUID
    let nonce: Data
    let user: UUID
    let device: UUID
    let issued: Int64
    let expires: Int64
    let routing: RoutingDocument
    /// `routing_sha256` from `GET /v1/system/models`.
    let hash: String

    /// Compact bytes of client-core's `RoutingOperation`; the hash is hex-checked in `message()`.
    func canonicalPayload() -> Data {
        Data("{\"action\":\"model_routing_set\",\"routing\":\(routing.canonicalJSON()),\"expected_routing_sha256\":\(JSONText.quote(hash))}".utf8)
    }

    func message() throws -> Data {
        guard routing.version == 1, let state = Data(hexEncoded: hash) else { throw JarvisAPIError.invalidResponse }
        return try privilegedApprovalMessage(action: "model.routing_set", payload: canonicalPayload(), request: request,
            nonce: nonce, user: user, device: device, issued: issued, expires: expires, state: state)
    }

    func signed(_ signature: String) throws -> SignedModelRouting {
        _ = try message()
        guard Data(hexEncoded: signature)?.count == 64 else { throw JarvisAPIError.invalidResponse }
        let format = ISO8601DateFormatter()
        return SignedModelRouting(request_id: request, nonce_hex: nonce.hexEncodedString(), user_id: user, device_id: device,
            issued_at: format.string(from: Date(timeIntervalSince1970: TimeInterval(issued))),
            expires_at: format.string(from: Date(timeIntervalSince1970: TimeInterval(expires))),
            operation: .init(routing: routing, expected_routing_sha256: hash), signature_hex: signature)
    }
}

struct SignedModelRouting: Encodable {
    struct Operation: Encodable {
        let action = "model_routing_set"
        let routing: RoutingDocument
        let expected_routing_sha256: String
    }
    let request_id: UUID
    let nonce_hex: String
    let user_id: UUID
    let device_id: UUID
    let issued_at: String
    let expires_at: String
    let operation: Operation
    let signature_hex: String
}
