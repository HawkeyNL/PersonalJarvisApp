import CryptoKit
import Foundation
import LocalAuthentication
import Security

enum DeviceIdentityError: LocalizedError {
    case invalidChallenge
    case invalidStoredKey
    case missingStoredKey
    case biometryChanged
    case keyLocked

    var errorDescription: String? {
        switch self {
        case .invalidChallenge: "The Home Node supplied an invalid signing challenge."
        case .invalidStoredKey: "The secure device identity is damaged and must be reset."
        case .missingStoredKey: "This installation cannot access the original device key. Use the same signing team and app identity as before, or explicitly reset and re-enroll this device. Your password alone cannot replace the device key."
        case .biometryChanged: "This device key can no longer be used, usually because Face ID or Touch ID changed. Open Settings, choose Reset this device, then pair this device again and approve it from a trusted device."
        case .keyLocked: "Face ID or Touch ID could not unlock the device key. Try again. If Face ID or Touch ID was changed, reset this device in Settings and pair it again."
        }
    }
}

/// The Ed25519 seed lives in a Keychain item that only Face ID / Touch ID can
/// read; the public key is kept beside it as a plain item so fingerprints and
/// enrolment never prompt.
actor DeviceIdentityStore {
    private let keychain: any SecureValueStorage
    private let account = "device-ed25519-seed-v2"
    private let publicAccount = "device-ed25519-public-v2"
    private let legacyAccount = "device-ed25519-seed-v1"

    init(keychain: any SecureValueStorage = KeychainStore()) { self.keychain = keychain }

    /// Public key for enrolment: the stored one, or a new protected key.
    func publicKeyHex() throws -> String {
        try migrateIfNeeded()
        if let stored = try keychain.read(account: publicAccount) { return try Self.publicKey(stored).hexEncodedString() }
        let key = Curve25519.Signing.PrivateKey()
        try keychain.saveProtected(key.rawRepresentation, account: account)
        try keychain.save(key.publicKey.rawRepresentation, account: publicAccount)
        return key.publicKey.rawRepresentation.hexEncodedString()
    }

    func existingPublicKeyHex() throws -> String {
        try migrateIfNeeded()
        guard let stored = try keychain.read(account: publicAccount) else { throw DeviceIdentityError.missingStoredKey }
        return try Self.publicKey(stored).hexEncodedString()
    }

    /// Moves a pre-biometry seed behind Face ID / Touch ID without prompting and
    /// keeps its public key, so the Home Node registration stays valid. If both
    /// exist, the protected key wins unless it never got its public item (an
    /// interrupted write that was never handed out). The old item is deleted only
    /// once the new items are stored.
    func migrateIfNeeded() throws {
        guard let seed = try keychain.read(account: legacyAccount) else { return }
        let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        if try !keychain.containsProtected(account: account) || keychain.read(account: publicAccount) == nil {
            guard let key else { throw DeviceIdentityError.invalidStoredKey }
            try keychain.saveProtected(seed, account: account)
            try keychain.save(key.publicKey.rawRepresentation, account: publicAccount)
        }
        try keychain.delete(account: legacyAccount)
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

    /// Exact v1 client-core `pairing_approval_message`: domain, request id, 32-byte
    /// nonce, 32-byte candidate key, user id, approver device id, big-endian i64 expiry.
    static func pairingApprovalMessage(requestId: UUID, nonceHex: String, candidatePublicKeyHex: String,
                                       userId: UUID, approverDeviceId: UUID, expiresAt: Int64) throws -> Data {
        guard let nonce = Data(strictHex: nonceHex), nonce.count == 32,
              let key = Data(strictHex: candidatePublicKeyHex), key.count == 32 else {
            throw DeviceIdentityError.invalidChallenge
        }
        func bytes(_ value: UUID) -> Data { var raw = value.uuid; return withUnsafeBytes(of: &raw) { Data($0) } }
        var result = Data("jarvis-device-pairing-v1\0".utf8)
        result.append(bytes(requestId)); result.append(nonce); result.append(key)
        result.append(bytes(userId)); result.append(bytes(approverDeviceId))
        withUnsafeBytes(of: expiresAt.bigEndian) { result.append(contentsOf: $0) }
        return result
    }

    // Signing never creates a replacement key for an existing registration and
    // reads the seed only with a context Face ID / Touch ID has just evaluated.
    func signPairingApproval(_ message: Data, context: LAContext) throws -> String {
        try signingKey(context: context).signature(for: message).hexEncodedString()
    }

    func signLogin(challengeId: UUID, deviceId: UUID, nonceHex: String, context: LAContext) throws -> String {
        let message = try Self.loginMessage(challengeId: challengeId, deviceId: deviceId, nonceHex: nonceHex)
        return try signingKey(context: context).signature(for: message).hexEncodedString()
    }

    func reset() throws {
        try keychain.delete(account: account)
        try keychain.delete(account: publicAccount)
        try keychain.delete(account: legacyAccount)
    }

    func signModelToggle(_ approval: ModelToggleApproval, context: LAContext) throws -> String {
        try signingKey(context: context).signature(for: approval.message()).hexEncodedString()
    }

    func signModelRouting(_ approval: ModelRoutingApproval, context: LAContext) throws -> String {
        try signingKey(context: context).signature(for: approval.message()).hexEncodedString()
    }

    private func signingKey(context: LAContext) throws -> Curve25519.Signing.PrivateKey {
        try migrateIfNeeded()
        let publicKey = try keychain.read(account: publicAccount)
        let read: Data?
        do { read = try keychain.readProtected(account: account, context: context) }
        catch KeychainError.status(errSecAuthFailed) where publicKey != nil { throw DeviceIdentityError.keyLocked }
        guard let stored = read else {
            // A known key that no longer reads was invalidated by a biometry change.
            throw publicKey == nil ? DeviceIdentityError.missingStoredKey : DeviceIdentityError.biometryChanged
        }
        guard let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: stored),
              key.publicKey.rawRepresentation == publicKey else { throw DeviceIdentityError.invalidStoredKey }
        return key
    }

    private static func publicKey(_ raw: Data) throws -> Data {
        guard (try? Curve25519.Signing.PublicKey(rawRepresentation: raw)) != nil else { throw DeviceIdentityError.invalidStoredKey }
        return raw
    }
}

