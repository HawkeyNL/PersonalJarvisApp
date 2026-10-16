import XCTest
import Network
import SwiftUI
import UIKit
import CryptoKit
import LocalAuthentication
@testable import Jarvis

final class ModelApprovalTests: XCTestCase {
    func testCanonicalPayloadMatchesSharedRustVector() throws {
        let approval = ModelToggleApproval(request: UUID(uuidString: "01010101-0101-0101-0101-010101010101")!,
            nonce: Data(repeating: 2, count: 32), user: UUID(uuidString: "03030303-0303-0303-0303-030303030303")!,
            device: UUID(uuidString: "04040404-0404-0404-0404-040404040404")!, issued: 1, expires: 121,
            provider: "huggingface", model: "org/fixture", enabled: true, hash: String(repeating: "05", count: 32))
        XCTAssertEqual(Data(SHA256.hash(data: try approval.operationBytes())).hexEncodedString(), "db216127795b1015f2c95877514c1e8ecb492775276bbb73412b10a7073c98c0")
        var expected = Data("jarvis-privileged-config-v1\0".utf8)
        expected.append(Data([0,17])); expected.append(Data("model.set_enabled".utf8))
        expected.append(Data(hexEncoded: "db216127795b1015f2c95877514c1e8ecb492775276bbb73412b10a7073c98c0")!)
        expected.append(Data(repeating: 1, count: 16)); expected.append(Data(repeating: 2, count: 32))
        expected.append(Data(repeating: 3, count: 16)); expected.append(Data(repeating: 4, count: 16))
        expected.append(Data(hexEncoded: "00000000000000010000000000000079")!)
        expected.append(Data(repeating: 5, count: 32))
        XCTAssertEqual(try approval.message(), expected)
        XCTAssertThrowsError(try approval.signed("invalid"))
    }

    func testMissingKeyCannotBeCreatedForModelApproval() async throws {
        let storage = FixtureSecureStorage()
        let identity = DeviceIdentityStore(keychain: storage)
        do { _ = try await identity.existingPublicKeyHex(); XCTFail("Must not create a replacement identity") }
        catch DeviceIdentityError.missingStoredKey { }
        XCTAssertFalse(try storage.containsProtected(account: "device-ed25519-seed-v2"))
        XCTAssertNil(try storage.read(account: "device-ed25519-public-v2"))
    }
}

final class LockLifecycleTests: XCTestCase {
    func testFaceIDInterruptionDoesNotInvalidateUnlock() {
        XCTAssertEqual(lockTransition(for: .inactive), .privacyOnly)
        XCTAssertEqual(lockTransition(for: .active), .unlock)
    }

    func testRealBackgroundRequiresAuthenticationOnReturn() {
        XCTAssertEqual(lockTransition(for: .background), .lock)
        XCTAssertEqual(lockTransition(for: .active), .unlock)
    }
}

final class CredentialBuildIdentityTests: XCTestCase {
    func testAcceptanceRequiresSeparateIdentity() throws {
        let isolated = CredentialBuildIdentity.acceptanceIdentifier
        XCTAssertNoThrow(try CredentialBuildIdentity.validate(bundleIdentifier: isolated, acceptance: true))
        XCTAssertThrowsError(try CredentialBuildIdentity.validate(bundleIdentifier: "com.hawkeynl.jarvis", acceptance: true))
        XCTAssertThrowsError(try CredentialBuildIdentity.validate(bundleIdentifier: nil, acceptance: true))
        XCTAssertThrowsError(try CredentialBuildIdentity.validate(bundleIdentifier: isolated, acceptance: false))
    }

    func testProductionResigningKeepsExistingCredentialService() throws {
        XCTAssertNoThrow(try CredentialBuildIdentity.validate(bundleIdentifier: "com.example.resigned", acceptance: false))
        XCTAssertEqual(CredentialBuildIdentity.service, CredentialBuildIdentity.acceptance
            ? CredentialBuildIdentity.acceptanceIdentifier : "com.hawkeynl.jarvis")
    }
}

final class RealtimeEventDeliveryTests: XCTestCase {
    @MainActor
    func testDeletedSelectedConversationIsNotATransientFailure() async throws {
        let missing: String? = try await RealtimeEventDelivery.selectedHistory {
            throw JarvisAPIError.rejected(status: 404, message: nil)
        }
        XCTAssertNil(missing)
        let present = try await RealtimeEventDelivery.selectedHistory { "canonical" }
        XCTAssertEqual(present, "canonical")
        do {
            let _: String? = try await RealtimeEventDelivery.selectedHistory {
                throw JarvisAPIError.rejected(status: 503, message: nil)
            }
            XCTFail("Temporary failure must reconnect")
        } catch { XCTAssertEqual(error as? JarvisAPIError, .rejected(status: 503, message: nil)) }
    }

    private func event(epoch: UUID, sequence: UInt64, type: String, protocolVersion: Int = 1) throws -> RealtimeEvent {
        let bytes = try JSONSerialization.data(withJSONObject: [
            "protocol": protocolVersion, "epoch": epoch.uuidString,
            "sequence": sequence, "event_id": UUID().uuidString,
            "type": type, "payload": [:]
        ])
        return try JSONDecoder().decode(RealtimeEvent.self, from: bytes)
    }

    @MainActor
    func testFailedRecoveryPropagatesAndDoesNotConsumeReadyEvent() async throws {
        let delivery = RealtimeEventDelivery()
        let ready = try event(epoch: UUID(), sequence: 1, type: "connection.ready")
        do {
            try await delivery.deliver(ready) { _ in throw URLError(.timedOut) }
            XCTFail("Recovery failure must escape to reconnect")
        } catch { XCTAssertEqual((error as? URLError)?.code, .timedOut) }
        var delivered = 0
        try await delivery.deliver(ready) { _ in delivered += 1 }
        try await delivery.deliver(ready) { _ in delivered += 1 }
        XCTAssertEqual(delivered, 1)
    }

    @MainActor
    func testEpochChangeRequiresReadyAndCancellationPropagates() async throws {
        let delivery = RealtimeEventDelivery()
        let epoch = UUID()
        try await delivery.deliver(event(epoch: epoch, sequence: 10, type: "connection.ready")) { _ in }
        for bad in [
            try event(epoch: UUID(), sequence: 11, type: "assistant.delta"),
            try event(epoch: epoch, sequence: 11, type: "connection.ready", protocolVersion: 2)
        ] {
            do {
                try await delivery.deliver(bad) { _ in XCTFail("Invalid event delivered") }
                XCTFail("Invalid envelope accepted")
            } catch { }
        }
        let restarted = try event(epoch: UUID(), sequence: 1, type: "connection.ready")
        do {
            try await delivery.deliver(restarted) { _ in throw CancellationError() }
            XCTFail("Cancellation swallowed")
        } catch { XCTAssertTrue(error is CancellationError) }
        var delivered = false
        try await delivery.deliver(restarted) { _ in delivered = true }
        XCTAssertTrue(delivered)
    }
}

final class RealtimeHeartbeatTests: XCTestCase {
    @MainActor
    func testMissingPongClosesWithoutEnqueuingMorePings() {
        let heartbeat = RealtimeHeartbeat()
        XCTAssertEqual(heartbeat.tick(now: 30), .ping)
        XCTAssertEqual(heartbeat.tick(now: 59), .wait)
        XCTAssertEqual(heartbeat.tick(now: 60), .disconnect)
        heartbeat.pong() // A late completion cannot resurrect a dead connection.
        XCTAssertEqual(heartbeat.tick(now: 90), .wait)
    }

    @MainActor
    func testHealthyConnectionAndCancellationRemainBounded() {
        let heartbeat = RealtimeHeartbeat()
        for second in stride(from: 30, through: 3000, by: 30) {
            XCTAssertEqual(heartbeat.tick(now: Double(second)), .ping)
            heartbeat.pong()
        }
        heartbeat.stop()
        heartbeat.pong()
        XCTAssertEqual(heartbeat.tick(now: 3030), .wait)
        XCTAssertEqual(RealtimeHeartbeat().tick(now: 3060), .ping)
    }

    @MainActor
    func testResumeAfterLongSuspensionExpiresOutstandingProbe() {
        let heartbeat = RealtimeHeartbeat()
        XCTAssertEqual(heartbeat.tick(now: 30), .ping)
        XCTAssertEqual(heartbeat.tick(now: 3600), .disconnect)
    }
}

// Unsigned CI cannot access Apple's entitled Keychain. Exercise the same
// identity/auth logic with fixture-only storage; production has no fallback.
final class FixtureSecureStorage: SecureValueStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Data] = [:]
    private var protected: [String: Data] = [:]
    private var evaluated: [LAContext] = []
    /// Forces protected reads to fail with this Keychain status.
    var protectedReadStatus: OSStatus?
    func read(account: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }; return values[account]
    }
    func save(_ data: Data, account: String) throws {
        lock.lock(); defer { lock.unlock() }; values[account] = data
    }
    func delete(account: String) throws {
        lock.lock(); defer { lock.unlock() }; values.removeValue(forKey: account); protected.removeValue(forKey: account)
    }
    func saveProtected(_ data: Data, account: String) throws {
        lock.lock(); defer { lock.unlock() }; protected[account] = data
    }
    /// Like the Keychain item: only a context Face ID / Touch ID evaluated reads it.
    func readProtected(account: String, context: LAContext) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard let data = protected[account] else { return nil }
        if let status = protectedReadStatus { throw KeychainError.status(status) }
        // A context that does not satisfy the item fails rather than prompting again.
        guard evaluated.contains(where: { $0 === context }) else { throw KeychainError.status(errSecInteractionNotAllowed) }
        return data
    }
    func containsProtected(account: String) throws -> Bool {
        lock.lock(); defer { lock.unlock() }; return protected[account] != nil
    }
    /// A fixture biometric prompt: an evaluated context on success, as BiometricLock returns.
    func prompt(_ result: LocalUnlockResult) -> (result: LocalUnlockResult, context: LAContext?) {
        guard result == .unlocked else { return (result, nil) }
        let context = LAContext()
        lock.lock(); defer { lock.unlock() }; evaluated.append(context)
        return (result, context)
    }
}

final class DeviceKeyProtectionTests: XCTestCase {
    private let seedV1 = "device-ed25519-seed-v1", seedV2 = "device-ed25519-seed-v2", publicV2 = "device-ed25519-public-v2"
    private let nonce = String(repeating: "00", count: 32)

    func testMigrationMovesSeedBehindBiometryKeepsPublicKeyAndDeletesV1() async throws {
        let store = FixtureSecureStorage()
        let legacy = Curve25519.Signing.PrivateKey()
        try store.save(legacy.rawRepresentation, account: seedV1)
        let identity = DeviceIdentityStore(keychain: store)
        try await identity.migrateIfNeeded()
        XCTAssertNil(try store.read(account: seedV1))
        XCTAssertNil(try store.read(account: seedV2), "The seed is never stored as a plain item")
        let publicKey = try await identity.existingPublicKeyHex()
        XCTAssertEqual(publicKey, legacy.publicKey.rawRepresentation.hexEncodedString())
        try await identity.migrateIfNeeded()
        let challenge = UUID(), device = UUID()
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        let signature = try await identity.signLogin(challengeId: challenge, deviceId: device, nonceHex: nonce, context: context)
        let message = try DeviceIdentityStore.loginMessage(challengeId: challenge, deviceId: device, nonceHex: nonce)
        XCTAssertTrue(legacy.publicKey.isValidSignature(try XCTUnwrap(Data(hexEncoded: signature)), for: message))
    }

    func testDifferingLegacyKeyWinsAfterDowngradeAndRepair() async throws {
        let store = FixtureSecureStorage()
        let identity = DeviceIdentityStore(keychain: store)
        let older = try await identity.publicKeyHex()
        let repaired = Curve25519.Signing.PrivateKey()
        try store.save(repaired.rawRepresentation, account: seedV1)
        try await identity.migrateIfNeeded()
        XCTAssertNil(try store.read(account: seedV1))
        let current = try await identity.existingPublicKeyHex()
        XCTAssertNotEqual(current, older)
        XCTAssertEqual(current, repaired.publicKey.rawRepresentation.hexEncodedString())
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        let seed = try XCTUnwrap(store.readProtected(account: seedV2, context: context))
        XCTAssertEqual(seed, repaired.rawRepresentation)
    }

