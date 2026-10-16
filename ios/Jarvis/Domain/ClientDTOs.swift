import CryptoKit
import Foundation

struct AccountStatus: Decodable, Equatable {
    let protocolVersion: Int
    let passwordRequired: Bool
    let bootstrapRequired: Bool
    enum CodingKeys: String, CodingKey {
        case protocolVersion = "protocol"
        case passwordRequired = "password_required"
        case bootstrapRequired = "bootstrap_required"
    }
}

struct FirstDeviceResponse: Decodable {
    let deviceId: UUID
    enum CodingKeys: String, CodingKey { case deviceId = "device_id" }
}

// Client-local mirrors of the current v1 API. Replace these with generated or
// shared contract types once Jarvis publishes an authoritative mobile schema.

struct EnrollmentRequest: Encodable, Equatable {
    let name: String
    let platform: String
    let publicKey: String
    var password: String? = nil

    enum CodingKeys: String, CodingKey {
        case name, platform, password
        case publicKey = "public_key"
    }
}

struct PairingRequestResponse: Decodable, Equatable {
    let requestId: UUID
    let nonce: String
    let expiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case requestId = "request_id"
        case nonce
        case expiresAt = "expires_at"
    }
}

struct PairingStatusResponse: Decodable, Equatable {
    enum Status: String, Decodable, Equatable {
        case pending, approved, denied, expired
    }

    let status: Status
    let deviceId: UUID?

    enum CodingKeys: String, CodingKey {
        case status
        case deviceId = "device_id"
    }
}

/// `GET /v1/auth/pairing/requests`. Fields after `expires_at` are absent from
/// Cores before the pairing-details release; the candidate details are
/// unauthenticated hints. Display `PairingFingerprint.verified`, never the
/// server's fingerprint fields.
struct PairingApprovalList: Decodable { let requests: [PairingApprovalItem] }

struct PairingApprovalItem: Decodable, Identifiable, Equatable {
    let id: UUID
    let device_name: String
    let platform: String
    let fingerprint: String
    let nonce: String
    let candidate_public_key: String
    let created_at: Int64
    let expires_at: Int64
    var fingerprint_full: String? = nil
    var ip: String? = nil
    var user_agent: String? = nil
    var app_version: String? = nil
    var model: String? = nil
}

/// `GET /v1/auth/unlock/pending`: other devices of this account waiting to be unlocked.
struct UnlockRequestList: Decodable { let requests: [UnlockRequestItem] }

struct UnlockRequestItem: Decodable, Identifiable, Equatable {
    let id: UUID
    let device_name: String
    let platform: String
    let nonce: String
    let created_at: Int64
}

struct ChallengeRequest: Encodable, Equatable {
    let deviceId: UUID

    enum CodingKeys: String, CodingKey { case deviceId = "device_id" }
}

struct ChallengeResponse: Decodable, Equatable {
    let challengeId: UUID
    let nonce: String

    enum CodingKeys: String, CodingKey {
        case challengeId = "challenge_id"
        case nonce
    }
}

struct LoginRequest: Encodable, Equatable {
    let deviceId: UUID
    let challengeId: UUID
    let signature: String
    var password: String? = nil

    enum CodingKeys: String, CodingKey {
        case deviceId = "device_id"
        case challengeId = "challenge_id"
        case signature, password
    }
}

struct LoginResponse: Decodable, Equatable {
    let token: String
    let expiresAt: Int64

    enum CodingKeys: String, CodingKey {
        case token
        case expiresAt = "expires_at"
    }
}

struct AuthenticatedIdentity: Decodable, Equatable {
    let userId: UUID
    let deviceId: UUID

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case deviceId = "device_id"
    }
}

struct ConversationSummary: Decodable, Identifiable, Equatable {
    let id: UUID
    let title: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case id, title
        case updatedAt = "updated_at"
    }
}

struct ConversationListResponse: Decodable, Equatable {
    let conversations: [ConversationSummary]
}

struct ConversationMessage: Decodable, Identifiable, Equatable {
    let role: String
    let content: String
    let model: String?
    let at: String
    var canonicalId: String? = nil

    enum CodingKeys: String, CodingKey { case role, content, model, at; case canonicalId = "id" }

    var id: String { canonicalId ?? "\(at)-\(role)-\(content)" }
    var isAssistant: Bool { role == "assistant" || role == "jarvis" }
}

