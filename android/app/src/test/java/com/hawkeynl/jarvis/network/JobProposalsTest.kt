package com.hawkeynl.jarvis.network

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/job-proposals.test.mjs. Neutral test data only.
class JobProposalsTest {
    private val today = LocalDate.of(2026, 11, 14)
    private fun id(n: Int) = "00000000-0000-4000-8000-00000000000$n"
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun obj(text: String): JsonObject = json(text).jsonObject
    private fun withField(base: JsonObject, field: String, value: String) = JsonObject(base + (field to json(value)))
    private fun item(n: Int, body: Any) = """{"id":"${id(n)}","body":$body}"""
    private fun read(vararg items: String) = readProposals(json("[${items.joinToString(",")}]"), today)

    private val prompt = obj("""{"kind":"prompt","cadence":"weekly","weekday":5,"at_minute":510,
        "params":{"title":"AI news","instruction":"Summarize this week's AI news","notify":false}}""")
    private val reminder = obj("""{"kind":"reminder","cadence":"once","start_date":"2026-12-01","at_minute":540,"params":{"title":"Call the bank","text":"Ask about the card"}}""")
    private val disk = obj("""{"kind":"disk_check","cadence":"daily","params":{"threshold_percent":90}}""")

    @Test fun `no proposals when absent, empty or not a list`() {
        assertEquals(emptyList<JobProposal>(), readProposals(null, today))
        assertEquals(emptyList<JobProposal>(), readProposals(json("[]"), today))
        assertEquals(emptyList<JobProposal>(), readProposals(json(item(1, prompt)), today))
        assertEquals(emptyList<JobProposal>(), readProposals(json("\"x\""), today))
    }

    @Test fun `Core's bodies decode with its defaults and the body is kept as sent`() {
        val out = read(item(1, prompt), item(2, reminder), item(3, disk))
        assertEquals(listOf(id(1), id(2), id(3)), out.map { it.id })
        assertEquals(prompt, out[0].body)
        assertEquals("08:30", out[0].draft.time)
        assertEquals(false, out[0].draft.notify)
        assertEquals("07:00", out[2].draft.time)
        assertEquals("80", read(item(4, """{"kind":"disk_check"}""")).single().draft.threshold)
    }

    @Test fun `at most 3 are used`() {
        assertEquals(listOf(id(1), id(2), id(3)), read(*(1..4).map { item(it, disk) }.toTypedArray()).map { it.id })
    }

    @Test fun `a bad id, unknown kind or cadence, or malformed body is skipped and the rest stays`() {
        val bad = listOf(
            """{"id":"not-a-uuid","body":$disk}""",
            """{"body":$disk}""",
            item(1, withField(disk, "kind", "\"dependency_review\"")),
            item(2, withField(disk, "kind", "\"shell\"")),
            item(3, withField(disk, "cadence", "\"hourly\"")),
            item(4, "\"disk_check\""),
            item(5, withField(prompt, "weekday", "\"friday\"")),
            item(6, withField(prompt, "at_minute", "1440")),
            item(7, withField(prompt, "params", """{"title":"","instruction":"x"}""")),
            item(8, withField(prompt, "enabled", "false")),
            item(9, withField(prompt, "params", """{"title":"AI news","instruction":"x","roadmap_id":"x"}""")),
            item(1, withField(disk, "params", """{"threshold_percent":10}""")),
            item(2, withField(reminder, "start_date", "\"2026-01-01\"")),
            item(3, withField(prompt, "every", "-1")),
            // Params of another kind, or fields the cadence does not take: Core refuses them.
            item(4, withField(disk, "params", """{"threshold_percent":90,"title":"x"}""")),
            item(5, withField(reminder, "params", """{"title":"Call the bank","notify":false}""")),
            item(6, withField(prompt, "params", """{"title":"AI news","instruction":"x","text":"y"}""")),
            item(7, withField(disk, "weekday", "1")),
            item(8, withField(prompt, "month_day", "1")),
            item(9, withField(reminder, "max_runs", "2")),
            "null",
        )
        for (one in bad) assertEquals(one, emptyList<JobProposal>(), read(one))
        assertEquals(listOf(id(9)), read(bad[0], item(9, disk), bad[3]).map { it.id })
    }

    @Test fun `each proposal shows once per id`() {
        val seen = mutableSetOf<String>()
        val a = read(item(1, disk))
        val cards = newCards(seen, a, "c", "m")
        assertEquals(1, cards.size)
        assertEquals(ProposalState.OPEN, cards[0].state)
        // Again (a replayed event), also after it was dismissed: nothing new.
        assertEquals(emptyList<ProposalCard>(), newCards(seen, a, "c", "m"))
        // Twice in one answer: once.
        assertEquals(1, read(item(2, disk), item(2, prompt)).size)
    }

