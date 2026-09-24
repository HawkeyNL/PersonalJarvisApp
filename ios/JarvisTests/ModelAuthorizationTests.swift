import XCTest
@testable import Jarvis

final class ModelAuthorizationTests: XCTestCase {
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
