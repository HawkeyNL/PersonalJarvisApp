package com.hawkeynl.jarvis.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicInteger

interface SpeechOutput { fun speak(text: String); fun stop() }

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

class RealtimeSpeech(private val output: SpeechOutput) {
    var enabled = false
        set(value) { field = value; if (!value) stop() }
    private var device: String? = null
    private var owner: String? = null
    private var ownerRun: String? = null
    private var run: String? = null
    private var received = ""
    private var pending = ""
    private var fenced = false
    fun stop() { output.stop(); run = null; received = ""; pending = ""; fenced = false }
    fun event(event: RealtimeEvent) {
        val p = event.payload
        if (event.type == "connection.ready") { device = p.device_id; stop(); return }
        if (event.type == "voice.owner_changed") { owner = p.device_id; ownerRun = p.run_id; stop(); return }
        if (!enabled || device == null || owner != device) return
        if (event.type == "assistant.started" && p.run_id == ownerRun) { stop(); run = p.run_id; return }
        if (p.run?.run_id == null || p.run.run_id != run) return
        when (event.type) {
            "assistant.failed" -> stop()
            "assistant.delta" -> {
                val text = p.text ?: return
                if (received.length + text.length > 128 * 1024) { stop(); return }
                received += text; pending += text; flush(false)
            }
            "assistant.completed" -> {
                val canonical = p.message?.content ?: return
                if (!canonical.startsWith(received) || canonical.length > 128 * 1024) { stop(); return }
                pending += canonical.removePrefix(received); flush(true); run = null
            }
        }
    }
    private fun flush(complete: Boolean) {
        while (pending.isNotEmpty()) {
            if (pending.startsWith("```")) {
                val end = pending.indexOf('\n')
                if (end < 0) { if (complete) pending = ""; return }
                pending = pending.substring(end + 1); fenced = !fenced; continue
            }
            if (fenced) {
                val fence = pending.indexOf("```")
                if (fence < 0) { if (complete) pending = ""; return }
                pending = pending.substring(fence); continue
            }
            val end = pending.indices.firstOrNull { index ->
                val ch = pending[index]
                ch == '\n' || (ch in ".!?" && index + 1 < pending.length && pending[index + 1].isWhitespace()) ||
                    (index >= 240 && ch.isWhitespace()) || index >= 480
            }?.plus(1) ?: if (complete) pending.length else return
            val raw = pending.take(end); pending = pending.drop(end)
            val clean = raw.filter { it !in "`*#_~" && (!it.isISOControl() || it.isWhitespace()) }.trim().replace(Regex("\\s+"), " ")
            if (clean.isNotEmpty()) output.speak(clean)
        }
    }
}
