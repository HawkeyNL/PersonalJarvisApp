import CryptoKit
import XCTest
@testable import Jarvis

final class ModelAuthorizationTests: XCTestCase {
    func testPaginationReachesEveryPageAndClampsAfterFiltering() {
        let models = (0..<103).map { ModelAccessEntry(provider: "openai-api", model: String(format: "fixture-%03d", $0), enabled: $0.isMultiple(of: 2)) }
        var seen: [String] = []
        for page in 0..<5 {
            let result = ModelCatalogPage(models: models, search: "", provider: "", access: "all", pricing: "all", requestedPage: page)
            XCTAssertEqual(result.index, page)
            XCTAssertEqual(result.count, 5)
            XCTAssertLessThanOrEqual(result.entries.count, 25)
            seen += result.entries.map(\.id)
        }
        XCTAssertEqual(Set(seen).count, 103)
        let filtered = ModelCatalogPage(models: models, search: "fixture-100", provider: "openai-api", access: "enabled", pricing: "unknown", requestedPage: 4)
        XCTAssertEqual(filtered.index, 0)
        XCTAssertEqual(filtered.total, 1)
        let empty = ModelCatalogPage(models: models, search: "", provider: "huggingface", access: "all", pricing: "all", requestedPage: -1)
        XCTAssertEqual(empty.index, 0)
        XCTAssertEqual(empty.count, 1)
        XCTAssertTrue(empty.entries.isEmpty)
    }

    func testLegacyAndPricedModelDecoding() throws {
        let legacy = try JSONDecoder().decode(ModelAccessEntry.self, from: Data(#"{"provider":"openai-api","model":"fixture","enabled":false}"#.utf8))
        XCTAssertFalse(legacy.hasPrice)
        let priced = try JSONDecoder().decode(ModelAccessEntry.self, from: Data(#"{"provider":"openai-api","model":"fixture","enabled":false,"price_status":"conservative","input_per_million_usd":1.25,"output_per_million_usd":3,"cache_read_per_million_usd":0.25}"#.utf8))
        XCTAssertTrue(priced.hasPrice)
        XCTAssertEqual(priced.input_per_million_usd, 1.25)
        XCTAssertEqual(priced.cache_read_per_million_usd, 0.25)
        let result = ModelCatalogPage(models: [legacy, priced], search: "", provider: "", access: "disabled", pricing: "priced", requestedPage: 0)
        XCTAssertEqual(result.total, 1)
        var invalid = priced
        invalid.input_per_million_usd = -1
        XCTAssertFalse(invalid.hasPrice)
    }

    func testControllerReusesOnlyOSAuthorizationNotSignedRequests() async throws {
        let store = FixtureSecureStorage()
        let credentials = SecureCredentialStore(keychain: store)
        let identity = DeviceIdentityStore(keychain: store)
        _ = try await identity.publicKeyHex()
        try await credentials.save(deviceId: ModelLeaseProtocol.device)
        try await credentials.save(session: SecureSession(token: "fixture-session-not-a-secret", expiresAt: Int64(Date().timeIntervalSince1970) + 3600))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ModelLeaseProtocol.self]
        let transport = URLSession(configuration: configuration)
        defer { transport.invalidateAndCancel() }
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: transport)
        let prompts = ModelPromptCounter()
        let auth = AuthService(api: api, identity: identity, credentials: credentials,
                               authenticateOwner: { _ in await prompts.authenticate() })
        ModelLeaseProtocol.reset()
        let entry = ModelAccessEntry(provider: "huggingface", model: "org/fixture", enabled: false)
        try await auth.setModelEnabled(entry, policyHash: ModelLeaseProtocol.hash)
        try await auth.setModelEnabled(entry, policyHash: ModelLeaseProtocol.hash)
        let initialCount = await prompts.count
        XCTAssertEqual(initialCount, 1)
        auth.modelAuthorization.invalidate()
        try await auth.setModelEnabled(entry, policyHash: ModelLeaseProtocol.hash)
        let afterLock = await prompts.count
        XCTAssertEqual(afterLock, 2)
        await api.configure(baseURL: URL(string: "https://home.example.org")!)
        try await auth.setModelEnabled(entry, policyHash: ModelLeaseProtocol.hash)
        let afterOrigin = await prompts.count
        XCTAssertEqual(afterOrigin, 3)
        let requests = ModelLeaseProtocol.requests()
        XCTAssertEqual(requests.count, 4)
        for field in ["request_id", "nonce_hex", "signature_hex"] {
            XCTAssertEqual(Set(requests.compactMap { $0[field] as? String }).count, 4)
        }
        XCTAssertTrue(requests.allSatisfy { $0["token"] == nil && $0["password"] == nil })
        auth.modelAuthorization.invalidate()
        await prompts.deny()
        for _ in 0..<2 {
            do {
                try await auth.setModelEnabled(entry, policyHash: ModelLeaseProtocol.hash)
                XCTFail("Denied OS authentication must not sign or send")
            } catch { }
        }
        let afterDenial = await prompts.count
        XCTAssertEqual(afterDenial, 5)
        XCTAssertEqual(ModelLeaseProtocol.requests().count, 4)
    }

    func testFixedExpiryBindingAndClockChanges() {
        let lease = ModelAuthorization()
        let ticket = lease.ticket()
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 10, wall: 100))
        XCTAssertTrue(lease.remember("session", ticket: ticket, uptime: 10, wall: 100))
        XCTAssertTrue(lease.valid("session", ticket: ticket, uptime: 309, wall: 399))
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 310, wall: 400))
        XCTAssertFalse(lease.valid("other", ticket: ticket, uptime: 11, wall: 101))
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 11, wall: 99))
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 11, wall: 401))
    }

    func testLockDuringPromptCannotRestoreLease() {
        let lease = ModelAuthorization()
        let ticket = lease.ticket()
        lease.invalidate()
        XCTAssertFalse(lease.accepts(ticket))
        XCTAssertFalse(lease.remember("session", ticket: ticket))
        XCTAssertFalse(lease.valid("session", ticket: lease.ticket()))
    }
}

