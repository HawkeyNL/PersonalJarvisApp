package com.hawkeynl.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hawkeynl.jarvis.AppContainer
import com.hawkeynl.jarvis.network.AgentAuditResponse
import com.hawkeynl.jarvis.network.AgentsResponse
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.Availability
import com.hawkeynl.jarvis.network.CodingSessionsResponse
import com.hawkeynl.jarvis.network.DevicesResponse
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.IbkrStatus
import com.hawkeynl.jarvis.network.ModelPolicyList
import com.hawkeynl.jarvis.network.PendingResponse
import com.hawkeynl.jarvis.network.Registry
import com.hawkeynl.jarvis.network.ServicesResponse
import com.hawkeynl.jarvis.network.SystemAuditResponse
import com.hawkeynl.jarvis.network.SystemUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer

/** Everything the hub and node pages read from Core; each source loads on its own. */
data class CoreData(
    val agents: Availability<AgentsResponse> = Availability.Loading,
    val devices: Availability<DevicesResponse> = Availability.Loading,
    val pending: Availability<PendingResponse> = Availability.Loading,
    val sessions: Availability<CodingSessionsResponse> = Availability.Loading,
    val agentAudit: Availability<AgentAuditResponse> = Availability.Loading,
    val registry: Availability<Registry> = Availability.Loading,
    val models: Availability<ModelPolicyList> = Availability.Loading,
    val ibkr: Availability<IbkrStatus> = Availability.Loading,
    val usage: Availability<SystemUsage> = Availability.Loading,
    val services: Availability<ServicesResponse> = Availability.Loading,
    val systemAudit: Availability<SystemAuditResponse> = Availability.Loading,
    val loadedAt: Long = System.currentTimeMillis(),
    val denying: String? = null,
    val denyError: String? = null,
)

/**
 * Read-only Core data for the hub and node pages. Pages ask for a refresh when
 * they open; [clear] drops all data and cancels running reads when the app
 * locks or signs out.
 */
class CoreViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CoreData())
    val state: StateFlow<CoreData> = _state.asStateFlow()
    private var scope = childScope()
    private val inFlight = mutableSetOf<String>()

    private fun childScope() = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

    private fun <T> source(path: String, serializer: KSerializer<T>, set: CoreData.(Availability<T>) -> CoreData): suspend (HomeNodeEndpoint) -> Unit =
        { endpoint ->
            val result = container.core.read(endpoint, path, serializer)
            _state.update { it.set(result) }
        }

    private val agents = source("/v1/agents", AgentsResponse.serializer()) { copy(agents = it) }
    private val devices = source("/v1/devices", DevicesResponse.serializer()) { copy(devices = it) }
    private val pending = source("/v1/agent/pending", PendingResponse.serializer()) { copy(pending = it) }
    private val sessions = source("/v1/coding/sessions", CodingSessionsResponse.serializer()) { copy(sessions = it) }
    private val agentAudit = source("/v1/agent/audit", AgentAuditResponse.serializer()) { copy(agentAudit = it) }
    private val registry = source("/v1/system/registry", Registry.serializer()) { copy(registry = it) }
    private val models = source("/v1/system/models", ModelPolicyList.serializer()) { copy(models = it) }
    private val ibkr = source("/v1/broker/ibkr/status", IbkrStatus.serializer()) { copy(ibkr = it) }
    private val usage = source("/v1/system/usage", SystemUsage.serializer()) { copy(usage = it) }
    private val services = source("/v1/system/services", ServicesResponse.serializer()) { copy(services = it) }
    private val systemAudit = source("/v1/system/audit", SystemAuditResponse.serializer()) { copy(systemAudit = it) }

    fun refreshHub() = refresh("hub", agents, devices, pending, sessions, registry)
    fun refreshAgents() = refresh("agents", agents)
    fun refreshTasks() = refresh("tasks", sessions, pending, agentAudit)
    fun refreshIntegrations() = refresh("integrations", registry, models, ibkr)
    fun refreshHealth() = refresh("health", registry, usage, services, systemAudit)
    fun refreshContext() = refresh("context", devices)

    private fun refresh(page: String, vararg loads: suspend (HomeNodeEndpoint) -> Unit) {
        if (!inFlight.add(page)) return
        scope.launch {
            try {
                val endpoint = container.settings.endpoint.first() ?: return@launch
                coroutineScope { loads.forEach { load -> launch { load(endpoint) } } }
                _state.update { it.copy(loadedAt = System.currentTimeMillis()) }
            } finally {
                inFlight.remove(page)
            }
        }
    }

    /** Deny (cancel) a pending agent action. Approving needs a signature and
     *  is only possible on the desktop app. */
    fun deny(pendingId: String) {
        if (_state.value.denying != null) return
        _state.update { it.copy(denying = pendingId, denyError = null) }
        scope.launch {
            val endpoint = container.settings.endpoint.first()
            val result = if (endpoint == null) ApiResult.InvalidResponse("No Home Node configured.")
            else container.core.denyPending(endpoint, pendingId)
            _state.update { it.copy(denying = null, denyError = if (result is ApiResult.Success) null else "Denial failed: ${describe(result)}") }
            refreshTasks()
        }
    }

    fun clear() {
        scope.cancel()
        scope = childScope()
        inFlight.clear()
        _state.value = CoreData()
    }

    private fun describe(result: ApiResult<*>): String = when (result) {
        is ApiResult.Success -> "ok"
        ApiResult.Unauthorized -> "sign in again."
        is ApiResult.HttpError -> result.message ?: "HTTP ${result.status}"
        is ApiResult.Unreachable -> "Home Node unreachable."
        is ApiResult.InvalidResponse -> result.message
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CoreViewModel(container) as T
            }
    }
}
