package com.hawkeynl.jarvis.chat

interface SpeechOutput {
    fun begin(runId: String) {}
    fun speak(text: String)
    fun finish(runId: String) {}
    fun stop()
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
    private var fence: String? = null
    private var lineStart = true
    fun stop() { output.stop(); run = null; received = ""; pending = ""; fence = null; lineStart = true }
    fun event(event: RealtimeEvent) {
        val p = event.payload
        if (event.type == "connection.ready") { device = p.device_id; stop(); return }
        if (event.type == "voice.owner_changed") { owner = p.device_id; ownerRun = p.run_id; stop(); return }
        if (!enabled || device == null || owner != device) return
        if (event.type == "assistant.started" && p.run_id != null && p.run_id == ownerRun) {
            stop(); run = p.run_id; output.begin(p.run_id); return
        }
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
                pending += canonical.removePrefix(received); flush(true)
                output.finish(p.run.run_id); run = null
            }
        }
    }
    private fun flush(complete: Boolean) {
        while (pending.isNotEmpty()) {
            val lineEnd = pending.indexOf('\n').takeIf { it >= 0 }?.plus(1) ?: if (complete) pending.length else null
            val line = pending.take(lineEnd ?: pending.length)
            val trimmed = line.trimStart(' ')
            val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
            val count = if (marker == null) 0 else trimmed.takeWhile { it == marker }.length
            val opening = lineStart && line.length - trimmed.length <= 3 && count >= 3
            if (fence != null || opening) {
                val end = lineEnd ?: return
                val current = fence
                if (current != null) {
                    if (opening && marker == current.first() && count >= current.length && trimmed.drop(count).isBlank()) fence = null
                } else if (marker != null) fence = marker.toString().repeat(count)
                lineStart = line.endsWith('\n')
                pending = pending.drop(end)
                continue
            }
            val end = pending.indices.firstOrNull { index ->
                val ch = pending[index]
                ch == '\n' || (ch in ".!?" && index + 1 < pending.length && pending[index + 1].isWhitespace()) ||
                    (index >= 240 && ch.isWhitespace()) || (index >= 480 && !ch.isHighSurrogate())
            }?.plus(1) ?: if (complete) pending.length else return
            val raw = pending.take(end); pending = pending.drop(end)
            lineStart = raw.endsWith('\n')
            val clean = raw.filter { it !in "`*#_~" && (!it.isISOControl() || it.isWhitespace()) }.trim().replace(Regex("\\s+"), " ")
            if (clean.isNotEmpty()) output.speak(clean)
        }
    }
}