private actor ModelPromptCounter {
    private(set) var count = 0
    private var accepted = true
    func deny() { accepted = false }
    func authenticate() -> LocalUnlockResult { count += 1; return accepted ? .unlocked : .cancelled }
}

private final class ModelLeaseProtocol: URLProtocol {
    static let device = UUID(uuidString: "04040404-0404-0404-0404-040404040404")!
    static let hash = String(repeating: "05", count: 32)
    private static let lock = NSLock()
    private static var captured: [[String: Any]] = []
    static func reset() { lock.withLock { captured = [] } }
    static func requests() -> [[String: Any]] { lock.withLock { captured } }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let object: [String: Any]
        if request.url?.path == "/v1/system/models" {
            object = ["mutation": "device-signed-model-toggle-v1", "policy_sha256": Self.hash,
                      "user_id": Self.device.uuidString, "device_id": Self.device.uuidString,
                      "server_time": Int64(Date().timeIntervalSince1970),
                      "models": [["provider": "huggingface", "model": "org/fixture", "enabled": false]]]
        } else if request.url?.path == "/v1/system/config/privileged" {
            var data = request.httpBody ?? Data()
            if let stream = request.httpBodyStream {
                stream.open(); defer { stream.close() }
                var buffer = [UInt8](repeating: 0, count: 4096)
                while data.count < 16384 {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }
                    data.append(contentsOf: buffer.prefix(count))
                }
            }
            if let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                Self.lock.withLock { Self.captured.append(body) }
            }
            object = ["status": "active"]
        } else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL)); return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: object))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

