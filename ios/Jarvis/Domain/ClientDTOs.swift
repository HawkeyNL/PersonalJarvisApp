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

/// An agent action waiting for the owner. iOS can only deny; approving needs
/// the desktop's native agent-approval-v1 signature.
struct PendingAction: Decodable, Identifiable {
    let pending_id: String
    let action: String
    let preview: String?
    let created_at: String
    var id: String { pending_id }
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
