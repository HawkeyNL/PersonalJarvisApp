package com.hawkeynl.jarvis.ui

import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
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
import com.hawkeynl.jarvis.network.PhotoMeta
import com.hawkeynl.jarvis.network.UnreachableReason
import com.hawkeynl.jarvis.security.ApprovalBusyException
import com.hawkeynl.jarvis.security.BiometricAvailability
import com.hawkeynl.jarvis.security.BiometricResult
import com.hawkeynl.jarvis.security.PairingApproval
import com.hawkeynl.jarvis.security.SigningCancelledException
import com.hawkeynl.jarvis.storage.ThemeMode
import com.hawkeynl.jarvis.update.AndroidUpdateCheck
import com.hawkeynl.jarvis.update.AndroidUpdateDownload
import com.hawkeynl.jarvis.update.InstallerHandoff
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.hawkeynl.jarvis.chat.DictationEvent
import com.hawkeynl.jarvis.chat.MIC_DENIED
import com.hawkeynl.jarvis.chat.MicTap
import com.hawkeynl.jarvis.chat.dictationAllowed
import com.hawkeynl.jarvis.chat.micTap
import com.hawkeynl.jarvis.chat.passesToSpeech
import com.hawkeynl.jarvis.chat.unsentTranscript
import com.hawkeynl.jarvis.chat.RealtimeEvent
import com.hawkeynl.jarvis.chat.RealtimeSpeech
import com.hawkeynl.jarvis.chat.startsAfterReply
import com.hawkeynl.jarvis.chat.SpeechRate
import com.hawkeynl.jarvis.chat.realtimeSnapshot
import com.hawkeynl.jarvis.chat.realtimeSelectedHistory

/** Where the authenticated app is: a tab root (HUB, TRADING, ACTIVITY, SETTINGS) or a
 *  screen pushed on one of the tabs; see [tabOf]. */
enum class Screen { HUB, CHAT, SETTINGS, CONVERSATIONS, AGENTS, TASKS, INTEGRATIONS, HEALTH, MEMORY, CONTEXT, PAIRING_APPROVALS, TRADING, ACTIVITY, JOBS, ROADMAPS, STOCKS, POSITIONS, HOLDINGS, MEMECOIN }

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