    func testMatchingLegacyKeyKeepsProtectedKey() async throws {
        let store = FixtureSecureStorage()
        let identity = DeviceIdentityStore(keychain: store)
        let current = try await identity.publicKeyHex()
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        let seed = try XCTUnwrap(store.readProtected(account: seedV2, context: context))
        try store.save(seed, account: seedV1)
        try await identity.migrateIfNeeded()
        XCTAssertNil(try store.read(account: seedV1))
        let kept = try await identity.existingPublicKeyHex()
        XCTAssertEqual(kept, current)
        // A damaged legacy seed never replaces a complete protected key.
        try store.save(Data([1, 2, 3]), account: seedV1)
        try await identity.migrateIfNeeded()
        XCTAssertNil(try store.read(account: seedV1))
        let stillKept = try await identity.existingPublicKeyHex()
        XCTAssertEqual(stillKept, current)
    }

    func testUnreadableKnownKeyIsReportedAsRetryable() async throws {
        let store = FixtureSecureStorage()
        let identity = DeviceIdentityStore(keychain: store)
        _ = try await identity.publicKeyHex()
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        for status in [errSecAuthFailed, errSecUserCanceled, errSecInteractionNotAllowed] {
            store.protectedReadStatus = status
            do {
                _ = try await identity.signLogin(challengeId: UUID(), deviceId: UUID(), nonceHex: nonce, context: context)
                XCTFail("Status \(status) must not sign")
            } catch DeviceIdentityError.keyLocked { }
        }
    }

    func testEnrolmentReplacesAPublicKeyWithoutItsSeed() async throws {
        let store = FixtureSecureStorage()
        let stale = Curve25519.Signing.PrivateKey().publicKey.rawRepresentation
        try store.save(stale, account: publicV2)
        let identity = DeviceIdentityStore(keychain: store)
        let fresh = try await identity.publicKeyHex()
        XCTAssertNotEqual(fresh, stale.hexEncodedString())
        XCTAssertTrue(try store.containsProtected(account: seedV2))
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        let seed = try XCTUnwrap(store.readProtected(account: seedV2, context: context))
        XCTAssertEqual(try Curve25519.Signing.PrivateKey(rawRepresentation: seed).publicKey.rawRepresentation.hexEncodedString(), fresh)
    }

    func testLegacySeedReplacesAnInterruptedProtectedWrite() async throws {
        let store = FixtureSecureStorage()
        let legacy = Curve25519.Signing.PrivateKey()
        try store.saveProtected(Curve25519.Signing.PrivateKey().rawRepresentation, account: seedV2)
        try store.save(legacy.rawRepresentation, account: seedV1)
        let identity = DeviceIdentityStore(keychain: store)
        try await identity.migrateIfNeeded()
        XCTAssertNil(try store.read(account: seedV1))
        let publicKey = try await identity.existingPublicKeyHex()
        XCTAssertEqual(publicKey, legacy.publicKey.rawRepresentation.hexEncodedString())
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        _ = try await identity.signLogin(challengeId: UUID(), deviceId: UUID(), nonceHex: nonce, context: context)
    }

    func testDamagedLegacySeedIsKeptWhenNothingReplacesIt() async throws {
        let store = FixtureSecureStorage()
        try store.save(Data([1, 2, 3]), account: seedV1)
        do { try await DeviceIdentityStore(keychain: store).migrateIfNeeded(); XCTFail("Damaged seed must not migrate") }
        catch DeviceIdentityError.invalidStoredKey { }
        XCTAssertNotNil(try store.read(account: seedV1))
    }

    func testSigningRequiresAnEvaluatedContext() async throws {
        let store = FixtureSecureStorage()
        let identity = DeviceIdentityStore(keychain: store)
        _ = try await identity.publicKeyHex()
        do {
            _ = try await identity.signLogin(challengeId: UUID(), deviceId: UUID(), nonceHex: nonce, context: LAContext())
            XCTFail("An unevaluated context must not unlock the device key")
        } catch DeviceIdentityError.keyLocked { }
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        _ = try await identity.signLogin(challengeId: UUID(), deviceId: UUID(), nonceHex: nonce, context: context)
    }

    func testPlainPublicKeyMatchesProtectedSeed() async throws {
        let store = FixtureSecureStorage()
        let identity = DeviceIdentityStore(keychain: store)
        let publicKey = try await identity.publicKeyHex()
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        let seed = try XCTUnwrap(store.readProtected(account: seedV2, context: context))
        let derived = try Curve25519.Signing.PrivateKey(rawRepresentation: seed).publicKey.rawRepresentation
        XCTAssertEqual(derived.hexEncodedString(), publicKey)
        XCTAssertEqual(try store.read(account: publicV2), derived)
    }

    func testInvalidatedKeyAsksToRepair() async throws {
        let store = FixtureSecureStorage()
        try store.save(Curve25519.Signing.PrivateKey().publicKey.rawRepresentation, account: publicV2)
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        do {
            _ = try await DeviceIdentityStore(keychain: store).signLogin(challengeId: UUID(), deviceId: UUID(), nonceHex: nonce, context: context)
            XCTFail("An invalidated key must not sign")
        } catch DeviceIdentityError.biometryChanged { }
        XCTAssertFalse(try store.containsProtected(account: seedV2), "Signing never creates a replacement key")
    }
}

final class DeviceLoginRecoveryTests: XCTestCase {
    func testLoginMessageGoldenVector() throws {
        let message = try DeviceIdentityStore.loginMessage(
            challengeId: UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!,
            deviceId: UUID(uuidString: "ffeeddcc-bbaa-9988-7766-554433221100")!,
            nonceHex: (0..<32).map { String(format: "%02x", $0) }.joined())
        XCTAssertEqual(message.hexEncodedString(),
            "6a61727669732d6c6f67696e2d763100" + "00112233445566778899aabbccddeeff" +
            "ffeeddccbbaa99887766554433221100" + "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        XCTAssertThrowsError(try DeviceIdentityStore.loginMessage(challengeId: UUID(), deviceId: UUID(), nonceHex: "00"))
        XCTAssertEqual(try DeviceIdentityStore.unlockApprovalMessage(
            requestId: UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!,
            approverDeviceId: UUID(uuidString: "ffeeddcc-bbaa-9988-7766-554433221100")!,
            nonceHex: (0..<32).map { String(format: "%02x", $0) }.joined()).hexEncodedString(),
            "6a61727669732d756e6c6f636b2d617070726f76616c2d763100" + "00112233445566778899aabbccddeeff" +
            "ffeeddccbbaa99887766554433221100" + "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        // A sign prefix is not hex, even where Swift's integer parser accepts it.
        XCTAssertThrowsError(try DeviceIdentityStore.unlockApprovalMessage(requestId: UUID(), approverDeviceId: UUID(),
                                                                           nonceHex: "+0" + String(repeating: "00", count: 31)))
    }

    func testMissingSigningKeyIsNotRegenerated() async throws {
        let store = FixtureSecureStorage()
        let identity = DeviceIdentityStore(keychain: store)
        let context = try XCTUnwrap(store.prompt(.unlocked).context)
        do {
            _ = try await identity.signLogin(challengeId: UUID(), deviceId: UUID(), nonceHex: String(repeating: "00", count: 32), context: context)
            XCTFail("Missing registered identity must fail closed")
        } catch DeviceIdentityError.missingStoredKey { }
        XCTAssertFalse(try store.containsProtected(account: "device-ed25519-seed-v2"))
    }

    func testRecreatedStoresKeepIdentityAndSettings() async throws {
        let store = FixtureSecureStorage()
        defer { try? store.delete(account: "device-ed25519-seed-v2") }
        let first = DeviceIdentityStore(keychain: store)
        let publicKey = try await first.publicKeyHex()
        let restored = try await DeviceIdentityStore(keychain: store).publicKeyHex()
        XCTAssertEqual(publicKey, restored)
        let suite = "jarvis.fixture.\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let origin = URL(string: "https://jarvis.example.com")!
        EndpointStore(defaults: defaults).save(origin)
        XCTAssertEqual(EndpointStore(defaults: defaults).endpoint, origin)
    }

    func testLoginFailuresKeepBindingAndSuccessfulRetryRestoresSession() async throws {
        for host in ["challenge.example.com", "login.example.com", "success.example.com"] {
            let store = FixtureSecureStorage()
            let credentials = SecureCredentialStore(keychain: store)
            let identity = DeviceIdentityStore(keychain: store)
            defer {
                for account in ["device-ed25519-seed-v2", "device-ed25519-public-v2", "registered-device-id-v1", "authenticated-session-v1"] {
                    try? store.delete(account: account)
                }
            }
            let deviceId = UUID()
            try await credentials.save(deviceId: deviceId)
            let key = try await identity.publicKeyHex()
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [LoginRecoveryProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = JarvisAPIClient(baseURL: URL(string: "https://\(host)")!, session: transport)
            let auth = AuthService(api: api, identity: identity, credentials: credentials,
                                   authenticateBiometric: { _ in store.prompt(.unlocked) })
            let initial = try await auth.restore()
            XCTAssertEqual(initial, .needsPassword)
            do {
                let outcome = try await auth.restore(password: "fixture-password-only")
                XCTAssertEqual(host, "success.example.com")
                XCTAssertEqual(outcome, .authenticated)
            } catch let error as DeviceLoginError {
                XCTAssertEqual(error, host == "challenge.example.com" ? .deviceRejected : .loginRejected)
                let session = try await credentials.session()
                XCTAssertNil(session)
            }
            let storedId = try await credentials.deviceId()
            let storedKey = try await identity.publicKeyHex()
            XCTAssertEqual(storedId, deviceId)
            XCTAssertEqual(storedKey, key)
        }
    }

    func testPairingDecisionsNeverAuthenticateWithoutPassword() async throws {
        for decision in ["pending", "approved", "denied", "expired"] {
            let store = FixtureSecureStorage()
            let credentials = SecureCredentialStore(keychain: store)
            defer {
                for account in ["registered-device-id-v1", "pending-pairing-v1"] {
                    try? store.delete(account: account)
                }
            }
            try await credentials.save(pairingTicket: PairingTicket(requestId: UUID(), nonce: "fixture-nonce", expiresAt: 4102444800))
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [LoginRecoveryProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = JarvisAPIClient(baseURL: URL(string: "https://\(decision).example.com")!, session: transport)
            let auth = AuthService(api: api, credentials: credentials)
            let outcome = try await auth.refreshEnrollment()
            switch decision {
            case "pending":
                XCTAssertEqual(outcome, .awaitingApproval(expiresAt: Date(timeIntervalSince1970: 4102444800)))
            case "approved": XCTAssertEqual(outcome, .needsPassword)
            default: XCTAssertEqual(outcome, .needsEnrollment)
            }
            let session = try await credentials.session()
            XCTAssertNil(session)
            let ticket = try await credentials.pairingTicket()
            XCTAssertEqual(ticket != nil, decision == "pending")
        }
    }
}

private final class LoginRecoveryProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        var status = 200
        let body: String
        switch path {
        case "/v1/auth/account/status":
            body = #"{"protocol":1,"password_required":true,"bootstrap_required":false}"#
        case "/v1/auth/challenge":
            status = request.url!.host == "challenge.example.com" ? 401 : 200
            body = "{\"challenge_id\":\"00000000-0000-0000-0000-000000000001\",\"nonce\":\"\(String(repeating: "00", count: 32))\"}"
        case "/v1/auth/login":
            status = request.url!.host == "login.example.com" ? 401 : 200
            body = #"{"token":"fixture-session-only","expires_at":4102444800}"#
        default:
            if path.hasPrefix("/v1/auth/pairing/requests/"), path.hasSuffix("/status") {
                let decision = request.url!.host!.split(separator: ".")[0]
                body = "{\"status\":\"\(decision)\",\"device_id\":\"00000000-0000-0000-0000-000000000002\"}"
            } else {
                XCTFail("Unexpected auth request")
                status = 500; body = "{}"
            }
        }
        XCTAssertNil(request.url!.query)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class EnrollmentInputTests: XCTestCase {
    func testActivationAcceptsNewAndLegacyCodesAndRejectsHeaderInjection() {
        XCTAssertTrue(JarvisAPIClient.validActivationCode("ABCD2345EFGH"))
        XCTAssertTrue(JarvisAPIClient.validActivationCode(String(repeating: "a1B2", count: 16)))
        for invalid in ["", "abcd2345efgh", "ABCD2345EFG", "ABCD2345EFGHI", "ABCD0123EFGH",
                        "ABCD2345EFGH\r\n", " ABCD2345EFGH", String(repeating: "é", count: 12),
                        String(repeating: "z", count: 64)] {
            XCTAssertFalse(JarvisAPIClient.validActivationCode(invalid))
        }
    }

    @MainActor
    func testActivationFailureKeepsActivationFormAndRecoversSavedDevice() {
        let model = JarvisAppModel()
        for error in [JarvisAPIError.invalidActivationCode, .invalidAccountPassword, .timedOut,
                      .rejected(status: 403, message: "bootstrap unavailable")] {
            model.applyActivationFailure(error, recovery: nil)
            XCTAssertEqual(model.enrollmentState, .needsActivation)
            XCTAssertEqual(model.notice, error.localizedDescription)
        }
        model.applyActivationFailure(JarvisAPIError.unauthorized, recovery: .needsPassword)
        XCTAssertEqual(model.enrollmentState, .needsPassword)
        XCTAssertNotEqual(JarvisAPIError.invalidActivationCode.localizedDescription,
                          JarvisAPIError.invalidConfiguration.localizedDescription)
        XCTAssertNotEqual(JarvisAPIError.invalidAccountPassword.localizedDescription,
                          JarvisAPIError.invalidConfiguration.localizedDescription)
    }

    func testValidActivationCodesReachBootstrapWithoutEnteringURL() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ActivationProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: session)
        let request = EnrollmentRequest(name: "Fixture iPhone", platform: "ios",
                                       publicKey: String(repeating: "a", count: 64), password: "fixture-password-only")
        for code in ["ABCD2345EFGH", String(repeating: "a1B2", count: 16)] {
            let response = try await api.activateFirstDevice(request, code: code)
            XCTAssertEqual(response.deviceId.uuidString, "00000000-0000-0000-0000-000000000002")
        }
        do {
            _ = try await api.activateFirstDevice(request, code: "bad")
            XCTFail("Invalid code accepted")
        } catch let error as JarvisAPIError {
            XCTAssertEqual(error, .invalidActivationCode)
        }
    }

    @MainActor
    func testActivationAndPasswordAcceptFocusAndTyping() async throws {
        var code = ""
        var password = ""
        let form = EnrollmentCredentialsForm(
            requiresActivation: true,
            password: Binding(get: { password }, set: { password = $0 }),
            activationCode: Binding(get: { code }, set: { code = $0 }),
            submit: {}
        )
        let controller = UIHostingController(rootView: form.padding())
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        controller.view.layoutIfNeeded()
        try await Task.sleep(nanoseconds: 200_000_000)

        func fields(in view: UIView) -> [UITextField] {
            (view as? UITextField).map { [$0] } ?? view.subviews.flatMap { fields(in: $0) }
        }
        let inputs = fields(in: controller.view)
        let activation = try XCTUnwrap(inputs.first { $0.placeholder == "One-time activation code" })
        let account = try XCTUnwrap(inputs.first { $0.placeholder == "Account password" })
        for field in [activation, account] {
            XCTAssertEqual(field.isSecureTextEntry, field === account)
            XCTAssertTrue(field.isEnabled)
            XCTAssertGreaterThan(field.bounds.height, 0)
            let center = field.convert(CGPoint(x: field.bounds.midX, y: field.bounds.midY), to: window)
            let hit = try XCTUnwrap(window.hitTest(center, with: nil))
            XCTAssertTrue(hit === field || hit.isDescendant(of: field))
            XCTAssertTrue(field.becomeFirstResponder())
            XCTAssertTrue(field.isFirstResponder)
            field.insertText(field === activation ? "ABCD2345EFGH" : "fixture-password-only")
            field.resignFirstResponder()
        }
        XCTAssertEqual(code, "ABCD2345EFGH")
        XCTAssertEqual(password, "fixture-password-only")
    }

    @MainActor
    func testBrandAccentAndBundledIconArePresent() throws {
        let accent = try XCTUnwrap(UIColor(named: "AccentColor"))
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        XCTAssertTrue(accent.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        XCTAssertEqual(red, 46 / 255.0, accuracy: 0.001)
        XCTAssertEqual(green, 230 / 255.0, accuracy: 0.001)
        XCTAssertEqual(blue, 162 / 255.0, accuracy: 0.001)
        let icons = try XCTUnwrap(Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any])
        let primary = try XCTUnwrap(icons["CFBundlePrimaryIcon"] as? [String: Any])
        XCTAssertEqual(primary["CFBundleIconName"] as? String, "AppIcon")
    }
}

private final class ActivationProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.absoluteString, "https://jarvis.example.com/v1/auth/bootstrap")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertTrue(JarvisAPIClient.validActivationCode(request.value(forHTTPHeaderField: "X-Jarvis-Bootstrap-Secret") ?? ""))
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"user_id":"00000000-0000-0000-0000-000000000001","device_id":"00000000-0000-0000-0000-000000000002"}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

