package com.hawkeynl.jarvis.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RealtimeSpeechTest {
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
        val event=Json { ignoreUnknownKeys=true }.decodeFromString<RealtimeEvent>("""{"protocol":1,"epoch":"00000000-0000-0000-0000-000000000001","sequence":1,"event_id":"00000000-0000-0000-0000-000000000002","at":"2026-01-01T00:00:00Z","type":"assistant.delta","payload":{"run":{"run_id":"run","request_id":"request","conversation_id":"conversation"},"text":"canonical text"}}""")
        assertEquals("canonical text",event.payload.text)
        assertEquals("conversation",event.payload.run?.conversation_id)
    }
}
