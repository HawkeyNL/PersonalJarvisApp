package com.hawkeynl.jarvis.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hawkeynl.jarvis.AppContainer
import com.hawkeynl.jarvis.auth.EnrollmentOutcome
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.ConnectionState
import com.hawkeynl.jarvis.network.ConversationMessage
import com.hawkeynl.jarvis.network.ConversationSummary
import com.hawkeynl.jarvis.network.EndpointValidation
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.UnreachableReason
import com.hawkeynl.jarvis.security.BiometricAvailability
import com.hawkeynl.jarvis.security.BiometricResult
import com.hawkeynl.jarvis.update.AndroidUpdateCheck
import com.hawkeynl.jarvis.update.AndroidUpdateDownload
import com.hawkeynl.jarvis.update.InstallerHandoff
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.hawkeynl.jarvis.chat.RealtimeEvent
import com.hawkeynl.jarvis.chat.RealtimeSpeech
import com.hawkeynl.jarvis.chat.SpeechRate

enum class AppTab { CHAT, CONVERSATIONS, SETTINGS }

sealed interface AndroidUpdateUiState {
    data object Idle : AndroidUpdateUiState
    data object Checking : AndroidUpdateUiState
    data object Current : AndroidUpdateUiState
    data class Available(val metadata: com.hawkeynl.jarvis.network.AndroidUpdateMetadata) : AndroidUpdateUiState
    data object Downloading : AndroidUpdateUiState
    data class Ready(val versionName: String) : AndroidUpdateUiState
    data object PermissionRequired : AndroidUpdateUiState
    data class Failed(val message: String) : AndroidUpdateUiState
}

data class JarvisUiState(
    val voiceEnabled: Boolean = false,
    val voiceRate: Float = 1f,
    val voiceStatus: String? = null,
    val selectedTab: AppTab = AppTab.CHAT,
    val endpoint: HomeNodeEndpoint? = null,
    val endpointDraft: String = "",
    val connection: ConnectionState = ConnectionState.NotConfigured,
    val locked: Boolean = false,
    val biometricMessage: String? = null,
    val authenticated: Boolean = false,
    val pairingPending: Boolean = false,
    val pairingExpiresAt: Long? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val conversationId: String? = null,
    val conversationTitle: String = "Nieuw gesprek",
    val messages: List<ConversationMessage> = emptyList(),
    val busy: Boolean = false,
    val appUpdate: AndroidUpdateUiState = AndroidUpdateUiState.Idle,
    val error: String? = null,
)

class JarvisViewModel(private val container: AppContainer) : ViewModel() {
    private val speech = RealtimeSpeech(container.localSpeech)
    private var realtimeAvailable = false
    private val pending = com.hawkeynl.jarvis.chat.PendingRuns()
    private val _state = MutableStateFlow(
        JarvisUiState(locked = container.sessions.hasSessionRecord(), voiceEnabled = container.voicePreferences.getBoolean("enabled", false),
            voiceRate = SpeechRate.normalize(container.voicePreferences.getFloat("rate", 1f))),
    )
    val state: StateFlow<JarvisUiState> = _state.asStateFlow()

    init {
        container.localSpeech.rate = _state.value.voiceRate
        viewModelScope.launch {
            container.settings.endpoint.collect { endpoint ->
                _state.update {
                    it.copy(
                        endpoint = endpoint,
                        endpointDraft = endpoint?.baseUrl ?: it.endpointDraft,
                        connection = if (endpoint == null) ConnectionState.NotConfigured else it.connection,
                    )
                }
                if (endpoint != null && !_state.value.locked) refreshAfterUnlock(endpoint)
            }
        }
    }

    fun selectTab(tab: AppTab) = _state.update { it.copy(selectedTab = tab) }
    fun editEndpoint(value: String) = _state.update { it.copy(endpointDraft = value, error = null) }