private final class NoNetworkProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost)) }
    override func stopLoading() {}
}

private final class BoundedResponseServer {
    let listener: NWListener
    private let queue = DispatchQueue(label: "jarvis.tests.bounded-http")
    private var connection: NWConnection?

    init(response: String, ready: XCTestExpectation, sent: XCTestExpectation) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready: ready.fulfill()
            case .failed(let error):
                XCTFail("Loopback fixture listener failed: \(error)")
                ready.fulfill()
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self, self.connection == nil else { connection.cancel(); return }
            self.connection = connection
            connection.start(queue: self.queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { _, _, _, error in
                guard error == nil else { connection.cancel(); return }
                // Keep the socket open, including on oversized responses. The
                // reader must reject before EOF rather than wait for completion.
                connection.send(content: Data(response.utf8), completion: .contentProcessed { error in
                    XCTAssertNil(error, "Fixture must send its response successfully")
                    sent.fulfill()
                })
            }
        }
        listener.start(queue: queue)
    }
    func stop() {
        queue.sync { connection?.cancel(); listener.cancel() }
    }
}

final class ClientDTOTests: XCTestCase {
    func testAccountStatusAndPasswordRequestsMatchCoreContract() throws {
        let status = try JSONDecoder().decode(AccountStatus.self, from: Data(#"{"protocol":1,"password_required":true,"bootstrap_required":false}"#.utf8))
        XCTAssertEqual(status.protocolVersion, 1)
        XCTAssertTrue(status.passwordRequired)
        XCTAssertFalse(status.bootstrapRequired)
        let request = LoginRequest(deviceId: UUID(), challengeId: UUID(), signature: String(repeating: "ab", count: 64), password: "fixture account password")
        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as! [String: Any]
        XCTAssertEqual(encoded["password"] as? String, "fixture account password")
        XCTAssertNotNil(encoded["device_id"])
        XCTAssertNotNil(encoded["challenge_id"])
        let legacy = LoginRequest(deviceId: UUID(), challengeId: UUID(), signature: "fixture")
        let oldShape = try JSONSerialization.jsonObject(with: JSONEncoder().encode(legacy)) as! [String: Any]
        XCTAssertNil(oldShape["password"])
    }
    func testHTTPBodyIsBoundedBeforeEOFWithOrWithoutContentLength() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let fixtures = [
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 99999\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 99999\r\n\r\nA",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n8\r\nAAAAAAAA\r\n9\r\nBBBBBBBBB\r\n",
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n8\r\nAAAAAAAA\r\n8\r\nBBBBBBBB\r\n0\r\n\r\n"
        ]
        for (index, response) in fixtures.enumerated() {
            let ready = expectation(description: "Loopback HTTP fixture ready")
            let sent = expectation(description: "Loopback HTTP fixture sent headers/body")
            let server = try BoundedResponseServer(response: response, ready: ready, sent: sent)
            defer { server.stop() }
            // Simulator startup can be delayed on a loaded shared runner.
            // Keep this separate from the deliberately short HTTP timeout.
            await fulfillment(of: [ready], timeout: 30)
            let port = try XCTUnwrap(server.listener.port)
            guard case .ready = server.listener.state, port.rawValue != 0 else {
                XCTFail("Loopback fixture never became ready; refusing a request to port zero")
                return
            }
            let request = URLRequest(url: URL(string: "http://127.0.0.1:\(port.rawValue)/fixture")!)
            do {
                let (data, _) = try await BoundedAPIResponse.read(session: session, request: request, limit: 16)
                XCTAssertEqual(index, 3, "Oversized response must fail before EOF")
                XCTAssertEqual(data, Data(repeating: 65, count: 8) + Data(repeating: 66, count: 8))
            } catch let error as JarvisAPIError {
                XCTAssertTrue(index == 1 || index == 2, "Only oversized delivered responses use the size error")
                XCTAssertEqual(error, .responseTooLarge)
            } catch let error as URLError where index == 0 && error.code == .timedOut {
                // Foundation may withhold the response callback until body
                // bytes arrive. A header-only peer must still fail within the
                // configured resource timeout, not hang or accumulate a body.
            } catch {
                XCTFail("HTTP fixture \(index) failed unexpectedly: \(error)")
            }
            await fulfillment(of: [sent], timeout: 5)
        }
    }

    @MainActor
    func testLateChatPresentationCannotPopulateAnotherSession() async {
        var lifetime = ChatPresentationLifetime()
        let old = lifetime.id
        var rows = ["old conversation"]
        let late = Task { @MainActor in
            if lifetime.accepts(old) { rows.append("late old response") }
        }
        lifetime.invalidate()
        rows = ["new session"]
        await late.value
        XCTAssertEqual(rows, ["new session"])
        XCTAssertFalse(lifetime.accepts(old))
        let current = lifetime.id
        XCTAssertTrue(lifetime.accepts(current))
        lifetime.invalidate()
        XCTAssertFalse(lifetime.accepts(current))
    }
    func testLocalVoiceSelectionIsBoundedAndNeverSubstitutesMissingExplicitVoice() {
        let records = [LocalSpeechVoice(id: "local", label: "Fixture voice"),
                       LocalSpeechVoice(id: "local", label: "Duplicate"),
                       LocalSpeechVoice(id: "bad\n", label: "Invalid"),
                       LocalSpeechVoice(id: "long", label: String(repeating: "x", count: 257))]
        let voices = LocalSpeechVoice.catalog(records)
        XCTAssertEqual(voices, [records[0]])
        XCTAssertEqual(LocalSpeechVoice.selected(in: voices, id: "local", defaultID: nil), "local")
        XCTAssertNil(LocalSpeechVoice.selected(in: voices, id: "removed", defaultID: "local"))
        XCTAssertEqual(LocalSpeechVoice.selected(in: voices, id: "", defaultID: "removed"), "local")
        XCTAssertNil(LocalSpeechVoice.selected(in: [], id: "", defaultID: nil))
        XCTAssertEqual(LocalSpeechVoice.catalog((0..<1000).lazy.map { LocalSpeechVoice(id: "v\($0)", label: "Voice \($0)") }).count, 128)
    }
    func testSpeechRateIsBoundedAndRejectsNonFinitePreferences() {
        for value in [Double.nan, Double.infinity, -Double.infinity] { XCTAssertEqual(SpeechRate.normalize(value), 1) }
        XCTAssertEqual(SpeechRate.normalize(-100), 0.5)
        XCTAssertEqual(SpeechRate.normalize(100), 2)
        XCTAssertEqual(SpeechRate.normalize(1.25), 1.25)
    }
    func testEveryChatPathRefusesOriginSwitchDuringCredentialLoad() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [NoNetworkProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let origin = URL(string: "https://jarvis.example.com")!
        let api = JarvisAPIClient(baseURL: origin, session: session)
        let chat = ChatService(api: api, tokenLoader: {
            // Force actor reentrancy exactly between binding capture and use.
            await api.configure(baseURL: URL(string: "https://home.example.org")!)
            await api.configure(baseURL: origin)
            return "fixture-session"
        })
        for operation in 0..<4 {
            do {
                switch operation {
                case 0: _ = try await chat.conversations()
                case 1: _ = try await chat.conversation(id: UUID())
                case 2: _ = try await chat.submit(requestId: UUID(), text: "Fixture", conversationId: nil, history: [])
                default: _ = try await chat.send(text: "Fixture", conversationId: nil, history: [])
                }
                XCTFail("Stale credential binding must fail before HTTP dispatch")
            } catch let error as JarvisAPIError {
                XCTAssertEqual(error, .invalidConfiguration)
            }
        }
        let capability = await chat.realtimeAvailable()
        XCTAssertFalse(capability)
    }
    @MainActor
    func testPlaybackRequestContainsOnlyNativeAuthAndTypedRunState() throws {
        let run = UUID()
        let event = SpeechPlaybackEvent(run: run, state: .started)
        let request = try XCTUnwrap(VoicePlaybackReporter.request(origin: URL(string: "https://jarvis.example.com")!, token: "fixture-session", event: event))
        XCTAssertEqual(request.url?.absoluteString, "https://jarvis.example.com/v1/voice/playback")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.timeoutInterval, 5)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-session")
        let body = try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: String]
        XCTAssertEqual(body, ["run_id":run.uuidString,"state":"started"])
        for invalid in ["http://jarvis.example.com", "https://user:password@jarvis.example.com", "https://jarvis.example.com/wrong", "https://jarvis.example.com?token=fixture", "https://jarvis.example.com#fragment"] {
            XCTAssertNil(VoicePlaybackReporter.request(origin: URL(string: invalid)!, token: "fixture-session", event: event))
        }
    }
    func testSpeechRunWaitsForFinalSealAndDrainsOnlyOnce() {
        let queue = SpeechQueueRegistry()
        let run = UUID()
        let first = NSObject(), second = NSObject()
        queue.begin(run: run)
        XCTAssertTrue(queue.insert(first))
        queue.didStart(first)
        queue.remove(first)
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: run, state: .started)])
        XCTAssertTrue(queue.insert(second))
        queue.didStart(second)
        queue.seal(run: run)
        XCTAssertTrue(queue.drain().isEmpty)
        queue.remove(second)
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: run, state: .stopped)])
        queue.remove(second); queue.seal(run: run); queue.clear()
        XCTAssertTrue(queue.drain().isEmpty)
    }
    func testCancelledOldUtteranceCannotFailNewRun() {
        let queue = SpeechQueueRegistry()
        let oldRun = UUID(), newRun = UUID()
        let old = NSObject(), current = NSObject()
        queue.begin(run: oldRun); XCTAssertTrue(queue.insert(old))
        queue.clear()
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: oldRun, state: .stopped)])
        queue.begin(run: newRun); XCTAssertTrue(queue.insert(current))
        queue.fail(old); queue.didStart(old); queue.remove(old); queue.seal(run: oldRun)
        XCTAssertTrue(queue.drain().isEmpty)
        queue.didStart(current); queue.fail(current); queue.remove(current)
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: newRun, state: .started), SpeechPlaybackEvent(run: newRun, state: .failed)])
        queue.seal(run: newRun)
        XCTAssertTrue(queue.drain().isEmpty)
    }
    func testSpeechMetadataQueueIsBounded() {
        let queue = SpeechQueueRegistry()
        for _ in 0..<100 { queue.begin(run: UUID()); queue.clear() }
        XCTAssertEqual(queue.drain().count, 16)
        XCTAssertTrue(queue.drain().isEmpty)
    }
    func testRecoveryCannotDispatchUsingAnOldOriginBinding() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [NoNetworkProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let origin = URL(string: "https://jarvis.example.com")!
        let api = JarvisAPIClient(baseURL: origin, session: session)
        let binding = await api.binding()
        await api.configure(baseURL: URL(string: "https://home.example.org")!)
        await api.configure(baseURL: origin)
        do {
            let _: RecoveredChatRun = try await api.get("/v1/assistant/requests/00000000-0000-0000-0000-000000000001", token: "fixture-session", expectedBinding: binding)
            XCTFail("Old binding must be refused")
        } catch let error as JarvisAPIError {
            XCTAssertEqual(error, .invalidConfiguration)
        }
        do {
            let _: VoiceReleaseResult = try await api.post("/v1/voice/release",
                body: VoiceReleaseRequest(run_id: UUID()), token: "fixture-session", expectedBinding: binding)
            XCTFail("Old binding must also refuse voice mutation")
        } catch let error as JarvisAPIError {
            XCTAssertEqual(error, .invalidConfiguration)
        }
    }
    func testOnlyMatchingTerminalRecoveryClearsPendingRequest() {
        var pending = PendingChatRequests()
        let request = UUID()
        XCTAssertTrue(pending.insert(request, optimisticID: "row"))
        pending.reconcile(request, result: RecoveredChatRun(request_id: UUID(), run_id: UUID(), conversation_id: UUID(), state: "completed"))
        pending.reconcile(request, result: RecoveredChatRun(request_id: request, run_id: UUID(), conversation_id: UUID(), state: "running"))
        XCTAssertEqual(pending[request], "row")
        pending.reconcile(request, result: RecoveredChatRun(request_id: request, run_id: UUID(), conversation_id: UUID(), state: "interrupted"))
        XCTAssertNil(pending[request])
    }
    func testPendingRequestsAreBoundedAndClearedAcrossSessions() {
        var pending = PendingChatRequests()
        let first = UUID()
        XCTAssertTrue(pending.insert(first, optimisticID: "first"))
        for _ in 0..<31 { XCTAssertTrue(pending.insert(UUID(), optimisticID: "fixture")) }
        XCTAssertTrue(pending.isFull)
        XCTAssertFalse(pending.insert(UUID(), optimisticID: "overflow"))
        XCTAssertFalse(pending.insert(first, optimisticID: "replacement"))
        XCTAssertEqual(pending[first], "first")
        pending.removeValue(forKey: first)
        XCTAssertFalse(pending.isFull)
        XCTAssertNil(pending[first])
        pending.clear()
        XCTAssertFalse(pending.isFull)
    }
    func testConversationRecoveryPreservesActiveGenerationAndAcceptsLegacyShape() throws {
        let legacy = Data(#"{"id":"00000000-0000-0000-0000-000000000001","title":"Fixture","messages":[]}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(ConversationResponse.self, from: legacy).assistantRunning)
        let active = Data(#"{"id":"00000000-0000-0000-0000-000000000001","title":"Fixture","messages":[],"assistant_running":true}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(ConversationResponse.self, from: active).assistantRunning, true)
    }
    func testStoppedUtteranceCallbackCannotFreeANewerQueueSlot() {
        let queue = SpeechQueueRegistry()
        let old = NSObject()
        XCTAssertTrue(queue.insert(old))
        queue.clear()
        let current = (0..<32).map { _ in NSObject() }
        for utterance in current { XCTAssertTrue(queue.insert(utterance)) }
        queue.remove(old)
        XCTAssertFalse(queue.insert(NSObject()))
        queue.remove(current[0])
        queue.remove(current[0])
        let next = NSObject()
        XCTAssertTrue(queue.insert(next))
        XCTAssertFalse(queue.insert(NSObject()))
    }
    @MainActor
    func testDisconnectedSpeechDropsBufferedSuffixAndLateCompletion() throws {
        final class FakeSpeech: SpeechOutput {
            var spoken: [String] = []
            var stops = 0
            func speak(_ text: String) { spoken.append(text) }
            func stop() { stops += 1 }
        }
        let id = "00000000-0000-0000-0000-000000000001"
        let identity = ["run_id": id, "request_id": id, "conversation_id": id]
        func event(_ type: String, _ payload: [String: Any]) throws -> RealtimeEvent {
            let bytes = try JSONSerialization.data(withJSONObject: [
                "protocol": 1, "epoch": id, "sequence": 1, "event_id": id,
                "type": type, "payload": payload
            ])
            return try JSONDecoder().decode(RealtimeEvent.self, from: bytes)
        }
        let output = FakeSpeech()
        let voice = RealtimeSpeech(output: output)
        voice.enabled = true
        voice.receive(try event("connection.ready", ["device_id": id]))
        voice.receive(try event("voice.owner_changed", ["device_id": id, "run_id": id]))
        voice.receive(try event("assistant.started", identity))
        voice.receive(try event("assistant.delta", ["run": identity, "text": "Hello. Buffered suffix"]))
        let beforeDisconnect = output.spoken
        let stops = output.stops

        // The socket's connection-scoped defer clears this handler on failure.
        voice.setPlaybackHandler(nil)
        XCTAssertEqual(output.stops, stops + 1)
        let completed = try event("assistant.completed", ["run": identity, "message": [
            "id": id, "conversation_id": id, "role": "assistant",
            "content": "Hello. Buffered suffix.", "created_at": "2026-01-01T00:00:00Z"
        ]])
        voice.receive(completed)
        XCTAssertEqual(output.spoken, beforeDisconnect)

        // Reconnection does not replay historical audio or inherit voice authority.
        voice.receive(try event("connection.ready", ["device_id": id]))
        XCTAssertNil(voice.ownedRun)
        voice.receive(try event("assistant.started", identity))
        voice.receive(completed)
        XCTAssertEqual(output.spoken, beforeDisconnect)
    }

    @MainActor
    func testFragmentedFencesNeverSpeakEmbeddedCode() throws {
        final class FakeSpeech: SpeechOutput {
            var spoken: [String] = []
            func speak(_ text: String) { spoken.append(text) }
            func stop() {}
        }
        let id = "00000000-0000-0000-0000-000000000001"
        let identity = ["run_id":id,"request_id":id,"conversation_id":id]
        func event(_ type: String, _ payload: [String: Any]) throws -> RealtimeEvent {
            let bytes = try JSONSerialization.data(withJSONObject: ["protocol":1,"epoch":id,"sequence":1,"event_id":id,"type":type,"payload":payload])
            return try JSONDecoder().decode(RealtimeEvent.self, from: bytes)
        }
        func render(_ text: String, streaming: Bool) throws -> [String] {
            let out = FakeSpeech(); let voice = RealtimeSpeech(output: out); voice.enabled = true
            voice.receive(try event("connection.ready", ["device_id":id]))
            voice.receive(try event("voice.owner_changed", ["device_id":id,"run_id":id]))
            voice.receive(try event("assistant.started", identity))
            if streaming { for ch in text { voice.receive(try event("assistant.delta", ["run":identity,"text":String(ch)])) } }
            let completed = try event("assistant.completed", ["run":identity,"message":["id":id,"conversation_id":id,"role":"assistant","content":text,"created_at":"2026-01-01T00:00:00Z"]])
            voice.receive(completed); voice.receive(completed)
            return out.spoken
        }
        for text in [
            "Before.\n   ```rust\nlet s = \"```\";\nnot speech\n   ```\nAfter.",
            "Before.\n  ~~~~text\ncode\n~~~\nstill code\n  ~~~~\nAfter.",
            "Before.\n```\nunterminated code",
        ] {
            let spoken = try render(text, streaming: true)
            XCTAssertEqual(spoken, try render(text, streaming: false))
            XCTAssertEqual(spoken.first, "Before.")
            XCTAssertTrue(spoken.allSatisfy { $0 == "Before." || $0 == "After." })
        }
    }
    @MainActor
    func testRealtimeVoiceOnlySpeaksOnOwnerAndDoesNotRepeatFinal() throws {
        final class FakeSpeech: SpeechOutput {
            var spoken: [String] = []
            func speak(_ text: String) { spoken.append(text) }
            func stop() {}
        }
        let a = "00000000-0000-0000-0000-000000000001"
        let b = "00000000-0000-0000-0000-000000000002"
        let run = "00000000-0000-0000-0000-000000000003"
        func event(_ type: String, _ payload: [String: Any]) throws -> RealtimeEvent {
            let bytes = try JSONSerialization.data(withJSONObject: ["protocol":1,"epoch":a,"sequence":1,"event_id":a,"type":type,"payload":payload])
            return try JSONDecoder().decode(RealtimeEvent.self, from: bytes)
        }
        let outA = FakeSpeech(); let outB = FakeSpeech()
        let voiceA = RealtimeSpeech(output:outA); let voiceB = RealtimeSpeech(output:outB)
        voiceA.enabled = true; voiceB.enabled = true
        voiceA.receive(try event("connection.ready",["device_id":a]))
        voiceB.receive(try event("connection.ready",["device_id":b]))
        let identity = ["run_id":run,"request_id":a,"conversation_id":b]
        let events = [
            try event("voice.owner_changed",["device_id":a,"run_id":run]),
            try event("assistant.started",identity),
            try event("assistant.delta",["run":identity,"text":"One answer. Next"]),
            try event("assistant.completed",["run":identity,"message":["id":a,"conversation_id":b,"role":"assistant","content":"One answer. Next sentence.","created_at":"2026-01-01T00:00:00Z"]]),
        ]
        for event in events { voiceA.receive(event); voiceB.receive(event) }
        voiceA.receive(events.last!)
        XCTAssertEqual(outA.spoken,["One answer.","Next sentence."])
        XCTAssertTrue(outB.spoken.isEmpty)
        XCTAssertEqual(voiceA.ownedRun, UUID(uuidString: run))
        XCTAssertNil(voiceB.ownedRun)
        voiceA.stop()
        XCTAssertTrue(voiceA.enabled)
        voiceA.receive(try event("assistant.delta", ["run":identity,"text":"Late speech. "]))
        XCTAssertEqual(outA.spoken,["One answer.","Next sentence."])
        let release = try JSONSerialization.jsonObject(with: JSONEncoder().encode(VoiceReleaseRequest(run_id: UUID(uuidString: run)!))) as? [String: String]
        XCTAssertEqual(release?.count, 1)
        XCTAssertEqual(release?["run_id"].flatMap(UUID.init(uuidString:)), UUID(uuidString: run))
        // A new connection must not reuse an old voice lease. Even a late
        // started/delta event cannot speak until ownership is explicitly sent.
        voiceA.receive(try event("connection.ready", ["device_id":a]))
        voiceA.receive(try event("assistant.started", identity))
        voiceA.receive(try event("assistant.delta", ["run":identity,"text":"Stale speech. "]))
        XCTAssertEqual(outA.spoken,["One answer.","Next sentence."])
    }
    func testEnrollmentUsesBackendFieldNames() throws {
        let encoded = try JSONEncoder().encode(
            EnrollmentRequest(name: "Gus's iPhone", platform: "ios", publicKey: "ab")
        )
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: String])
        XCTAssertEqual(json["public_key"], "ab")
        XCTAssertNil(json["publicKey"])
        XCTAssertEqual(json["platform"], "ios")
    }

    func testDecodesCurrentPairingContract() throws {
        let data = #"{"request_id":"018f47de-936a-7000-8000-000000000001","nonce":"00","expires_at":1800000000}"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(PairingRequestResponse.self, from: data)
        XCTAssertEqual(decoded.expiresAt, 1_800_000_000)
        XCTAssertEqual(decoded.nonce, "00")
    }

    func testDecodesCurrentConversationContract() throws {
        let data = #"{"id":"018f47de-936a-7000-8000-000000000001","title":"Hello","messages":[{"role":"assistant","content":"Hi","model":null,"at":"2026-08-30T10:00:00Z"}]}"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(ConversationResponse.self, from: data)
        XCTAssertEqual(decoded.messages.first?.content, "Hi")
        XCTAssertTrue(decoded.messages.first?.isAssistant == true)
    }

    func testHexChallengeRejectsMalformedInput() {
        XCTAssertNil(Data(hexEncoded: "0"))
        XCTAssertNil(Data(hexEncoded: "zz"))
        XCTAssertEqual(Data(hexEncoded: "00ff"), Data([0, 255]))
    }

    func testDecodesHomeNodeResourcesWithAndWithoutLiveReadings() throws {
        let base = #"{"host":{"os":"Linux","arch":"x86_64","cpu":"Mini PC","cpu_cores":8,"mem_total_gb":16,"gpu":"Integrated"},"software":[{"name":"Ollama","present":true,"version":"1.0","detail":null}]}"#
        let decoder = JSONDecoder()
        let inventory = try decoder.decode(HomeNodeRegistry.self, from: Data(base.utf8))
        XCTAssertNil(inventory.liveHost)
        XCTAssertEqual(inventory.host.cpuCores, 8)
        XCTAssertEqual(inventory.software.first?.version, "1.0")

        let live = base.replacingOccurrences(of: #""software""#, with: #""live_host":{"sampled_at":1800000000,"cpu_percent":12.5,"memory_total_bytes":17179869184,"memory_used_bytes":4294967296,"uptime_seconds":3720},"software""#)
        let current = try decoder.decode(HomeNodeRegistry.self, from: Data(live.utf8))
        XCTAssertEqual(current.liveHost?.cpuPercent, 12.5)
        XCTAssertEqual(current.liveHost?.memoryUsedBytes, 4_294_967_296)
        XCTAssertEqual(current.liveHost?.uptimeSeconds, 3_720)
    }

    func testIOSUpdateDecodesAndValidates() throws {
        func json(version: String = "0.2.0", altstore: String = "altstore://source?url=https%3A%2F%2Fh%2Fa.json",
                  downloads: String = "https://h/downloads/") -> Data {
            Data(#"{"schema_version":1,"platform":"ios","version":"\#(version)","released_at":"2026-10-01T00:00:00Z","notes":"","downloads_page_url":"\#(downloads)","altstore_source_url":"https://h/a.json","altstore_add_source_url":"\#(altstore)","sidestore_add_source_url":"sidestore://source?url=https%3A%2F%2Fh%2Fa.json"}"#.utf8)
        }
        let decoder = JSONDecoder()
        let ok = try decoder.decode(IOSUpdateResponse.self, from: json())
        XCTAssertEqual(IOSUpdateNotice.validated(ok, running: "0.1.0")?.version, "0.2.0")
        XCTAssertNil(IOSUpdateNotice.validated(ok, running: "0.2.0"))
        XCTAssertNil(IOSUpdateNotice.validated(ok, running: "0.10.0"))
        XCTAssertNil(IOSUpdateNotice.validated(try decoder.decode(IOSUpdateResponse.self, from: json(version: "0.2.0-rc1")), running: "0.1.0"))
        XCTAssertNil(IOSUpdateNotice.validated(try decoder.decode(IOSUpdateResponse.self, from: json(altstore: "javascript:alert(1)")), running: "0.1.0"))
        XCTAssertNil(IOSUpdateNotice.validated(try decoder.decode(IOSUpdateResponse.self, from: json(downloads: "http://h/downloads/")), running: "0.1.0"))
    }
}

final class PairingApprovalTests: XCTestCase {
    private let key = Data((32..<64).map { UInt8($0) })
    private let full = "72dbb7336c76780023f83da4c355f2eeea85733b13d3477697917790c1229084"
    private let other = String(repeating: "ab", count: 32)

    private func item(fingerprint: String? = nil, full: String? = nil, key: String? = nil, expiresIn: Int64 = 300) -> PairingApprovalItem {
        let now = Int64(Date().timeIntervalSince1970)
        return PairingApprovalItem(id: UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!, device_name: "Fixture phone",
                                   platform: "android", fingerprint: fingerprint ?? String(self.full.prefix(16)),
                                   nonce: String(repeating: "00", count: 32), candidate_public_key: key ?? self.key.hexEncodedString(),
                                   created_at: now, expires_at: now + expiresIn, fingerprint_full: full)
    }

    func testPairingApprovalMessageMatchesClientCoreGoldenVector() throws {
        let message = try DeviceIdentityStore.pairingApprovalMessage(
            requestId: UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!,
            nonceHex: Data((0..<32).map { UInt8($0) }).hexEncodedString(),
            candidatePublicKeyHex: key.hexEncodedString(),
            userId: UUID(uuidString: "10213243-5465-7687-98a9-bacbdcedfe0f")!,
            approverDeviceId: UUID(uuidString: "ffeeddcc-bbaa-9988-7766-554433221100")!,
            expiresAt: 1_800_000_000)
        XCTAssertEqual(message.hexEncodedString(),
            "6a61727669732d6465766963652d70616972696e672d763100" + "00112233445566778899aabbccddeeff" +
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
            "102132435465768798a9bacbdcedfe0f" + "ffeeddccbbaa99887766554433221100" + "000000006b49d200")
        for (nonce, candidate) in [("00", key.hexEncodedString()), (String(repeating: "00", count: 32), "+f" + String(repeating: "00", count: 31))] {
            XCTAssertThrowsError(try DeviceIdentityStore.pairingApprovalMessage(requestId: UUID(), nonceHex: nonce,
                candidatePublicKeyHex: candidate, userId: UUID(), approverDeviceId: UUID(), expiresAt: 1))
        }
    }

    func testFingerprintIsComputedLocallyAndAnyMismatchIsRefused() throws {
        XCTAssertEqual(PairingFingerprint.of(publicKey: key), full)
        XCTAssertEqual(try PairingFingerprint.verified(item(full: full)), full)
        // Older Core: no fingerprint_full, but the legacy prefix must still match.
        XCTAssertEqual(try PairingFingerprint.verified(item()), full)
        for tampered in [item(full: other), item(fingerprint: String(other.prefix(16))), item(full: full.uppercased())] {
            XCTAssertThrowsError(try PairingFingerprint.verified(tampered)) { XCTAssertEqual($0 as? PairingFingerprint.Failure, .mismatch) }
        }
        for bad in ["", "zz", String(repeating: "07", count: 31)] {
            XCTAssertThrowsError(try PairingFingerprint.verified(item(key: bad))) { XCTAssertEqual($0 as? PairingFingerprint.Failure, .invalidKey) }
        }
        XCTAssertEqual(PairingFingerprint.grouped("0123456789abcdef01"), "0123 4567 89ab cdef 01")
        XCTAssertEqual(PairingFingerprint.displayName(nil), "unknown")
        XCTAssertEqual(PairingFingerprint.displayName("Pixel\u{202E} 9\u{7}"), "Pixel 9")
        XCTAssertEqual(pairingCountdown(100, now: Date(timeIntervalSince1970: 0)), "1:40")
        XCTAssertEqual(pairingCountdown(100, now: Date(timeIntervalSince1970: 100)), "Expired")
    }

    /// The desktop `display_name` table, unchanged.
    func testDisplayNamesDropControlAndFormatCharacters() {
        func name(_ value: String) -> String { PairingFingerprint.displayName(value, max: 40) }
        XCTAssertEqual(name("Owner's iPhone"), "Owner's iPhone")
        XCTAssertEqual(name("Evil\u{202E}enohPi\u{200B}\u{2066}"), "EvilenohPi")
        XCTAssertEqual(name("a\nApprove\u{2028}all\t"), "a Approve all")
        XCTAssertEqual(name("\u{0}\u{7}\u{FEFF} "), "unknown")
        XCTAssertEqual(name("A\u{34F}\u{3164}\u{2800}\u{FE0F}\u{E0100}\u{F0000}\u{E000}\"B"), "AB")
        XCTAssertEqual(name("\u{201C}A\u{2019}\u{AB}B\u{BB}\u{FF02}\u{2039}C\u{203A}"), "ABC")
        // At most two combining marks per base character.
        XCTAssertEqual(name("e\u{301}\u{302}\u{303}\u{304} o\u{308}"), "e\u{301}\u{302} o\u{308}")
        let long = name(String(repeating: "a", count: 60) + String(repeating: "z", count: 40))
        XCTAssertEqual(long.unicodeScalars.count, 40)
        XCTAssertEqual(long, String(repeating: "a", count: 20) + "…" + String(repeating: "z", count: 19))
        XCTAssertEqual(name(String(repeating: "x", count: 40)), String(repeating: "x", count: 40))
    }

    func testApproverPlatformsCarryAWarning() {
        for platform in ["ios", "android", "macos", " MacOS "] {
            XCTAssertTrue(PairingFingerprint.approverWarning(platform: platform)?.contains("approve unlocks, account changes and agent actions") == true)
        }
        for platform in ["linux", "windows", ""] { XCTAssertNil(PairingFingerprint.approverWarning(platform: platform)) }
    }

    func testDecodesPairingListWithAndWithoutDetails() throws {
        let legacy = #"{"requests":[{"id":"018f47de-936a-7000-8000-000000000001","device_name":"Phone","platform":"ios","fingerprint":"72dbb7336c767800","nonce":"00","candidate_public_key":"20","created_at":1,"expires_at":2}]}"#
        let old = try JSONDecoder().decode(PairingApprovalList.self, from: Data(legacy.utf8)).requests[0]
        XCTAssertNil(old.fingerprint_full); XCTAssertNil(old.ip); XCTAssertNil(old.model)
        let current = #"{"requests":[{"id":"018f47de-936a-7000-8000-000000000001","device_name":"Phone","platform":"ios","fingerprint":"72dbb7336c767800","fingerprint_full":"72dbb7336c76780023f83da4c355f2eeea85733b13d3477697917790c1229084","nonce":"00","candidate_public_key":"20","created_at":1,"expires_at":2,"ip":"192.0.2.7","user_agent":null,"app_version":"0.1.21","model":"iPhone16,1"}]}"#
        let new = try JSONDecoder().decode(PairingApprovalList.self, from: Data(current.utf8)).requests[0]
        XCTAssertEqual(new.fingerprint_full, full)
        XCTAssertEqual(new.ip, "192.0.2.7"); XCTAssertNil(new.user_agent)
        XCTAssertEqual(new.app_version, "0.1.21"); XCTAssertEqual(new.model, "iPhone16,1")
    }

    func testDecisionsSignOnlyAfterBiometricSuccess() async throws {
        let store = FixtureSecureStorage()
        let credentials = SecureCredentialStore(keychain: store)
        let identity = DeviceIdentityStore(keychain: store)
        let publicKey = try await identity.publicKeyHex()
        try await credentials.save(deviceId: PairingDecisionProtocol.device)
        try await credentials.save(session: SecureSession(token: "fixture-session-not-a-secret", expiresAt: Int64(Date().timeIntervalSince1970) + 3600))
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PairingDecisionProtocol.self]
        let transport = URLSession(configuration: configuration)
        defer { transport.invalidateAndCancel() }
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: transport)
        let prompts = PairingPromptCounter()
        let auth = AuthService(api: api, identity: identity, credentials: credentials,
                               authenticateBiometric: { _ in store.prompt(await prompts.authenticate()) })
        PairingDecisionProtocol.reset()
        let request = item(full: full)

        await prompts.set(.cancelled)
        do { try await auth.approvePairing(request); XCTFail("Cancelled biometrics must not sign") } catch { }
        do { try await auth.denyPairing(request); XCTFail("Cancelled biometrics must not deny") } catch { }
        XCTAssertTrue(PairingDecisionProtocol.decisions().isEmpty)

        await prompts.set(.unlocked)
        do { try await auth.approvePairing(item(full: other)); XCTFail("Mismatch must be refused") }
        catch { XCTAssertEqual(error as? PairingFingerprint.Failure, .mismatch) }
        do { try await auth.approvePairing(item(full: full, expiresIn: -1)); XCTFail("Expired request must be refused") } catch { }
        let refusedPrompts = await prompts.count
        XCTAssertEqual(refusedPrompts, 2, "Mismatched or expired requests never prompt")

        try await auth.approvePairing(request)
        let decisions = PairingDecisionProtocol.decisions()
        XCTAssertEqual(decisions.map { $0.path }, ["/v1/auth/pairing/requests/\(request.id.uuidString)/approve"])
        let signature = try XCTUnwrap(decisions.first?.body["signature"].flatMap { Data(hexEncoded: $0) })
        let message = try DeviceIdentityStore.pairingApprovalMessage(
            requestId: request.id, nonceHex: request.nonce, candidatePublicKeyHex: request.candidate_public_key,
            userId: PairingDecisionProtocol.user, approverDeviceId: PairingDecisionProtocol.device, expiresAt: request.expires_at)
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: XCTUnwrap(Data(hexEncoded: publicKey)))
        XCTAssertTrue(verifier.isValidSignature(signature, for: message))
    }
}

