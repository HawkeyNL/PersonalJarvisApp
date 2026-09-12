package com.hawkeynl.jarvis.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicBoolean

class AndroidSpeechOutput(context: Context) : SpeechOutput {
    @Volatile var selectedVoice: String = ""
    fun availableVoices(): List<LocalVoice> {
        if (!ready.get()) return emptyList()
        return LocalVoiceCatalog.catalog(engine.voices.orEmpty().asSequence().map {
            VoiceCandidate(it.name, "${it.locale.displayName} — ${it.name}".take(128),
                it.isNetworkConnectionRequired, !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        })
    }
    @Volatile var rate: Float = 1f
        set(value) { field = SpeechRate.normalize(value) }
    @Volatile var onPlayback: (PlaybackReport) -> Unit = {}
    private val playback = PlaybackTracker { onPlayback(it) }
    private val ready = AtomicBoolean(false)
    private val engine = TextToSpeech(context.applicationContext) { status -> ready.set(status == TextToSpeech.SUCCESS) }
    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { playback.started(id) }
            override fun onDone(id: String?) { playback.finished(id) }
            @Deprecated("Required platform callback")
            override fun onError(id: String?) { if (playback.failed(id)) engine.stop() }
        })
    }
    override fun begin(runId: String) { playback.begin(runId) }
    override fun finish(runId: String) { playback.seal(runId) }
    override fun speak(text: String) {
        if (!ready.get()) { playback.unavailable(); return }
        // Never select a network-only voice or download voice data implicitly.
        val selected = LocalVoiceCatalog.choose(availableVoices(), selectedVoice, engine.voice?.name)
        val voice = engine.voices?.firstOrNull { it.name == selected && !it.isNetworkConnectionRequired
            && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        if (voice == null || engine.setVoice(voice) == TextToSpeech.ERROR) { playback.unavailable(); engine.stop(); return }
        if (engine.setSpeechRate(rate) == TextToSpeech.ERROR) { playback.unavailable(); engine.stop(); return }
        val utterance = playback.enqueue() ?: run { engine.stop(); return }
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, utterance) == TextToSpeech.ERROR) {
            playback.failed(utterance); engine.stop()
        }
    }
    override fun stop() { playback.cancel(); engine.stop() }
    fun close() { stop(); engine.shutdown() }
}
