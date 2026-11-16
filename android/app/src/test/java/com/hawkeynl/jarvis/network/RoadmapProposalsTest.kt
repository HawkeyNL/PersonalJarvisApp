package com.hawkeynl.jarvis.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/roadmap-proposals.test.mjs. Neutral test data only.
class RoadmapProposalsTest {
    private val id1 = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a51"
    private val id2 = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a52"
    private val roadmapId = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun obj(text: String): JsonObject = json(text).jsonObject
    private fun with(base: JsonObject, field: String, value: String?) =
        JsonObject(if (value == null) base - field else base + (field to json(value)))

    private val newBody = obj(
        """{"title":"Trip","description":"Line one\nline two","phases":["Book","Go"],"items":[
            {"title":"Flights","status":"ready","phase":"Book","notes":"Window seat",
             "links":[{"kind":"issue","repo":"example/trip","number":3},{"kind":"release","repo":"example/trip","tag":"v1.0"}]},
            {"title":"Hotel","status":"planned","phase":"Book"},
            {"title":"Pack","status":"planned"}]}""",
    )
    private val itemsBody = obj("""{"items":[{"title":"Chat cards","status":"in_progress","phase":"Now"},{"title":"Docs","status":"planned"}]}""")
    private fun entry(body: JsonObject, id: String = id1, roadmap: String? = null, title: String = "Trip") =
        """{"id":"$id",${roadmap?.let { "\"roadmap_id\":\"$it\"," } ?: ""}"title":"$title","body":$body}"""
    private fun read(vararg entries: String) = readRoadmapProposal(json("[${entries.joinToString(",")}]"))
    private fun item(n: Int, extra: String = "") = """{"title":"Item $n","status":"planned"$extra}"""

    @Test fun `a new roadmap reads with its phases, items and the body as sent`() {
        val p = read(entry(newBody))!!
        assertEquals(id1, p.id)
        assertNull(p.roadmapId)
        assertEquals("Line one\nline two", p.description)
        assertEquals("Trip", p.title)
        assertEquals(listOf("Book", "Go"), p.phases)
        assertEquals(newBody, p.body)
        assertEquals(listOf(RoadmapStatus.READY, RoadmapStatus.PLANNED, RoadmapStatus.PLANNED), p.items.map { it.status })
        assertEquals(listOf("example/trip#3", "example/trip@v1.0"), p.items[0].links.map(::linkLabel))
        assertEquals("New roadmap: Trip", roadmapHeading(p))
        assertEquals("3 items in 2 phases", roadmapSummary(p))
        // Grouped as on the Roadmaps page: the phases in order (empty ones too), then no phase.
        assertEquals(listOf("Book" to 2, "Go" to 0, null to 1), roadmapGroups(p).map { it.phase to it.items.size })
        assertEquals(listOf("Flights", "Hotel"), roadmapGroups(p)[0].items.map { it.title })
        // The smallest body Core sends, and the singulars.
        val small = read(entry(obj("""{"title":"Empty","phases":["Now"],"items":[{"title":"One","status":"live","phase":"Now"}]}""")))!!
        assertEquals("1 item in 1 phase", roadmapSummary(small))
        assertEquals("0 items in 0 phases", roadmapSummary(read(entry(obj("""{"title":"Empty","phases":[],"items":[]}""")))!!))
    }

    @Test fun `items for an existing roadmap read with its title`() {
        val p = read(entry(itemsBody, roadmap = roadmapId, title = "Jarvis"))!!
        assertEquals(roadmapId, p.roadmapId)
        assertEquals(itemsBody, p.body)
        assertEquals("Add 2 items to Jarvis", roadmapHeading(p))
        assertEquals("2 items", roadmapSummary(p))
        assertEquals(listOf("Now" to 1, null to 1), roadmapGroups(p).map { it.phase to it.items.size })
        val one = read(entry(obj("""{"items":[${item(1)}]}"""), roadmap = roadmapId, title = "Jarvis"))!!
        assertEquals("Add 1 item to Jarvis", roadmapHeading(one))
        assertEquals("1 item", roadmapSummary(one))
        // The status is required.
        assertNull(read(entry(obj("""{"items":[{"title":"X"}]}"""), roadmap = roadmapId)))
    }