private actor PairingPromptCounter {
    private(set) var count = 0
    private(set) var reasons: [String] = []
    private var result = LocalUnlockResult.unlocked
    func set(_ value: LocalUnlockResult) { result = value }
    func authenticate(_ reason: String = "") -> LocalUnlockResult { count += 1; reasons.append(reason); return result }
}

private final class PairingDecisionProtocol: URLProtocol {
    static let device = UUID(uuidString: "04040404-0404-0404-0404-040404040404")!
    static let user = UUID(uuidString: "03030303-0303-0303-0303-030303030303")!
    private static let lock = NSLock()
    private static var captured: [(path: String, body: [String: String])] = []
    static func reset() { lock.withLock { captured = [] } }
    static func decisions() -> [(path: String, body: [String: String])] { lock.withLock { captured } }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url?.path ?? ""
        let object: [String: Any]
        if path == "/v1/auth/me" {
            object = ["user_id": Self.user.uuidString, "device_id": Self.device.uuidString]
        } else if path.hasPrefix("/v1/auth/pairing/requests/") {
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
            let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] ?? [:]
            Self.lock.withLock { Self.captured.append((path, body)) }
            object = [:]
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

final class AccountApprovalTests: XCTestCase {
    private let nonce = (0..<32).map { String(format: "%02x", $0) }.joined()

