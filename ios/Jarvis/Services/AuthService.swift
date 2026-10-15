import CryptoKit
import Foundation
import LocalAuthentication
import Security

enum DeviceLoginError: LocalizedError, Equatable {
    case deviceRejected
    case loginRejected

    var errorDescription: String? {
        switch self {
        case .deviceRejected:
            "Device challenge refused (HTTP 401). Check whether this iPhone is still approved in Core Admin. If revoked, explicitly reset its local binding and request approval again."
        case .loginRejected:
            "Password/device login refused (HTTP 401). Check the account password. If correct, this installation's key may not match the approved device. No settings or keys were erased."
        }
    }
}

/// An approval stopped on this device before anything was signed or sent.
enum ApprovalInterrupted: LocalizedError, Equatable {
    case busy, cancelled

    var errorDescription: String? {
        switch self {
        case .busy: "Another approval is in progress."
        case .cancelled: "Cancelled; Face ID or Touch ID is required."
        }
    }
}

enum AuthServiceOutcome: Equatable {
    case needsEnrollment
    case needsPassword
    case needsActivation
    case awaitingApproval(expiresAt: Date)
    case authenticated
    case signedOut
}

actor AuthService {
    private var approvalRunning = false
    private let api: JarvisAPIClient
    private let identity: DeviceIdentityStore
    private let credentials: SecureCredentialStore
    private let authenticateBiometric: @Sendable (String) async -> (result: LocalUnlockResult, context: LAContext?)

    init(
        api: JarvisAPIClient,
        identity: DeviceIdentityStore = DeviceIdentityStore(),
        credentials: SecureCredentialStore = SecureCredentialStore(),
        authenticateBiometric: @escaping @Sendable (String) async -> (result: LocalUnlockResult, context: LAContext?) = {
            await BiometricLock().biometricOnly(reason: $0)
        }
    ) {
        self.api = api
        self.identity = identity
        self.credentials = credentials
        self.authenticateBiometric = authenticateBiometric
    }

    func restore(password: String? = nil) async throws -> AuthServiceOutcome {
        if let session = try await credentials.session() {
            do {
                let _: AuthenticatedIdentity = try await api.get("/v1/auth/me", token: session.token)
                return .authenticated
            } catch JarvisAPIError.unauthorized {
                try await credentials.clearSession()
            }
        }
        if let deviceId = try await credentials.deviceId() {
            let status: AccountStatus = try await api.get("/v1/auth/account/status")
            if status.passwordRequired && password == nil { return .needsPassword }
            try await login(deviceId: deviceId, password: password)
            return .authenticated
        }
        if let ticket = try await credentials.pairingTicket() {
            return try await poll(ticket: ticket)
        }
        let status: AccountStatus = try await api.get("/v1/auth/account/status")
        return status.bootstrapRequired ? .needsActivation : .needsEnrollment
    }

    func requestEnrollment(deviceName: String, password: String? = nil) async throws -> AuthServiceOutcome {
        if try await credentials.deviceId() != nil { return try await restore(password: password) }
        if let ticket = try await credentials.pairingTicket() {
            return try await poll(ticket: ticket)
        }
        let request = EnrollmentRequest(
            name: deviceName.prefix(128).description,
            platform: "ios",
            publicKey: try await identity.publicKeyHex(),
            password: password
        )
        let response: PairingRequestResponse = try await api.post(
            "/v1/auth/pairing/requests",
            body: request
        )
        let ticket = PairingTicket(
            requestId: response.requestId,
            nonce: response.nonce,
            expiresAt: response.expiresAt
        )
        try await credentials.save(pairingTicket: ticket)
        return .awaitingApproval(expiresAt: Date(timeIntervalSince1970: TimeInterval(ticket.expiresAt)))
    }

    func refreshEnrollment() async throws -> AuthServiceOutcome {
        guard let ticket = try await credentials.pairingTicket() else { return .needsEnrollment }
        return try await poll(ticket: ticket)
    }

    func activateFirstDevice(deviceName: String, code: String, password: String) async throws -> AuthServiceOutcome {
        guard password.count >= 15, password.utf8.count <= 1024 else { throw JarvisAPIError.invalidAccountPassword }
        let response = try await api.activateFirstDevice(EnrollmentRequest(name: String(deviceName.prefix(128)),
            platform: "ios", publicKey: try await identity.publicKeyHex(), password: password), code: code)
        try await credentials.save(deviceId: response.deviceId)
        try await login(deviceId: response.deviceId, password: password)
        return .authenticated
    }

    func sessionToken() async throws -> String? { try await credentials.session()?.token }

    func homeNodeRegistry() async throws -> HomeNodeRegistry {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        return try await api.get("/v1/system/registry", token: token, expectedBinding: binding)
    }

    func modelPolicy() async throws -> ModelPolicySnapshot {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        return try await api.get("/v1/system/models", token: token, expectedBinding: binding)
    }

    /// Authenticated GET for read-only owner routes, bound to the current origin.
    func ownerGet<Response: Decodable>(_ path: String) async throws -> Response {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        return try await api.get(path, token: token, expectedBinding: binding)
    }

    /// Authenticated POST with an empty JSON body, bound to the current origin.
    func ownerPost(_ path: String) async throws {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        try await api.postDiscardingResponse(path, body: [String: String](), token: token, expectedBinding: binding)
    }

    func registeredDeviceID() async -> UUID? { try? await credentials.deviceId() }

    /// This installation's key fingerprint, shown while it waits for pairing approval.
    func ownKeyFingerprint() async throws -> String {
        try PairingFingerprint.of(publicKeyHex: try await identity.existingPublicKeyHex())
    }

    /// Approve one pairing request: verify the fingerprint locally, build the exact
    /// v1 message, then sign with the context of that one Face ID / Touch ID prompt.
    func approvePairing(_ item: PairingApprovalItem) async throws { try await exclusive {
        let fingerprint = try PairingFingerprint.verified(item)
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token,
              let device = try await credentials.deviceId() else { throw JarvisAPIError.unauthorized }
        // Core owns the user binding; learn it from the authenticated session.
        let me: AuthenticatedIdentity = try await api.get("/v1/auth/me", token: token, expectedBinding: binding)
        guard me.deviceId == device else { throw JarvisAPIError.unauthorized }
        // Built once before prompting, so a malformed request never prompts.
        _ = try DeviceIdentityStore.pairingApprovalMessage(
            requestId: item.id, nonceHex: item.nonce, candidatePublicKeyHex: item.candidate_public_key,
            userId: me.userId, approverDeviceId: device, expiresAt: item.expires_at)
        try requireUnexpired(item)
        // The verified key comes first, so a candidate-chosen name cannot pose as it.
        let context = try await requireBiometric("Pair key \(PairingFingerprint.grouped(String(fingerprint.prefix(16)))): "
            + "\"\(PairingFingerprint.displayName(item.device_name, max: 40))\" (\(PairingFingerprint.displayName(item.platform, max: 16)))")
        defer { context.invalidate() }
        guard await api.binding() == binding, try await credentials.session()?.token == token,
              try await credentials.deviceId() == device else { throw JarvisAPIError.unauthorized }
        try requireUnexpired(item)
        try Task.checkCancellation()
        let signature = try await identity.signPairingApproval(
            requestId: item.id, nonceHex: item.nonce, candidatePublicKeyHex: item.candidate_public_key,
            userId: me.userId, approverDeviceId: device, expiresAt: item.expires_at, context: context)
        try await pairingDecision("/v1/auth/pairing/requests/\(item.id.uuidString)/approve",
                                  body: ["signature": signature], token: token, binding: binding)
    } }

    /// Deny one pairing request. Also gated on Face ID / Touch ID.
    func denyPairing(_ item: PairingApprovalItem) async throws { try await exclusive {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        try await requireBiometric("Deny pairing of \"\(PairingFingerprint.displayName(item.device_name, max: 40))\"").invalidate()
        guard await api.binding() == binding, try await credentials.session()?.token == token else { throw JarvisAPIError.unauthorized }
        try await pairingDecision("/v1/auth/pairing/requests/\(item.id.uuidString)/deny",
                                  body: [String: String](), token: token, binding: binding)
    } }

    /// One approval (fetch, prompt, sign, send) at a time across the app. A second one
    /// is refused rather than queued, so prompts never stack up behind each other.
    private func exclusive<T>(_ body: () async throws -> T) async throws -> T {
        guard !approvalRunning else { throw ApprovalInterrupted.busy }
        approvalRunning = true
        defer { approvalRunning = false }
        return try await body()
    }

    private func requireUnexpired(_ item: PairingApprovalItem) throws {
        guard item.expires_at > Int64(Date().timeIntervalSince1970) else {
            throw JarvisAPIError.rejected(status: 410, message: "This pairing request has expired.")
        }
    }

    /// Face ID / Touch ID only; returns the evaluated context that unlocks the device key.
    private func requireBiometric(_ reason: String) async throws -> LAContext {
        let (result, context) = await authenticateBiometric(reason)
        if case let .unavailable(message) = result { throw JarvisAPIError.rejected(status: 403, message: message) }
        guard result == .unlocked, let context else {
            throw ApprovalInterrupted.cancelled
        }
        return context
    }

    /// Core answers 401 for an unknown, handled or expired request too; report that
    /// as a refusal instead of ending the session (the next read still detects a real 401).
    private func pairingDecision(_ path: String, body: [String: String], token: String, binding: UUID) async throws {
        do {
            try await api.postDiscardingResponse(path, body: body, token: token, expectedBinding: binding)
        } catch JarvisAPIError.unauthorized {
            throw JarvisAPIError.rejected(status: 401, message: "Core refused this decision. The request may have expired or been handled already.")
        }
    }

    func setModelEnabled(_ entry: ModelAccessEntry, policyHash: String) async throws { try await exclusive {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token,
              let device = try await credentials.deviceId() else { throw JarvisAPIError.unauthorized }
        let snapshot: ModelPolicySnapshot = try await api.get("/v1/system/models", token: token, expectedBinding: binding)
        let now = Int64(Date().timeIntervalSince1970)
        guard snapshot.mutable, snapshot.policy_sha256 == policyHash, snapshot.device_id == device,
              snapshot.server_time >= now - 30, snapshot.server_time <= now + 30,
              snapshot.models.contains(where: { $0.provider == entry.provider && $0.model == entry.model && $0.enabled == entry.enabled })
        else { throw JarvisAPIError.rejected(status: 409, message: "Model policy changed. Refresh before approving.") }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw JarvisAPIError.invalidResponse }
        let approval = ModelToggleApproval(request: UUID(), nonce: Data(bytes), user: snapshot.user_id, device: device,
            issued: now, expires: now + 120, provider: entry.provider, model: entry.model, enabled: !entry.enabled, hash: policyHash)
        _ = try approval.message()
        let key = try await identity.existingPublicKeyHex()
        try Task.checkCancellation()
        // Every change gets its own Face ID / Touch ID prompt; nothing is remembered.
        let context = try await requireBiometric("\(entry.enabled ? "Disable" : "Enable") a Jarvis model")
        defer { context.invalidate() }
        guard await api.binding() == binding, try await credentials.session()?.token == token,
              try await credentials.deviceId() == device, try await identity.existingPublicKeyHex() == key,
              Int64(Date().timeIntervalSince1970) < approval.expires else { throw JarvisAPIError.unauthorized }
        try Task.checkCancellation()
        let signature = try await identity.signModelToggle(approval, context: context)
        let result: StatusResponse = try await api.post("/v1/system/config/privileged", body: approval.signed(signature), token: token, expectedBinding: binding)
        guard result.status == "active" else { throw JarvisAPIError.rejected(status: 503, message: "Model activation was not confirmed. Refresh its status.") }
    } }
    /// Replace the owner routing document. Mirrors desktop `set_model_routing`: validate
    /// against a fresh snapshot, then sign bytes built here from the typed document. Any
    /// change gets its own Face ID / Touch ID prompt naming any cost relaxation or
    /// web-search opt-in.
    func setModelRouting(_ routing: RoutingDocument, routingHash: String) async throws { try await exclusive {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token,
              let device = try await credentials.deviceId() else { throw JarvisAPIError.unauthorized }
        let snapshot: ModelPolicySnapshot = try await api.get("/v1/system/models", token: token, expectedBinding: binding)
        let now = Int64(Date().timeIntervalSince1970)
        guard snapshot.routingMode == .editable, snapshot.routing_sha256 == routingHash, snapshot.device_id == device,
              snapshot.server_time >= now - 30, snapshot.server_time <= now + 30
        else { throw JarvisAPIError.rejected(status: 409, message: "Model routing, device or clock changed; refresh and try again.") }
        if let issue = RoutingRules.issue(routing, discovered: snapshot.models.map { RouteEntry($0.provider, $0.model) }) {
            throw JarvisAPIError.rejected(status: 400, message: issue)
        }
        let relaxed = RoutingRules.relaxation(current: snapshot.currentRouting, next: routing)
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw JarvisAPIError.invalidResponse }
        let approval = ModelRoutingApproval(request: UUID(), nonce: Data(bytes), user: snapshot.user_id, device: device,
            issued: now, expires: now + RoutingRules.approvalSeconds, routing: routing, hash: routingHash)
        _ = try approval.message()
        // The broker reads one 16 KiB frame; refuse before prompting.
        let sized = try approval.signed(String(repeating: "00", count: 64))
        guard try JSONEncoder().encode(["request": sized]).count < 16 * 1024 else {
            throw JarvisAPIError.rejected(status: 400, message: "Routing is too large to send")
        }
        let key = try await identity.existingPublicKeyHex()
        try Task.checkCancellation()
        let context = try await requireBiometric(relaxed.prompt)
        defer { context.invalidate() }
        guard await api.binding() == binding, try await credentials.session()?.token == token,
              try await credentials.deviceId() == device, try await identity.existingPublicKeyHex() == key,
              Int64(Date().timeIntervalSince1970) < approval.expires else { throw JarvisAPIError.unauthorized }
        try Task.checkCancellation()
        let signature = try await identity.signModelRouting(approval, context: context)
        let result: StatusResponse
        do {
            result = try await api.post("/v1/system/config/privileged", body: approval.signed(signature), token: token, expectedBinding: binding)
        } catch JarvisAPIError.rejected(let status, _) {
            throw JarvisAPIError.rejected(status: status, message: RoutingRules.refused(status: status))
        } catch JarvisAPIError.unreachable, JarvisAPIError.timedOut {
            throw JarvisAPIError.rejected(status: 503, message: "Outcome unknown; refresh the model status")
        }
        guard result.status == "active" else { throw JarvisAPIError.rejected(status: 503, message: "Activation not confirmed. Refresh the routing status.") }
    } }

    // Origin changes must clear server-specific state before the new host is
    // configured. No revocation request is sent to the replacement host.
    func clearLocalBinding() async throws { try await credentials.reset(); try await identity.reset() }

    /// Silent launch-time move of an older device key behind Face ID / Touch ID.
    func migrateIdentity() async throws { try await identity.migrateIfNeeded() }

    func requiresLocalUnlock() async throws -> Bool {
        let hasSession = try await credentials.session() != nil
        let hasRegisteredDevice = try await credentials.deviceId() != nil
        return hasSession || hasRegisteredDevice
    }

    func logout() async throws -> AuthServiceOutcome {
        if let token = try await credentials.session()?.token {
            let _: StatusResponse? = try? await api.post(
                "/v1/auth/logout",
                token: token,
                response: StatusResponse.self
            )
        }
        try await credentials.clearSession()
        return .signedOut
    }

    /// Revokes this device on Core with a signed device-revoke approval. Throws unless
    /// Core confirmed; erases nothing (the user decides, see `eraseLocally`).
    func unlinkDevice() async throws { try await exclusive {
        let signedIn = try await signedIn()
        try await revoke(signedIn, target: signedIn.device, reason: "Unlink this device from Jarvis")
    } }

    /// Revokes another device of this account. The prompt names it from a fresh device
    /// list; an id Core does not list as active is refused before anything is signed.
    func revokeDevice(_ id: UUID) async throws { try await exclusive {
        let signedIn = try await signedIn()
        guard id != signedIn.device else {
            throw JarvisAPIError.rejected(status: 409, message: "Use Reset this device to remove this iPhone.")
        }
        let list: DeviceListResponse = try await accountDecision {
            try await api.get("/v1/devices", token: signedIn.token, expectedBinding: signedIn.binding)
        }
        let matches = list.devices.filter { UUID(uuidString: $0.id) == id && $0.status == "active" }
        guard matches.count == 1, let target = matches.first else {
            throw JarvisAPIError.rejected(status: 404, message: "Core no longer lists this device as active.")
        }
        try await revoke(signedIn, target: id, reason: "Revoke \"\(PairingFingerprint.displayName(target.name, max: 40))\" "
            + "(\(PairingFingerprint.displayName(target.platform, max: 16)))")
    } }

    /// Changes the account password with the current one. Core binds the new verifier to
    /// this device's signed approval and then ends every session, so this session is
    /// cleared here and the user signs in again with the new password.
    func changePassword(current: String, new: String) async throws { try await exclusive {
        guard Self.acceptablePassword(current) else {
            throw JarvisAPIError.rejected(status: 400, message: "Enter the current account password.")
        }
        guard Self.acceptablePassword(new) else {
            throw JarvisAPIError.rejected(status: 400, message: "Choose a new password of at least 15 characters and at most 1024 bytes, without control characters.")
        }
        let signedIn = try await signedIn()
        let approval: AccountApproval
        do {
            approval = try await accountDecision {
                try await api.post("/v1/auth/account/password/requests", body: ["password": new, "current_password": current],
                                   token: signedIn.token, expectedBinding: signedIn.binding)
            }
        } catch JarvisAPIError.rejected(let status, _) where status == 401 {
            throw JarvisAPIError.rejected(status: 401, message: "The current password was not accepted, or the session expired.")
        }
        // Sign only the request Core just issued for this account's own password.
        try approval.require(action: AccountApproval.passwordSet, user: signedIn.user, device: signedIn.device,
                             target: signedIn.user, now: Int64(Date().timeIntervalSince1970))
        try await signAndApprove(approval, signedIn: signedIn, reason: "Change the Jarvis account password")
        // Core already ended every session; a local clearing failure must not report "not changed".
        try? await credentials.clearSession()
    } }

    /// Core's account password rules: at least 15 characters, at most 1024 UTF-8 bytes, no controls.
    static func acceptablePassword(_ password: String) -> Bool {
        password.unicodeScalars.count >= 15 && password.utf8.count <= 1024
            && !password.unicodeScalars.contains { $0.properties.generalCategory == .control }
    }

    private struct SignedIn { let binding: UUID; let token: String; let device: UUID; let user: UUID }

    /// The signed-in session, confirmed by Core to belong to this device.
    private func signedIn() async throws -> SignedIn {
        let binding = await api.binding()
        guard let device = try await credentials.deviceId() else {
            throw JarvisAPIError.rejected(status: 409, message: "This device is not paired, so there is nothing to revoke on Core.")
        }
        guard let token = try await credentials.session()?.token else {
            throw JarvisAPIError.rejected(status: 401, message: "Not signed in, so Core cannot be asked for this change.")
        }
        let me: AuthenticatedIdentity = try await accountDecision {
            try await api.get("/v1/auth/me", token: token, expectedBinding: binding)
        }
        guard me.deviceId == device else { throw JarvisAPIError.rejected(status: 401, message: "Session does not belong to this device.") }
        return SignedIn(binding: binding, token: token, device: device, user: me.userId)
    }

    private func revoke(_ signedIn: SignedIn, target: UUID, reason: String) async throws {
        let approval: AccountApproval = try await accountDecision {
            try await api.post("/v1/devices/\(target.uuidString)/revoke-request", body: [String: String](),
                               token: signedIn.token, expectedBinding: signedIn.binding)
        }
        try approval.require(action: AccountApproval.deviceRevoke, user: signedIn.user, device: signedIn.device, target: target,
                             now: Int64(Date().timeIntervalSince1970))
        try await signAndApprove(approval, signedIn: signedIn, reason: reason)
    }

    /// One Face ID / Touch ID prompt, then the approval, only while nothing changed.
    private func signAndApprove(_ approval: AccountApproval, signedIn: SignedIn, reason: String) async throws {
        let context = try await requireBiometric(reason)
        defer { context.invalidate() }
        guard await api.binding() == signedIn.binding, try await credentials.session()?.token == signedIn.token,
              try await credentials.deviceId() == signedIn.device, approval.expires_at > Int64(Date().timeIntervalSince1970) else {
            throw JarvisAPIError.rejected(status: 409, message: "Approval expired or session changed.")
        }
        try Task.checkCancellation()
        let signature = try await identity.signAccountApproval(approval, context: context)
        let result: StatusResponse
        do {
            result = try await accountDecision {
                try await api.post("/v1/auth/account/requests/\(approval.request_id.uuidString)/approve",
                                   body: ["signature": signature], token: signedIn.token, expectedBinding: signedIn.binding)
            }
        } catch JarvisAPIError.rejected(let status, _) where status == 503 {
            // The signed approval may have reached Core before the connection failed.
            throw JarvisAPIError.rejected(status: 503, message: "Home Node unreachable; the outcome is unknown. Refresh, or sign in again if asked.")
        }
        guard result.status == "completed" else { throw JarvisAPIError.rejected(status: 503, message: "Core did not confirm the change.") }
    }

    /// Unlock requests waiting for this device, held up to `wait` seconds until one appears.
    /// Callers poll only while the app is in the foreground and unlocked. Staleness is
    /// checked when deciding, not here.
    func pendingUnlocks(wait: Int) async throws -> [UnlockRequestItem] {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        // Stay inside the 15 s request timeout; Core holds the call at most 25 s.
        let list: UnlockRequestList = try await api.get("/v1/auth/unlock/pending?wait=\(min(max(wait, 0), 10))",
                                                        token: token, expectedBinding: binding)
        return list.requests
    }

    /// Three numbers for the owner to pick from, built here from the request Core lists for
    /// this id; one of them is the code the other device shows.
    func unlockChoices(_ id: UUID) async throws -> [Int] {
        let (_, _, item) = try await currentUnlock(id)
        let code = try UnlockCode.code(requestId: item.id, nonceHex: item.nonce)
        var random = SystemRandomNumberGenerator()
        return UnlockCode.choices(for: code, using: &random)
    }

    /// Approve another device's unlock with the owner's number pick: re-read the request by
    /// id and refuse a stale one. A wrong pick denies it without any prompt or signature;
    /// the right one signs the exact v1 unlock message after one Face ID / Touch ID prompt.
    func approveUnlock(_ id: UUID, choice: Int) async throws { try await exclusive {
        let (binding, token, item) = try await currentUnlock(id)
        let code = try UnlockCode.code(requestId: item.id, nonceHex: item.nonce)
        guard choice == code else {
            try await postUnlockDeny(item.id, token: token, binding: binding)
            throw UnlockWrongNumber()
        }
        guard let device = try await credentials.deviceId() else { throw JarvisAPIError.unauthorized }
        // Built once before prompting, so a malformed request never prompts.
        _ = try DeviceIdentityStore.unlockApprovalMessage(requestId: item.id, approverDeviceId: device, nonceHex: item.nonce)
        let context = try await requireBiometric("Unlock \"\(PairingFingerprint.displayName(item.device_name, max: 40))\" "
            + "(\(PairingFingerprint.displayName(item.platform, max: 16)))")
        defer { context.invalidate() }
        guard await api.binding() == binding, try await credentials.session()?.token == token,
              try await credentials.deviceId() == device, Self.fresh(item) else {
            throw JarvisAPIError.rejected(status: 409, message: "Request expired or session changed.")
        }
        try Task.checkCancellation()
        let signature = try await identity.signUnlockApproval(requestId: item.id, approverDeviceId: device, nonceHex: item.nonce, context: context)
        let result: StatusResponse = try await accountDecision {
            try await api.post("/v1/auth/unlock/\(item.id.uuidString)/approve", body: ["signature": signature],
                               token: token, expectedBinding: binding)
        }
        guard result.status == "approved" else { throw JarvisAPIError.rejected(status: 503, message: "Core did not confirm the decision.") }
    } }

    /// Deny another device's unlock. Also re-read by id and gated on Face ID / Touch ID.
    func denyUnlock(_ id: UUID) async throws { try await exclusive {
        let (binding, token, item) = try await currentUnlock(id)
        try await requireBiometric("Deny unlock of \"\(PairingFingerprint.displayName(item.device_name, max: 40))\"").invalidate()
        guard await api.binding() == binding, try await credentials.session()?.token == token else {
            throw JarvisAPIError.rejected(status: 409, message: "Session changed; try again.")
        }
        try await postUnlockDeny(item.id, token: token, binding: binding)
    } }

    private func postUnlockDeny(_ id: UUID, token: String, binding: UUID) async throws {
        let result: StatusResponse = try await accountDecision {
            try await api.post("/v1/auth/unlock/\(id.uuidString)/deny", body: [String: String](), token: token, expectedBinding: binding)
        }
        guard result.status == "denied" else { throw JarvisAPIError.rejected(status: 503, message: "Core did not confirm the decision.") }
    }

    /// The request as Core lists it right now, never a copy the UI held on to.
    private func currentUnlock(_ id: UUID) async throws -> (UUID, String, UnlockRequestItem) {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        let list: UnlockRequestList = try await accountDecision {
            try await api.get("/v1/auth/unlock/pending?wait=0", token: token, expectedBinding: binding)
        }
        guard let item = list.requests.first(where: { $0.id == id }) else {
            throw JarvisAPIError.rejected(status: 404, message: "This unlock request is no longer pending.")
        }
        guard Self.fresh(item) else {
            throw JarvisAPIError.rejected(status: 410, message: "This unlock request is too old; ask again on the other device.")
        }
        return (binding, token, item)
    }

    /// Core expires unlock requests after two minutes; anything older is refused here too.
    static func fresh(_ item: UnlockRequestItem, now: Int64 = Int64(Date().timeIntervalSince1970)) -> Bool {
        (-30...130).contains(now - item.created_at)
    }

    /// Core answers 401 for a refused account action too; report it instead of ending
    /// the session here.
    private func accountDecision<T>(_ call: () async throws -> T) async throws -> T {
        do { return try await call() }
        catch JarvisAPIError.unauthorized { throw JarvisAPIError.rejected(status: 401, message: "Core refused this change.") }
        catch JarvisAPIError.unreachable, JarvisAPIError.timedOut {
            throw JarvisAPIError.rejected(status: 503, message: "Home Node unreachable; the change was not confirmed.")
        }
    }

    /// Erases this device's session, binding and key here only.
    func eraseLocally() async throws {
        var resetError: Error?
        do { try await credentials.reset() }
        catch { resetError = error }
        do { try await identity.reset() }
        catch { if resetError == nil { resetError = error } }
        if let resetError { throw resetError }
    }

    private func poll(ticket: PairingTicket) async throws -> AuthServiceOutcome {
        guard ticket.expiresAt > Int64(Date().timeIntervalSince1970) else {
            try await credentials.clearPairingTicket()
            return .needsEnrollment
        }
        let status: PairingStatusResponse = try await api.get(
            "/v1/auth/pairing/requests/\(ticket.requestId.uuidString)/status",
            headers: ["X-Jarvis-Pairing-Nonce": ticket.nonce]
        )
        switch status.status {
        case .pending:
            return .awaitingApproval(expiresAt: Date(timeIntervalSince1970: TimeInterval(ticket.expiresAt)))
        case .approved:
            guard let deviceId = status.deviceId else { throw JarvisAPIError.invalidResponse }
            try await credentials.save(deviceId: deviceId)
            try await credentials.clearPairingTicket()
            return try await restore()
        case .denied, .expired:
            try await credentials.clearPairingTicket()
            return .needsEnrollment
        }
    }

    private func login(deviceId: UUID, password: String? = nil) async throws {
        // Prompt before fetching the challenge so a slow prompt cannot expire it.
        let context = try await requireBiometric("Sign in to Jarvis")
        defer { context.invalidate() }
        let challenge: ChallengeResponse
        do {
            challenge = try await api.post("/v1/auth/challenge", body: ChallengeRequest(deviceId: deviceId))
        } catch JarvisAPIError.unauthorized {
            throw DeviceLoginError.deviceRejected
        }
        let signature = try await identity.signLogin(challengeId: challenge.challengeId, deviceId: deviceId,
                                                     nonceHex: challenge.nonce, context: context)
        let response: LoginResponse
        do {
            response = try await api.post(
                "/v1/auth/login",
                body: LoginRequest(
                    deviceId: deviceId,
                    challengeId: challenge.challengeId,
                    signature: signature,
                    password: password
                )
            )
        } catch JarvisAPIError.unauthorized {
            throw DeviceLoginError.loginRejected
        }
        try await credentials.save(session: SecureSession(token: response.token, expiresAt: response.expiresAt))
    }
}

