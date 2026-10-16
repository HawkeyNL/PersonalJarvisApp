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
        case .biometryChanged: "This device key can no longer be used, usually because Face ID or Touch ID changed or the device passcode was removed. Open Settings, choose Reset this device, then pair this device again and approve it from a trusted device."
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

    /// Public key for enrolment: the stored one, or a new protected key. A public
    /// item without its seed is unusable, so enrolment replaces it.
    func publicKeyHex() throws -> String {
        try migrateIfNeeded()
        if try keychain.containsProtected(account: account), let stored = try keychain.read(account: publicAccount) {
            return try Self.publicKey(stored).hexEncodedString()
        }
        try keychain.delete(account: publicAccount)
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
    /// keeps its public key, so the Home Node registration stays valid. Builds
    /// with the protected key never write the old item, so an old key that differs
    /// from the stored public key is newer (an older build paired again) and wins.
    /// A damaged old key never replaces a complete protected key. The old item is
    /// deleted only once the new items are stored.
    func migrateIfNeeded() throws {
        guard let seed = try keychain.read(account: legacyAccount) else { return }
        let stored = try keychain.read(account: publicAccount)
        let complete = try keychain.containsProtected(account: account) && stored != nil
        if let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: seed) {
            if !complete || stored != key.publicKey.rawRepresentation {
                try keychain.saveProtected(seed, account: account)
                try keychain.save(key.publicKey.rawRepresentation, account: publicAccount)
            }
        } else if !complete {
            throw DeviceIdentityError.invalidStoredKey
        }
        try keychain.delete(account: legacyAccount)
    }

    /// Exact v1 client-core `login_message`: domain, challenge id, device id, 32-byte nonce.
    static func loginMessage(challengeId: UUID, deviceId: UUID, nonceHex: String) throws -> Data {
        try challengeMessage("jarvis-login-v1", id: challengeId, deviceId: deviceId, nonceHex: nonceHex)
    }

    /// Exact v1 client-core `unlock_approval_message`: domain, request id, approver device id, 32-byte nonce.
    static func unlockApprovalMessage(requestId: UUID, approverDeviceId: UUID, nonceHex: String) throws -> Data {
        try challengeMessage("jarvis-unlock-approval-v1", id: requestId, deviceId: approverDeviceId, nonceHex: nonceHex)
    }

    private static func challengeMessage(_ domain: String, id: UUID, deviceId: UUID, nonceHex: String) throws -> Data {
        guard let nonce = Data(strictHex: nonceHex), nonce.count == 32 else {
            throw DeviceIdentityError.invalidChallenge
        }
        var result = Data("\(domain)\0".utf8)
        result.append(id.rawBytes); result.append(deviceId.rawBytes); result.append(nonce)
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
        var result = Data("jarvis-device-pairing-v1\0".utf8)
        result.append(requestId.rawBytes); result.append(nonce); result.append(key)
        result.append(userId.rawBytes); result.append(approverDeviceId.rawBytes)
        withUnsafeBytes(of: expiresAt.bigEndian) { result.append(contentsOf: $0) }
        return result
    }

    // Signing takes typed fields only: each method builds its exact client-core
    // message here, so raw server bytes never reach the key. It never creates a
    // replacement key and reads the seed only with a context Face ID / Touch ID
    // has just evaluated.
    func signLogin(challengeId: UUID, deviceId: UUID, nonceHex: String, context: LAContext) throws -> String {
        try sign(Self.loginMessage(challengeId: challengeId, deviceId: deviceId, nonceHex: nonceHex), context: context)
    }

    func signPairingApproval(requestId: UUID, nonceHex: String, candidatePublicKeyHex: String, userId: UUID,
                             approverDeviceId: UUID, expiresAt: Int64, context: LAContext) throws -> String {
        try sign(Self.pairingApprovalMessage(requestId: requestId, nonceHex: nonceHex, candidatePublicKeyHex: candidatePublicKeyHex,
                                             userId: userId, approverDeviceId: approverDeviceId, expiresAt: expiresAt), context: context)
    }

    func signUnlockApproval(requestId: UUID, approverDeviceId: UUID, nonceHex: String, context: LAContext) throws -> String {
        try sign(Self.unlockApprovalMessage(requestId: requestId, approverDeviceId: approverDeviceId, nonceHex: nonceHex), context: context)
    }

    func signAccountApproval(_ approval: AccountApproval, context: LAContext) throws -> String {
        try sign(approval.message(), context: context)
    }

    func signModelToggle(_ approval: ModelToggleApproval, context: LAContext) throws -> String {
        try sign(approval.message(), context: context)
    }

    func signModelRouting(_ approval: ModelRoutingApproval, context: LAContext) throws -> String {
        try sign(approval.message(), context: context)
    }

    private func sign(_ message: Data, context: LAContext) throws -> String {
        try signingKey(context: context).signature(for: message).hexEncodedString()
    }

    func reset() throws {
        try keychain.delete(account: account)
        try keychain.delete(account: publicAccount)
        try keychain.delete(account: legacyAccount)
    }

    private func signingKey(context: LAContext) throws -> Curve25519.Signing.PrivateKey {
        try migrateIfNeeded()
        let publicKey = try keychain.read(account: publicAccount)
        let read: Data?
        do { read = try keychain.readProtected(account: account, context: context) }
        catch let KeychainError.status(status) where publicKey != nil
                && [errSecAuthFailed, errSecUserCanceled, errSecInteractionNotAllowed].contains(status) {
            throw DeviceIdentityError.keyLocked
        }
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

    /// Server text for display and prompts, ported from desktop `display_name`:
    /// control, hidden and quote characters dropped (prompts quote it), at most two
    /// combining marks per character, whitespace collapsed, at most `max` scalars.
    /// Longer text keeps its start and end, so a long name cannot push its end out of view.
    static func displayName(_ value: String?, max: Int = 64) -> String {
        guard let value else { return "unknown" }
        var marks = 0
        var kept = String.UnicodeScalarView()
        for scalar in value.unicodeScalars {
            let c: Unicode.Scalar = scalar.properties.isWhitespace ? " " : scalar
            guard c.properties.generalCategory != .control, !isHidden(c.value), c != "\"" else { continue }
            marks = isCombining(c.value) ? marks + 1 : 0
            if marks <= 2 { kept.append(c) }
        }
        var clean: [Unicode.Scalar] = []
        for word in kept.split(separator: " ") {
            if !clean.isEmpty { clean.append(" ") }
            clean.append(contentsOf: word)
        }
        if clean.isEmpty { return "unknown" }
        guard clean.count > max else { return String(String.UnicodeScalarView(clean)) }
        let head = max / 2, tail = max - head - 1
        return String(String.UnicodeScalarView(clean[..<head])) + "…" + String(String.UnicodeScalarView(clean[(clean.count - tail)...]))
    }

    /// Unicode category Cf (bidi controls, zero-width, tags) plus other invisible or
    /// blank-looking default-ignorable and private-use characters, and quotation marks.
    private static func isHidden(_ c: UInt32) -> Bool {
        switch c {
        case 0xAD, 0x34F, 0x600...0x605, 0x61C, 0x6DD, 0x70F, 0x890...0x891, 0x8E2, 0x115F...0x1160,
             0x17B4...0x17B5, 0x180B...0x180F, 0x200B...0x200F, 0x202A...0x202E, 0x2060...0x2064, 0x2066...0x206F,
             // Quotation marks (Pi/Pf and fullwidth): prompts quote server text.
             0xAB, 0xBB, 0x2018...0x201F, 0x2039...0x203A, 0x2E02...0x2E05, 0x2E09...0x2E0A, 0x2E0C...0x2E0D,
             0x2E1C...0x2E1D, 0x2E20...0x2E21, 0xFF02,
             0x2800, 0x3164, 0xE000...0xF8FF, 0xFE00...0xFE0F, 0xFEFF, 0xFFA0, 0xFFF9...0xFFFC, 0x110BD, 0x110CD,
             0x13430...0x1343F, 0x1BCA0...0x1BCA3, 0x1D173...0x1D17A, 0xE0000...0xE0FFF, 0xF0000...0x10FFFF:
            return true
        default:
            return false
        }
    }

    /// Common combining-mark blocks, as on desktop.
    private static func isCombining(_ c: UInt32) -> Bool {
        switch c {
        case 0x300...0x36F, 0x1AB0...0x1AFF, 0x1DC0...0x1DFF, 0x20D0...0x20FF, 0xFE20...0xFE2F: return true
        default: return false
        }
    }

    /// A device on these platforms can approve unlocks, account changes and agent
    /// actions once paired, so its pairing card says so.
    static func approverWarning(platform: String) -> String? {
        guard ["ios", "android", "macos"].contains(platform.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()) else { return nil }
        return "Once paired, this \(displayName(platform, max: 16)) device can approve unlocks, account changes and agent actions."
    }
}