    private func approval(action: String = AccountApproval.deviceRevoke, user: UUID = AccountDecisionProtocol.user,
                          device: UUID = AccountDecisionProtocol.device, target: UUID = AccountDecisionProtocol.device,
                          expiresAt: Int64 = 4102444800) -> AccountApproval {
        AccountApproval(request_id: AccountDecisionProtocol.request, user_id: user, device_id: device, action: action,
                        target: target, nonce: nonce, expires_at: expiresAt)
    }

    /// client-core `account_approval_v1_matches_golden_bytes`.
    func testMessageMatchesClientCoreGoldenVectors() throws {
        let vector = AccountApproval(request_id: UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!,
            user_id: UUID(uuidString: "10213243-5465-7687-98a9-bacbdcedfe0f")!,
            device_id: UUID(uuidString: "ffeeddcc-bbaa-9988-7766-554433221100")!, action: AccountApproval.deviceRevoke,
            target: UUID(uuidString: "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")!, nonce: nonce, expires_at: 1_800_000_000)
        let tail = "00112233445566778899aabbccddeeff" + "102132435465768798a9bacbdcedfe0f" +
            "ffeeddccbbaa99887766554433221100" + "0f1e2d3c4b5a69788796a5b4c3d2e1f0" + nonce + "000000006b49d200"
        XCTAssertEqual(try vector.message().hexEncodedString(),
                       "6a61727669732d6163636f756e742d617070726f76616c2d7631006465766963652d7265766f6b6500" + tail)
        let password = AccountApproval(request_id: vector.request_id, user_id: vector.user_id, device_id: vector.device_id,
                                       action: AccountApproval.passwordSet, target: vector.target, nonce: nonce, expires_at: 1_800_000_000)
        XCTAssertEqual(try password.message().hexEncodedString(),
                       "6a61727669732d6163636f756e742d617070726f76616c2d76310070617373776f72642d73657400" + tail)
        // client-core `password_reset_approval_matches_golden_bytes`: target is the user.
        let reset = AccountApproval(request_id: vector.request_id, user_id: vector.user_id, device_id: vector.device_id,
                                    action: AccountApproval.passwordReset, target: vector.user_id, nonce: nonce, expires_at: 1_800_000_000)
        XCTAssertEqual(try reset.message().hexEncodedString(),
                       "6a61727669732d6163636f756e742d617070726f76616c2d763100" + "70617373776f72642d726573657400"
                       + "00112233445566778899aabbccddeeff" + "102132435465768798a9bacbdcedfe0f"
                       + "ffeeddccbbaa99887766554433221100" + "102132435465768798a9bacbdcedfe0f" + nonce + "000000006b49d200")
        for bad in [approval(action: "device-delete"), approval(expiresAt: 0),
                    AccountApproval(request_id: UUID(), user_id: UUID(), device_id: UUID(), action: AccountApproval.deviceRevoke,
                                    target: UUID(), nonce: String(repeating: "ab", count: 31), expires_at: 1)] {
            XCTAssertThrowsError(try bad.message())
        }
    }

