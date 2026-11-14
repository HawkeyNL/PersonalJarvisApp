package com.hawkeynl.jarvis.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
    val nonce: String = "",
    val action_sha256: String = "",
    /** The exact JSON `action_sha256` hashes; null when too large to review here or from an older Core. */
    val action_json: String? = null,
    val approval_message: String? = null,
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

/** The part of GET /v1/trading/portfolio the Integrations page shows. */
@Serializable
data class IbkrPortfolio(val as_of: String = "", val positions: List<JsonElement> = emptyList())

/** One error of the sync or the broker: a code from Core's fixed list and when. */
data class IbkrError(val code: String?, val at: String?)

/** Something to fix: [commands] are for the owner to copy, never run, linked or opened. */
data class IbkrProblem(val code: String?, val severity: String?, val summary: String?, val commands: List<String>)

data class IbkrToken(val state: String?, val usable: Boolean?, val refreshedAt: String?, val expiresAt: String?)

data class IbkrLink(val lastError: IbkrError?, val rateLimitedUntil: String?, val brokerageSession: String?)

/** The part of GET /v1/trading/ibkr/status the Integrations page shows. Every field may be
 *  null: a missing, mistyped or oversized value reads as unknown, never as a failed pane. */
data class IbkrStatus(
    val overall: String?,
    val syncEnabled: Boolean?,
    val syncState: String?,
    val lastSuccessAt: String?,
    val syncError: IbkrError?,
    val service: String?,
    val credentials: String?,
    val reachable: Boolean?,
    val token: IbkrToken?,
    val ibkr: IbkrLink?,
    val problems: List<IbkrProblem>,
)

object IbkrStatusLimits {
    /** Core sends at most 8 problems. */
    const val PROBLEMS = 8
    const val COMMANDS = 8
    const val TEXT = 500
    /** Core's answer is a few KiB; anything larger is refused. */
    const val BYTES = 64 * 1024
}

private val IBKR_CODE = Regex("^[a-z0-9_]{1,64}$")
private val CONTROL = Regex("\\p{Cntrl}")

private fun JsonObject.ibkrObj(key: String) = this[key] as? JsonObject
private fun JsonObject.ibkrBool(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** A short single-line string, else null. */
private fun JsonObject.ibkrStr(key: String, max: Int = 64): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= max && !CONTROL.containsMatchIn(it) }

private fun JsonObject.ibkrCode(key: String) = ibkrStr(key)?.takeIf { IBKR_CODE.matches(it) }

private fun JsonObject.ibkrError(key: String) = ibkrObj(key)?.let { IbkrError(it.ibkrCode("code"), it.ibkrStr("at")) }

/** The status route's answer; null only when it is not a JSON object. */
fun readIbkrStatus(json: JsonElement): IbkrStatus? {
    val o = json as? JsonObject ?: return null
    val sync = o.ibkrObj("sync")
    val broker = o.ibkrObj("broker")
    val problems = (o["problems"] as? JsonArray).orEmpty().take(IbkrStatusLimits.PROBLEMS).filterIsInstance<JsonObject>().map { p ->
        IbkrProblem(
            p.ibkrCode("code"), p.ibkrCode("severity"), p.ibkrStr("summary", IbkrStatusLimits.TEXT),
            (p["commands"] as? JsonArray).orEmpty().take(IbkrStatusLimits.COMMANDS).mapNotNull { c ->
                // A command with a line break could paste as two; it is dropped, not shown.
                (c as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() && it.length <= IbkrStatusLimits.TEXT && !CONTROL.containsMatchIn(it) }
            },
        )
    }
    return IbkrStatus(
        overall = o.ibkrCode("overall"),
        syncEnabled = sync?.ibkrBool("enabled"),
        syncState = sync?.ibkrCode("state"),
        lastSuccessAt = sync?.ibkrStr("last_success_at"),
        syncError = sync?.ibkrError("last_error"),
        service = broker?.ibkrCode("service"),
        credentials = broker?.ibkrCode("credentials"),
        reachable = broker?.ibkrBool("reachable"),
        token = o.ibkrObj("token")?.let { IbkrToken(it.ibkrCode("state"), it.ibkrBool("usable"), it.ibkrStr("refreshed_at"), it.ibkrStr("expires_at")) },
        ibkr = o.ibkrObj("ibkr")?.let { IbkrLink(it.ibkrError("last_error"), it.ibkrStr("rate_limited_until"), it.ibkrCode("brokerage_session")) },
        problems = problems,
    )
}

@Serializable
data class BackendUsage(val backend: String = "", val spent_eur: Double = 0.0, val total_tokens: Long = 0)

@Serializable
data class SystemUsage(
    val budget_eur: Double = 0.0,
    val spent_eur: Double = 0.0,
    val remaining_eur: Double = 0.0,
    val over_budget: Boolean = false,
    val reserved_eur: Double = 0.0,
    val above_soft_budget: Boolean = false,
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