struct ConversationResponse: Decodable, Equatable {
    let id: UUID
    let title: String
    let messages: [ConversationMessage]
    let assistantRunning: Bool?
    enum CodingKeys: String, CodingKey {
        case id, title, messages
        case assistantRunning = "assistant_running"
    }
}

struct ChatTurn: Encodable, Equatable {
    let role: String
    let content: String
}

struct ChatRequest: Encodable, Equatable {
    let messages: [ChatTurn]
    let conversationId: UUID?

    enum CodingKeys: String, CodingKey {
        case messages
        case conversationId = "conversation_id"
    }
}

struct ChatResponse: Decodable, Equatable {
    let reply: String
    let model: String?
    let stopReason: String?
    let conversationId: UUID
    let conversationTitle: String
    let newTopic: Bool

    enum CodingKeys: String, CodingKey {
        case reply, model
        case stopReason = "stop_reason"
        case conversationId = "conversation_id"
        case conversationTitle = "conversation_title"
        case newTopic = "new_topic"
    }
}

struct APIErrorBody: Decodable {
    let error: String?
    let hint: String?
    var code: String? = nil
}

// Read-only subset of /v1/system/registry used by the Resources, Health and
// Integrations pages. Older Cores omit brains.
struct HomeNodeRegistry: Decodable {
    let host: Host
    let liveHost: LiveHost?
    let software: [Software]
    let brains: [Brain]?

    enum CodingKeys: String, CodingKey { case host, software, brains; case liveHost = "live_host" }

    struct Brain: Decodable {
        let id: String
        let label: String
        let cost: String
        let available: Bool
        let note: String?
    }

    struct Host: Decodable {
        let os: String
        let arch: String
        let cpu: String
        let cpuCores: Int
        let memTotalGB: Double
        let gpu: String

        enum CodingKeys: String, CodingKey {
            case os, arch, cpu, gpu
            case cpuCores = "cpu_cores"
            case memTotalGB = "mem_total_gb"
        }
    }

    struct LiveHost: Decodable {
        let sampledAt: Int64
        let cpuPercent: Double?
        let memoryTotalBytes: Int64
        let memoryUsedBytes: Int64
        let uptimeSeconds: Int64

        enum CodingKeys: String, CodingKey {
            case sampledAt = "sampled_at"
            case cpuPercent = "cpu_percent"
            case memoryTotalBytes = "memory_total_bytes"
            case memoryUsedBytes = "memory_used_bytes"
            case uptimeSeconds = "uptime_seconds"
        }
    }

    struct Software: Decodable {
        let name: String
        let present: Bool
        let version: String?
        let detail: String?
    }
}

// Owner-only reads for the hub and node pages (same routes as the desktop
// app). Fields the UI can live without are optional so that one missing value
// does not hide a whole page.

struct DeviceListResponse: Decodable { let devices: [LinkedDevice] }

struct LinkedDevice: Decodable, Identifiable {
    let id: String
    let name: String
    let platform: String
    let status: String
    let created_at: Double?
}

struct PendingActionList: Decodable { let pending: [PendingAction] }

/// An agent action waiting for the owner. `action_json` is the exact JSON whose
/// SHA-256 (`action_sha256`) the agent-approval-v1 signature binds; Core leaves it
/// out (null) when it is too large to review here, and older Cores omit it.
struct PendingAction: Decodable, Identifiable, Equatable {
    let pending_id: String
    let action: String
    let preview: String?
    let created_at: String
    var nonce: String? = nil
    var action_sha256: String? = nil
    var action_json: String? = nil
    var approval_message: String? = nil
    var id: String { pending_id }
}

/// An agent action this app can show in full. Only these kinds and exactly these
/// fields are accepted (client-core `MOBILE_APPROVABLE_AGENT_ACTIONS`); Core refuses
/// other kinds from a phone too.
enum ReviewedAgentAction: Equatable {
    case writeFile(path: String, content: String)
    case gitCommit(message: String)

    var kind: String {
        switch self {
        case .writeFile: "write_file"
        case .gitCommit: "git_commit"
        }
    }

    /// The compact serde_json bytes Core stores for this action and hashes.
    var canonicalJSON: String {
        switch self {
        case let .writeFile(path, content):
            "{\"type\":\(Self.quote(kind)),\"path\":\(Self.quote(path)),\"content\":\(Self.quote(content))}"
        case let .gitCommit(message):
            "{\"type\":\(Self.quote(kind)),\"message\":\(Self.quote(message))}"
        }
    }