/** A chat photo for display: decoded, gone (deleted or expired), or not loadable now. */
sealed interface PhotoResult {
    data class Image(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : PhotoResult
    data object Removed : PhotoResult
    data object Unavailable : PhotoResult
}

/** An offer is shown and sent only while its own conversation is open. */
data class WebSearchOffer(val conversationId: String, val question: String)

data class JarvisUiState(
    val activationRequired: Boolean = false,
    val voiceEnabled: Boolean = false,
    val voiceRate: Float = 1f,
    val selectedVoice: String = "",
    val availableVoices: List<com.hawkeynl.jarvis.chat.LocalVoice> = emptyList(),
    val voiceStatus: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val screen: Screen = Screen.HUB,
    /** Node the Health screen opens on; set by a deep link. */
    val healthNode: String = "core",
    val endpoint: HomeNodeEndpoint? = null,
    val endpointDraft: String = "",
    val connection: ConnectionState = ConnectionState.NotConfigured,
    val locked: Boolean = false,
    val biometricMessage: String? = null,
    val authenticated: Boolean = false,
    /** This device's id as Core knows it, read once at sign-in; read it through [ownDeviceId]. */
    val signedInDeviceId: String? = null,
    val pairingPending: Boolean = false,
    val pairingExpiresAt: Long? = null,
    /** Keystore invalidated the device key; onboarding offers erase and pair again. */
    val deviceKeyLost: Boolean = false,
    /** This device's key fingerprint, shown while waiting for approval. */
    val ownFingerprint: String? = null,
    /** Why Core did not revoke this device; set while the user decides on erasing locally only. */
    val localEraseOffer: String? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val conversationId: String? = null,
    val conversationTitle: String = "New conversation",
    val messages: List<ConversationMessage> = emptyList(),
    val busy: Boolean = false,
    /** Core's web search advice after the last reply in [WebSearchOffer.conversationId]. */
    val researchOffer: WebSearchOffer? = null,
    /** Jobs answers in the open conversation proposed; only Add or a saved edit makes one. */
    val jobProposals: List<com.hawkeynl.jarvis.network.ProposalCard> = emptyList(),
    /** A run Core started itself right after a reply (its automatic web search);
     *  only meaningful while its `run:` placeholder row is shown. */
    val webSearchRun: String? = null,
    val appUpdate: AndroidUpdateUiState = AndroidUpdateUiState.Idle,
    val error: String? = null,
    /** A signed password reset waiting for root confirmation on the Home Node. */
    val passwordResetRequest: String? = null,
    /** Photos in the composer, in order. */
    val chips: List<PhotoChip> = emptyList(),
    /** Why a photo was not added (the limit); cleared by the next attach. */
    val chipNotice: String? = null,
    /** The one-time note on where photos go was shown with a sent photo. */
    val photosDisclosed: Boolean = false,
    /** The open conversation's photos that still exist (from its last read). */
    val photoMeta: Map<String, PhotoMeta> = emptyMap(),
    /** Photos known to be gone: shown as "Photo removed" without a download. */
    val removedPhotos: Set<String> = emptySet(),
    /** The text of a message Core refused; the composer takes it back once. */
    val returnedDraft: String? = null,
    /** The chat mic is listening. */
    val listening: Boolean = false,
    /** Why the mic stopped (no access, no recogniser); cleared by the next tap. */
    val dictationNotice: String? = null,
    /** A transcript that could not be sent; the composer appends it once. */
    val dictated: String? = null,
    /** [VOICE_DISCLOSURE] is showing: the next mic tap may use the phone's speech service. */
    val dictationDisclosure: Boolean = false,
) {
    /** This device's id, only while signed in (history shows it as "this device"). */
    val ownDeviceId: String? get() = signedInDeviceId.takeIf { authenticated }
}

class JarvisViewModel(private val container: AppContainer) : ViewModel() {
    private val speech = RealtimeSpeech(container.localSpeech)
    private var realtimeAvailable = false
    private val pending = com.hawkeynl.jarvis.chat.PendingRuns()
    private var unlinking = false
    private var chipKeys = 0
    // One sign-in on one Home Node: bumped on sign-out, Home Node change, password change,
    // erase and sign-in, never by the background lock. A late answer from another sign-in
    // must not restore, delete or sign out anything in this one. Main thread only.
    private var identity = 0
    private fun newIdentity() {
        identity++
        seenProposals.clear()
        _state.update { it.copy(jobProposals = emptyList()) }
    }
    // Each job proposal shows once per sign-in, also after a replayed event. Main thread only.
    private val seenProposals = mutableSetOf<String>()
    /** A job a chat card opens on the Jobs screen; taken once by [takeJobToOpen]. */
    private var jobToOpen: String? = null
    /** One picked photo is shrunk at a time (each decode can take tens of MB). */
    private val preparing = kotlinx.coroutines.sync.Mutex()
    /** Photos picked while the app was locked (the picker stops this activity, which locks
     *  it), with the sign-in they were picked in; attached after the unlock. */
    private var picked: Pair<Int, List<android.net.Uri>>? = null
    // Decoded chat photos, least recently shown out first; main thread only. Cleared with
    // every identity change (sign-out, erase, password, Home Node, session expiry).
    private var photoEpoch = 0
    private fun clearPhotos() { photoEpoch++; photos.clear() }
    private val photos = object : LinkedHashMap<String, PhotoResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PhotoResult>?) = size > 24
    }
    private val _state = MutableStateFlow(
        JarvisUiState(locked = container.sessions.hasSessionRecord(), voiceEnabled = container.voicePreferences.getBoolean("enabled", false),
            voiceRate = SpeechRate.normalize(container.voicePreferences.getFloat("rate", 1f)),
            selectedVoice = container.voicePreferences.getString("voice", "") ?: "", themeMode = container.appearance.mode,
            photosDisclosed = container.photoPreferences.getBoolean("disclosed", false)),
    )
    val state: StateFlow<JarvisUiState> = _state.asStateFlow()
    // Apart from [state]: it changes many times a second while listening.
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    init {
        container.localSpeech.rate = _state.value.voiceRate
        container.localSpeech.selectedVoice = _state.value.selectedVoice
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

    fun navigate(screen: Screen) = _state.update { it.copy(screen = screen, healthNode = "core") }

    /** Only navigates. The shell renders it after unlock and sign-in. */
    fun openDeepLink(link: DeepLink) = _state.update { it.copy(screen = link.screen, healthNode = link.healthNode) }

    fun editEndpoint(value: String) = _state.update { it.copy(endpointDraft = value, error = null) }

    fun saveEndpoint() {
        container.sessions.modelAuthorization.invalidate()
        clearPhotos()
        viewModelScope.launch {
            val parsed = HomeNodeEndpoint.parse(_state.value.endpointDraft)
            if (parsed is EndpointValidation.Valid && parsed.endpoint != _state.value.endpoint) {
                container.realtime.stop(); speech.stop(); cancelDictation(); pending.clear(); container.sessions.reset()
                newIdentity()
                _state.update {
                    it.copy(authenticated = false, messages = emptyList(), conversations = emptyList(), conversationId = null, passwordResetRequest = null,
                        chips = emptyList(), chipNotice = null)
                }
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
                    it.copy(authenticated = false, appUpdate = AndroidUpdateUiState.Failed("Session expired."))
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

    fun beginEnrollment(password: String? = null, activationCode: String? = null) {
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = container.enrollment.startOrResume(
                endpoint,
                "Jarvis Android (${Build.MODEL.take(40)})",
                Instant.now().epochSecond,
                password,
                activationCode,
            )
            handleEnrollment(result)
            if (result is EnrollmentOutcome.Pending) pollPairingUntilResolved(endpoint)
        }
    }

    fun retryUnlockResult(result: BiometricResult) {
        when (result) {
            is BiometricResult.Authenticated -> {
                _state.update { it.copy(locked = false, biometricMessage = null) }
                _state.value.endpoint?.let { endpoint ->
                    viewModelScope.launch { refreshAfterUnlock(endpoint); attachPicked() }
                }
            }
            BiometricResult.Cancelled -> _state.update { it.copy(biometricMessage = "Unlock cancelled.") }
            BiometricResult.Failed -> _state.update { it.copy(biometricMessage = "Biometrics not recognised. Try again.") }
            BiometricResult.LockedOut -> _state.update { it.copy(biometricMessage = "Biometrics are temporarily locked.") }
            is BiometricResult.Unavailable -> _state.update {
                it.copy(biometricMessage = result.availability.message())
            }
        }
    }

    fun openConversation(id: String) {
        val endpoint = _state.value.endpoint ?: return
        cancelDictation()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, researchOffer = null, jobProposals = emptyList()) }
            when (val result = container.conversations.load(endpoint, id)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        screen = Screen.CHAT,
                        conversationId = result.value.id,
                        conversationTitle = result.value.title,
                        messages = result.value.messages,
                        busy = result.value.assistant_running,
                        photoMeta = result.value.photos.associateBy { photo -> photo.id },
                        removedPhotos = missingPhotos(result.value.messages, result.value.photos),
                    )
                }
                else -> handleApiFailure(result)
            }
        }
    }

    fun newConversation() {
        // A transcript is never sent into another conversation than the one it was spoken in.
        cancelDictation()
        _state.update {
            it.copy(
                screen = Screen.CHAT,
                conversationId = null,
                conversationTitle = "New conversation",
                messages = emptyList(),
                error = null,
                researchOffer = null,
                jobProposals = emptyList(),
                photoMeta = emptyMap(),
                removedPhotos = emptySet(),
            )
        }
    }

    /** Creates a proposed job: its body as Core sent it, or [edited] (the editor's draft).
     *  Never behind the lock or twice at once; a result for a sign-in or conversation the
     *  owner has left is dropped. */
    fun addProposal(id: String, edited: com.hawkeynl.jarvis.network.JobDraft? = null) {
        val state = _state.value
        val edit = edited?.let { com.hawkeynl.jarvis.network.newJob(it) ?: return }
        val started = com.hawkeynl.jarvis.network.startAdd(state.jobProposals, id, !state.locked && state.authenticated) ?: return
        val card = started.first { it.id == id }
        val asked = identity
        val conversation = state.conversationId
        _state.update { it.copy(jobProposals = started) }
        viewModelScope.launch {
            val result = com.hawkeynl.jarvis.network.addJob(edit ?: card.body, edited?.kind ?: card.draft.kind) { body ->
                jobs { e, t -> createJob(e, t, body) }
            }
            _state.update {
                it.copy(jobProposals = com.hawkeynl.jarvis.network.finishAdd(it.jobProposals, id, result, asked == identity && it.conversationId == conversation))
            }
        }
    }

    fun dismissProposal(id: String) = _state.update { it.copy(jobProposals = it.jobProposals.filterNot { card -> card.id == id }) }

    /** Opens Jobs from a chat card: on the job's page when [id] is set. */
    fun openJob(id: String?) {
        jobToOpen = id?.takeIf { com.hawkeynl.jarvis.network.isJobId(it) }
        navigate(Screen.JOBS)
    }

    fun takeJobToOpen(): String? = jobToOpen.also { jobToOpen = null }

    /** Only the owner's click on a Core offer passes [research]. */
    fun searchWeb() {
        val state = _state.value
        val offer = state.researchOffer?.takeIf { it.conversationId == state.conversationId } ?: return
        if (realtimeAvailable) send(offer.question, research = true)
    }

    /** Sends [text] with the composer's photos (none for a web search or a transcript). False
     *  when nothing was sent, so the composer keeps the draft. */
    fun send(text: String, research: Boolean = false, withPhotos: Boolean = !research): Boolean {
        val endpoint = _state.value.endpoint ?: return false
        if (withPhotos) _state.update { it.copy(chips = expireStale(it.chips, System.currentTimeMillis())) }
        val current = _state.value
        val (taken, kept) = takePhotos(current.chips, withPhotos)
        if (!canSend(text, taken, current.busy) || !current.authenticated || current.locked) return false
        val attachments = chipIds(taken)
        // Photos go only with the runs API; the older chat endpoint refuses them.
        if (attachments.isNotEmpty() && !realtimeAvailable) {
            _state.update { it.copy(error = "Photos need a live connection to your Home Node. Nothing was sent.") }
            return false
        }
        if (realtimeAvailable) {
            val requestId = java.util.UUID.randomUUID().toString()
            val optimisticId = "request:$requestId"
            if (!pending.add(requestId, optimisticId)) {
                _state.update { it.copy(error = "Too many unconfirmed requests. Restore the connection first; nothing is sent again.") }
                return false
            }
            // The message shows its photos from the previews, without a download.
            for (chip in taken) chip.preview?.let { photos[chip.id!!] = PhotoResult.Image(it) }
            if (attachments.isNotEmpty()) container.photoPreferences.edit().putBoolean("disclosed", true).apply()
            _state.update {
                it.copy(busy = true, researchOffer = null, chips = kept, chipNotice = null,
                    photosDisclosed = it.photosDisclosed || attachments.isNotEmpty(),
                    messages = it.messages + ConversationMessage("user", text.trim(), at = Instant.now().toString(), id = optimisticId, attachments = attachments))
            }
            val asked = identity
            viewModelScope.launch {
                try {
                    val history = current.messages.map { com.hawkeynl.jarvis.network.ChatTurn(it.role, it.content) }
                    val run = container.realtime.submit(endpoint, requestId, current.conversationId, history, text.trim(), if (research) "research" else null, attachments)
                    if (asked != identity) return@launch
                    _state.update { if (current.conversationId == null && it.conversationId == null && it.messages.any { message -> message.id == optimisticId }) it.copy(conversationId = run.conversation_id) else it }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
                    val status = (error as? com.hawkeynl.jarvis.chat.SubmitFailed)?.status
                    when (submitFailure(status, asked == identity)) {
                        SubmitOutcome.IGNORE -> return@launch
                        SubmitOutcome.UNCONFIRMED -> {
                            _state.update { it.copy(busy = false, error = "Sending was not confirmed. Reconnect to check the stored history; nothing is regenerated automatically.") }
                            return@launch
                        }
                        SubmitOutcome.REFUSED -> Unit
                    }
                    val refusal = runRefusal(status)
                    // Core refused it outright and stored nothing: the draft and photos return.
                    pending.remove(requestId)
                    for (chip in taken) chip.id?.let { photos.remove(it) }
                    val back = restoreChips(_state.value.chips, taken)
                    _state.update {
                        it.copy(busy = false, error = refusal, chips = back.chips, chipNotice = back.notice ?: it.chipNotice,
                            messages = it.messages.filterNot { message -> message.id == optimisticId }, returnedDraft = text.takeUnless { research })
                    }
                    // No room left (photos added since): the rest are deleted on the Home Node.
                    for (id in back.dropped) forgetPhoto(endpoint, id)
                }
            }
            return true
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
        return true
    }

    fun draftReturned() = _state.update { it.copy(returnedDraft = null) }

    fun dictationAvailable(): Boolean = container.dictation.available()

    /** The mic button (desktop useMic): listen, or stop and send what was heard. */
    fun toggleDictation() {
        val current = _state.value
        val disclosed = container.voicePreferences.getBoolean("dictationDisclosed", false)
        when (val tap = micTap(current.listening, current.locked, current.authenticated, disclosed, current.dictationDisclosure)) {
            MicTap.Stop -> container.dictation.stop()
            MicTap.Ignore -> Unit
            is MicTap.Start -> {
                // A second tap on the shown notice is the owner's go for the phone's speech service.
                if (tap.offDevice && !disclosed) container.voicePreferences.edit().putBoolean("dictationDisclosed", true).apply()
                val asked = identity
                _micLevel.value = 0f
                // Listening before start: the session may end at once (no recogniser, no consent).
                _state.update { it.copy(listening = true, dictationNotice = null, dictationDisclosure = false) }
                container.dictation.start(tap.offDevice) { event -> dictationEvent(asked, event) }
            }
        }
    }

    /** Leaving the chat, the lock and sign-out drop what was not recognised yet. */
    fun cancelDictation() {
        container.dictation.cancel()
        _micLevel.value = 0f
        if (_state.value.listening || _state.value.dictationDisclosure) _state.update { it.copy(listening = false, dictationDisclosure = false) }
    }

    fun dictationDenied() = _state.update { it.copy(dictationNotice = MIC_DENIED) }

    fun dictatedTaken() = _state.update { it.copy(dictated = null) }

    private fun dictationEvent(asked: Int, event: DictationEvent) {
        // Fail closed: nothing heard behind the lock or from another sign-in is used.
        if (!dictationAllowed(asked, identity, _state.value.locked)) { cancelDictation(); return }
        when (event) {
            // Jarvis must not dictate its own spoken reply; a notice-only tap leaves it speaking.
            DictationEvent.Listening -> {
                speech.stop()
                speech.ownedRun()?.let(container.realtime::releaseVoice)
            }
            is DictationEvent.Level -> _micLevel.value = event.value
            is DictationEvent.Ended -> {
                _micLevel.value = 0f
                _state.update { it.copy(listening = false, dictationNotice = event.message) }
            }
            DictationEvent.NeedsConsent -> _state.update { it.copy(listening = false, dictationDisclosure = true) }
            is DictationEvent.Heard -> {
                _micLevel.value = 0f
                _state.update { it.copy(listening = false, dictationNotice = null) }
                // Sent at once like desktop; while a reply runs it waits in the composer instead.
                unsentTranscript(send(event.text, withPhotos = false), event.text)?.let { text -> _state.update { it.copy(dictated = text) } }
            }
        }
    }

    /** The photo picker's answer. Opening it stops this activity, so the app is usually
     *  locked by now: the photos then wait for the unlock. */
    fun photosPicked(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        if (_state.value.locked) picked = addPicked(picked, identity, uris) else attachPhotos(uris)
    }

    /** After an unlock: photos picked while locked, if still the same sign-in. */
    private fun attachPicked() {
        val (pickedIn, uris) = picked ?: return
        picked = null
        attachPhotos(pickedToAttach(pickedIn, identity, uris))
    }

    /** Adds picked photos, up to [MAX_PHOTOS] in the composer. Shrinking and uploading start at once. */
    fun attachPhotos(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || _state.value.locked || !_state.value.authenticated) return
        val endpoint = _state.value.endpoint ?: return
        val room = maxOf(MAX_PHOTOS - _state.value.chips.size, 0)
        val added = uris.take(room).map { it to PhotoChip(chipKeys++) }
        _state.update { it.copy(chips = it.chips + added.map { (_, chip) -> chip }, chipNotice = if (uris.size > room) TOO_MANY_PHOTOS else null) }
        for ((uri, chip) in added) viewModelScope.launch { upload(endpoint, chip.key, uri) }
    }

    private suspend fun upload(endpoint: HomeNodeEndpoint, key: Int, uri: android.net.Uri) {
        val asked = identity
        val prepared = try {
            preparing.withLock {
                // Removed (or dropped by a sign-out) while waiting its turn: nothing to shrink.
                if (_state.value.chips.none { it.key == key }) return
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { preparePhoto(container.contentResolver, uri) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            updateChip(key) { it.copy(state = ChipState.FAILED, error = (error as? PhotoRefused)?.message ?: UNSUPPORTED) }
            return
        }
        // Another sign-in while shrinking: its token does not get this photo.
        if (asked != identity) {
            updateChip(key) { it.copy(state = ChipState.FAILED, error = "Signed in again; remove this photo and attach it again.") }
            return
        }
        if (!updateChip(key) { it.copy(state = ChipState.UPLOADING, preview = prepared.preview) }) return
        val result = container.conversations.uploadPhoto(endpoint, prepared.jpeg)
        val id = (result as? ApiResult.Success)?.value
        val shown = updateChip(key) {
            if (id != null) it.copy(state = ChipState.READY, id = id, at = System.currentTimeMillis())
            else it.copy(state = ChipState.FAILED, error = when (result) {
                is ApiResult.HttpError -> uploadFailure(result.status)
                is ApiResult.InvalidResponse -> result.message
                ApiResult.Unauthorized -> "Session expired. Sign this device in again."
                else -> "Could not reach your Home Node."
            })
        }
        // Removed (✕) while uploading: delete what Core kept. After a sign-out the photo
        // stays pending and Core removes it after 24 hours.
        if (forgetAfterUpload(shown, id, asked == identity)) forgetPhoto(endpoint, id!!)
        if (result == ApiResult.Unauthorized && asked == identity) handleApiFailure(result)
    }

    /** Changes one composer photo; false when it is no longer there. Main thread only. */
    private fun updateChip(key: Int, change: (PhotoChip) -> PhotoChip): Boolean {
        if (_state.value.chips.none { it.key == key }) return false
        _state.update { it.copy(chips = it.chips.map { chip -> if (chip.key == key) change(chip) else chip }) }
        return true
    }

    /** ✕ on a chip: drop it, and delete it on the Home Node once uploaded. */
    fun removeChip(key: Int) {
        val chip = _state.value.chips.firstOrNull { it.key == key } ?: return
        _state.update { it.copy(chips = it.chips.filterNot { other -> other.key == key }, chipNotice = null) }
        val endpoint = _state.value.endpoint ?: return
        chip.id?.let { forgetPhoto(endpoint, it) }
    }

    /** Best effort: Core removes an unsent photo after 24 hours anyway. */
    private fun forgetPhoto(endpoint: HomeNodeEndpoint, id: String) {
        viewModelScope.launch { container.conversations.deletePhoto(endpoint, id) }
    }

    /** Keeps a photo past its 90 days, or stops keeping it; returns why it failed. A photo
     *  Core no longer has (deleted elsewhere or expired) is marked removed instead. */
    suspend fun keepPhoto(id: String, saved: Boolean): String? = viewModelScope.async {
        val endpoint = _state.value.endpoint ?: return@async KEEP_FAILED
        when (val result = container.conversations.keepPhoto(endpoint, id, saved)) {
            is ApiResult.Success -> { _state.update { it.copy(photoMeta = it.photoMeta + (id to result.value)) }; null }
            is ApiResult.HttpError -> if (result.status == 404) { markRemoved(id); null } else KEEP_FAILED
            ApiResult.Unauthorized -> { handleApiFailure(result); KEEP_FAILED }
            else -> KEEP_FAILED
        }
    }.await()

    /** Deletes a photo for good (already gone counts as deleted); returns why it failed. */
    suspend fun deletePhoto(id: String): String? = viewModelScope.async {
        val endpoint = _state.value.endpoint ?: return@async DELETE_FAILED
        when (val result = container.conversations.deletePhoto(endpoint, id)) {
            is ApiResult.Success -> { markRemoved(id); null }
            is ApiResult.HttpError -> if (result.status == 404) { markRemoved(id); null } else DELETE_FAILED
            ApiResult.Unauthorized -> { handleApiFailure(result); DELETE_FAILED }
            else -> DELETE_FAILED
        }
    }.await()

    /** One Jobs or Roadmaps call for the signed-in Home Node, with its token read here. Null
     *  when the app is locked or signed out, or the sign-in changed before the answer came: a
     *  late answer is dropped, and only a 401 of this sign-in ends it. */
    suspend fun <T> jobs(call: suspend com.hawkeynl.jarvis.network.JarvisApi.(HomeNodeEndpoint, String) -> ApiResult<T>): ApiResult<T>? =
        viewModelScope.async {
            val current = _state.value
            val endpoint = current.endpoint?.takeIf { !current.locked && current.authenticated } ?: return@async null
            val token = container.sessions.session().token ?: return@async null
            val asked = identity
            val result = container.api.call(endpoint, token)
            if (asked != identity || _state.value.locked || !_state.value.authenticated) return@async null
            if (result == ApiResult.Unauthorized) handleApiFailure(result)
            result
        }.await()

    /** Forget a photo Core no longer has: its messages show "Photo removed". */
    private fun markRemoved(id: String) {
        photos[id] = PhotoResult.Removed
        _state.update { it.copy(photoMeta = it.photoMeta - id, removedPhotos = it.removedPhotos + id) }
    }

    /** Deletes the open conversation on the Home Node, its photos included (kept ones too),
     *  then opens the next one; returns why it failed. */
    suspend fun deleteConversation(): String? = viewModelScope.async {
        val endpoint = _state.value.endpoint ?: return@async "Could not delete: no Home Node is set."
        val id = _state.value.conversationId ?: return@async null
        when (val result = container.conversations.delete(endpoint, id)) {
            is ApiResult.Success -> {
                _state.update { it.copy(conversations = it.conversations.filterNot { item -> item.id == id }) }
                // As on desktop: the first remaining conversation opens, or a new one.
                if (_state.value.conversationId == id) _state.value.conversations.firstOrNull()?.let { openConversation(it.id) } ?: newConversation()
                null
            }
            ApiResult.Unauthorized -> { handleApiFailure(result); "Session expired. Sign this device in again." }
            is ApiResult.HttpError -> "Could not delete: ${result.message ?: "HTTP ${result.status}"}"
            is ApiResult.Unreachable -> "Could not delete: ${result.reason.message()}"
            is ApiResult.InvalidResponse -> "Could not delete: ${result.message}"
        }
    }.await()

    /** One chat photo, fetched with the session token and decoded off the main thread.
     *  [full] is for the full-screen view: a larger decode that is not cached. */
    suspend fun photo(id: String, full: Boolean = false): PhotoResult {
        if (id in _state.value.removedPhotos) return PhotoResult.Removed
        if (!full) photos[id]?.let { return it }
        val endpoint = _state.value.endpoint ?: return PhotoResult.Unavailable
        val epoch = photoEpoch
        val result = when (val bytes = container.conversations.photo(endpoint, id)) {
            is ApiResult.Success -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodePhoto(bytes.value, if (full) 1600 else 512) }
                ?.let { PhotoResult.Image(it) } ?: PhotoResult.Unavailable
            is ApiResult.HttpError -> if (bytes.status == 404) PhotoResult.Removed else PhotoResult.Unavailable
            // An expired session goes through the usual sign-in-again flow (once: it clears the epoch).
            ApiResult.Unauthorized -> { if (epoch == photoEpoch) handleApiFailure(bytes); PhotoResult.Unavailable }
            else -> PhotoResult.Unavailable
        }
        // A late answer from before an identity change is dropped.
        if (epoch != photoEpoch) return PhotoResult.Unavailable
        // A passing failure is not remembered; the next showing retries.
        if (result == PhotoResult.Removed || (result is PhotoResult.Image && !full)) photos[id] = result
        return result
    }

    fun logout() {
        container.sessions.modelAuthorization.invalidate()
        clearPhotos()
        newIdentity()
        pending.clear()
        container.realtime.stop(); speech.stop(); cancelDictation()
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch {
            container.enrollment.logout(endpoint)
            _state.update {
                it.copy(authenticated = false, messages = emptyList(), conversations = emptyList(), passwordResetRequest = null,
                    chips = emptyList(), chipNotice = null)
            }
        }
    }

    /** Changes the account password (one strong-biometric prompt). Core then ends every
     *  session, so this phone signs in again with the new password. Returns why it failed. */
    // Runs in viewModelScope: leaving Settings must not cut the flow off after Core changed it.
    suspend fun changePassword(current: String, new: String): String? = viewModelScope.async {
        val failure = try {
            container.accountApprovals.changePassword(current, new)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        if (failure != null) return@async PairingApproval.displayName(failure.message ?: "The password was not changed.", 160)
        container.sessions.modelAuthorization.invalidate()
        clearPhotos()
        newIdentity()
        pending.clear()
        container.realtime.stop(); speech.stop(); cancelDictation()
        _state.update {
            it.copy(authenticated = false, messages = emptyList(), conversations = emptyList(), chips = emptyList(), chipNotice = null,
                error = "Password changed. Sign in with the new password.")
        }
        null
    }.await()

    /** Starts a forgotten-password reset (one strong-biometric prompt). Nothing changes until
     *  the owner confirms the returned request id as root on the Home Node. */
    // Kept in the UI state: the id must survive leaving Settings or rotation while it runs.
    suspend fun resetPassword(new: String): String? = viewModelScope.async {
        try {
            val id = container.accountApprovals.resetPassword(new)
            _state.update { it.copy(passwordResetRequest = id) }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PairingApproval.displayName(error.message ?: "The reset was not started.", 160)
        }
    }.await()

    fun dismissPasswordReset() = _state.update { it.copy(passwordResetRequest = null) }

    fun lockForBackground() {
        container.sessions.modelAuthorization.invalidate()
        clearPhotos()
        container.realtime.stop(); speech.stop(); cancelDictation()
        if (container.sessions.hasSessionRecord()) _state.update { it.copy(locked = true) }
    }

    /** Revokes this device on Core with a signed approval, then erases it here. If Core
     *  does not confirm, nothing is erased: the user is asked about erasing locally only. */
    fun resetDevice() {
        if (unlinking) return
        unlinking = true
        viewModelScope.launch {
            val failure = try {
                container.accountApprovals.unlinkThisDevice()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            } finally {
                unlinking = false
            }
            if (failure == null) eraseLocally() else _state.update { afterFailedUnlink(it, failure) }
        }
    }

    /** The user chose to erase this device here although Core still lists it as active. */
    fun eraseLocally() {
        _state.update { it.copy(localEraseOffer = null) }
        container.sessions.modelAuthorization.invalidate()
        clearPhotos()
        newIdentity()
        pending.clear()
        container.realtime.stop(); speech.stop(); cancelDictation()
        // A new owner of this phone hears the voice and photo notices again.
        container.voicePreferences.edit().remove("dictationDisclosed").apply()
        container.photoPreferences.edit().remove("disclosed").apply()
        viewModelScope.launch {
            container.enrollment.eraseLocally()
            _state.update {
                JarvisUiState(endpoint = it.endpoint, endpointDraft = it.endpointDraft, connection = it.connection, themeMode = it.themeMode)
            }
        }
    }

    fun keepDevice() = _state.update { it.copy(localEraseOffer = null) }

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
                _state.update { it.copy(authenticated = true, signedInDeviceId = session.deviceId) }
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
            EnrollmentOutcome.PasswordRequired -> _state.update {
                it.copy(busy = false, pairingPending = false, authenticated = false, locked = false,
                    activationRequired = false, error = "Enter your account password to sign in.")
            }
            EnrollmentOutcome.ActivationRequired -> _state.update { it.copy(busy = false, pairingPending = false, activationRequired = true, error = null) }
            is EnrollmentOutcome.Pending -> {
                // Read every time: a re-pair after a lost device key has a new key.
                val fingerprint = runCatching {
                    PairingApproval.grouped(PairingApproval.fingerprint(container.identity.publicKeyHex()))
                }.getOrNull()
                _state.update {
                    it.copy(
                        busy = false,
                        pairingPending = true,
                        pairingExpiresAt = result.ticket.expires_at,
                        ownFingerprint = fingerprint,
                        error = null,
                    )
                }
            }
            is EnrollmentOutcome.Authenticated -> {
                cancelDictation()
                newIdentity()
                _state.update {
                    it.copy(
                        busy = false,
                        pairingPending = false,
                        authenticated = true,
                        // A new sign-in: the id is read again after the unlock.
                        signedInDeviceId = null,
                        activationRequired = false,
                        locked = true,
                        error = null,
                    )
                }
            }
            EnrollmentOutcome.Denied -> enrollmentError("Pairing request denied.")
            EnrollmentOutcome.Expired -> enrollmentError("Pairing request expired. Start again.")
            EnrollmentOutcome.Unauthorized -> enrollmentError("This device is no longer authorised.")
            is EnrollmentOutcome.Unreachable -> enrollmentError(result.reason.message())
            is EnrollmentOutcome.Rejected -> enrollmentError(result.message ?: "Home Node refused the request (${result.status}).")
            is EnrollmentOutcome.InvalidResponse -> enrollmentError(result.message)
            EnrollmentOutcome.DeviceKeyLost -> _state.update {
                it.copy(busy = false, pairingPending = false, authenticated = false, deviceKeyLost = true, error = null)
            }
        }
    }

    private fun enrollmentError(message: String) = _state.update {
        it.copy(busy = false, pairingPending = false, error = message)
    }

    fun refreshConversations() {
        val endpoint = _state.value.endpoint ?: return
        viewModelScope.launch { loadConversations(endpoint) }
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

    fun setThemeMode(mode: ThemeMode) {
        container.appearance.save(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun refreshLocalVoices() {
        _state.update { it.copy(availableVoices = container.localSpeech.availableVoices()) }
    }
    fun selectLocalVoice(id: String) {
        val voices = container.localSpeech.availableVoices()
        if (id.isNotEmpty() && voices.none { it.id == id }) {
            _state.update { it.copy(voiceStatus = "The chosen local voice is not available", availableVoices = voices) }
            return
        }
        container.localSpeech.selectedVoice = id
        container.voicePreferences.edit().putString("voice", id).apply()
        _state.update { it.copy(selectedVoice = id, availableVoices = voices) }
    }

    fun stopSpeaking() {
        speech.stop()
        speech.ownedRun()?.let(container.realtime::releaseVoice)
        _state.update { it.copy(voiceStatus = "Local speech stopped") }
        // Keep voiceEnabled unchanged for the next response. No chat request,
        // history mutation or generation cancellation is performed here.
    }

    override fun onCleared() { container.realtime.stop(); speech.stop(); container.dictation.release(); super.onCleared() }

    private suspend fun startRealtime(endpoint: HomeNodeEndpoint) {
        realtimeAvailable = container.realtime.available(endpoint)
        if (!realtimeAvailable || _state.value.locked || !_state.value.authenticated) return
        speech.enabled = _state.value.voiceEnabled
        container.realtime.start(viewModelScope, endpoint, disconnected = { speech.stop() }) { event ->
            if (_state.value.endpoint != endpoint || _state.value.locked || !_state.value.authenticated) return@start
            if (passesToSpeech(_state.value.listening, event.type)) speech.event(event)
            if (event.type == "connection.ready") {
                val recovered = container.realtime.recover(endpoint, pending.requests())
                if (_state.value.endpoint != endpoint || _state.value.locked || !_state.value.authenticated) return@start
                for ((request, run) in recovered) pending.reconcile(request, run)
                val conversations = container.conversations.list(endpoint).realtimeSnapshot()
                if (_state.value.endpoint != endpoint || _state.value.locked || !_state.value.authenticated) return@start
                _state.update { it.copy(conversations = conversations.conversations) }
                val selected = _state.value.conversationId
                if (selected != null) {
                    val snapshot = container.conversations.load(endpoint, selected).realtimeSelectedHistory()
                    if (_state.value.endpoint != endpoint || _state.value.locked || !_state.value.authenticated) return@start
                    _state.update {
                        if (it.conversationId != selected) it
                        else if (snapshot == null) it.copy(conversationId = null, messages = emptyList(), busy = false)
                        else it.copy(messages = snapshot.messages, busy = snapshot.assistant_running,
                            photoMeta = snapshot.photos.associateBy { photo -> photo.id }, removedPhotos = missingPhotos(snapshot.messages, snapshot.photos))
                    }
                }
            } else receiveRealtime(event)
        }
    }

    private fun receiveRealtime(event: RealtimeEvent) {
        val p = event.payload
        when (event.type) {
            "voice.started" -> _state.update { it.copy(voiceStatus = "Active device is speaking") }
            "voice.stopped" -> _state.update { it.copy(voiceStatus = "Speech stopped") }
            "voice.failed" -> _state.update { it.copy(voiceStatus = "Local speech unavailable on the active device") }
            "voice.owner_changed" -> _state.update { it.copy(voiceStatus = null) }
            "conversation.created", "conversation.updated" -> {
                val id = p.id ?: return; val title = p.title ?: return; val at = p.updated_at ?: return
                _state.update { it.copy(conversations = (it.conversations.filterNot { item -> item.id == id } + ConversationSummary(id, title, at)).sortedByDescending { item -> item.updated_at }) }
            }
            "conversation.deleted" -> _state.update {
                if (it.conversationId == p.conversation_id) it.copy(conversationId = null, messages = emptyList(), photoMeta = emptyMap(), removedPhotos = emptySet(),
                    conversations = it.conversations.filterNot { item -> item.id == p.conversation_id })
                else it.copy(conversations = it.conversations.filterNot { item -> item.id == p.conversation_id })
            }
            "message.created" -> {
                val message = p.message ?: return
                val optimistic = pending[p.request_id]
                _state.update { state ->
                    val selected = state.conversationId ?: if (optimistic != null && state.messages.any { it.id == optimistic }) message.conversation_id else null
                    if (selected != message.conversation_id) state else state.copy(conversationId = selected,
                        messages = upsertRealtime(state.messages, ConversationMessage(message.role, message.content, message.model, message.created_at, message.id, message.attachments), optimistic))
                }
            }
            "assistant.started" -> {
                val run = p.run_id ?: return
                _state.update { state ->
                    if (state.conversationId != p.conversation_id || state.messages.any { it.id == "run:$run" }) state else {
                        val last = state.messages.lastOrNull()
                        state.copy(busy = true, messages = state.messages + ConversationMessage("assistant", "", at = "", id = "run:$run"),
                            webSearchRun = run.takeIf { p.web_search == true || startsAfterReply(last?.role, last?.content) })
                    }
                }
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
                // Each proposal shows once, under this answer, only while its conversation is open.
                val fresh = if (_state.value.conversationId != message.conversation_id) emptyList() else com.hawkeynl.jarvis.network.newCards(
                    seenProposals, com.hawkeynl.jarvis.network.readProposals(p.job_proposals), message.conversation_id, message.id)
                _state.update { state -> if (state.conversationId != message.conversation_id) state else state.copy(busy = false,
                    jobProposals = state.jobProposals + fresh,
                    researchOffer = p.research_offer?.question?.trim()?.takeIf { it.isNotEmpty() }?.let { WebSearchOffer(message.conversation_id, it) },
                    messages = upsertRealtime(state.messages, ConversationMessage(message.role, message.content, message.model, message.created_at, message.id, message.attachments), "run:${run.run_id}")) }
            }
            "assistant.failed" -> {
                val run = p.run ?: return; pending.remove(run.request_id)
                // Drop the run's empty placeholder row (also an automatic web search's).
                _state.update { if (it.conversationId == run.conversation_id) it.copy(busy = false, error = "Reply interrupted. Your message was saved.",
                    messages = it.messages.filterNot { m -> m.id == "run:${run.run_id}" && m.content.isEmpty() }, webSearchRun = null) else it }
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
                it.copy(authenticated = false, appUpdate = AndroidUpdateUiState.Failed("Session expired."))
            }
            is AndroidUpdateCheck.Failed -> _state.update {
                it.copy(appUpdate = AndroidUpdateUiState.Failed(result.message))
            }
        }
    }

    private fun handleApiFailure(result: ApiResult<*>) = _state.update { state ->
        when (result) {
            ApiResult.Unauthorized -> state.also { clearPhotos() }.copy(
                authenticated = false,
                busy = false,
                error = "Session expired. Sign this device in again.",
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
    UnreachableReason.TIMEOUT -> "Home Node did not answer in time."
    UnreachableReason.DNS -> "The Home Node host name cannot be found."
    UnreachableReason.TLS -> "The secure connection to the Home Node is invalid."
    UnreachableReason.REFUSED -> "Home Node refused the connection."
    UnreachableReason.NETWORK -> "Home Node is unreachable. Check Wi-Fi and the address."
}

private fun BiometricAvailability.message(): String = when (this) {
    BiometricAvailability.Available -> "Biometrics available."
    BiometricAvailability.NotEnrolled -> "Set up strong biometrics in Android settings first."
    BiometricAvailability.NoHardware -> "This device has no supported strong biometrics."
    BiometricAvailability.TemporarilyUnavailable -> "Biometrics are temporarily unavailable."
    BiometricAvailability.SecurityUpdateRequired -> "Install the Android security update for biometrics."
    BiometricAvailability.Unsupported -> "Strong biometrics are not supported on this device."
}

/** What a failed or unconfirmed unlink leaves on screen. Nothing is erased: a refusal only
 *  offers erasing locally, and a cancelled or busy prompt offers nothing at all. */
internal fun afterFailedUnlink(state: JarvisUiState, failure: Exception): JarvisUiState = when (failure) {
    // The user stopped, or another prompt is open: nothing to offer.
    is ApprovalBusyException, is SigningCancelledException -> state.copy(error = failure.message)
    // Server text is shown sanitised and bounded.
    else -> state.copy(localEraseOffer = PairingApproval.displayName(failure.message ?: "Core did not confirm the revocation.", 160))
}

private const val KEEP_FAILED = "Could not change this photo. Try again."
private const val DELETE_FAILED = "Could not delete this photo. Try again."

/** Decode a JPEG to at most about [maxSide] px on its long side: 512 for a 140 dp thumbnail,
 *  1600 (all Core sends) for the full-screen view. */
private fun decodePhoto(bytes: ByteArray, maxSide: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** Why a picked photo cannot be sent; [message] is shown on its chip. */
private class PhotoRefused(override val message: String) : Exception(message)

private class PreparedPhoto(val jpeg: ByteArray, val preview: androidx.compose.ui.graphics.ImageBitmap)

/** The picked photo as an upright JPEG of at most [MAX_SIDE] px, never upscaled, with
 *  transparency on white; plus a small preview for its chip. Blocking: call off the main thread. */
private fun preparePhoto(resolver: android.content.ContentResolver, uri: android.net.Uri): PreparedPhoto {
    // ponytail: a byte cap only when the provider knows the size (the photo picker does);
    // setTargetSize bounds the decoded pixels either way.
    val size = try { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } } catch (_: Exception) { throw PhotoRefused(UNREADABLE) }
    if (size != null && size > MAX_INPUT_BYTES) throw PhotoRefused(TOO_LARGE)
    val decoded = try {
        // ImageDecoder applies the EXIF orientation, so the JPEG we send is upright.
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val (width, height) = fitWithin(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            // Software pixels: drawn on a Canvas and compressed below.
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        throw PhotoRefused(UNSUPPORTED)
    }
    // JPEG has no alpha: transparent pixels would turn black, so they go on white.
    val bitmap = if (!decoded.hasAlpha()) decoded else android.graphics.Bitmap.createBitmap(decoded.width, decoded.height, android.graphics.Bitmap.Config.ARGB_8888).also {
        android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
        decoded.recycle()
    }
    return try {
        val jpeg = java.io.ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw PhotoRefused(UNSUPPORTED)
            out.toByteArray()
        }
        if (jpeg.size > com.hawkeynl.jarvis.network.KtorJarvisApi.MAX_UPLOAD_BYTES) throw PhotoRefused(TOO_LARGE)
        val (width, height) = fitWithin(bitmap.width, bitmap.height, 512)
        // A copy even when the size already fits, so the full bitmap can be freed below.
        val preview = if (width == bitmap.width && height == bitmap.height) bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            else android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
        PreparedPhoto(jpeg, preview.asImageBitmap())
    } finally {
        bitmap.recycle()
    }
}
