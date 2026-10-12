import Foundation
import LocalAuthentication
import Security

enum KeychainError: LocalizedError {
    case status(OSStatus)
    case invalidData
    case unsafeAcceptanceIdentity
    case biometryRequired

    var errorDescription: String? {
        switch self {
        case let .status(status):
            (SecCopyErrorMessageString(status, nil) as String?) ?? "Keychain operation failed."
        case .invalidData:
            "Secure data could not be decoded."
        case .unsafeAcceptanceIdentity:
            "Realtime test build requires its separate application identity."
        case .biometryRequired:
            "Set up Face ID or Touch ID on this device to protect the Jarvis device key."
        }
    }
}

enum CredentialBuildIdentity {
    static let acceptanceIdentifier = "com.hawkeynl.jarvis.realtime-acceptance"
    #if JARVIS_REALTIME_ACCEPTANCE
    static let acceptance = true
    #else
    static let acceptance = false
    #endif
    // Keep the production service stable across owner re-signing/AltStore updates.
    static let service = acceptance ? acceptanceIdentifier : "com.hawkeynl.jarvis"

    static func validate(bundleIdentifier: String?, acceptance: Bool) throws {
        guard acceptance == (bundleIdentifier == acceptanceIdentifier) else {
            throw KeychainError.unsafeAcceptanceIdentity
        }
    }
}

protocol SecureValueStorage: Sendable {
    func read(account: String) throws -> Data?
    func save(_ data: Data, account: String) throws
    func delete(account: String) throws
    /// Stores a device-only item that only Face ID / Touch ID with the biometrics
    /// enrolled now can read; the device passcode alone never unlocks it.
    func saveProtected(_ data: Data, account: String) throws
    /// Reads a protected item with an already evaluated context and never shows a
    /// second prompt. `nil` when the item is missing or invalidated.
    func readProtected(account: String, context: LAContext) throws -> Data?
    /// Whether a protected item exists, without reading it or prompting.
    func containsProtected(account: String) throws -> Bool
}

struct KeychainStore: SecureValueStorage {
    private let service: String

    init(service: String = CredentialBuildIdentity.service) { self.service = service }

    func read(account: String) throws -> Data? { try copyData(account: account, context: nil) }

    func readProtected(account: String, context: LAContext) throws -> Data? {
        // Fail instead of a second system prompt if the context does not satisfy the item.
        context.interactionNotAllowed = true
        return try copyData(account: account, context: context)
    }

    func containsProtected(account: String) throws -> Bool {
        var query = try baseQuery(account: account)
        let context = LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context
        // No kSecReturn* key: an existence check that never decrypts the item.
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess || status == errSecInteractionNotAllowed else { throw KeychainError.status(status) }
        return true
    }

    func saveProtected(_ data: Data, account: String) throws {
        guard LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else {
            throw KeychainError.biometryRequired
        }
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .biometryCurrentSet, nil
        ) else { throw KeychainError.biometryRequired }
        // Updating a protected item would prompt; replace it instead.
        try delete(account: account)
        var item = try baseQuery(account: account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessControl as String] = access
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }

    private func copyData(account: String, context: LAContext?) throws -> Data? {
        var query = try baseQuery(account: account)
        if let context { query[kSecUseAuthenticationContext as String] = context }
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.status(status) }
        guard let data = result as? Data else { throw KeychainError.invalidData }
        return data
    }

    func save(_ data: Data, account: String) throws {
        let query = try baseQuery(account: account)
        let updates = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, updates as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else { throw KeychainError.status(updateStatus) }

        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        item[kSecAttrSynchronizable as String] = false
        let addStatus = SecItemAdd(item as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw KeychainError.status(addStatus) }
    }

    func delete(account: String) throws {
        let status = SecItemDelete(try baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.status(status)
        }
    }

    private func baseQuery(account: String) throws -> [String: Any] {
        try CredentialBuildIdentity.validate(
            bundleIdentifier: Bundle.main.bundleIdentifier,
            acceptance: CredentialBuildIdentity.acceptance
        )
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
    }
}