final class ModelRoutingTests: XCTestCase {
    // Core's fixed vector (crates/client-core/src/model_control.rs).
    private let payload = #"{"action":"model_routing_set","routing":{"version":1,"paid_api":"off","tiers":{"cheap":{"chain":[{"provider":"huggingface","model":"org/modèl"},{"provider":"claude-cli","model":"claude-haiku-4-5"}],"metered_after_subscription":false},"hard":{"chain":[{"provider":"claude-cli","model":"claude-opus-5"},{"provider":"anthropic-api","model":"claude-opus-5"}],"metered_after_subscription":true}}},"expected_routing_sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}"#
    private let payloadSHA = "a9c73476991bb884fbaf43378882c25f4403a8ce3daa44d1012221ce15e30a59"
    private let stateHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private func doc(_ json: String) throws -> RoutingDocument { try JSONDecoder().decode(RoutingDocument.self, from: Data(json.utf8)) }
    private func e(_ provider: String, _ model: String) -> RouteEntry { RouteEntry(provider, model) }
    private func tier(_ chain: [RouteEntry], metered: Bool = false) -> RoutingDocument {
        var routing = RoutingDocument()
        routing.tiers[.default] = TierRoute(chain: chain, metered_after_subscription: metered)
        return routing
    }
    private func vector() throws -> RoutingDocument {
        // Loose input: other key order, a null tier, a tier without metered_after_subscription.
        try doc(#"{"tiers":{"hard":{"metered_after_subscription":true,"chain":[{"model":"claude-opus-5","provider":"claude-cli"},{"provider":"anthropic-api","model":"claude-opus-5"}]},"default":null,"cheap":{"chain":[{"provider":"huggingface","model":"org/modèl"},{"provider":"claude-cli","model":"claude-haiku-4-5"}]}},"paid_api":"off","version":1}"#)
    }
    private func approval(_ routing: RoutingDocument, issued: Int64 = 1, expires: Int64 = 121, hash: String? = nil) -> ModelRoutingApproval {
        ModelRoutingApproval(request: UUID(uuid: (1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
            nonce: Data(repeating: 2, count: 32), user: UUID(uuid: (3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3)),
            device: UUID(uuid: (4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4)),
            issued: issued, expires: expires, routing: routing, hash: hash ?? stateHash)
    }

    func testCanonicalPayloadAndMessageMatchCoreFixedVector() throws {
        let a = approval(try vector())
        XCTAssertEqual(String(decoding: a.canonicalPayload(), as: UTF8.self), payload)
        XCTAssertEqual(Data(SHA256.hash(data: a.canonicalPayload())).hexEncodedString(), payloadSHA)
        let message = try a.message()
        let action = Data("model.routing_set".utf8)
        XCTAssertEqual(message.prefix(28), Data("jarvis-privileged-config-v1\0".utf8))
        XCTAssertEqual(message[28..<30], Data([0, 17]))
        XCTAssertEqual(message[30..<(30 + action.count)], action)
        XCTAssertEqual(message[(30 + action.count)..<(30 + action.count + 32)].hexEncodedString(), payloadSHA)
        let tail = 30 + action.count + 32
        XCTAssertEqual(message[tail..<(tail + 16)], Data(repeating: 1, count: 16))
        XCTAssertEqual(message[(tail + 16)..<(tail + 48)], Data(repeating: 2, count: 32))
        XCTAssertEqual(message[(tail + 48)..<(tail + 64)], Data(repeating: 3, count: 16))
        XCTAssertEqual(message[(tail + 64)..<(tail + 80)], Data(repeating: 4, count: 16))
        XCTAssertEqual(message[(tail + 80)..<(tail + 96)].hexEncodedString(), "00000000000000010000000000000079")
        XCTAssertEqual(message.suffix(32).hexEncodedString(), stateHash)
        XCTAssertEqual(message.count, tail + 96 + 32)
        // The signed request carries the same operation.
        let body = try JSONSerialization.jsonObject(with: JSONEncoder().encode(a.signed(String(repeating: "00", count: 64)))) as! [String: Any]
        XCTAssertEqual((body["operation"] as! [String: Any])["action"] as? String, "model_routing_set")
        XCTAssertEqual(body["issued_at"] as? String, "1970-01-01T00:00:01Z")
    }

    func testCanonicalBytesTrackEveryChangeAndEscapeLikeSerdeJson() throws {
        let routing = try vector()
        let message = try approval(routing).message()
        var changed = routing; changed.paidApi = .allowed
        XCTAssertNotEqual(try approval(changed).message(), message)
        XCTAssertNotEqual(try approval(routing, hash: String(repeating: "00", count: 32)).message(), message)
        changed = routing; changed.researchWebSearch = true
        XCTAssertTrue(String(decoding: approval(changed).canonicalPayload(), as: UTF8.self)
            .hasSuffix(#"}},"research_web_search":"on"},"expected_routing_sha256":"\#(stateHash)"}"#))
        XCTAssertNotEqual(try approval(changed).message(), message)
        // Research written last and only when on; absent tiers omitted; paid_api always written.
        XCTAssertEqual(try doc(#"{"version":1,"research_web_search":"off"}"#).canonicalJSON(), #"{"version":1,"paid_api":"allowed","tiers":{}}"#)
        XCTAssertEqual(try doc(#"{"version":1,"paid_api":"off","research_web_search":"on"}"#).canonicalJSON(),
                       #"{"version":1,"paid_api":"off","tiers":{},"research_web_search":"on"}"#)
        XCTAssertEqual(JSONText.quote("a\"b\\c/d\u{08}\u{0C}\n\r\t\u{01}\u{1f}é😀\u{7f}"), "\"a\\\"b\\\\c/d\\b\\f\\n\\r\\t\\u0001\\u001fé😀\u{7f}\"")
        var bad = routing; bad.version = 2
        XCTAssertThrowsError(try approval(bad).message())
        XCTAssertThrowsError(try doc(#"{"version":1,"extra":1}"#))
        XCTAssertThrowsError(try doc(#"{"version":1,"tiers":{"other":null}}"#))
        XCTAssertThrowsError(try doc(#"{"version":1,"tiers":{"hard":{"chain":[],"x":1}}}"#))
    }

    func testApprovalLifetimeStaysWithinCoreLimits() throws {
        // authenticateOwner can wait up to 120 s; Core accepts at most 300 s.
        XCTAssertGreaterThan(RoutingRules.approvalSeconds, 120 + 60)
        XCTAssertNoThrow(try approval(RoutingDocument(), issued: 1, expires: 1 + RoutingRules.approvalSeconds).message())
        XCTAssertNoThrow(try approval(RoutingDocument(), issued: 1, expires: 301).message())
        XCTAssertThrowsError(try approval(RoutingDocument(), issued: 1, expires: 302).message())
        XCTAssertThrowsError(try approval(RoutingDocument(), issued: 1, expires: 1).message())
    }

    func testRelaxationClassification() throws {
        let sub = e("claude-cli", "claude-opus-5"), paid = e("anthropic-api", "claude-opus-5"), local = e("ollama", "llama3.2")
        func routed(_ paidApi: PaidApiSetting, _ chain: [RouteEntry], _ fallback: Bool) -> RoutingDocument {
            var routing = tier(chain, metered: fallback); routing.paidApi = paidApi; return routing
        }
        var off = RoutingDocument(); off.paidApi = .off
        let allowed = RoutingDocument()
        let withPaid = routed(.allowed, [sub, paid], true)
        func relaxed(_ api: Bool, _ fallback: Bool, _ models: Bool) -> RoutingRelaxation {
            RoutingRelaxation(paidApi: api, paidFallback: fallback, paidModels: models, researchWebSearch: false)
        }
        XCTAssertEqual(RoutingRules.relaxation(current: off, next: allowed), relaxed(true, false, false))
        XCTAssertEqual(RoutingRules.relaxation(current: nil, next: allowed), relaxed(true, false, false))
        XCTAssertEqual(RoutingRules.relaxation(current: routed(.allowed, [paid, sub], false), next: withPaid), relaxed(false, true, false))
        XCTAssertEqual(RoutingRules.relaxation(current: routed(.allowed, [sub], false), next: routed(.allowed, [sub, local, paid], true)), relaxed(false, true, true))
        XCTAssertEqual(RoutingRules.relaxation(current: allowed, next: routed(.allowed, [paid], false)), relaxed(false, false, true))
        let onlySub = routed(.allowed, [sub], false)
        for (current, next) in [(withPaid, off), (withPaid, onlySub), (withPaid, withPaid), (withPaid, routed(.allowed, [paid, sub], true)),
                                (allowed, routed(.allowed, [sub, local], false))] {
            XCTAssertFalse(RoutingRules.relaxation(current: current, next: next).any)
        }
        XCTAssertFalse(RoutingRules.relaxation(current: nil, next: off).any)
        XCTAssertEqual(relaxed(false, false, false).prompt, "Jarvis: replace model routing; allow model changes for five minutes")
        let prompt = relaxed(true, true, true).prompt
        XCTAssertTrue(prompt.contains("allow paid APIs") && prompt.contains("allow paid fallback after subscription"))
        XCTAssertFalse(prompt.contains("five minutes"))
    }

    func testResettingATierToTheBuiltInOrderNeedsAFreshPrompt() throws {
        let pinned = try doc(#"{"version":1,"tiers":{"hard":{"chain":[{"provider":"claude-cli","model":"claude-opus-5"}],"metered_after_subscription":false}}}"#)
        let builtIn = RoutingDocument()
        let relaxed = RoutingRules.relaxation(current: pinned, next: builtIn)
        XCTAssertTrue(relaxed.paidFallback && relaxed.paidModels)
        XCTAssertFalse(RoutingRules.relaxation(current: builtIn, next: builtIn).any)
        var off = RoutingDocument(); off.paidApi = .off
        XCTAssertFalse(RoutingRules.relaxation(current: pinned, next: off).paidFallback)
    }

    func testSwitchingResearchWebSearchOnAlwaysNeedsAFreshPrompt() throws {
        func research(_ paidApi: String, _ on: String) throws -> RoutingDocument { try doc(#"{"version":1,"paid_api":"\#(paidApi)","research_web_search":"\#(on)"}"#) }
        let onOnly = RoutingRelaxation(researchWebSearch: true)
        XCTAssertEqual(RoutingRules.relaxation(current: try research("off", "off"), next: try research("off", "on")), onOnly)
        XCTAssertEqual(RoutingRules.relaxation(current: nil, next: try research("off", "on")), onOnly)
        XCTAssertTrue(onOnly.prompt.hasSuffix("; allow research web search"))
        XCTAssertFalse(RoutingRules.relaxation(current: try research("off", "on"), next: try research("off", "on")).any)
        XCTAssertFalse(RoutingRules.relaxation(current: try research("off", "on"), next: try research("off", "off")).any)
    }

    func testCurrentRoutingFailsClosedAndModeFollowsSnapshot() throws {
        func snapshot(_ extra: String) throws -> ModelPolicySnapshot {
            let id = "01010101-0101-0101-0101-010101010101"
            return try JSONDecoder().decode(ModelPolicySnapshot.self, from: Data(#"{"models":[],"mutation":"unavailable","user_id":"\#(id)","device_id":"\#(id)","server_time":100\#(extra)}"#.utf8))
        }
        XCTAssertEqual(try snapshot("").routingMode, .unsupported)
        XCTAssertEqual(try snapshot(#","routing_mutation":"unavailable","routing_sha256":"ab""#).routingMode, .readonly)
        XCTAssertEqual(try snapshot(#","routing_mutation":"device-signed-model-route-v1","routing_sha256":null"#).routingMode, .readonly)
        let editable = #","routing_mutation":"device-signed-model-route-v1","routing_sha256":"ab""#
        XCTAssertEqual(try snapshot(editable).routingMode, .editable)
        // No file: built-in order, paid APIs allowed.
        XCTAssertEqual(try snapshot(editable).currentRouting?.paidApi, .allowed)
        XCTAssertEqual(try snapshot(editable + #","routing":null"#).currentRouting?.paidApi, .allowed)
        XCTAssertEqual(try snapshot(editable + #","routing":{"version":1,"paid_api":"off"}"#).currentRouting?.paidApi, .off)
        XCTAssertNil(try snapshot(editable + #","routing":{"version":1,"future":true}"#).currentRouting)
        XCTAssertNil(try snapshot(editable + #","routing":{"version":1},"routing_unavailable_reason":"routing_invalid""#).currentRouting)
    }

    func testValidationMirrorsCoreRules() throws {
        let discovered = [e("huggingface", "org/modèl"), e("claude-cli", "claude-haiku-4-5"), e("claude-cli", "claude-opus-5"),
                          e("anthropic-api", "claude-opus-5"), e("ollama", "llama3.2"), e("codex-cli", "gpt-6-luna"), e("openai-api", "gpt-6-luna")]
        XCTAssertNil(RoutingRules.issue(RoutingDocument(), discovered: []))
        for ok in [tier([e("claude-cli", "claude-opus-5"), e("anthropic-api", "claude-opus-5")], metered: true),
                   tier([e("anthropic-api", "claude-opus-5"), e("claude-cli", "claude-opus-5")]),
                   tier([e("claude-cli", "claude-opus-5"), e("ollama", "llama3.2")])] {
            XCTAssertNil(RoutingRules.issue(ok, discovered: discovered))
        }
        let many = (0..<10).map { e("ollama", "m\($0)") }
        XCTAssertNil(RoutingRules.issue(tier(Array(many.prefix(9))), discovered: many))
        var wrongVersion = RoutingDocument(); wrongVersion.version = 2
        let bad: [RoutingDocument] = [
            tier([]), tier(many), tier([e("jev", "a")]), tier([e("Claude-CLI", "claude-opus-5")]), tier([e("ollama", "")]),
            tier([e("ollama", String(repeating: "m", count: 257))]), tier([e("ollama", "a\nb")]),
            tier([e("ollama", "llama3.2"), e("ollama", "llama3.2")]),
            tier([e("claude-cli", "claude-opus-5"), e("anthropic-api", "claude-opus-5")]),
            tier([e("codex-cli", "gpt-6-luna"), e("openai-api", "gpt-6-luna")]),
            tier([e("claude-cli", "claude-opus-5"), e("ollama", "llama3.2"), e("huggingface", "org/modèl")]),
            tier([e("ollama", "not-discovered")]), wrongVersion,
        ]
        for routing in bad { XCTAssertNotNil(RoutingRules.issue(routing, discovered: discovered + many), "\(routing)") }
    }

    func testChainHelpersAndReasons() {
        let chain = [e("a", "1"), e("b", "2"), e("c", "3")]
        XCTAssertEqual(RoutingRules.moved(chain, 2, -1).map(\.provider), ["a", "c", "b"])
        XCTAssertEqual(RoutingRules.moved(chain, 0, -1), chain)
        XCTAssertEqual(RoutingRules.moved(chain, 2, 1), chain)
        XCTAssertFalse(RoutingRules.meteredAfterSubscription([e("anthropic-api", "a"), e("claude-cli", "a")]))
        XCTAssertFalse(RoutingRules.meteredAfterSubscription([e("claude-cli", "a"), e("ollama", "b")]))
        XCTAssertTrue(RoutingRules.meteredAfterSubscription([e("claude-cli", "a"), e("ollama", "b"), e("zai-api", "c")]))
        let models = [ModelAccessEntry(provider: "ollama", model: "z", enabled: false), ModelAccessEntry(provider: "claude-cli", model: "claude-opus-5", enabled: true),
                      ModelAccessEntry(provider: "jev", model: "x", enabled: true), ModelAccessEntry(provider: "anthropic-api", model: "claude-opus-5", enabled: false)]
        XCTAssertEqual(RoutingRules.candidates(models, excluding: [e("claude-cli", "claude-opus-5")]).map(\.provider), ["anthropic-api", "ollama"])
        XCTAssertEqual(RoutingRules.candidates(models, excluding: []).map(\.provider), ["claude-cli", "anthropic-api", "ollama"])
        XCTAssertNil(RoutingRules.reason(nil))
        XCTAssertTrue(RoutingRules.reason("routing_invalid")!.contains("without paid APIs"))
        XCTAssertTrue(RoutingRules.reason("routing_activation_unverified")!.contains("Paid APIs stay off"))
        XCTAssertFalse(RoutingRules.reason("routing_reload_required")!.contains("without paid APIs"))
        XCTAssertTrue(RoutingRules.reason("routing_new_code")!.contains("routing_new_code"))
        XCTAssertTrue(RoutingRules.refused(status: 409).contains("undiscovered"))
        XCTAssertTrue(RoutingRules.refused(status: 503).contains("not verified"))
    }
}