    /// serde_json string escaping: quote, backslash, \b \f \n \r \t, other C0 controls as
    /// lowercase \u00xx; everything else stays raw UTF-8.
    private static func quote(_ value: String) -> String {
        var out = "\""
        for scalar in value.unicodeScalars {
            switch scalar.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0x00...0x1F: out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out + "\""
    }
}

/// What this app shows for one pending agent action, and whether it may approve it.
enum AgentReview: Equatable {
    /// `sha256` is computed here from the shown action: the hash the approval signs.
    case approvable(ReviewedAgentAction, sha256: String)
    /// Shown with `raw` (escaped) when present; only deny is offered.
    case refused(reason: String, raw: String?)

    /// client-core `MAX_REVIEWABLE_ACTION_JSON_LEN`.
    static let maxActionJSONBytes = 16 * 1024
    private static let fields: [String: Set<String>] = ["write_file": ["type", "path", "content"], "git_commit": ["type", "message"]]

    /// Approvable only if the listed JSON is one known kind with exactly its string
    /// fields, re-encodes to the same bytes, and those bytes hash to Core's `action_sha256`.
    static func of(_ item: PendingAction) -> AgentReview {
        guard let raw = item.action_json else {
            return .refused(reason: "Too large to review on this iPhone. Approve it on the desktop app, or deny it.", raw: nil)
        }
        guard item.approval_message == "agent-approval-v1" else {
            return .refused(reason: "This Home Node uses an approval format this app does not know.", raw: raw)
        }
        let bytes = Data(raw.utf8)
        guard bytes.count <= maxActionJSONBytes else { return .refused(reason: "Too large to review on this iPhone.", raw: raw) }
        guard let action = parse(bytes) else {
            return .refused(reason: "This app cannot show this kind of action, so it cannot approve it.", raw: raw)
        }
        // Bytes, not String ==: Swift compares strings by canonical equivalence.
        guard action.kind == item.action, Data(action.canonicalJSON.utf8) == bytes else {
            return .refused(reason: "Core listed this action inconsistently. Do not approve it.", raw: raw)
        }
        let sha256 = Data(SHA256.hash(data: bytes)).hexEncodedString()
        guard sha256 == item.action_sha256?.lowercased() else {
            return .refused(reason: "The action does not match its hash. Do not approve it.", raw: raw)
        }
        return .approvable(action, sha256: sha256)
    }

    private static func parse(_ bytes: Data) -> ReviewedAgentAction? {
        guard let object = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any] else { return nil }
        var values: [String: String] = [:]
        for (key, value) in object {
            guard let text = value as? String else { return nil }
            values[key] = text
        }
        guard let type = values["type"], fields[type] == Set(values.keys) else { return nil }
        switch type {
        case "write_file":
            guard let path = values["path"], let content = values["content"] else { return nil }
            return .writeFile(path: path, content: content)
        case "git_commit":
            guard let message = values["message"] else { return nil }
            return .gitCommit(message: message)
        default:
            return nil
        }
    }

    /// Server text shown in full, with every invisible, control, bidi or private-use
    /// character (and stacked combining marks past two) as a visible \u{XXXX}.
    /// Newlines stay only where `keepNewlines`.
    static func visible(_ text: String, keepNewlines: Bool = true) -> String {
        let invisible: [Unicode.GeneralCategory] = [.control, .format, .privateUse, .surrogate, .unassigned,
                                                       .lineSeparator, .paragraphSeparator, .spaceSeparator]
        var marks = 0
        var out = ""
        for scalar in text.unicodeScalars {
            marks = PairingFingerprint.isCombining(scalar.value) ? marks + 1 : 0
            let plain = (scalar.value == 0x0A && keepNewlines) || scalar.value == 0x09 || scalar.value == 0x20
                || (!invisible.contains(scalar.properties.generalCategory) && !PairingFingerprint.isHidden(scalar.value) && marks <= 2)
            if plain { out.unicodeScalars.append(scalar) } else { out += String(format: "\\u{%04X}", scalar.value) }
        }
        return out
    }
}

struct CodingSessionList: Decodable { let sessions: [CodingSession] }

struct CodingSession: Decodable, Identifiable {
    let id: String
    let repository: String?
    let objective: String?
    let state: String
    let updated_at: String?
}

struct AgentAuditResponse: Decodable { let entries: [AgentAuditEntry] }

struct AgentAuditEntry: Decodable {
    let action: String
    let risk: String?
    let outcome: String
    let note: String?
    let ts: String
}

