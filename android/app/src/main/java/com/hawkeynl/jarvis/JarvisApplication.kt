package com.hawkeynl.jarvis

import android.app.Application
import com.hawkeynl.jarvis.auth.EnrollmentService
import com.hawkeynl.jarvis.chat.ConversationService
import com.hawkeynl.jarvis.network.KtorJarvisApi
import com.hawkeynl.jarvis.security.AndroidDeviceKeyVault
import com.hawkeynl.jarvis.security.BiometricGate
import com.hawkeynl.jarvis.security.KeystoreBackedEd25519Identity
import com.hawkeynl.jarvis.storage.AndroidKeystoreSecureValueStore
import com.hawkeynl.jarvis.storage.EndpointSettingsRepository
import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.update.AndroidUpdateService
import kotlinx.coroutines.flow.first

class JarvisApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val voicePreferences = application.getSharedPreferences("jarvis-local-voice", android.content.Context.MODE_PRIVATE)
    val appearance = com.hawkeynl.jarvis.storage.AppearancePreference(application)
    val localSpeech = com.hawkeynl.jarvis.chat.AndroidSpeechOutput(application)
    private val secureValues = AndroidKeystoreSecureValueStore(application)
    val settings = EndpointSettingsRepository(application)
    val sessions = SessionRepository(secureValues)
    val realtime = com.hawkeynl.jarvis.chat.RealtimeService(sessions)
    init { localSpeech.onPlayback = realtime::reportPlayback }
    val identity = KeystoreBackedEd25519Identity(secureValues, AndroidDeviceKeyVault(application))
    val api = KtorJarvisApi()
    val enrollment = EnrollmentService(api, identity, sessions)
    /** One approval prompt at a time, shared by every signing service. */
    val approvals = com.hawkeynl.jarvis.security.ApprovalLock()
    /** Unlock number choices outlive the Activity, so a rotation cannot re-roll them. */
    val unlockChoices = com.hawkeynl.jarvis.security.UnlockChoiceSets()
    val accountApprovals = com.hawkeynl.jarvis.security.AccountApprovalService(
        api, sessions, { settings.endpoint.first() }, identity, approvals,
    )
    val conversations = ConversationService(api, sessions)
    val core = com.hawkeynl.jarvis.network.CoreReader(api, sessions)
    val appUpdates = AndroidUpdateService(application, api, sessions)
    val biometricGate = BiometricGate()
}
