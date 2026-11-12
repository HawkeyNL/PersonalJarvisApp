package com.hawkeynl.jarvis.chat

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RealtimeStateTest {
    private val epoch = UUID(0, 1).toString()
    private fun event(n: Long, type: String = "assistant.delta", connection: String = epoch) =
        RealtimeEvent(1, connection, n, UUID(0, n).toString(), type, RealtimePayload())

    @Test fun researchOfferIsOptional() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val run = """{"run_id":"r","request_id":"q","conversation_id":"c"}"""
        val old = json.decodeFromString<RealtimePayload>("""{"run":$run}""")
        assertNull(old.research_offer)
        val offered = json.decodeFromString<RealtimePayload>("""{"run":$run,"research_offer":{"question":"weer?"}}""")
        assertEquals("weer?", offered.research_offer?.question)
    }

    @Test fun messagePhotosAreOptionalIds() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val message = """"id":"m","conversation_id":"c","role":"user","content":"","created_at":"t""""
        assertEquals(emptyList<String>(), json.decodeFromString<RealtimeMessage>("{$message}").attachments)
        assertEquals(listOf("p1", "p2"), json.decodeFromString<RealtimeMessage>("{$message,\"attachments\":[\"p1\",\"p2\"]}").attachments)
    }

    @Test fun webSearchMarkerIsOptional() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertNull(json.decodeFromString<RealtimePayload>("""{"run_id":"r"}""").web_search)
        assertEquals(true, json.decodeFromString<RealtimePayload>("""{"run_id":"r","web_search":true}""").web_search)
    }

    @Test fun duplicatesAndOutOfOrderEventsAreIgnored() {
        val cursor = RealtimeCursor()
        assertTrue(cursor.accept(event(10, "connection.ready")))
        assertTrue(cursor.accept(event(12)))
        assertFalse(cursor.accept(event(12)))
        assertFalse(cursor.accept(event(11)))
        assertTrue(cursor.accept(event(13)))
    }
    @Test fun reconnectStartsWithReadyEvenAfterCoreSequenceReset() {
        val cursor = RealtimeCursor()
        assertTrue(cursor.accept(event(100, "connection.ready")))
        val next = UUID(0, 2).toString()
        assertThrows(IllegalArgumentException::class.java) { cursor.accept(event(1, connection = next)) }
        assertTrue(cursor.accept(event(1, "connection.ready", next)))
        assertTrue(cursor.accept(event(2, connection = next)))
    }
    @Test fun malformedEnvelopeRequiresRecovery() {
        val cursor = RealtimeCursor()
        assertThrows(IllegalArgumentException::class.java) { cursor.accept(event(1, "connection.ready").copy(protocol = 2)) }
        assertThrows(IllegalArgumentException::class.java) { cursor.accept(event(1, "connection.ready").copy(event_id = "not-an-id")) }
        assertThrows(IllegalArgumentException::class.java) { cursor.accept(event(0, "connection.ready")) }
    }
    @Test fun unknownEventTypeIsSkippedWithoutBreakingTheStream() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun frame(n: Long, type: String, payload: String) =
            """{"protocol":1,"epoch":"$epoch","sequence":$n,"event_id":"${UUID(0, n)}","at":"2026-11-08T12:00:00Z","type":"$type","payload":$payload}"""
        val cursor = RealtimeCursor()
        assertTrue(cursor.accept(decodeRealtimeEvent(json, frame(1, "connection.ready", "{}"))))
        // Payload fields may clash with known names (id, run); they are dropped.
        val unknown = decodeRealtimeEvent(json, frame(2, "trading.meme.decision", """{"id":5,"run":"x"}"""))
        assertEquals(RealtimePayload(), unknown.payload)
        assertTrue(cursor.accept(unknown))
        assertFalse(cursor.accept(event(2)))
        val delta = decodeRealtimeEvent(json, frame(3, "assistant.delta", """{"run":{"run_id":"r","request_id":"q","conversation_id":"c"},"text":"Hi"}"""))
        assertEquals("Hi", delta.payload.text)
        assertTrue(cursor.accept(delta))
    }
    @Test fun malformedFramesStillFail() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for (text in listOf(
            "{",
            """{"protocol":1,"sequence":2,"event_id":"${UUID(0, 2)}","type":"roadmap.changed","payload":{}}""",
            """{"protocol":1,"epoch":"$epoch","sequence":2,"event_id":"${UUID(0, 2)}","type":7,"payload":{}}""",
            """{"protocol":1,"epoch":"$epoch","sequence":2,"event_id":"${UUID(0, 2)}","type":"assistant.delta","payload":{"text":{}}}""",
        )) assertThrows(IllegalArgumentException::class.java) { decodeRealtimeEvent(json, text) }
    }
    private data class Row(val id: String?, val text: String)
    @Test fun canonicalResultReplacesBothRestAndProvisionalCopiesWithoutReordering() {
        val before = listOf(Row("previous", "older"), Row("canonical", "REST copy"), Row("run:1", "partial"), Row("later", "later"))
        val final = Row("canonical", "canonical bytes")
        val result = mergeCanonical(before, final, "run:1") { it.id }
        assertEquals(listOf(before[0], final, before[3]), result)
        assertEquals(result, mergeCanonical(result, final, "run:1") { it.id })
    }
    @Test fun unknownLegacyIdsAreNotMergedWithEachOther() {
        val before = listOf(Row(null, "legacy one"), Row(null, "legacy two"), Row("request:1", "optimistic"))
        val final = Row("canonical", "server user message")
        assertEquals(listOf(before[0], before[1], final), mergeCanonical(before, final, "request:1") { it.id })
    }
}