    func testOnlyTheRequestedChallengeIsAccepted() throws {
        let me = AccountDecisionProtocol.user, device = AccountDecisionProtocol.device
        XCTAssertNoThrow(try approval().require(action: AccountApproval.deviceRevoke, user: me, device: device, target: device, now: 1))
        for other in [approval(action: AccountApproval.passwordSet), approval(user: UUID()), approval(device: UUID()),
                      approval(target: UUID()), approval(expiresAt: 1)] {
            XCTAssertThrowsError(try other.require(action: AccountApproval.deviceRevoke, user: me, device: device, target: device, now: 1))
        }
    }

    func testUnlinkSignsCoresRevokeChallengeAndErasesNothing() async throws {
        let (auth, credentials, publicKey, prompts) = try await fixture(revoke: AccountDecisionProtocol.json(approval()))
        try await auth.unlinkDevice()
        let promptCount = await prompts.count
        XCTAssertEqual(promptCount, 1)
        let calls = AccountDecisionProtocol.calls()
        XCTAssertEqual(calls.map { $0.path }, ["/v1/auth/me", "/v1/devices/\(AccountDecisionProtocol.device.uuidString)/revoke-request",
                                            "/v1/auth/account/requests/\(AccountDecisionProtocol.request.uuidString)/approve"])
        let signature = try XCTUnwrap(calls.last?.body["signature"].flatMap { Data(hexEncoded: $0) })
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: XCTUnwrap(Data(hexEncoded: publicKey)))
        XCTAssertTrue(verifier.isValidSignature(signature, for: try approval().message()))
        let device = try await credentials.deviceId()
        XCTAssertEqual(device, AccountDecisionProtocol.device, "Erasing is the user's separate decision")
    }

    func testSwappedChallengeOrRefusalNeverSigns() async throws {
        for revoke in [AccountDecisionProtocol.json(approval(target: UUID())), AccountDecisionProtocol.json(approval(user: UUID())),
                       AccountDecisionProtocol.json(approval(action: AccountApproval.passwordSet)), "{}", nil] {
            let (auth, _, _, prompts) = try await fixture(revoke: revoke)
            do { try await auth.unlinkDevice(); XCTFail("Must not unlink") } catch { }
            let promptCount = await prompts.count
            XCTAssertEqual(promptCount, 0)
            XCTAssertFalse(AccountDecisionProtocol.calls().contains { $0.path.hasSuffix("/approve") })
        }
    }

    func testRevokingAnotherDeviceUsesAFreshListAndRefusesUnknownIds() async throws {
        let other = UUID(uuidString: "05050505-0505-0505-0505-050505050505")!
        let gone = UUID(uuidString: "06060606-0606-0606-0606-060606060606")!
        let devices = #"{"devices":[{"id":"\#(other)","name":"Old\u202E laptop","platform":"linux","status":"active","created_at":1},"#
            + #"{"id":"\#(gone)","name":"Gone","platform":"ios","status":"revoked","created_at":1},"#
            + #"{"id":"\#(AccountDecisionProtocol.device)","name":"Me","platform":"ios","status":"active","created_at":1}]}"#
        let (auth, _, _, prompts) = try await fixture(revoke: AccountDecisionProtocol.json(approval(target: other)), devices: devices)
        try await auth.revokeDevice(other)
        XCTAssertEqual(AccountDecisionProtocol.calls().map { $0.path }.dropFirst(2).first, "/v1/devices/\(other.uuidString)/revoke-request")
        let reasons = await prompts.reasons
        XCTAssertEqual(reasons, ["Revoke \"Old laptop\" (linux, 05050505)"])
        for id in [UUID(), gone, AccountDecisionProtocol.device] {
            let (refused, _, _, refusedPrompts) = try await fixture(revoke: AccountDecisionProtocol.json(approval(target: id)), devices: devices)
            do { try await refused.revokeDevice(id); XCTFail("Must refuse \(id)") } catch { }
            let count = await refusedPrompts.count
            XCTAssertEqual(count, 0)
            XCTAssertFalse(AccountDecisionProtocol.calls().contains { $0.path.hasSuffix("/revoke-request") })
        }
    }