    /** What the card shows (RoadmapProposalCard.kt), as text. */
    private fun shown(p: RoadmapProposal): List<String> = listOfNotNull(roadmapHeading(p), roadmapSummary(p), p.description) +
        roadmapGroups(p).flatMap { g ->
            listOf(g.phase ?: "No phase") + g.items.flatMap { listOfNotNull(it.title, it.status.label, it.notes) + it.links.map(::linkLabel) }
        }

    /** Every string and number in [x] but the wire codes (`status`, `kind`) the card shows as words. */
    private fun leaves(x: JsonElement, key: String? = null): List<String> = when (x) {
        is JsonObject -> x.flatMap { (k, v) -> leaves(v, k) }
        is kotlinx.serialization.json.JsonArray -> x.flatMap { leaves(it, key) }
        else -> if (key == "status" || key == "kind") emptyList() else listOf((x as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `the card shows everything Add sends`() {
        for (p in listOf(read(entry(newBody))!!, read(entry(itemsBody, roadmap = roadmapId, title = "Jarvis"))!!)) {
            val text = shown(p)
            for (leaf in leaves(p.body)) assertTrue(leaf, text.any { leaf in it })
        }
        // An empty phase still shows with its heading.
        assertTrue("Go" in shown(read(entry(newBody))!!))
    }

    @Test fun `only the first entry counts`() {
        assertEquals(id1, read(entry(newBody), entry(newBody, id2))!!.id)
        assertNull(read("""{"id":"x"}""", entry(newBody, id2)))
    }

    @Test fun `no card when absent, empty or not a list`() {
        assertNull(readRoadmapProposal(null))
        assertNull(read())
        for (shape in listOf("{}", "\"x\"", "[1]", "[null]", entry(newBody))) assertNull(shape, readRoadmapProposal(json(shape)))
    }

    @Test fun `each invalid shape is skipped`() {
        val bad = listOf(
            """{"title":"Trip","body":$newBody}""",
            entry(newBody, id = "not-a-uuid"),
            """{"id":"$id1","body":$newBody}""",
            """{"id":"$id1","title":"Trip"}""",
            """{"id":"$id1","title":"Trip","body":"x"}""",
            """{"id":"$id1","title":"Trip","body":$newBody,"extra":1}""",
            """{"id":"$id1","roadmap_id":null,"title":"Trip","body":$newBody}""",
            entry(itemsBody, roadmap = "../x"),
            entry(itemsBody, roadmap = "not-a-uuid"),
            entry(obj("""{"title":"T","phases":null,"items":[{"title":"A","status":"planned"}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","status":"planned","phase":null}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","status":"planned","notes":null}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","status":"planned","links":null}]}""")),
            entry(with(newBody, "extra", "1")),
            entry(with(newBody, "title", null)),
            entry(with(newBody, "items", null)),
            entry(with(newBody, "items", "{}")),
            entry(with(newBody, "phases", "\"Book\"")),
            entry(with(newBody, "phases", """{"0":"Book"}""")),
            entry(with(newBody, "phases", "null")),
            entry(with(newBody, "phases", """["Book","Book"]""")),
            entry(with(newBody, "description", "null")),
            entry(obj("""{"title":"T","items":[{"title":"A","position":1}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","depends_on":[]}]}""")),
            entry(obj("""{"title":"T","items":[{"status":"planned"}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","status":null}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","links":[{"kind":"pr","repo":"no slash","number":1}]}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","links":[{"kind":"pr","repo":"a/b"}]}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","links":[{"kind":"pr","repo":"a/b","number":0}]}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","links":[{"kind":"release","repo":"a/b","tag":"v 1"}]}]}""")),
            entry(obj("""{"title":"T","items":[{"title":"A","links":[{"kind":"pr","repo":"a/b","number":1,"state":"open"}]}]}""")),
            // Items for an existing roadmap: only items, at least one.
            entry(obj("""{"items":[]}"""), roadmap = roadmapId),
            entry(with(itemsBody, "phases", """["Now"]"""), roadmap = roadmapId),
            entry(with(itemsBody, "description", "\"x\""), roadmap = roadmapId),
            entry(with(itemsBody, "title", "\"x\""), roadmap = roadmapId),
        )
        for (shape in bad) assertNull(shape, read(shape))
    }

    @Test fun `text must fit Core's rules`() {
        val long = { n: Int -> "x".repeat(n) }
        assertNotNull(read(entry(obj("""{"title":"${long(120)}","description":"${long(2000)}","phases":[${(1..20).joinToString(",") { "\"" + "P$it".padEnd(60, 'x') + "\"" }}],
            "items":[{"title":"${long(200)}","status":"planned","notes":"${long(4000)}"}]}"""))))
        val bad = mutableListOf(
            obj("""{"title":"${long(121)}","items":[]}"""),
            obj("""{"title":" ","items":[]}"""),
            obj("""{"title":"T","description":"${long(2001)}","items":[]}"""),
            obj("""{"title":"T","phases":["${long(61)}"],"items":[]}"""),
            obj("""{"title":"T","phases":[${(1..21).joinToString(",") { "\"P$it\"" }}],"items":[]}"""),
            obj("""{"title":"T","items":[{"title":"${long(201)}","status":"planned"}]}"""),
            obj("""{"title":"T","items":[{"title":"Two\nlines","status":"planned"}]}"""),
            obj("""{"title":"T","items":[{"title":"Hidden\u202Etext","status":"planned"}]}"""),
            obj("""{"title":"T","items":[{"title":"A","status":"planned","notes":"${long(4001)}"}]}"""),
        )
        // Hidden and bidi characters (U+202E) anywhere Core's text rules apply.
        val rlo = "\\u202E"
        for (field in listOf("title", "description")) bad += with(obj("""{"title":"T","items":[]}"""), field, "\"A${rlo}B\"")
        bad += obj("""{"title":"T","phases":["A${rlo}B"],"items":[]}""")
        bad += obj("""{"title":"T","items":[{"title":"A","status":"planned","notes":"A${rlo}B"}]}""")
        for (body in bad) assertNull(body.toString(), read(entry(body)))
        assertNull(read(entry(obj("""{"items":[{"title":"A","status":"planned","phase":"A${rlo}B"}]}"""), roadmap = roadmapId)))
        assertNull(read(entry(itemsBody, roadmap = roadmapId, title = long(121))))
    }

    @Test fun `an unknown status is refused`() {
        assertNull(read(entry(obj("""{"title":"T","items":[{"title":"A","status":"done"}]}"""))))
        for (s in listOf("planned", "in_progress", "ready", "live", "blocked")) {
            assertNotNull(s, read(entry(obj("""{"title":"T","items":[{"title":"A","status":"$s"}]}"""))))
        }
    }

    @Test fun `a url link is refused`() {
        assertNull(read(entry(obj("""{"title":"T","items":[{"title":"A","links":[{"kind":"url","url":"https://example.com"}]}]}"""))))
        assertNull(read(entry(obj("""{"title":"T","items":[{"title":"A","links":[{"kind":"pr","repo":"a/b","number":1,"url":"https://example.com"}]}]}"""))))
    }

    @Test fun `more than 100 items are refused`() {
        val items = { n: Int -> (1..n).joinToString(",") { item(it) } }
        assertEquals(100, read(entry(obj("""{"title":"T","items":[${items(100)}]}""")))!!.items.size)
        assertNull(read(entry(obj("""{"title":"T","items":[${items(101)}]}"""))))
        assertNull(read(entry(obj("""{"items":[${items(101)}]}"""), roadmap = roadmapId)))
    }

    @Test fun `a phase not among the new roadmap's phases is refused`() {
        assertNull(read(entry(obj("""{"title":"T","phases":["Now"],"items":[${item(1, ",\"phase\":\"Later\"")}]}"""))))
        assertNull(read(entry(obj("""{"title":"T","items":[${item(1, ",\"phase\":\"Now\"")}]}"""))))
    }

    @Test fun `a duplicate id shows once`() {
        val seen = mutableSetOf<String>()
        val p = read(entry(newBody))
        assertNotNull(newRoadmapCard(seen, p, "c", "m"))
        assertNull(newRoadmapCard(seen, p, "c", "m2"))
        assertNull(newRoadmapCard(seen, null, "c", "m"))
    }

    // ---- add ---------------------------------------------------------------

    private val newProposal = read(entry(newBody))!!
    private val itemsProposal = read(entry(itemsBody, roadmap = roadmapId, title = "Jarvis"))!!
    private fun snapshot(id: String = roadmapId, revision: Long = 4) =
        RoadmapSnapshot(Roadmap(id, "Jarvis", null, listOf("Now"), revision, "t", "t", null), emptyList())
    private val unconfirmed = RoadmapAddResult(ProposalState.UNCONFIRMED, null, ROADMAP_NOT_CONFIRMED)

    private fun addNew(answer: ApiResult<RoadmapSnapshot?>?): RoadmapAddResult = runBlocking {
        addRoadmap(newProposal, { answer }, { error("no read") }, { _, _, _ -> error("no batch") }, { true })
    }

    /** An items Add answered with [answers] in turn; the reads answer revisions 4, 5, and so on. */
    private class Calls(val answers: List<ApiResult<RoadmapSnapshot?>?>, val allowed: Boolean) {
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Triple<String, Long, JsonObject>>()
    }
    private fun addItems(vararg answers: ApiResult<RoadmapSnapshot?>?, allowed: Boolean = true): Pair<RoadmapAddResult, Calls> {
        val calls = Calls(answers.toList(), allowed)
        val result = runBlocking {
            addRoadmap(
                itemsProposal, { error("no create") },
                { id -> calls.reads += id; ApiResult.Success(snapshot(revision = 3L + calls.reads.size)) },
                { id, revision, body -> calls.writes += Triple(id, revision, body); calls.answers[calls.writes.size - 1] },
                { calls.allowed },
            )
        }
        return result to calls
    }

    @Test fun `2xx adds and names the roadmap to open`() {
        var sent: JsonObject? = null
        val created = runBlocking {
            addRoadmap(newProposal, { sent = it; ApiResult.Success(snapshot(id = "new-id")) }, { error("no read") }, { _, _, _ -> error("no batch") }, { true })
        }
        assertEquals(RoadmapAddResult(ProposalState.ADDED, "new-id", ""), created)
        assertEquals(newBody, sent)
        val (added, calls) = addItems(ApiResult.Success(snapshot()))
        assertEquals(RoadmapAddResult(ProposalState.ADDED, roadmapId, ""), added)
        assertEquals(listOf(roadmapId), calls.reads)
        assertEquals(listOf(Triple(roadmapId, 4L, itemsBody)), calls.writes)
    }

    @Test fun `4xx opens the card again with the refusal`() {
        val refused = RoadmapAddResult(ProposalState.OPEN, null, ROADMAP_REFUSED)
        // A 409 on a new roadmap is a refusal, never retried.
        for (status in listOf(400, 409, 413, 422)) assertEquals("$status", refused, addNew(ApiResult.HttpError(status, null)))
        for (status in listOf(400, 413, 422)) assertEquals("$status", refused, addItems(ApiResult.HttpError(status, null)).first)
        // Other refusals open the card again with the failure text.
        assertEquals(RoadmapAddResult(ProposalState.OPEN, null, roadmapFailureText(JobFailure.SIGNIN)), addNew(ApiResult.Unauthorized))
        val failed = RoadmapAddResult(ProposalState.OPEN, null, roadmapFailureText(JobFailure.FAILED))
        for (status in listOf(403, 429, 302)) assertEquals("$status", failed, addNew(ApiResult.HttpError(status, null)))
    }

    @Test fun `a 409 reads the revision again and retries once`() {
        val (added, calls) = addItems(ApiResult.HttpError(409, null), ApiResult.Success(snapshot(revision = 6)))
        assertEquals(ProposalState.ADDED, added.state)
        assertEquals(listOf(4L, 5L), calls.writes.map { it.second })
        val (refused, twice) = addItems(ApiResult.HttpError(409, null), ApiResult.HttpError(409, null))
        assertEquals(RoadmapAddResult(ProposalState.OPEN, null, ROADMAP_REFUSED), refused)
        assertEquals(2, twice.writes.size)
    }

    @Test fun `5xx, no answer or an unreadable answer is not confirmed`() {
        val answers = listOf(
            ApiResult.HttpError(500, null), ApiResult.HttpError(503, null),
            ApiResult.Unreachable(UnreachableReason.TIMEOUT), ApiResult.InvalidResponse("Home Node sent an invalid answer"),
            ApiResult.Success(null), null,
        )
        for (answer in answers) {
            assertEquals("$answer", unconfirmed, addNew(answer))
            assertEquals("$answer", unconfirmed, addItems(answer).first)
        }
    }

    @Test fun `404 or 405 asks for a newer Core`() {
        val old = RoadmapAddResult(ProposalState.OPEN, null, ROADMAP_CORE_TOO_OLD)
        for (status in listOf(404, 405)) {
            assertEquals(old, addNew(ApiResult.HttpError(status, null)))
            assertEquals(old, addItems(ApiResult.HttpError(status, null)).first)
        }
    }

    @Test fun `a failed read sends nothing and opens the card again`() {
        val gone = runBlocking {
            addRoadmap(itemsProposal, { error("no create") }, { ApiResult.HttpError(404, null) }, { _, _, _ -> error("no batch") }, { true })
        }
        assertEquals(RoadmapAddResult(ProposalState.OPEN, null, roadmapFailureText(JobFailure.NOT_FOUND)), gone)
        val locked = runBlocking { addRoadmap(itemsProposal, { error("no create") }, { null }, { _, _, _ -> error("no batch") }, { true }) }
        assertEquals(RoadmapAddResult(ProposalState.OPEN, null, ""), locked)
        // Locked, signed out or left between the read and the write: nothing is written.
        val (between, calls) = addItems(ApiResult.Success(snapshot()), allowed = false)
        assertEquals(RoadmapAddResult(ProposalState.OPEN, null, ""), between)
        assertEquals(listOf(roadmapId), calls.reads)
        assertEquals(emptyList<Triple<String, Long, JsonObject>>(), calls.writes)
    }

    @Test fun `never while locked, never twice, and a late result is gated`() {
        val cards = listOf(newRoadmapCard(mutableSetOf(), newProposal, "c", "m")!!)
        assertNull(startRoadmapAdd(cards, id1, allowed = false, identity = 1, conversationId = "c"))
        assertNull(startRoadmapAdd(cards, id2, allowed = true, identity = 1, conversationId = "c"))
        val start = startRoadmapAdd(cards, id1, allowed = true, identity = 1, conversationId = "c")!!
        assertEquals(ProposalState.ADDING, start.cards.single().state)
        assertNull(startRoadmapAdd(start.cards, id1, allowed = true, identity = 1, conversationId = "c"))
        val added = RoadmapAddResult(ProposalState.ADDED, "new-id", "")
        assertEquals(start.cards, finishRoadmapAdd(start.cards, start, added, identity = 2, conversationId = "c"))
        assertEquals(start.cards, finishRoadmapAdd(start.cards, start, added, identity = 1, conversationId = "other"))
        assertEquals(emptyList<RoadmapChatCard>(), finishRoadmapAdd(emptyList(), start, added, identity = 1, conversationId = "c"))
        val done = finishRoadmapAdd(start.cards, start, added, identity = 1, conversationId = "c").single()
        assertEquals(ProposalState.ADDED to "new-id", done.state to done.roadmapId)
    }
}