/// client-core `AccountApproval`: Core's challenge for one account action. It is
/// signed only after `require` matched every bound field to what this device asked for.
struct AccountApproval: Decodable, Equatable {
    static let deviceRevoke = "device-revoke"
    static let passwordSet = "password-set"

    let request_id: UUID
    let user_id: UUID
    let device_id: UUID
    let action: String
    let target: UUID
    let nonce: String
    let expires_at: Int64

    /// Exact v1 client-core `account_approval_message`: domain, action name, request,
    /// user, device and target ids, 32-byte nonce, big-endian i64 expiry.
    func message() throws -> Data {
        guard [Self.deviceRevoke, Self.passwordSet].contains(action),
              let nonce = Data(strictHex: nonce), nonce.count == 32, expires_at > 0 else {
            throw DeviceIdentityError.invalidChallenge
        }
        var result = Data("jarvis-account-approval-v1\0".utf8)
        result.append(Data(action.utf8)); result.append(0)
        for id in [request_id, user_id, device_id, target] { result.append(id.rawBytes) }
        result.append(nonce)
        withUnsafeBytes(of: expires_at.bigEndian) { result.append(contentsOf: $0) }
        return result
    }

    /// Core must have issued exactly the challenge this device asked for, still unexpired.
    func require(action: String, user: UUID, device: UUID, target: UUID, now: Int64) throws {
        _ = try message()
        guard self.action == action, user_id == user, device_id == device, self.target == target, expires_at > now else {
            throw JarvisAPIError.rejected(status: 409, message: "Core returned a different or expired approval request; nothing was signed.")
        }
    }
}

extension UUID {
    /// The 16 bytes client-core signs, in RFC 4122 order.
    var rawBytes: Data { withUnsafeBytes(of: uuid) { Data($0) } }
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
