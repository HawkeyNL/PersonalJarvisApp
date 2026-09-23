import Foundation
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
    private let api: JarvisAPIClient
    private let identity: DeviceIdentityStore
    private let credentials: SecureCredentialStore
    private let authenticateOwner: @Sendable (String) async -> LocalUnlockResult

    init(
        api: JarvisAPIClient,
        identity: DeviceIdentityStore = DeviceIdentityStore(),
        credentials: SecureCredentialStore = SecureCredentialStore(),
        authenticateOwner: @escaping @Sendable (String) async -> LocalUnlockResult = { await BiometricLock().unlock(reason: $0) }
    ) {
        self.api = api
        self.identity = identity
        self.credentials = credentials
        self.authenticateOwner = authenticateOwner
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

    func modelPolicy() async throws -> ModelPolicySnapshot {
        let binding = await api.binding()
        guard let token = try await credentials.session()?.token else { throw JarvisAPIError.unauthorized }
        return try await api.get("/v1/system/models", token: token, expectedBinding: binding)
    }

    func setModelEnabled(_ entry: ModelAccessEntry, policyHash: String) async throws {
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
        guard await authenticateOwner("\(entry.enabled ? "Disable" : "Enable") Jarvis model \(entry.provider)/\(entry.model)") == .unlocked
        else { throw JarvisAPIError.rejected(status: 403, message: "Model change cancelled; device authentication is required.") }
        guard await api.binding() == binding, try await credentials.session()?.token == token,
              try await credentials.deviceId() == device, try await identity.existingPublicKeyHex() == key,
              Int64(Date().timeIntervalSince1970) < approval.expires else { throw JarvisAPIError.unauthorized }
        try Task.checkCancellation()
        let signature = try await identity.signModelToggle(approval)
        let result: StatusResponse = try await api.post("/v1/system/config/privileged", body: approval.signed(signature), token: token, expectedBinding: binding)
        guard result.status == "active" else { throw JarvisAPIError.rejected(status: 503, message: "Model activation was not confirmed. Refresh its status.") }
    }
    // Origin changes must clear server-specific state before the new host is
    // configured. No revocation request is sent to the replacement host.
    func clearLocalBinding() async throws { try await credentials.reset(); try await identity.reset() }

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
        let challenge: ChallengeResponse
        do {
            challenge = try await api.post("/v1/auth/challenge", body: ChallengeRequest(deviceId: deviceId))
        } catch JarvisAPIError.unauthorized {
            throw DeviceLoginError.deviceRejected
        }
        let signature = try await identity.signChallenge(hex: challenge.nonce)
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
