package com.hawkeynl.jarvis.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Read-only owner endpoints shown on the hub and node pages. Every field the
// app does not strictly need has a default, so an older or newer Core shape
// still decodes (unknown keys are ignored, nulls fall back to defaults).

@Serializable
data class AgentLimits(
    val max_runtime_seconds: Long = 0,
    val max_context_chars: Long = 0,
    val max_output_chars: Long = 0,
    val max_parallel_runs: Int = 0,
)

@Serializable
data class AgentUsage(
    val requests: Long = 0,
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val total_tokens: Long = 0,
    val spent_eur: Double = 0.0,
    val last_used: String? = null,
)

@Serializable
data class AgentInfo(
    val id: String,
    val name: String = "",
    val group: String? = null,
    val description: String = "",
    val model_policy: String = "",
    val allowed_tools: List<String> = emptyList(),
    val limits: AgentLimits? = null,
    val usage: AgentUsage? = null,
)

@Serializable
data class AgentsResponse(
    val agent_count: Int? = null,
    val unavailable_reason: String? = null,
    val usage_unavailable_reason: String? = null,
    val agents: List<AgentInfo> = emptyList(),
)

@Serializable
data class LinkedDevice(
    val id: String,
    val name: String = "",
    val platform: String = "",
    val status: String = "",
    val created_at: Long? = null,
)

@Serializable
data class DevicesResponse(val devices: List<LinkedDevice> = emptyList())

@Serializable
data class PendingAction(
    val pending_id: String,
    val action: String = "",
    val preview: String = "",
    val created_at: String = "",
)

@Serializable
data class PendingResponse(val pending: List<PendingAction> = emptyList())

@Serializable
data class CodingSession(
    val id: String,
    val repository: String? = null,
    val objective: String? = null,
    val state: String = "",
    val updated_at: String? = null,
)

@Serializable
data class CodingSessionsResponse(val sessions: List<CodingSession> = emptyList())

@Serializable
data class AgentAuditEntry(
    val action: String = "",
    val risk: String = "",
    val outcome: String = "",
    val note: String? = null,
    val ts: String = "",
)

@Serializable
data class AgentAuditResponse(val enabled: Boolean = false, val entries: List<AgentAuditEntry> = emptyList())

@Serializable
data class LiveHost(
    val sampled_at: Long = 0,
    val cpu_percent: Double? = null,
    val memory_total_bytes: Long = 0,
    val memory_used_bytes: Long = 0,
    val uptime_seconds: Long = 0,
)

@Serializable
data class HostInfo(
    val os: String = "",
    val arch: String = "",
    val cpu: String = "",
    val cpu_cores: Int = 0,
    val mem_total_gb: Double = 0.0,
    val gpu: String = "",
)

@Serializable
data class SoftwareItem(
    val name: String,
    val present: Boolean = false,
    val version: String? = null,
    val detail: String? = null,
)

@Serializable
data class Brain(
    val id: String,
    val label: String = "",
    val cost: String = "",
    val available: Boolean = false,
    val note: String = "",
)

@Serializable
data class CatalogModel(
    val id: String,
    val backend: String = "",
    @SerialName("class") val modelClass: String = "",
    val cost: String = "",
    val available: Boolean = false,
)

@Serializable
data class Registry(
    val live_host: LiveHost? = null,
    val host: HostInfo? = null,
    val software: List<SoftwareItem> = emptyList(),
    val brains: List<Brain> = emptyList(),
    val models: List<CatalogModel> = emptyList(),
    val active_brain: String? = null,
)

@Serializable
data class PolicyModel(
    val provider: String = "",
    val model: String = "",
    val enabled: Boolean = false,
    val route: String? = null,
)

@Serializable
data class ModelPolicyList(val models: List<PolicyModel> = emptyList())

@Serializable
data class IbkrStatus(
    val reachable: Boolean = false,
    val authenticated: Boolean = false,
    val connected: Boolean? = null,
)

@Serializable
data class BackendUsage(val backend: String = "", val spent_eur: Double = 0.0, val total_tokens: Long = 0)

@Serializable
data class SystemUsage(
    val budget_eur: Double = 0.0,
    val spent_eur: Double = 0.0,
    val remaining_eur: Double = 0.0,
    val over_budget: Boolean = false,
    val requests: Long = 0,
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val total_tokens: Long = 0,
    val by_backend: List<BackendUsage> = emptyList(),
)

@Serializable
data class ServiceStatus(val label: String = "", val unit: String = "", val state: String = "")

@Serializable
data class DiskStatus(
    val label: String = "",
    val state: String = "",
    val total_bytes: Long? = null,
    val free_bytes: Long? = null,
    val used_percent: Double? = null,
)

@Serializable
data class ServicesResponse(val services: List<ServiceStatus> = emptyList(), val disks: List<DiskStatus> = emptyList())

@Serializable
data class SystemAuditEntry(val event: String = "", val outcome: String = "", val ts: String = "")

@Serializable
data class SystemAuditResponse(val entries: List<SystemAuditEntry> = emptyList())
