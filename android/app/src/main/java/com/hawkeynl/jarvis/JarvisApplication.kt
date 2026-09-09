package com.hawkeynl.jarvis

import android.app.Application
import com.hawkeynl.jarvis.auth.EnrollmentService
import com.hawkeynl.jarvis.chat.ConversationService
import com.hawkeynl.jarvis.network.KtorJarvisApi
import com.hawkeynl.jarvis.security.BiometricGate
import com.hawkeynl.jarvis.security.KeystoreBackedEd25519Identity
import com.hawkeynl.jarvis.storage.AndroidKeystoreSecureValueStore
import com.hawkeynl.jarvis.storage.EndpointSettingsRepository
import com.hawkeynl.jarvis.storage.SessionRepository
import com.hawkeynl.jarvis.update.AndroidUpdateService

class JarvisApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    val voicePreferences = application.getSharedPreferences("jarvis-local-voice", android.content.Context.MODE_PRIVATE)
    val localSpeech = com.hawkeynl.jarvis.chat.AndroidSpeechOutput(application)
    private val secureValues = AndroidKeystoreSecureValueStore(application)
    val settings = EndpointSettingsRepository(application)
    val sessions = SessionRepository(secureValues)
    val realtime = com.hawkeynl.jarvis.chat.RealtimeService(sessions)
    init { localSpeech.onPlayback = realtime::reportPlayback }
    val identity = KeystoreBackedEd25519Identity(secureValues)
    val api = KtorJarvisApi()
    val enrollment = EnrollmentService(api, identity, sessions)
    val conversations = ConversationService(api, sessions)
    val appUpdates = AndroidUpdateService(application, api, sessions)
    val biometricGate = BiometricGate()
}
