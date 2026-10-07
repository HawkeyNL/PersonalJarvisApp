import CryptoKit
import Foundation

enum DeviceIdentityError: LocalizedError {
    case invalidChallenge
    case invalidStoredKey
    case missingStoredKey

    var errorDescription: String? {
        switch self {
        case .invalidChallenge: "The Home Node supplied an invalid signing challenge."
        case .invalidStoredKey: "The secure device identity is damaged and must be reset."
        case .missingStoredKey: "This installation cannot access the original device key. Use the same signing team and app identity as before, or explicitly reset and re-enroll this device. Your password alone cannot replace the device key."
        }
    }
}

actor DeviceIdentityStore {
    private let keychain: any SecureValueStorage
    private let account = "device-ed25519-seed-v1"

    init(keychain: any SecureValueStorage = KeychainStore()) { self.keychain = keychain }

    func publicKeyHex() throws -> String {
        let key = try signingKey()
        return key.publicKey.rawRepresentation.hexEncodedString()
    }

    func existingPublicKeyHex() throws -> String {
        try signingKey(createIfMissing: false).publicKey.rawRepresentation.hexEncodedString()
    }

    /// Exact v1 client-core `login_message`: domain, challenge id, device id, 32-byte nonce.
    static func loginMessage(challengeId: UUID, deviceId: UUID, nonceHex: String) throws -> Data {
        guard let nonce = Data(hexEncoded: nonceHex), nonce.count == 32 else {
            throw DeviceIdentityError.invalidChallenge
        }
        func bytes(_ value: UUID) -> Data { var raw = value.uuid; return withUnsafeBytes(of: &raw) { Data($0) } }
        var result = Data("jarvis-login-v1\0".utf8)
        result.append(bytes(challengeId)); result.append(bytes(deviceId)); result.append(nonce)
        return result
    }

    func signLogin(challengeId: UUID, deviceId: UUID, nonceHex: String) throws -> String {
        let message = try Self.loginMessage(challengeId: challengeId, deviceId: deviceId, nonceHex: nonceHex)
        // Login must never create a replacement key for an existing registration.
        return try signingKey(createIfMissing: false).signature(for: message).hexEncodedString()
    }

    func reset() throws { try keychain.delete(account: account) }

    func signModelToggle(_ approval: ModelToggleApproval) throws -> String {
        try signingKey(createIfMissing: false).signature(for: approval.message()).hexEncodedString()
    }

    func signModelRouting(_ approval: ModelRoutingApproval) throws -> String {
        try signingKey(createIfMissing: false).signature(for: approval.message()).hexEncodedString()
    }

    private func signingKey(createIfMissing: Bool = true) throws -> Curve25519.Signing.PrivateKey {
        if let stored = try keychain.read(account: account) {
            do { return try Curve25519.Signing.PrivateKey(rawRepresentation: stored) }
            catch { throw DeviceIdentityError.invalidStoredKey }
        }
        guard createIfMissing else { throw DeviceIdentityError.missingStoredKey }
        let key = Curve25519.Signing.PrivateKey()
        try keychain.save(key.rawRepresentation, account: account)
        return key
    }
}

extension Data {
    init?(hexEncoded value: String) {
        guard value.count.isMultiple(of: 2) else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(value.count / 2)
        var index = value.startIndex
        while index < value.endIndex {
            let next = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self = Data(bytes)
    }

    func hexEncodedString() -> String { map { String(format: "%02x", $0) }.joined() }
}
