package com.hawkeynl.jarvis.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicInteger

class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private var ready = false
    private val queued = AtomicInteger(0)
    private val engine = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }
    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { queued.updateAndGet { (it - 1).coerceAtLeast(0) } }
            @Deprecated("Required platform callback")
            override fun onError(id: String?) { queued.updateAndGet { (it - 1).coerceAtLeast(0) } }
        })
    }
    override fun speak(text: String) {
        if (!ready) return
        // Never select a network-only voice or download voice data implicitly.
        val voice = engine.voice?.takeIf { !it.isNetworkConnectionRequired }
            ?: engine.voices?.firstOrNull { !it.isNetworkConnectionRequired } ?: return
        engine.setVoice(voice)
        if (queued.incrementAndGet() > 32) { stop(); return }
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, java.util.UUID.randomUUID().toString()) == TextToSpeech.ERROR) queued.decrementAndGet()
    }
    override fun stop() { engine.stop(); queued.set(0) }
    fun close() { stop(); engine.shutdown() }
}
