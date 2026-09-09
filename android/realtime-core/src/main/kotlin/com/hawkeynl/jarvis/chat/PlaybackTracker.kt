package com.hawkeynl.jarvis.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class PlaybackState {
    @SerialName("started") STARTED,
    @SerialName("stopped") STOPPED,
    @SerialName("failed") FAILED,
}
@Serializable data class PlaybackReport(val run_id: String, val state: PlaybackState)

/** Correlates asynchronous OS utterance callbacks, never text or credentials.
 * emit must be nonblocking (the native transport uses a bounded trySend).
 * Stale callbacks after cancellation cannot affect another run. */
class PlaybackTracker(private val emit: (PlaybackReport) -> Unit) {
    private var run: String? = null
    private var started = false
    private var sealed = false
    private var terminal = false
    private val pending = mutableSetOf<String>()

    @Synchronized fun begin(id: String) {
        cancel()
        run = id; started = false; sealed = false; terminal = false
    }
    @Synchronized fun enqueue(): String? {
        if (run == null || sealed || terminal) return null
        if (pending.size >= 32) { unavailable(); return null }
        val id = UUID.randomUUID().toString()
        pending.add(id)
        return id
    }
    @Synchronized fun started(id: String?) {
        if (id !in pending || terminal || started) return
        started = true
        emit(PlaybackReport(requireNotNull(run), PlaybackState.STARTED))
    }
    @Synchronized fun finished(id: String?) {
        if (!pending.remove(id)) return
        finishIfDrained()
    }
    @Synchronized fun failed(id: String?): Boolean {
        if (id !in pending || terminal) return false
        unavailable()
        return true
    }
    @Synchronized fun unavailable() {
        val id = run ?: return
        if (!terminal) emit(PlaybackReport(id, PlaybackState.FAILED))
        terminal = true; pending.clear()
    }
    @Synchronized fun seal(id: String) {
        if (id != run || terminal) return
        sealed = true
        finishIfDrained()
    }
    @Synchronized fun cancel() {
        val id = run
        if (id != null && !terminal) emit(PlaybackReport(id, PlaybackState.STOPPED))
        run = null; pending.clear(); terminal = true
    }
    private fun finishIfDrained() {
        if (sealed && pending.isEmpty() && !terminal) {
            if (started) emit(PlaybackReport(requireNotNull(run), PlaybackState.STOPPED))
            run = null; terminal = true
        }
    }
}
