package com.hawkeynl.jarvis.chat

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PlaybackTrackerTest {
    @Test fun completionWaitsForRealPlaybackAndNeverRepeatsStarted() {
        val reports = mutableListOf<PlaybackReport>()
        val tracker = PlaybackTracker { reports.add(it) }
        tracker.begin("run")
        val a = tracker.enqueue(); val b = tracker.enqueue()
        tracker.started(a); tracker.started(b); tracker.seal("run")
        tracker.finished(a)
        assertEquals(listOf(PlaybackReport("run", PlaybackState.STARTED)), reports)
        tracker.finished(b); tracker.finished(b)
        assertEquals(listOf(PlaybackState.STARTED, PlaybackState.STOPPED), reports.map { it.state })
    }
    @Test fun staleCallbacksCannotStopOrStartTheNextRun() {
        val reports = mutableListOf<PlaybackReport>()
        val tracker = PlaybackTracker { reports.add(it) }
        tracker.begin("old"); val old = tracker.enqueue()
        tracker.begin("new"); val current = tracker.enqueue()
        reports.clear()
        tracker.started(old); tracker.finished(old); assertFalse(tracker.failed(old))
        assertTrue(reports.isEmpty())
        tracker.started(current)
        assertEquals(listOf(PlaybackReport("new", PlaybackState.STARTED)), reports)
    }
    @Test fun queueOverflowFailsOnceAndCannotResumeUntilAnotherRun() {
        val reports = mutableListOf<PlaybackReport>()
        val tracker = PlaybackTracker { reports.add(it) }
        tracker.begin("run")
        repeat(32) { assertNotNull(tracker.enqueue()) }
        assertNull(tracker.enqueue()); assertNull(tracker.enqueue())
        tracker.unavailable(); tracker.cancel()
        assertEquals(listOf(PlaybackReport("run", PlaybackState.FAILED)), reports)
        tracker.begin("next"); assertNotNull(tracker.enqueue())
    }
    @Test fun reportsContainOnlyRunAndFixedStatusNoSpeechText() {
        assertEquals("""{"run_id":"run","state":"started"}""", Json.encodeToString(PlaybackReport("run", PlaybackState.STARTED)))
    }
}