/// Pairing key fingerprints, mirroring client-core `key_fingerprint` and
/// `PairingRequestItem::verified_fingerprint`.
enum PairingFingerprint {
    enum Failure: LocalizedError, Equatable {
        case invalidKey, mismatch
        var errorDescription: String? {
            switch self {
            case .invalidKey: "The pairing request has an invalid device key."
            case .mismatch: "The Home Node's fingerprint does not match the candidate key. Do not approve; check the Home Node."
            }
        }
    }

    /// Lowercase hex SHA-256 of a raw 32-byte public key.
    static func of(publicKey: Data) -> String { Data(SHA256.hash(data: publicKey)).hexEncodedString() }

    static func of(publicKeyHex: String) throws -> String {
        guard let key = Data(strictHex: publicKeyHex), key.count == 32 else { throw Failure.invalidKey }
        return of(publicKey: key)
    }

    /// The fingerprint an approver shows: computed here from the key the approval
    /// signs. Any disagreement with the server's fields refuses the request.
    static func verified(_ item: PairingApprovalItem) throws -> String {
        let local = try of(publicKeyHex: item.candidate_public_key)
        guard local.prefix(16) == item.fingerprint, item.fingerprint_full.map({ $0 == local }) ?? true else {
            throw Failure.mismatch
        }
        return local
    }

    /// Groups of four hex characters for side-by-side comparison.
    static func grouped(_ fingerprint: String) -> String {
        stride(from: 0, to: fingerprint.count, by: 4).map { offset in
            let start = fingerprint.index(fingerprint.startIndex, offsetBy: offset)
            return String(fingerprint[start..<(fingerprint.index(start, offsetBy: 4, limitedBy: fingerprint.endIndex) ?? fingerprint.endIndex)])
        }.joined(separator: " ")
    }

    /// An unauthenticated candidate hint as bounded plain text: control and
    /// format (bidi, zero-width) characters dropped, at most `max` characters.
    static func hint(_ value: String?, max: Int = 64) -> String {
        guard let value else { return "unknown" }
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: value.unicodeScalars.filter {
            $0.properties.generalCategory != .control && $0.properties.generalCategory != .format
        })
        let clean = String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "unknown" }
        return clean.count > max ? String(clean.prefix(max)) + "…" : clean
    }
}

extension Data {
    /// Hex of ASCII digits only (no sign or other prefixes).
    init?(strictHex value: String) {
        guard value.unicodeScalars.allSatisfy({ $0.isASCII && $0.properties.isASCIIHexDigit }) else { return nil }
        self.init(hexEncoded: value)
    }

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
