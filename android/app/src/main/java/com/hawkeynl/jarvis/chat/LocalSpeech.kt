package com.hawkeynl.jarvis.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicBoolean

class AndroidSpeechOutput(context: Context) : SpeechOutput {
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
        val voice = engine.voice?.takeIf { !it.isNetworkConnectionRequired }
            ?: engine.voices?.firstOrNull { !it.isNetworkConnectionRequired }
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