    @Test fun `the card's wording shows all that Add sends`() {
        val (p, r, d) = read(item(1, prompt), item(2, reminder), item(3, disk))
        assertEquals("AI news", proposalTitle(p.draft))
        assertEquals("Disk check", proposalTitle(d.draft))
        assertEquals("Every Friday at 08:30 · No push", proposalSchedule(p.draft, today))
        assertEquals("Once on 1 Dec at 09:00", proposalSchedule(r.draft, today))
        assertEquals("Daily at 08:00, 2 runs", proposalSchedule(read(item(4, withField(withField(disk, "at_minute", "480"), "max_runs", "2"))).single().draft, today))
        assertEquals("Daily at 08:00, 1 run", proposalSchedule(read(item(4, withField(withField(disk, "at_minute", "480"), "max_runs", "1"))).single().draft, today))
        // An edited draft switched to once keeps a hidden run limit that is not sent: not shown.
        assertEquals("Once on 1 Dec at 09:00", proposalSchedule(r.draft.copy(maxRuns = "3"), today))
        // No cadence is weekly, so a weekday is fine.
        assertEquals("Every Tuesday at 07:00", proposalSchedule(read(item(5, """{"kind":"disk_check","weekday":2}""")).single().draft, today))
        assertEquals("Summarize this week's AI news", proposalDetail(p.draft))
        assertEquals("Ask about the card", proposalDetail(r.draft))
        assertEquals("Threshold 90%", proposalDetail(d.draft))
        // The whole instruction, never cut.
        val long = read(item(5, withField(prompt, "params", """{"title":"x","instruction":"${"a".repeat(300)}"}"""))).single()
        assertEquals(300, proposalDetail(long.draft)!!.length)
        assertEquals("Every Friday at 08:30", proposalSchedule(long.draft, today))
    }

    private val created = Job(id(7), JobKind.PROMPT, Cadence.WEEKLY, 5, null, 1, null, null, 510, true, 0, JobParams(), null, null)

    @Test fun `Add sends exactly the body and links the new job`() = runBlocking<Unit> {
        val sent = mutableListOf<JsonObject>()
        val result = addJob(prompt, JobKind.PROMPT) { sent += it; ApiResult.Success(created) }
        assertEquals(listOf(prompt.toString()), sent.map { it.toString() })
        assertEquals(AddResult(ProposalState.ADDED, id(7), ""), result)
        assertEquals("Added to Jobs", PROPOSAL_ADDED)
    }

    @Test fun `anything but a refusal is not confirmed`() = runBlocking<Unit> {
        val unconfirmed = AddResult(ProposalState.UNCONFIRMED, null, PROPOSAL_NOT_CONFIRMED)
        assertEquals(unconfirmed, addJob(prompt, JobKind.PROMPT) { ApiResult.Success(null) })
        assertEquals(unconfirmed, addJob(prompt, JobKind.PROMPT) { ApiResult.InvalidResponse("Home Node sent an invalid answer") })
        assertEquals(unconfirmed, addJob(prompt, JobKind.PROMPT) { ApiResult.Unreachable(UnreachableReason.TIMEOUT) })
        assertEquals(unconfirmed, addJob(prompt, JobKind.PROMPT) { null })
        for (status in listOf(500, 502, 503, 504, 599, 302)) assertEquals("$status", unconfirmed, addJob(prompt, JobKind.PROMPT) { ApiResult.HttpError(status, null) })
        assertEquals("Not confirmed: it may have been added.", PROPOSAL_NOT_CONFIRMED)
    }

    @Test fun `a 409, 400 or other 4xx refusal lets the owner try again`() = runBlocking<Unit> {
        assertEquals(AddResult(ProposalState.OPEN, null, "Core refused this. $JOBS_FULL_TEXT"), addJob(prompt, JobKind.PROMPT) { ApiResult.HttpError(409, null) })
        assertEquals(AddResult(ProposalState.OPEN, null, PROPOSAL_INVALID), addJob(prompt, JobKind.PROMPT) { ApiResult.HttpError(400, null) })
        val gone = addJob(disk, JobKind.DISK_CHECK) { ApiResult.HttpError(404, null) }
        assertEquals(ProposalState.OPEN, gone.state)
        assertTrue(gone.error.startsWith("Not saved"))
        for (refusal in listOf(ApiResult.HttpError(404, null), ApiResult.HttpError(429, null), ApiResult.Unauthorized)) {
            assertEquals("$refusal", ProposalState.OPEN, addJob(prompt, JobKind.PROMPT) { refusal }.state)
        }
    }

    @Test fun `Add is refused behind the lock and on a second tap, and a late result is dropped`() {
        val cards = newCards(mutableSetOf(), read(item(1, disk), item(2, prompt)), "c", "m")
        assertNull(startAdd(cards, id(1), allowed = false))
        assertNull(startAdd(cards, id(3), allowed = true))
        val adding = startAdd(cards, id(1), allowed = true)!!
        assertEquals(listOf(ProposalState.ADDING, ProposalState.OPEN), adding.map { it.state })
        assertNull(startAdd(adding, id(1), allowed = true))
        val done = AddResult(ProposalState.ADDED, id(7), "")
        assertSame(adding, finishAdd(adding, id(1), done, same = false))
        assertEquals(listOf(ProposalState.ADDED, ProposalState.OPEN), finishAdd(adding, id(1), done, same = true).map { it.state })
        // An edited job that may exist shows on the card; after a refusal the proposal stays.
        val edited = cards[0].draft.copy(threshold = "95")
        assertEquals("95", finishAdd(adding, id(1), done, same = true, edited = edited)[0].draft.threshold)
        assertEquals("90", finishAdd(adding, id(1), AddResult(ProposalState.OPEN, null, "x"), same = true, edited = edited)[0].draft.threshold)
        // Dismissed while it ran: nothing comes back.
        assertEquals(listOf(id(2)), finishAdd(adding.drop(1), id(1), done, same = true).map { it.id })
    }
}