    fun saveEndpoint() {
        viewModelScope.launch {
            val parsed = HomeNodeEndpoint.parse(_state.value.endpointDraft)
            if (parsed is EndpointValidation.Valid && parsed.endpoint != _state.value.endpoint) {
                container.realtime.stop(); speech.stop(); pending.clear(); container.sessions.reset()
                _state.update { it.copy(authenticated = false, messages = emptyList(), conversations = emptyList(), conversationId = null) }
            }
            when (val result = container.settings.save(_state.value.endpointDraft)) {
                is EndpointValidation.Valid -> checkConnection(result.endpoint)
                is EndpointValidation.Invalid -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun checkConnection() {
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch { checkConnection(endpoint) }
    }

    fun checkForUpdate() {
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch { checkForUpdate(endpoint) }
    }

    fun downloadUpdate() {
        val endpoint = _state.value.endpoint ?: return
        val available = _state.value.appUpdate as? AndroidUpdateUiState.Available ?: return
        viewModelScope.launch {
            _state.update { it.copy(appUpdate = AndroidUpdateUiState.Downloading) }
            when (val result = container.appUpdates.download(endpoint, available.metadata)) {
                is AndroidUpdateDownload.Ready -> _state.update {
                    it.copy(appUpdate = AndroidUpdateUiState.Ready(result.versionName))
                }
                AndroidUpdateDownload.Unauthorized -> _state.update {
                    it.copy(authenticated = false, appUpdate = AndroidUpdateUiState.Failed("Sessie verlopen."))
                }
                is AndroidUpdateDownload.Failed -> _state.update {
                    it.copy(appUpdate = AndroidUpdateUiState.Failed(result.message))
                }
            }
        }
    }

    fun installerHandoff(result: InstallerHandoff) {
        _state.update {
            when (result) {
                InstallerHandoff.Started -> it
                InstallerHandoff.PermissionRequired -> it.copy(
                    appUpdate = AndroidUpdateUiState.PermissionRequired,
                )
                is InstallerHandoff.Failed -> it.copy(
                    appUpdate = AndroidUpdateUiState.Failed(result.message),
                )
            }
        }
    }

    fun beginEnrollment() {
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = container.enrollment.startOrResume(
                endpoint,
                "Jarvis Android (${Build.MODEL.take(40)})",
                Instant.now().epochSecond,
            )
            handleEnrollment(result)
            if (result is EnrollmentOutcome.Pending) pollPairingUntilResolved(endpoint)
        }
    }

    fun retryUnlockResult(result: BiometricResult) {
        when (result) {
            BiometricResult.Authenticated -> {
                _state.update { it.copy(locked = false, biometricMessage = null) }
                _state.value.endpoint?.let { endpoint ->
                    viewModelScope.launch { refreshAfterUnlock(endpoint) }
                }
            }
            BiometricResult.Cancelled -> _state.update { it.copy(biometricMessage = "Ontgrendeling geannuleerd.") }
            BiometricResult.Failed -> _state.update { it.copy(biometricMessage = "Biometrie niet herkend. Probeer opnieuw.") }
            BiometricResult.LockedOut -> _state.update { it.copy(biometricMessage = "Biometrie is tijdelijk geblokkeerd.") }
            is BiometricResult.Unavailable -> _state.update {
                it.copy(biometricMessage = result.availability.message())
            }
        }
    }

    fun openConversation(id: String) {
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            when (val result = container.conversations.load(endpoint, id)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        selectedTab = AppTab.CHAT,
                        conversationId = result.value.id,
                        conversationTitle = result.value.title,
                        messages = result.value.messages,
                        busy = result.value.assistant_running,
                    )
                }
                else -> handleApiFailure(result)
            }
        }
    }

    fun newConversation() = _state.update {
        it.copy(
            selectedTab = AppTab.CHAT,
            conversationId = null,
            conversationTitle = "Nieuw gesprek",
            messages = emptyList(),
            error = null,
        )
    }

    fun send(text: String) {
        val endpoint = _state.value.endpoint ?: return
        val current = _state.value
        if (current.busy || text.isBlank() || !current.authenticated || current.locked) return
        if (realtimeAvailable) {
            val requestId = java.util.UUID.randomUUID().toString()
            val optimisticId = "request:$requestId"
            if (!pending.add(requestId, optimisticId)) {
                _state.update { it.copy(error = "Te veel onbevestigde verzoeken. Herstel eerst de verbinding; niets wordt opnieuw verstuurd.") }
                return
            }
            _state.update { it.copy(busy = true, messages = it.messages + ConversationMessage("user", text.trim(), at = Instant.now().toString(), id = optimisticId)) }
            viewModelScope.launch {
                try {
                    val history = current.messages.map { com.hawkeynl.jarvis.network.ChatTurn(it.role, it.content) }
                    val run = container.realtime.submit(endpoint, requestId, current.conversationId, history, text.trim())
                    _state.update { if (current.conversationId == null && it.conversationId == null && it.messages.any { message -> message.id == optimisticId }) it.copy(conversationId = run.conversation_id) else it }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (_: Exception) {
                    _state.update { it.copy(busy = false, error = "Verzending niet bevestigd. Verbind opnieuw om de opgeslagen geschiedenis te controleren; geen automatische hergeneratie.") }
                }
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val history = current.messages.map { message ->
                com.hawkeynl.jarvis.network.ChatTurn(message.role, message.content)
            }
            when (val result = container.conversations.send(
                endpoint,
                current.conversationId,
                history,
                text,
            )) {
                is ApiResult.Success -> {
                    val now = Instant.now().toString()
                    _state.update {
                        it.copy(
                            conversationId = result.value.conversation_id,
                            conversationTitle = result.value.conversation_title,
                            messages = it.messages + listOf(
                                ConversationMessage("user", text.trim(), at = now),
                                ConversationMessage("assistant", result.value.reply, result.value.model, now),
                            ),
                            busy = false,
                        )
                    }
                    loadConversations(endpoint)
                }
                else -> handleApiFailure(result)
            }
        }
    }

    fun logout() {
        pending.clear()
        container.realtime.stop(); speech.stop()
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch {
            container.enrollment.logout(endpoint)
            _state.update { it.copy(authenticated = false, messages = emptyList(), conversations = emptyList()) }
        }
    }

    fun lockForBackground() {
        container.realtime.stop(); speech.stop()
        if (container.sessions.hasSessionRecord()) _state.update { it.copy(locked = true) }
    }

    fun resetDevice() {
        pending.clear()
        container.realtime.stop(); speech.stop()
        viewModelScope.launch {
            container.enrollment.resetDevice(_state.value.endpoint)
            _state.update {
                JarvisUiState(endpoint = it.endpoint, endpointDraft = it.endpointDraft, connection = it.connection)
            }
        }
    }

    private suspend fun refreshAfterUnlock(endpoint: HomeNodeEndpoint) {
        checkConnection(endpoint)
        val session = container.enrollment.currentSession()
        when {
            session.deviceId == null -> {
                val pending = container.sessions.pairingTicket()
                _state.update {
                    it.copy(
                        authenticated = false,
                        pairingPending = pending != null,
                        pairingExpiresAt = pending?.expires_at,
                    )
                }
                if (pending != null) pollPairingUntilResolved(endpoint)
            }
            session.token == null || session.expiresAt?.let { it <= Instant.now().epochSecond } != false ->
                handleEnrollment(container.enrollment.login(endpoint))
            else -> {
                _state.update { it.copy(authenticated = true) }
                loadConversations(endpoint)
                startRealtime(endpoint)
                checkForUpdate(endpoint)
            }
        }
    }

    private suspend fun checkConnection(endpoint: HomeNodeEndpoint) {
        _state.update { it.copy(connection = ConnectionState.Checking) }
        _state.update { state ->
            when (val result = container.api.ready(endpoint)) {
                is ApiResult.Success -> state.copy(connection = ConnectionState.Reachable(result.value.status))
                is ApiResult.Unreachable -> state.copy(connection = ConnectionState.Unreachable(result.reason))
                is ApiResult.HttpError -> state.copy(connection = ConnectionState.Rejected(result.status))
                ApiResult.Unauthorized -> state.copy(connection = ConnectionState.Rejected(401))
                is ApiResult.InvalidResponse -> state.copy(
                    connection = ConnectionState.Rejected(200),
                    error = result.message,
                )
            }
        }
    }

    private suspend fun pollPairingUntilResolved(endpoint: HomeNodeEndpoint) {
        while (_state.value.pairingPending) {
            val expiry = _state.value.pairingExpiresAt ?: break
            if (expiry <= Instant.now().epochSecond) {
                handleEnrollment(EnrollmentOutcome.Expired)
                break
            }
            delay(3_000)
            handleEnrollment(container.enrollment.poll(endpoint))
        }
    }

    private suspend fun handleEnrollment(result: EnrollmentOutcome) {
        when (result) {
            is EnrollmentOutcome.Pending -> _state.update {
                it.copy(
                    busy = false,
                    pairingPending = true,
                    pairingExpiresAt = result.ticket.expires_at,
                    error = null,
                )
            }
            is EnrollmentOutcome.Authenticated -> {
                _state.update {
                    it.copy(
                        busy = false,
                        pairingPending = false,
                        authenticated = true,
                        locked = true,
                        error = null,
                    )
                }
            }
            EnrollmentOutcome.Denied -> enrollmentError("Koppelverzoek geweigerd.")
            EnrollmentOutcome.Expired -> enrollmentError("Koppelverzoek verlopen. Start opnieuw.")
            EnrollmentOutcome.Unauthorized -> enrollmentError("Dit apparaat is niet meer geautoriseerd.")
            is EnrollmentOutcome.Unreachable -> enrollmentError(result.reason.message())
            is EnrollmentOutcome.Rejected -> enrollmentError(result.message ?: "Home Node weigerde het verzoek (${result.status}).")
            is EnrollmentOutcome.InvalidResponse -> enrollmentError(result.message)
        }
    }

    private fun enrollmentError(message: String) = _state.update {
        it.copy(busy = false, pairingPending = false, error = message)
    }

    private suspend fun loadConversations(endpoint: HomeNodeEndpoint) {
        when (val result = container.conversations.list(endpoint)) {
            is ApiResult.Success -> _state.update { it.copy(conversations = result.value.conversations) }
            else -> handleApiFailure(result)
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        speech.enabled = enabled
        container.voicePreferences.edit().putBoolean("enabled", enabled).apply()
        _state.update { it.copy(voiceEnabled = enabled) }
        if (!enabled) speech.ownedRun()?.let(container.realtime::releaseVoice)
    }

    fun setVoiceRate(value: Float) {
        val rate = SpeechRate.normalize(value)
        container.localSpeech.rate = rate
        container.voicePreferences.edit().putFloat("rate", rate).apply()
        _state.update { it.copy(voiceRate = rate) }
    }

    fun stopSpeaking() {
        speech.stop()
        speech.ownedRun()?.let(container.realtime::releaseVoice)
        _state.update { it.copy(voiceStatus = "Lokale spraak gestopt") }
        // Keep voiceEnabled unchanged for the next response. No chat request,
        // history mutation or generation cancellation is performed here.
    }

    override fun onCleared() { container.realtime.stop(); speech.stop(); super.onCleared() }

    private suspend fun startRealtime(endpoint: HomeNodeEndpoint) {
        realtimeAvailable = container.realtime.available(endpoint)
        if (!realtimeAvailable || _state.value.locked || !_state.value.authenticated) return
        speech.enabled = _state.value.voiceEnabled
        container.realtime.start(viewModelScope, endpoint, disconnected = { speech.stop() }) { event ->
            if (_state.value.endpoint != endpoint || _state.value.locked || !_state.value.authenticated) return@start
            speech.event(event)
            if (event.type == "connection.ready") {
                val recovered = container.realtime.recover(endpoint, pending.requests())
                if (_state.value.endpoint != endpoint || _state.value.locked || !_state.value.authenticated) return@start
                for ((request, run) in recovered) pending.reconcile(request, run)
                loadConversations(endpoint)
                val selected = _state.value.conversationId
                if (selected != null) {
                    val snapshot = container.conversations.load(endpoint, selected)
                    if (snapshot is ApiResult.Success) _state.update { if (it.conversationId == selected) it.copy(messages = snapshot.value.messages, busy = snapshot.value.assistant_running) else it }
                }
            } else receiveRealtime(event)
        }
    }

    private fun receiveRealtime(event: RealtimeEvent) {
        val p = event.payload
        when (event.type) {
            "voice.started" -> _state.update { it.copy(voiceStatus = "Actieve apparaat spreekt") }
            "voice.stopped" -> _state.update { it.copy(voiceStatus = "Spraak gestopt") }
            "voice.failed" -> _state.update { it.copy(voiceStatus = "Lokale spraak niet beschikbaar op actieve apparaat") }
            "voice.owner_changed" -> _state.update { it.copy(voiceStatus = null) }
            "conversation.created", "conversation.updated" -> {
                val id = p.id ?: return; val title = p.title ?: return; val at = p.updated_at ?: return
                _state.update { it.copy(conversations = (it.conversations.filterNot { item -> item.id == id } + ConversationSummary(id, title, at)).sortedByDescending { item -> item.updated_at }) }
            }
            "conversation.deleted" -> _state.update {
                if (it.conversationId == p.conversation_id) it.copy(conversationId = null, messages = emptyList(), conversations = it.conversations.filterNot { item -> item.id == p.conversation_id })
                else it.copy(conversations = it.conversations.filterNot { item -> item.id == p.conversation_id })
            }
            "message.created" -> {
                val message = p.message ?: return
                val optimistic = pending[p.request_id]
                _state.update { state ->
                    val selected = state.conversationId ?: if (optimistic != null && state.messages.any { it.id == optimistic }) message.conversation_id else null
                    if (selected != message.conversation_id) state else state.copy(conversationId = selected,
                        messages = upsertRealtime(state.messages, ConversationMessage(message.role, message.content, message.model, message.created_at, message.id), optimistic))
                }
            }
            "assistant.started" -> {
                val run = p.run_id ?: return
                _state.update { if (it.conversationId != p.conversation_id || it.messages.any { message -> message.id == "run:$run" }) it
                    else it.copy(busy = true, messages = it.messages + ConversationMessage("assistant", "", at = "", id = "run:$run")) }
            }
            "assistant.delta" -> {
                val run = p.run ?: return; val text = p.text ?: return
                _state.update { state ->
                    if (state.conversationId != run.conversation_id) state else {
                        val id = "run:${run.run_id}"
                        val rows = if (state.messages.any { it.id == id }) state.messages else state.messages + ConversationMessage("assistant", "", at = "", id = id)
                        state.copy(busy = true, messages = rows.map {
                            if (it.id == id && it.content.length + text.length <= 128 * 1024) it.copy(content = it.content + text) else it
                        })
                    }
                }
            }
            "assistant.completed" -> {
                val run = p.run ?: return; val message = p.message ?: return
                pending.remove(run.request_id)
                _state.update { state -> if (state.conversationId != message.conversation_id) state else state.copy(busy = false,
                    messages = upsertRealtime(state.messages, ConversationMessage(message.role, message.content, message.model, message.created_at, message.id), "run:${run.run_id}")) }
            }
            "assistant.failed" -> {
                val run = p.run ?: return; pending.remove(run.request_id)
                _state.update { if (it.conversationId == run.conversation_id) it.copy(busy = false, error = "Antwoord onderbroken. Je bericht is opgeslagen.") else it }
            }
        }
    }

    private suspend fun checkForUpdate(endpoint: HomeNodeEndpoint) {
        _state.update { it.copy(appUpdate = AndroidUpdateUiState.Checking) }
        when (val result = container.appUpdates.check(endpoint)) {
            AndroidUpdateCheck.Current -> _state.update {
                it.copy(appUpdate = AndroidUpdateUiState.Current)
            }
            is AndroidUpdateCheck.Available -> _state.update {
                it.copy(appUpdate = AndroidUpdateUiState.Available(result.metadata))
            }
            AndroidUpdateCheck.Unauthorized -> _state.update {
                it.copy(authenticated = false, appUpdate = AndroidUpdateUiState.Failed("Sessie verlopen."))
            }
            is AndroidUpdateCheck.Failed -> _state.update {
                it.copy(appUpdate = AndroidUpdateUiState.Failed(result.message))
            }
        }
    }

    private fun handleApiFailure(result: ApiResult<*>) = _state.update { state ->
        when (result) {
            ApiResult.Unauthorized -> state.copy(
                authenticated = false,
                busy = false,
                error = "Sessie verlopen. Meld dit apparaat opnieuw aan.",
            )
            is ApiResult.Unreachable -> state.copy(
                connection = ConnectionState.Unreachable(result.reason),
                busy = false,
                error = result.reason.message(),
            )
            is ApiResult.HttpError -> state.copy(busy = false, error = result.message ?: "HTTP ${result.status}")
            is ApiResult.InvalidResponse -> state.copy(busy = false, error = result.message)
            is ApiResult.Success -> state
        }
    }

    private fun upsertRealtime(rows: List<ConversationMessage>, canonical: ConversationMessage, optimistic: String?): List<ConversationMessage> {
        return com.hawkeynl.jarvis.chat.mergeCanonical(rows, canonical, optimistic) { it.id }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    JarvisViewModel(container) as T
            }
    }
}

private fun UnreachableReason.message(): String = when (this) {
    UnreachableReason.TIMEOUT -> "Home Node antwoordt niet binnen de tijdslimiet."
    UnreachableReason.DNS -> "De hostnaam van de Home Node kan niet worden gevonden."
    UnreachableReason.TLS -> "De beveiligde verbinding met de Home Node is ongeldig."
    UnreachableReason.REFUSED -> "Home Node weigert de verbinding."
    UnreachableReason.NETWORK -> "Home Node is niet bereikbaar. Controleer wifi en het adres."
}

private fun BiometricAvailability.message(): String = when (this) {
    BiometricAvailability.Available -> "Biometrie beschikbaar."
    BiometricAvailability.NotEnrolled -> "Stel eerst sterke biometrie in bij Android-instellingen."
    BiometricAvailability.NoHardware -> "Dit toestel heeft geen ondersteunde sterke biometrie."
    BiometricAvailability.TemporarilyUnavailable -> "Biometrie is tijdelijk niet beschikbaar."
    BiometricAvailability.SecurityUpdateRequired -> "Installeer de Android-beveiligingsupdate voor biometrie."
    BiometricAvailability.Unsupported -> "Sterke biometrie wordt niet ondersteund op dit toestel."
}
