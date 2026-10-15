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

enum AuthServiceOutcome: Equatable {
    case needsEnrollment
    case needsPassword
    case needsActivation
    case awaitingApproval(expiresAt: Date)
    case authenticated
    case signedOut
}

actor AuthService {
    private var modelChangeRunning = false
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

    /// PUT or DELETE this device's own APNs push target. Core binds it to the
    /// session's device; no other device can be named.
    func setPushTarget(_ target: (token: String, environment: String)?) async throws {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        if let target {
            try await api.put("/v1/devices/self/push", body: ["apns_token": target.token, "apns_env": target.environment],
                              token: token, expectedBinding: binding)
        } else {
            try await api.delete("/v1/devices/self/push", token: token, expectedBinding: binding)
        }
    }

    /// This installation's key fingerprint, shown while it waits for pairing approval.
    func ownKeyFingerprint() async throws -> String {
        try PairingFingerprint.of(publicKeyHex: try await identity.existingPublicKeyHex())
    }

    /// Approve one pairing request: verify the fingerprint locally, build the exact
    /// v1 message, then sign with the context of that one Face ID / Touch ID prompt.
    func approvePairing(_ item: PairingApprovalItem) async throws {
        let fingerprint = try PairingFingerprint.verified(item)
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token,
              let device = try await credentials.deviceId() else { throw JarvisAPIError.unauthorized }
        // Core owns the user binding; learn it from the authenticated session.
        let me: AuthenticatedIdentity = try await api.get("/v1/auth/me", token: token, expectedBinding: binding)
        guard me.deviceId == device else { throw JarvisAPIError.unauthorized }
        let message = try DeviceIdentityStore.pairingApprovalMessage(
            requestId: item.id, nonceHex: item.nonce, candidatePublicKeyHex: item.candidate_public_key,
            userId: me.userId, approverDeviceId: device, expiresAt: item.expires_at)
        try requireUnexpired(item)
        let name = PairingFingerprint.hint(item.device_name, max: 40)
        let context = try await requireBiometric("Approve \(name) with key \(PairingFingerprint.grouped(String(fingerprint.prefix(16))))")
        defer { context.invalidate() }
        guard await api.binding() == binding, try await credentials.session()?.token == token,
              try await credentials.deviceId() == device else { throw JarvisAPIError.unauthorized }
        try requireUnexpired(item)
        try Task.checkCancellation()
        let signature = try await identity.signPairingApproval(message, context: context)
        try await pairingDecision("/v1/auth/pairing/requests/\(item.id.uuidString)/approve",
                                  body: ["signature": signature], token: token, binding: binding)
    }

    /// Deny one pairing request. Also gated on Face ID / Touch ID.
    func denyPairing(_ item: PairingApprovalItem) async throws {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        try await requireBiometric("Deny pairing of \(PairingFingerprint.hint(item.device_name, max: 40))").invalidate()
        guard await api.binding() == binding, try await credentials.session()?.token == token else { throw JarvisAPIError.unauthorized }
        try await pairingDecision("/v1/auth/pairing/requests/\(item.id.uuidString)/deny",
                                  body: [String: String](), token: token, binding: binding)
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
            throw JarvisAPIError.rejected(status: 403, message: "Cancelled; Face ID or Touch ID is required.")
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

    func setModelEnabled(_ entry: ModelAccessEntry, policyHash: String) async throws {
        guard !modelChangeRunning else { throw JarvisAPIError.rejected(status: 409, message: "Another model change is running.") }
        modelChangeRunning = true
        defer { modelChangeRunning = false }
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
    }
    /// Replace the owner routing document. Mirrors desktop `set_model_routing`: validate
    /// against a fresh snapshot, then sign bytes built here from the typed document. Any
    /// change gets its own Face ID / Touch ID prompt naming any cost relaxation or
    /// web-search opt-in.
    func setModelRouting(_ routing: RoutingDocument, routingHash: String) async throws {
        guard !modelChangeRunning else { throw JarvisAPIError.rejected(status: 409, message: "Another model change is running.") }
        modelChangeRunning = true
        defer { modelChangeRunning = false }
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
    }

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

    func resetDevice() async throws {
        let session = try await credentials.session()
        let deviceId = try await credentials.deviceId()
        if let token = session?.token, let deviceId {
            try? await api.delete("/v1/devices/\(deviceId.uuidString)", token: token)
        }
        // Local erasure is authoritative even if the Home Node is offline.
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
