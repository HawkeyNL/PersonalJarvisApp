package com.hawkeynl.jarvis.network

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/memecoin.test.mjs: candidate paging, the background merge and the
// auto-refresh timer. Neutral test data; the mint is plausible base58, not a real token.
class MemePagingTest {
    private val mint = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private val base = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    private fun row(i: Int, version: Long = 3) = Candidate(
        mint, java.time.Instant.ofEpochMilli(base - i * 60_000L).toString(), version, false, "lp_not_secured", "0.0213", "41250",
    )
    private fun pageOf(from: Int, n: Int, next: String? = null) = CandidatePage(List(n) { row(from + it) }, next)

    private val rowJson = """{"mint":"$mint","observed_at":"2026-11-01T11:00:00Z","strategy_version":3,"verdict":"reject",
        "reason":"lp_not_secured","price_usd":"0.0213","liquidity_usd":"41250"}"""
    private fun body(next: String?) = Json.parseToJsonElement("""{"candidates":[$rowJson]${if (next == null) "" else ",\"next_before\":$next"}}""")

    @Test fun `the page reader takes a valid next_before and drops a bad one`() {
        assertEquals("MjAyNi0xMS0yNlQx:Mw.~_-", readCandidatePage(body("\"MjAyNi0xMS0yNlQx:Mw.~_-\""))!!.next)
        assertNull(readCandidatePage(body(null))!!.next) // older Core: no paging, no button
        val bad = listOf("null", "7", "true", "\"\"", "\"a b\"", "\"a/b\"", "\"a?b=c\"", "\"a&before=x\"", "\"é\"", "\"${"x".repeat(129)}\"", "[\"x\"]", "{\"x\":1}")
        for (value in bad) assertNull(value, readCandidatePage(body(value))!!.next)
        assertTrue(isCursor("x".repeat(128)))
        assertFalse(isCursor(null))
        assertNull(readCandidatePage(Json.parseToJsonElement("""{"next_before":"abc"}""")))
    }

    @Test fun `older pages append without duplicates and keep the new cursor`() {
        val have = pageOf(0, 3, "c1")
        val out = appendCandidates(have, CandidatePage(listOf(row(2), row(3), row(3), row(4)), "c2"))
        assertEquals((0..4).map { row(it).observedAt }, out.rows.map { it.observedAt })
        assertEquals("c2", out.next)
        // Same mint and time but another strategy version is another decision.
        assertEquals(4, appendCandidates(have, CandidatePage(listOf(row(2, version = 4)), null)).rows.size)
        assertNull(appendCandidates(have, pageOf(3, 1)).next) // last page: no button
    }

    @Test fun `rows stop at the cap and paging ends there`() {
        var p = pageOf(0, 100, "c")
        for (i in 1 until 6) p = appendCandidates(p, pageOf(i * 100, 100, "c"))
        assertEquals(CANDIDATE_CAP, p.rows.size)
        assertNull(p.next)
        assertTrue(candidatesCapped(p))
        assertEquals(CANDIDATE_CAP, appendCandidates(pageOf(0, 450, "c"), pageOf(450, 100, "c")).rows.size)
    }

    @Test fun `a late older page is appended only while its cursor is still shown`() {
        val ok = MemeRead.Ok(pageOf(0, 2, "c1"))
        assertEquals(4, (appendOlder(ok, "c1", pageOf(2, 2)) as MemeRead.Ok).data.rows.size)
        assertSame(ok, appendOlder(ok, "c0", pageOf(2, 2))) // a reset or refresh moved on
        assertEquals(MemeRead.Loading, appendOlder(MemeRead.Loading, "c1", pageOf(2, 2)))
    }

    @Test fun `a failed refresh keeps the rows and reports quietly`() {
        val ok: MemeRead<CandidatePage> = MemeRead.Ok(pageOf(0, 3, "c1"))
        val failed = MemeRead.Failed(MemeFailure.UNREACHABLE)
        assertEquals(Settled(ok, MemeFailure.UNREACHABLE), settleRead(ok, failed, true, ::prependCandidates))
        assertEquals(Settled<CandidatePage>(failed, null), settleRead(ok, failed, false)) // a manual reload shows the error
        assertEquals(Settled<CandidatePage>(failed, null), settleRead<CandidatePage>(MemeRead.Loading, failed, true))
        val fresh = MemeRead.Ok(pageOf(0, 1))
        assertEquals(fresh, settleRead(ok, fresh, false, ::prependCandidates).state) // manual reload: first page again
    }

    @Test fun `a background refresh prepends new rows and keeps older pages and their cursor`() {
        val have = pageOf(0, 120, "old-cursor") // first page plus older pages
        val merged = prependCandidates(have, CandidatePage(listOf(row(-2), row(-1), row(0), row(1)), "first-page-cursor"))
        assertEquals(122, merged.rows.size)
        assertEquals(row(-2).observedAt, merged.rows.first().observedAt)
        assertEquals("old-cursor", merged.next)
        // No overlap with more pages behind it: a gap, so start over from that page.
        val gap = CandidatePage(listOf(row(-10)), "n")
        assertSame(gap, prependCandidates(have, gap))
        assertSame(gap, prependCandidates(pageOf(0, 0), gap)) // nothing shown yet
        // Capped: nothing is added any more.
        val capped = pageOf(0, CANDIDATE_CAP)
        assertSame(capped, prependCandidates(capped, CandidatePage(listOf(row(-1)), null)))
        val filled = prependCandidates(pageOf(0, 499, "c"), CandidatePage(listOf(row(-2), row(-1), row(0)), "x"))
        assertEquals(CANDIDATE_CAP, filled.rows.size)
        assertNull(filled.next)
    }

    @Test fun `auto-refresh runs only while active and restarts with one refresh`() = runTest {
        var calls = 0
        val auto = AutoRefresher(backgroundScope, AUTO_REFRESH_MS) { calls++ }
        auto.setActive(true) // first activation: the page has just loaded
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(AUTO_REFRESH_MS + 1)
        assertEquals(1, calls)
        auto.setActive(false) // hidden, locked or left
        advanceTimeBy(5 * AUTO_REFRESH_MS)
        assertEquals(1, calls)
        auto.setActive(true) // resumed again: refresh once, then the timer
        runCurrent()
        assertEquals(2, calls)
        auto.setActive(true) // no second loop
        advanceTimeBy(AUTO_REFRESH_MS + 1)
        assertEquals(3, calls)
        auto.setActive(false)
    }

    @Test fun `auto-refresh never overlaps a refresh still running`() = runTest {
        var calls = 0
        var running = 0
        var most = 0
        val auto = AutoRefresher(backgroundScope, AUTO_REFRESH_MS) {
            calls++
            running++
            most = maxOf(most, running)
            delay(3 * AUTO_REFRESH_MS) // slower than the tick
            running--
        }
        auto.setActive(true)
        advanceTimeBy(4 * AUTO_REFRESH_MS) // ticks at 60 s; the first refresh runs until 240 s
        assertEquals(1, calls)
        assertEquals(1, most)
        auto.setActive(false)
    }

    @Test fun `updated label is HH-MM in local time`() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val at = { h: Int, m: Int -> ZonedDateTime.of(2026, 11, 26, h, m, 0, 0, zone).toInstant().toEpochMilli() }
        assertEquals("Updated 09:05", updatedText(at(9, 5), zone))
        assertEquals("Updated 23:59", updatedText(at(23, 59), zone))
    }
}