struct SystemAuditResponse: Decodable { let entries: [SystemAuditEntry] }

struct SystemAuditEntry: Decodable {
    let event: String
    let outcome: String
    let ts: String
}

/// GET /v1/agents: metadata and this month's usage; never agent instructions.
struct AgentsResponse: Decodable {
    let agent_count: Int?
    let unavailable_reason: String?
    let usage_unavailable_reason: String?
    let agents: [AgentInfo]
}

struct AgentInfo: Decodable, Identifiable {
    let id: String
    let name: String
    let group: String?
    let description: String?
    let model_policy: String?
    let allowed_tools: [String]?
    let usage: AgentUsage?
}

/// `nil` measurements mean Core does not measure that yet (never a zero).
struct AgentUsage: Decodable {
    let requests: Int
    let total_tokens: Int?
    let spent_eur: Double?
    let failures: Int?
    let latency_p95_ms: Double?
    let last_used: String?
}

struct UsageSummary: Decodable {
    let budget_eur: Double
    let spent_eur: Double
    let remaining_eur: Double?
    let over_budget: Bool
    let reserved_eur: Double?
    let above_soft_budget: Bool?
    let requests: Int?
    let total_tokens: Int?
    let failures: Int?
    let latency_p95_ms: Double?
    let by_backend: [BackendUsage]?
}

struct BackendUsage: Decodable {
    let backend: String
    let spent_eur: Double
    let total_tokens: Int?
}

struct ServicesResponse: Decodable {
    let services: [ServiceStatus]
    let disks: [DiskStatus]
}

struct ServiceStatus: Decodable {
    let label: String
    let unit: String
    let state: String
}

struct DiskStatus: Decodable {
    let label: String
    let state: String
    let total_bytes: Int64?
    let free_bytes: Int64?
    let used_percent: Double?
}

struct IbkrStatus: Decodable {
    let reachable: Bool
    let authenticated: Bool
    let connected: Bool?
}

/// Public /livez and /readyz bodies.
struct HealthProbe: Decodable {
    let status: String?
    let environment: String?
}

// MARK: - iOS update notice (informational only; the app never downloads or installs)

/// `GET /v1/app-updates/ios/{running version}`; 204 means no update.
struct IOSUpdateResponse: Decodable, Equatable {
    let schemaVersion: Int
    let platform: String
    let version: String
    let releasedAt: String
    let notes: String
    let downloadsPageUrl: String
    let altstoreSourceUrl: String
    let altstoreAddSourceUrl: String
    let sidestoreAddSourceUrl: String

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version", platform, version
        case releasedAt = "released_at", notes
        case downloadsPageUrl = "downloads_page_url"
        case altstoreSourceUrl = "altstore_source_url"
        case altstoreAddSourceUrl = "altstore_add_source_url"
        case sidestoreAddSourceUrl = "sidestore_add_source_url"
    }
}

/// A validated update: newer numeric semver and only altstore/sidestore/https links.
struct IOSUpdateNotice: Equatable {
    let version: String
    let altstoreURL: URL
    let sidestoreURL: URL
    let downloadsURL: URL

    /// Numeric `X.Y.Z` only; anything else (pre-release, build metadata) is rejected.
    static func parseVersion(_ text: String) -> [Int]? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        let numbers = parts.compactMap { part -> Int? in
            part.isEmpty || !part.allSatisfy({ $0 >= "0" && $0 <= "9" }) ? nil : Int(part)
        }
        return numbers.count == 3 ? numbers : nil
    }

    private static func url(_ text: String, scheme: String) -> URL? {
        guard let url = URL(string: text), url.scheme?.lowercased() == scheme else { return nil }
        return url
    }

    static func validated(_ response: IOSUpdateResponse, running: String) -> IOSUpdateNotice? {
        guard response.schemaVersion == 1, response.platform == "ios",
              let offered = parseVersion(response.version), let current = parseVersion(running),
              offered.lexicographicallyPrecedes(current) == false, offered != current,
              let altstore = url(response.altstoreAddSourceUrl, scheme: "altstore"),
              let sidestore = url(response.sidestoreAddSourceUrl, scheme: "sidestore"),
              let downloads = url(response.downloadsPageUrl, scheme: "https")
        else { return nil }
        return IOSUpdateNotice(version: response.version, altstoreURL: altstore, sidestoreURL: sidestore, downloadsURL: downloads)
    }
}