private struct StatusResponse: Decodable { let status: String }

/// The owner picked the wrong number; the unlock request has been denied.
struct UnlockWrongNumber: LocalizedError {
    var errorDescription: String? { "Wrong number. The unlock request was denied." }
}

/// Unlock number matching, mirroring client-core `unlock_code`: the first two bytes
/// (big-endian) of SHA-256("jarvis-unlock-code-v1\0" || request id || 32-byte nonce),
/// modulo 100. The requesting device shows it; the owner must pick it here.
enum UnlockCode {
    static func code(requestId: UUID, nonceHex: String) throws -> Int {
        guard let nonce = Data(strictHex: nonceHex), nonce.count == 32 else { throw DeviceIdentityError.invalidChallenge }
        var input = Data("jarvis-unlock-code-v1\0".utf8)
        input.append(requestId.rawBytes); input.append(nonce)
        let digest = Array(SHA256.hash(data: input))
        return (Int(digest[0]) << 8 | Int(digest[1])) % 100
    }

    /// The code and two distinct random decoys, shuffled.
    static func choices<R: RandomNumberGenerator>(for code: Int, using random: inout R) -> [Int] {
        var choices = [code]
        while choices.count < 3 {
            let decoy = Int.random(in: 0..<100, using: &random)
            if !choices.contains(decoy) { choices.append(decoy) }
        }
        return choices.shuffled(using: &random)
    }

    static func display(_ code: Int) -> String { String(format: "%02d", code) }
}