    func testPasswordChangeSignsCoresPasswordChallengeAndClearsTheSession() async throws {
        let challenge = approval(action: AccountApproval.passwordSet, target: AccountDecisionProtocol.user)
        let (auth, credentials, publicKey, _) = try await fixture(revoke: AccountDecisionProtocol.json(challenge))
        try await auth.changePassword(current: "fixture old password only", new: "fixture new password only")
        let calls = AccountDecisionProtocol.calls()
        XCTAssertEqual(calls.dropFirst().first?.path, "/v1/auth/account/password/requests")
        XCTAssertEqual(calls.dropFirst().first?.body, ["password": "fixture new password only", "current_password": "fixture old password only"])
        let signature = try XCTUnwrap(calls.last?.body["signature"].flatMap { Data(hexEncoded: $0) })
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: XCTUnwrap(Data(hexEncoded: publicKey)))
        XCTAssertTrue(verifier.isValidSignature(signature, for: try challenge.message()))
        let session = try await credentials.session()
        XCTAssertNil(session)
    }

    func testTheCurrentPasswordIsOnlyCheckedByCore() async throws {
        let challenge = approval(action: AccountApproval.passwordSet, target: AccountDecisionProtocol.user)
        // Short or old-style current passwords still reach Core.
        let (auth, _, _, _) = try await fixture(revoke: AccountDecisionProtocol.json(challenge))
        try await auth.changePassword(current: "old", new: "fixture new password only")
        XCTAssertEqual(AccountDecisionProtocol.calls().dropFirst().first?.path, "/v1/auth/account/password/requests")
        for current in ["", "old\u{7}pw", String(repeating: "x", count: 1025)] {
            let (refused, _, _, _) = try await fixture(revoke: AccountDecisionProtocol.json(challenge))
            do { try await refused.changePassword(current: current, new: "fixture new password only"); XCTFail("Must refuse") }
            catch { XCTAssertEqual((error as? LocalizedError)?.errorDescription, "Enter the current account password.") }
            XCTAssertTrue(AccountDecisionProtocol.calls().isEmpty)
        }
    }

    func testPasswordChangeRefusesBeforeSigning() async throws {
        let (short, _, _, _) = try await fixture(revoke: nil)
        do { try await short.changePassword(current: "fixture old password only", new: "too short"); XCTFail("Must refuse") } catch { }
        XCTAssertTrue(AccountDecisionProtocol.calls().isEmpty)
        let (wrong, _, _, _) = try await fixture(revoke: nil)
        do { try await wrong.changePassword(current: "fixture old password only", new: "fixture new password only"); XCTFail("Must refuse") }
        catch { XCTAssertEqual((error as? LocalizedError)?.errorDescription, "The current password was not accepted, or the session expired.") }
        for swapped in [approval(action: AccountApproval.passwordSet), approval(target: AccountDecisionProtocol.user)] {
            let (auth, credentials, _, prompts) = try await fixture(revoke: AccountDecisionProtocol.json(swapped))
            do { try await auth.changePassword(current: "fixture old password only", new: "fixture new password only"); XCTFail("Must refuse") } catch { }
            let count = await prompts.count
            XCTAssertEqual(count, 0)
            let session = try await credentials.session()
            XCTAssertNotNil(session)
        }
    }

    func testPasswordResetSignsCoresResetChallengeAndKeepsTheSession() async throws {
        let challenge = approval(action: AccountApproval.passwordReset, target: AccountDecisionProtocol.user)
        let pending = #"{"status":"pending_confirmation","request_id":"\#(AccountDecisionProtocol.request)","expires_in_seconds":900}"#
        let (auth, credentials, publicKey, prompts) = try await fixture(revoke: AccountDecisionProtocol.json(challenge), approve: pending)
        let id = try await auth.resetPassword(new: "fixture new password only")
        XCTAssertEqual(id, AccountDecisionProtocol.request)
        let reasons = await prompts.reasons
        XCTAssertEqual(reasons, ["Reset Jarvis account password"])
        let calls = AccountDecisionProtocol.calls()
        XCTAssertEqual(calls.dropFirst().first?.path, "/v1/auth/account/password/reset-requests")
        XCTAssertEqual(calls.dropFirst().first?.body, ["password": "fixture new password only"])
        let signature = try XCTUnwrap(calls.last?.body["signature"].flatMap { Data(hexEncoded: $0) })
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: XCTUnwrap(Data(hexEncoded: publicKey)))
        XCTAssertTrue(verifier.isValidSignature(signature, for: try challenge.message()))
        let session = try await credentials.session()
        XCTAssertNotNil(session, "Core ends the session only after the Home Node confirms")
    }

    func testPasswordResetRefusesBeforeSigningOrWithoutPendingConfirmation() async throws {
        let (short, _, _, _) = try await fixture(revoke: nil)
        do { _ = try await short.resetPassword(new: "too short"); XCTFail("Must refuse") } catch { }
        XCTAssertTrue(AccountDecisionProtocol.calls().isEmpty)
        // A password-set challenge, or a reset for another target, is never signed.
        for swapped in [approval(action: AccountApproval.passwordSet, target: AccountDecisionProtocol.user),
                        approval(action: AccountApproval.passwordReset)] {
            let (auth, _, _, prompts) = try await fixture(revoke: AccountDecisionProtocol.json(swapped))
            do { _ = try await auth.resetPassword(new: "fixture new password only"); XCTFail("Must refuse") } catch { }
            let count = await prompts.count
            XCTAssertEqual(count, 0)
        }
        // "completed" or another request id is not a pending reset.
        let challenge = approval(action: AccountApproval.passwordReset, target: AccountDecisionProtocol.user)
        for answer in [#"{"status":"completed"}"#, #"{"status":"pending_confirmation","request_id":"\#(UUID())"}"#] {
            let (auth, _, _, prompts) = try await fixture(revoke: AccountDecisionProtocol.json(challenge), approve: answer)
            do { _ = try await auth.resetPassword(new: "fixture new password only"); XCTFail("Must refuse") }
            catch { XCTAssertEqual((error as? LocalizedError)?.errorDescription, "Core did not confirm the change.") }
            let count = await prompts.count
            XCTAssertEqual(count, 1)
            XCTAssertTrue(AccountDecisionProtocol.calls().last?.path.hasSuffix("/approve") ?? false)
        }
    }

    func testAPendingConfirmationIsNotACompletedChangeOrUnlink() async throws {
        let pending = #"{"status":"pending_confirmation","request_id":"\#(AccountDecisionProtocol.request)"}"#
        let change = approval(action: AccountApproval.passwordSet, target: AccountDecisionProtocol.user)
        let (auth, credentials, _, _) = try await fixture(revoke: AccountDecisionProtocol.json(change), approve: pending)
        do { try await auth.changePassword(current: "fixture old password only", new: "fixture new password only"); XCTFail("Must refuse") }
        catch { XCTAssertEqual((error as? LocalizedError)?.errorDescription, "Core did not confirm the change.") }
        let session = try await credentials.session()
        XCTAssertNotNil(session)
        let (unlink, _, _, _) = try await fixture(revoke: AccountDecisionProtocol.json(approval()), approve: pending)
        do { try await unlink.unlinkDevice(); XCTFail("Must refuse") }
        catch { XCTAssertEqual((error as? LocalizedError)?.errorDescription, "Core did not confirm the change.") }
    }

    func testASecondApprovalIsRefusedWhileOneIsPrompting() async throws {
        let gate = PromptGate()
        let (auth, _, _, _) = try await fixture(revoke: AccountDecisionProtocol.json(approval()), prompt: { await gate.wait() })
        let first = Task { try await auth.unlinkDevice() }
        await gate.waitUntilPrompting()
        do { try await auth.unlinkDevice(); XCTFail("A second approval must be refused") }
        catch { XCTAssertEqual((error as? LocalizedError)?.errorDescription, "Another approval is in progress.") }
        await gate.release()
        try await first.value
    }

    private func fixture(revoke: String?, devices: String = #"{"devices":[]}"#, approve: String = #"{"status":"completed"}"#,
                         prompt: (@Sendable () async -> Void)? = nil) async throws
        -> (AuthService, SecureCredentialStore, String, PairingPromptCounter) {
        let store = FixtureSecureStorage()
        let credentials = SecureCredentialStore(keychain: store)
        let identity = DeviceIdentityStore(keychain: store)
        let publicKey = try await identity.publicKeyHex()
        try await credentials.save(deviceId: AccountDecisionProtocol.device)
        try await credentials.save(session: SecureSession(token: "fixture-session-not-a-secret", expiresAt: Int64(Date().timeIntervalSince1970) + 3600))
        AccountDecisionProtocol.reset(revoke: revoke, devices: devices, approve: approve)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AccountDecisionProtocol.self]
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: URLSession(configuration: configuration))
        let prompts = PairingPromptCounter()
        let auth = AuthService(api: api, identity: identity, credentials: credentials, authenticateBiometric: { reason in
            await prompt?()
            return store.prompt(await prompts.authenticate(reason))
        })
        return (auth, credentials, publicKey, prompts)
    }
}

private actor PromptGate {
    private var prompting: CheckedContinuation<Void, Never>?
    private var released: CheckedContinuation<Void, Never>?
    private var started = false
    func wait() async {
        started = true
        prompting?.resume(); prompting = nil
        await withCheckedContinuation { released = $0 }
    }
    func waitUntilPrompting() async {
        if started { return }
        await withCheckedContinuation { prompting = $0 }
    }
    func release() { released?.resume(); released = nil }
}

