package com.hawkeynl.jarvis.chat

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RealtimeStateTest {
    private val epoch = UUID(0, 1).toString()
    private fun event(n: Long, type: String = "assistant.delta", connection: String = epoch) =
        RealtimeEvent(1, connection, n, UUID(0, n).toString(), type, RealtimePayload())

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
