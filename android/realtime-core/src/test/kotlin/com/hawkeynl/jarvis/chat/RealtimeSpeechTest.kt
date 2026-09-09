package com.hawkeynl.jarvis.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RealtimeSpeechTest {
    private companion object { val wireJson = Json { ignoreUnknownKeys = true } }
    @Test fun releaseCapturesOnlyOwnRunAndReconnectForgetsOldOwnership() {
        val speech = RealtimeSpeech(Fake())
        fun event(type: String, payload: RealtimePayload) = RealtimeEvent(1, "epoch", 1, "event", type, payload)
        val run = "00000000-0000-0000-0000-000000000001"
        speech.event(event("connection.ready", RealtimePayload(device_id = "a")))
        speech.event(event("voice.owner_changed", RealtimePayload(device_id = "b", run_id = run)))
        assertNull(speech.ownedRun())
        speech.event(event("voice.owner_changed", RealtimePayload(device_id = "a", run_id = run)))
        speech.stop()
        val release = VoiceRelease(requireNotNull(speech.ownedRun()))
        assertEquals("{\"run_id\":\"$run\"}", wireJson.encodeToString(release))
        speech.event(event("voice.owner_changed", RealtimePayload(device_id = "a", run_id = "new-run")))
        assertEquals(run, release.run_id)
        speech.event(event("connection.ready", RealtimePayload(device_id = "a")))
        assertNull(speech.ownedRun())
    }
    @Test fun realSpeechGateDrivesOnePlaybackLifecycleAndStopKeepsPreference() {
        val reports = mutableListOf<PlaybackReport>()
        val spoken = mutableListOf<String>()
        val tracker = PlaybackTracker { reports.add(it) }
        val output = object : SpeechOutput {
            override fun begin(runId: String) = tracker.begin(runId)
            override fun speak(text: String) {
                val utterance = tracker.enqueue() ?: return
                tracker.started(utterance); spoken.add(text); tracker.finished(utterance)
            }
            override fun finish(runId: String) = tracker.seal(runId)
            override fun stop() = tracker.cancel()
        }
        val speech = RealtimeSpeech(output).also { it.enabled = true }
        fun event(type: String, payload: RealtimePayload) = RealtimeEvent(1, "epoch", 1, "event", type, payload)
        speech.event(event("connection.ready", RealtimePayload(device_id = "a")))
        val run = RealtimeRun("one", "request", "conversation")
        speech.event(event("voice.owner_changed", RealtimePayload(device_id = "a", run_id = run.run_id)))
        speech.event(event("assistant.started", RealtimePayload(run_id = run.run_id)))
        speech.event(event("assistant.delta", RealtimePayload(run = run, text = "First phrase. Next")))
        val final = event("assistant.completed", RealtimePayload(run = run, message = RealtimeMessage("message", "conversation", "assistant", "First phrase. Next phrase.", created_at = "2026-01-01T00:00:00Z")))
        speech.event(final); speech.event(final)
        assertEquals(listOf("First phrase.", "Next phrase."), spoken)
        assertEquals(listOf(PlaybackReport("one", PlaybackState.STARTED), PlaybackReport("one", PlaybackState.STOPPED)), reports)
        speech.stop()
        assertTrue(speech.enabled)
        speech.event(event("assistant.delta", RealtimePayload(run = run, text = "Must not speak. ")))
        assertEquals(2, spoken.size)
    }
    @Test fun fragmentedFencesNeverSpeakEmbeddedCode() {
        val samples = listOf(
            "Before.\n   ```rust\nlet s = \"```\";\nnot speech\n   ```\nAfter.",
            "Before.\n  ~~~~text\ncode\n~~~\nstill code\n  ~~~~\nAfter.",
            "Before.\n```\nunterminated code",
        )
        for (text in samples) {
            val streamed = render(text, true)
            assertEquals(render(text, false), streamed)
            assertTrue(streamed.isNotEmpty())
            assertTrue(streamed.all { it == "Before." || it == "After." })
        }
    }
    private fun render(text: String, streaming: Boolean): List<String> {
        val output = Fake()
        val speech = RealtimeSpeech(output).also { it.enabled = true }
        val run = RealtimeRun("run", "request", "conversation")
        fun event(type: String, payload: RealtimePayload) = RealtimeEvent(1, "epoch", 1, "event", type, payload)
        speech.event(event("connection.ready", RealtimePayload(device_id = "a")))
        speech.event(event("voice.owner_changed", RealtimePayload(device_id = "a", run_id = "run")))
        speech.event(event("assistant.started", RealtimePayload(run_id = "run")))
        if (streaming) for (ch in text) speech.event(event("assistant.delta", RealtimePayload(run = run, text = ch.toString())))
        val completed = event("assistant.completed", RealtimePayload(run = run, message = RealtimeMessage("message", "conversation", "assistant", text, created_at = "2026-01-01T00:00:00Z")))
        speech.event(completed)
        speech.event(completed)
        return output.spoken
    }
    private class Fake : SpeechOutput {
        val spoken = mutableListOf<String>()
        override fun speak(text: String) { spoken += text }
        override fun stop() = Unit
    }
    @Test fun onlyOwnerSpeaksAndCompletionDoesNotRepeat() {
        val a = Fake(); val b = Fake()
        val va = RealtimeSpeech(a).also { it.enabled = true }
        val vb = RealtimeSpeech(b).also { it.enabled = true }
        fun event(type: String, payload: RealtimePayload) = RealtimeEvent(1,"epoch",1,"event",type,payload)
        va.event(event("connection.ready",RealtimePayload(device_id="a")))
        vb.event(event("connection.ready",RealtimePayload(device_id="b")))
        val run = RealtimeRun("run","request","conversation")
        val events = listOf(
            event("voice.owner_changed",RealtimePayload(device_id="a",run_id="run")),
            event("assistant.started",RealtimePayload(run_id="run",request_id="request",conversation_id="conversation")),
            event("assistant.delta",RealtimePayload(run=run,text="One answer. Next")),
            event("assistant.completed",RealtimePayload(run=run,message=RealtimeMessage("message","conversation","assistant","One answer. Next sentence.",created_at="2026-01-01T00:00:00Z"))),
        )
        events.forEach { va.event(it); vb.event(it) }
        va.event(events.last())
        assertEquals(listOf("One answer.","Next sentence."),a.spoken)
        assertTrue(b.spoken.isEmpty())
    }
    @Test fun decodeSharedEnvelope() {
        val event=wireJson.decodeFromString<RealtimeEvent>("""{"protocol":1,"epoch":"00000000-0000-0000-0000-000000000001","sequence":1,"event_id":"00000000-0000-0000-0000-000000000002","at":"2026-01-01T00:00:00Z","type":"assistant.delta","payload":{"run":{"run_id":"run","request_id":"request","conversation_id":"conversation"},"text":"canonical text"}}""")
        assertEquals("canonical text",event.payload.text)
        assertEquals("conversation",event.payload.run?.conversation_id)
    }
}