private final class AccountDecisionProtocol: URLProtocol {
    static let device = UUID(uuidString: "04040404-0404-0404-0404-040404040404")!
    static let user = UUID(uuidString: "03030303-0303-0303-0303-030303030303")!
    static let request = UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!
    private static let lock = NSLock()
    private static var revoke: String?
    private static var devices = #"{"devices":[]}"#
    private static var approve = #"{"status":"completed"}"#
    private static var captured: [(path: String, body: [String: String])] = []
    static func reset(revoke: String?, devices: String = #"{"devices":[]}"#, approve: String = #"{"status":"completed"}"#) {
        lock.withLock { self.revoke = revoke; self.devices = devices; self.approve = approve; captured = [] }
    }
    static func calls() -> [(path: String, body: [String: String])] { lock.withLock { captured } }
    static func json(_ approval: AccountApproval) -> String {
        #"{"request_id":"\#(approval.request_id)","user_id":"\#(approval.user_id)","device_id":"\#(approval.device_id)","action":"\#(approval.action)","target":"\#(approval.target)","nonce":"\#(approval.nonce)","expires_at":\#(approval.expires_at)}"#
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url?.path ?? ""
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
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] ?? [:]
        Self.lock.withLock { Self.captured.append((path, body)) }
        if path == "/v1/auth/me" {
            respond(200, #"{"user_id":"\#(Self.user)","device_id":"\#(Self.device)"}"#)
        } else if path == "/v1/devices" {
            respond(200, Self.lock.withLock { Self.devices })
        } else if path.hasSuffix("/revoke-request") || path.hasPrefix("/v1/auth/account/password/") {
            // No configured answer: Core refuses.
            if let revoke = Self.lock.withLock({ Self.revoke }) { respond(200, revoke) } else { respond(401, #"{"error":"unauthorized"}"#) }
        } else {
            respond(200, Self.lock.withLock { Self.approve })
        }
    }
    private func respond(_ status: Int, _ answer: String) {
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(answer.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

final class UnlockApprovalTests: XCTestCase {
    private let nonce = (0..<32).map { String(format: "%02x", $0) }.joined()

    func testFreshnessWindow() {
        func item(_ createdAt: Int64) -> UnlockRequestItem {
            UnlockRequestItem(id: UUID(), device_name: "Mac", platform: "macos", nonce: nonce, created_at: createdAt)
        }
        XCTAssertTrue(AuthService.fresh(item(1_000), now: 1_130))
        XCTAssertFalse(AuthService.fresh(item(1_000), now: 1_131))
        XCTAssertFalse(AuthService.fresh(item(1_031), now: 1_000))
    }

    func testApprovalRereadsTheRequestAndSignsTheUnlockMessage() async throws {
        let (auth, publicKey, prompts) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970) - 5)
        try await auth.approveUnlock(UnlockDecisionProtocol.request, choice: 89)
        let promptCount = await prompts.count
        XCTAssertEqual(promptCount, 1)
        let calls = UnlockDecisionProtocol.calls()
        XCTAssertEqual(calls.map { $0.path }, ["/v1/auth/unlock/pending", "/v1/auth/unlock/\(UnlockDecisionProtocol.request.uuidString)/approve"])
        let signature = try XCTUnwrap(calls.last?.body["signature"].flatMap { Data(hexEncoded: $0) })
        let message = try DeviceIdentityStore.unlockApprovalMessage(requestId: UnlockDecisionProtocol.request,
                                                                    approverDeviceId: UnlockDecisionProtocol.device, nonceHex: nonce)
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: XCTUnwrap(Data(hexEncoded: publicKey)))
        XCTAssertTrue(verifier.isValidSignature(signature, for: message))
    }

    func testStaleOrUnlistedRequestsNeverPrompt() async throws {
        let now = Int64(Date().timeIntervalSince1970)
        for (createdAt, id) in [(now - 200, UnlockDecisionProtocol.request), (now, UUID())] {
            let (auth, _, prompts) = try await fixture(createdAt: createdAt)
            do { try await auth.approveUnlock(id, choice: 89); XCTFail("Must refuse") } catch { }
            do { _ = try await auth.unlockChoices(id); XCTFail("Must refuse") } catch { }
            do { try await auth.denyUnlock(id); XCTFail("Must refuse") } catch { }
            let promptCount = await prompts.count
            XCTAssertEqual(promptCount, 0)
            XCTAssertFalse(UnlockDecisionProtocol.calls().contains { $0.path.hasSuffix("/approve") || $0.path.hasSuffix("/deny") })
        }
    }

    /// Same vectors as client-core `unlock_code` and the desktop and Android clients.
    func testUnlockCodeMatchesClientCoreVectors() throws {
        XCTAssertEqual(try UnlockCode.code(requestId: UnlockDecisionProtocol.request, nonceHex: nonce), 89)
        XCTAssertEqual(try UnlockCode.code(requestId: UUID(uuidString: "ffeeddcc-bbaa-9988-7766-554433221100")!,
                                           nonceHex: String(repeating: "0b", count: 32)), 0)
        XCTAssertEqual(UnlockCode.display(0), "00")
        XCTAssertEqual(UnlockCode.display(89), "89")
        XCTAssertThrowsError(try UnlockCode.code(requestId: UnlockDecisionProtocol.request, nonceHex: String(nonce.dropLast(2))))
    }

    func testChoicesHoldTheCodeAndTwoDistinctDecoys() throws {
        var random = SystemRandomNumberGenerator()
        var positions = [0, 0, 0]
        for code in (0..<3000).map({ $0 % 100 }) {
            let choices = UnlockCode.choices(for: code, using: &random)
            XCTAssertEqual(choices.count, 3)
            XCTAssertEqual(Set(choices).count, 3)
            XCTAssertTrue(choices.allSatisfy { (0..<100).contains($0) })
            positions[try XCTUnwrap(choices.firstIndex(of: code))] += 1
        }
        // Shuffled: the code is not stuck in one place (each slot ~1000 of 3000).
        XCTAssertTrue(positions.allSatisfy { $0 > 800 }, "\(positions)")
    }

    func testChoicesComeFromTheRequestListedForThatId() async throws {
        let (auth, _, prompts) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970))
        let choices = try await auth.unlockChoices(UnlockDecisionProtocol.request)
        XCTAssertTrue(choices.contains(89))
        XCTAssertEqual(Set(choices).count, 3)
        let promptCount = await prompts.count
        XCTAssertEqual(promptCount, 0)
        XCTAssertEqual(UnlockDecisionProtocol.calls().map { $0.path }, ["/v1/auth/unlock/pending"])
    }

    /// Fresh decoys per look would leak the code: it is the one number in every set.
    func testRepeatedLooksAtOneRequestShowTheSameChoices() async throws {
        let (auth, _, _) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970))
        let first = try await auth.unlockChoices(UnlockDecisionProtocol.request)
        for _ in 0..<20 {
            let again = try await auth.unlockChoices(UnlockDecisionProtocol.request)
            XCTAssertEqual(again, first)
        }
    }

    func testWrongPickDeniesWithoutPromptOrSignature() async throws {
        for choice in [0, 88, 90, 99] {
            let (auth, _, prompts) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970))
            do { try await auth.approveUnlock(UnlockDecisionProtocol.request, choice: choice); XCTFail("A wrong pick must not approve") }
            catch { XCTAssertTrue(error is UnlockWrongNumber, "\(error)") }
            let promptCount = await prompts.count
            XCTAssertEqual(promptCount, 0)
            let calls = UnlockDecisionProtocol.calls()
            XCTAssertEqual(calls.map { $0.path }, ["/v1/auth/unlock/pending", "/v1/auth/unlock/\(UnlockDecisionProtocol.request.uuidString)/deny"])
            XCTAssertTrue(calls.allSatisfy { $0.body["signature"] == nil })
        }
    }

    /// A full cache refuses a new set instead of evicting a live one (that would re-roll it).
    func testAFullChoiceCacheRefusesInsteadOfReRolling() async throws {
        let others = (0..<AuthService.maxUnlockChoiceSets).map { _ in UUID() }
        let (auth, _, _) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970), others: others)
        var sets: [[Int]] = []
        for id in others { sets.append(try await auth.unlockChoices(id)) }
        do { _ = try await auth.unlockChoices(UnlockDecisionProtocol.request); XCTFail("Must refuse") }
        catch { XCTAssertTrue(error is UnlockChoicesFull, "\(error)") }
        for (id, set) in zip(others, sets) {
            let again = try await auth.unlockChoices(id)
            XCTAssertEqual(again, set)
        }
    }

    func testApprovalStopsWhenJarvisLockedDuringFaceID() async throws {
        let (auth, _, prompts) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970))
        do { try await auth.approveUnlock(UnlockDecisionProtocol.request, choice: 89, unlocked: { false }); XCTFail("Must refuse") } catch { }
        let promptCount = await prompts.count
        XCTAssertEqual(promptCount, 1)
        XCTAssertFalse(UnlockDecisionProtocol.calls().contains { $0.path.hasSuffix("/approve") })
    }

    func testADuplicatedRequestIdIsRefused() async throws {
        let (auth, _, prompts) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970), copies: 2)
        do { try await auth.approveUnlock(UnlockDecisionProtocol.request, choice: 89); XCTFail("Must refuse") } catch { }
        do { try await auth.denyUnlock(UnlockDecisionProtocol.request); XCTFail("Must refuse") } catch { }
        let promptCount = await prompts.count
        XCTAssertEqual(promptCount, 0)
        XCTAssertFalse(UnlockDecisionProtocol.calls().contains { $0.path.hasSuffix("/approve") || $0.path.hasSuffix("/deny") })
    }

    func testDenyNeedsFaceIDToo() async throws {
        let (auth, _, prompts) = try await fixture(createdAt: Int64(Date().timeIntervalSince1970))
        await prompts.set(.cancelled)
        do { try await auth.denyUnlock(UnlockDecisionProtocol.request); XCTFail("Cancelled biometrics must not deny") } catch { }
        XCTAssertFalse(UnlockDecisionProtocol.calls().contains { $0.path.hasSuffix("/deny") })
        await prompts.set(.unlocked)
        try await auth.denyUnlock(UnlockDecisionProtocol.request)
        XCTAssertEqual(UnlockDecisionProtocol.calls().last?.path, "/v1/auth/unlock/\(UnlockDecisionProtocol.request.uuidString)/deny")
    }

    private func fixture(createdAt: Int64, copies: Int = 1, others: [UUID] = []) async throws -> (AuthService, String, PairingPromptCounter) {
        let store = FixtureSecureStorage()
        let credentials = SecureCredentialStore(keychain: store)
        let identity = DeviceIdentityStore(keychain: store)
        let publicKey = try await identity.publicKeyHex()
        try await credentials.save(deviceId: UnlockDecisionProtocol.device)
        try await credentials.save(session: SecureSession(token: "fixture-session-not-a-secret", expiresAt: Int64(Date().timeIntervalSince1970) + 3600))
        let item = #"{"id":"\#(UnlockDecisionProtocol.request)","device_name":"Work Mac","platform":"macos","nonce":"\#(nonce)","created_at":\#(createdAt)}"#
        let otherItems = others.map { #"{"id":"\#($0)","device_name":"Other Mac","platform":"macos","nonce":"\#(nonce)","created_at":\#(createdAt)}"# }
        UnlockDecisionProtocol.reset(pending: #"{"requests":["# + (Array(repeating: item, count: copies) + otherItems).joined(separator: ",") + "]}")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UnlockDecisionProtocol.self]
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: URLSession(configuration: configuration))
        let prompts = PairingPromptCounter()
        let auth = AuthService(api: api, identity: identity, credentials: credentials,
                               authenticateBiometric: { _ in store.prompt(await prompts.authenticate()) })
        return (auth, publicKey, prompts)
    }
}

private final class UnlockDecisionProtocol: URLProtocol {
    static let device = UUID(uuidString: "04040404-0404-0404-0404-040404040404")!
    static let request = UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!
    private static let lock = NSLock()
    private static var pending = ""
    private static var captured: [(path: String, body: [String: String])] = []
    static func reset(pending: String) { lock.withLock { self.pending = pending; captured = [] } }
    static func calls() -> [(path: String, body: [String: String])] { lock.withLock { captured } }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url?.path ?? ""
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
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] ?? [:]
        Self.lock.withLock { Self.captured.append((path, body)) }
        let answer: String
        if path == "/v1/auth/unlock/pending" {
            answer = Self.lock.withLock { Self.pending }
        } else {
            answer = path.hasSuffix("/approve") ? #"{"status":"approved"}"# : #"{"status":"denied"}"#
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(answer.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

/// A failed or unconfirmed revoke of this device never erases it silently.
@MainActor
final class UnlinkOutcomeTests: XCTestCase {
    private struct Refusal: LocalizedError {
        var errorDescription: String? { "Core refused\u{202E} this change.\n" + String(repeating: "x", count: 300) }
    }

    func testOnlyAConfirmedRevokeErases() {
        XCTAssertEqual(JarvisAppModel.afterUnlink(nil), .erase)
    }

    func testARefusalOnlyOffersErasingWithSanitisedText() {
        guard case let .offerLocalErase(reason) = JarvisAppModel.afterUnlink(Refusal()) else { return XCTFail("Must offer") }
        XCTAssertTrue(reason.hasPrefix("Core refused this change."))
        XCTAssertFalse(reason.unicodeScalars.contains { $0 == "\u{202E}" || $0 == "\n" })
        XCTAssertLessThanOrEqual(reason.count, 160)
    }

    func testACancelledOrBusyPromptOffersNothing() {
        XCTAssertEqual(JarvisAppModel.afterUnlink(ApprovalInterrupted.busy), .notice("Another approval is in progress."))
        XCTAssertEqual(JarvisAppModel.afterUnlink(ApprovalInterrupted.cancelled), .notice("Cancelled; Face ID or Touch ID is required."))
        XCTAssertEqual(JarvisAppModel.afterUnlink(CancellationError()), .nothing)
    }
}
