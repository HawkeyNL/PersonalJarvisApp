package com.hawkeynl.jarvis.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/roadmaps.test.mjs. Neutral test data only: no real roadmap content
// belongs in this repository.
class RoadmapsTest {
    private val roadmap = """{"id":"r1","title":"Example","description":null,"phases":["Now","Next","Later"],"revision":4,
        "created_at":"2026-11-01T10:00:00Z","updated_at":"2026-11-02T10:00:00Z","archived_at":null}"""

    private fun itemJson(id: String, extra: String = "") =
        """{"id":"$id","title":"Item $id","status":"planned","auto_status":null,"phase":"Now","position":0,"notes":null,
        "depends_on":[],"links":[],"updated_at":"2026-11-02T10:00:00Z"$extra}"""

    private fun item(
        id: String, status: RoadmapStatus = RoadmapStatus.PLANNED, phase: String? = "Now", position: Long = 0,
        notes: String? = null, dependsOn: List<String> = emptyList(), links: List<RoadmapLink> = emptyList(),
    ) = RoadmapItem(id, "Item $id", status, null, phase, position, notes, dependsOn, links, "2026-11-02T10:00:00Z")

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun with(base: String, field: String, value: String): String = JsonObject(json(base).jsonObject + (field to json(value))).toString()

    @Test fun `snapshot guard accepts Core's shape and fails closed`() {
        val good = itemJson("a", ""","links":[{"kind":"pr","repo":"o/r","number":3,"state":"open"}]""").let { with(it, "auto_status", "\"in_progress\"") }
        val snap = readSnapshot(json("""{"roadmap":$roadmap,"items":[$good]}"""))!!
        assertEquals("open", snap.items[0].links[0].state)
        assertEquals(RoadmapStatus.IN_PROGRESS, snap.items[0].autoStatus)
        // Absent optional fields read as null.
        val bare = JsonObject(json(roadmap).jsonObject - "description").toString()
        val bareItem = JsonObject(json(itemJson("a")).jsonObject - "notes").toString()
        assertNull(readSnapshot(json("""{"roadmap":$bare,"items":[$bareItem]}"""))!!.roadmap.description)
        for (broken in listOf(
            "null", "[]", """{"roadmap":$roadmap}""",
            """{"roadmap":${with(roadmap, "revision", "\"4\"")},"items":[]}""",
            """{"roadmap":$roadmap,"items":[${with(itemJson("a"), "status", "\"done\"")}]}""",
            """{"roadmap":$roadmap,"items":[${with(itemJson("a"), "auto_status", "\"merged\"")}]}""",
            """{"roadmap":$roadmap,"items":[${with(itemJson("a"), "links", """[{"kind":"ftp","url":"x"}]""")}]}""",
            """{"roadmap":$roadmap,"items":[${with(itemJson("a"), "depends_on", "\"b\"")}]}""",
            """{"roadmap":$roadmap,"items":[${with(itemJson("a"), "position", "-1")}]}""",
        )) assertNull(broken.take(80), readSnapshot(json(broken)))
        assertEquals("r1", readRoadmapList(json("""{"roadmaps":[$roadmap]}"""))!!.single().id)
        assertNull(readRoadmapList(json("""{"roadmaps":[${with(roadmap, "phases", "[1]")}]}""")))
    }

    @Test fun `history and proposal guards`() {
        val change = """{"id":"c1","revision":5,"at":"2026-11-02T10:00:00Z","author":"github","device_id":null,"proposal_id":null,
            "action":"item_updated","item_id":"a","title":"Item a","fields":["status"],"from_status":"in_progress","to_status":"ready"}"""
        assertEquals(Author.GITHUB, readHistory(json("""{"changes":[$change]}"""))!![0].author)
        assertNull(readHistory(json("""{"changes":[${with(change, "author", "\"someone\"")}]}""")))
        val proposal = """{"id":"p1","base_revision":4,"rationale":null,"created_at":"2026-11-02T10:00:00Z","expires_at":"2026-11-16T10:00:00Z",
            "ops":[{"op":"create_item","item":{"title":"New"}},{"op":"update_item","item_id":"a","patch":{"status":"ready","notes":null}}]}"""
        val p = readProposals(json("""{"proposals":[$proposal]}"""))!!.single()
        assertNull(p.ops[1].fields.notes)
        assertTrue("notes" in p.ops[1].fields.present)
        assertNull(readProposals(json("""{"proposals":[${with(proposal, "ops", """[{"op":"delete_item","item_id":"a"}]""")}]}""")))
        assertNull(readProposals(json("""{"proposals":[${with(proposal, "ops", """[{"op":"create_item","item":{}}]""")}]}""")))
        assertNull(readProposals(json("""{"proposals":[${with(proposal, "ops", """[{"op":"update_item","item_id":"a","patch":{"status":null}}]""")}]}""")))
    }

    @Test fun `text rules mirror Core`() {
        assertTrue(validText("Ship it", 120))
        assertFalse(validText("   ", 120))
        assertFalse(validText("a".repeat(121), 120))
        assertFalse(validText("two\nlines", 120))
        assertTrue(validText("two\nlines\tok", 4000, multiline = true))
        assertFalse(validText("zero​width", 120))
        assertFalse(validText("bidi‮flip", 4000, multiline = true))
        assertFalse(validText("tag󠀁", 120))
        assertTrue(validText("Gus’s plan ✓", 120))
        assertTrue("an emoji is one character", validText("🚀".repeat(120), 120))
        assertEquals(listOf("Now", "Next", "Later"), parsePhases("Now\n Next \n\nLater\n"))
        assertEquals(emptyList<String>(), parsePhases(""))
        assertNull(parsePhases("Now\nNow"))
        assertNull(parsePhases((0 until 21).joinToString("\n") { "P$it" }))
    }

    @Test fun `GitHub URLs and shorthands parse into typed links`() {
        fun ok(input: String, hint: LinkKind = LinkKind.PR) = (parseLink(input, hint) as ParsedLink.Ok).link
        assertEquals(RoadmapLink(LinkKind.PR, "owner/repo", 12), ok("https://github.com/owner/repo/pull/12"))
        assertEquals(RoadmapLink(LinkKind.PR, "owner/repo", 12), ok("https://github.com/owner/repo/pull/12/files#diff"))
        assertEquals(RoadmapLink(LinkKind.ISSUE, "owner/repo", 7), ok("https://github.com/owner/repo/issues/7?x=1"))
        assertEquals(RoadmapLink(LinkKind.RELEASE, "o-1/r.e_p", tag = "v1.2.3"), ok(" https://github.com/o-1/r.e_p/releases/tag/v1.2.3 "))
        assertEquals(RoadmapLink(LinkKind.RELEASE, "o/r", tag = "app/v1"), ok("https://github.com/o/r/releases/tag/app%2Fv1"))
        assertEquals(RoadmapLink(LinkKind.RELEASE, "o/r", tag = "v1+b"), ok("https://github.com/o/r/releases/tag/v1+b"))
        assertEquals(RoadmapLink(LinkKind.PR, "owner/repo", 5), ok("owner/repo#5"))
        assertEquals(RoadmapLink(LinkKind.ISSUE, "owner/repo", 5), ok("owner/repo#5", LinkKind.ISSUE))
        assertEquals(RoadmapLink(LinkKind.RELEASE, "owner/repo", tag = "v0.0.78"), ok("owner/repo@v0.0.78"))
        assertEquals(RoadmapLink(LinkKind.URL, url = "https://example.com/a?b=c"), ok("https://example.com/a?b=c"))
        for (bad in listOf(
            "http://example.com", "javascript:alert(1)", "https://user:pw@example.com", "https://exa mple.com",
            "https://github.com/owner/repo/pull/0", "https://github.com/owner/repo/pull/abc", "https://github.com/-o/r/pull/1",
            "owner/repo#0", "owner#5", "../x#1", "owner/..#1", "", "data:text/html,x", "owner/repo#99999999999999999999",
        )) assertTrue(bad, parseLink(bad) is ParsedLink.Bad)
        assertEquals(ParsedLink.Bad("Only https links are allowed."), parseLink("http://example.com"))
        assertTrue(validHttpsUrl("https://example.com"))
        assertFalse(validHttpsUrl("https://example.com/" + "a".repeat(500)))
    }

    @Test fun `links display, open only over https and never send the cached state`() {
        val pr = RoadmapLink(LinkKind.PR, "o/r", 3, state = "merged")
        assertEquals("o/r#3", linkLabel(pr))
        assertEquals("https://github.com/o/r/pull/3", linkHref(pr))
        assertEquals("https://github.com/o/r/issues/4", linkHref(RoadmapLink(LinkKind.ISSUE, "o/r", 4)))
        assertEquals("https://github.com/o/r/releases/tag/app/v1%2Bb", linkHref(RoadmapLink(LinkKind.RELEASE, "o/r", tag = "app/v1+b")))
        assertNull(linkHref(RoadmapLink(LinkKind.URL, url = "javascript:alert(1)")))
        assertNull("Core's cached repo is not trusted for a URL", linkHref(RoadmapLink(LinkKind.PR, "o/../../evil", 1)))
        assertEquals(json("""{"kind":"pr","repo":"o/r","number":3}"""), linkJson(pr))
        assertNull(addLink(listOf(pr), RoadmapLink(LinkKind.PR, "o/r", 3)))
        assertNull(addLink((1L..10L).map { RoadmapLink(LinkKind.PR, "o/r", it) }, RoadmapLink(LinkKind.URL, url = "https://a.b")))
        assertEquals(2, addLink(listOf(pr), RoadmapLink(LinkKind.ISSUE, "o/r", 3))!!.size)
    }

    @Test fun `tally, phases and waiting on`() {
        val items = listOf(
            item("a", RoadmapStatus.LIVE, position = 2),
            item("b", RoadmapStatus.BLOCKED, phase = "Next", notes = "Needs a decision\nmore"),
            item("c", dependsOn = listOf("b", "a"), position = 1),
            item("d", RoadmapStatus.READY, phase = "Gone"),
            item("e", phase = null, dependsOn = listOf("d")),
        )
        assertEquals(
            listOf(RoadmapStatus.LIVE to 1, RoadmapStatus.READY to 1, RoadmapStatus.PLANNED to 2, RoadmapStatus.BLOCKED to 1),
            tally(items),
        )
        val groups = groupByPhase(listOf("Now", "Next", "Later"), items)
        assertEquals(
            listOf("Now" to listOf("c", "a"), "Next" to listOf("b"), "Later" to emptyList(), null to listOf("d", "e")),
            groups.map { g -> g.phase to g.items.map { it.id } },
        )
        // Blocked items, and open items with a dependency that is not ready or live.
        assertEquals(listOf("b" to emptyList(), "c" to listOf("b")), waitingOn(items).map { w -> w.item.id to w.unmet.map { it.id } })
    }

    @Test fun `dependency picker leaves out the item and everything that depends on it`() {
        val items = listOf(item("a"), item("b", dependsOn = listOf("a")), item("c", dependsOn = listOf("b")), item("d"))
        assertEquals(listOf("d"), dependencyOptions(items, "a").map { it.id })
        assertEquals(listOf("a", "b", "d"), dependencyOptions(items, "c").map { it.id })
        assertEquals(4, dependencyOptions(items, null).size)
    }

    @Test fun `an item patch holds only what changed`() {
        val base = item("a", notes = "Old", links = listOf(RoadmapLink(LinkKind.PR, "o/r", 1, state = "open")))
        var draft = toDraft(base)
        assertNull(itemPatch(base, draft))
        draft = draft.copy(title = "  Item a  ")
        assertNull(itemPatch(base, draft))
        draft = draft.copy(status = RoadmapStatus.BLOCKED, phase = null, notes = "  ")
        assertEquals(json("""{"status":"blocked","phase":null,"notes":null}"""), itemPatch(base, draft))
        draft = draft.copy(links = draft.links + RoadmapLink(LinkKind.URL, url = "https://a.b"))
        assertEquals(json("""[{"kind":"pr","repo":"o/r","number":1},{"kind":"url","url":"https://a.b"}]"""), itemPatch(base, draft)!!["links"])
        assertNull(draftIssue(draft))
        assertTrue(draftIssue(draft.copy(title = ""))!!.contains("title"))
    }

    @Test fun `roadmap forms send only what changed`() {
        val r = readRoadmapList(json("""{"roadmaps":[$roadmap]}"""))!!.single()
        assertNull(roadmapPatch(r, " Example ", "  ", listOf("Now", "Next", "Later")))
        assertEquals(json("""{"description":"Why","phases":["Now"]}"""), roadmapPatch(r, "Example", "Why", listOf("Now")))
        assertEquals(json("""{"title":"New","phases":["Now","Next","Later"]}"""), newRoadmap(" New ", " ", DEFAULT_PHASES))
        assertEquals(ROADMAP_TITLE_RULE, roadmapIssue("two\nlines", "", emptyList()))
        assertEquals(PHASES_RULE, roadmapIssue("Ok", "", null))
        assertNull(roadmapIssue("Ok", "", emptyList()))
        assertEquals(json("""{"title":"T","phase":"Now"}"""), newItem("T", "Now"))
        assertEquals(json("""{"title":"T"}"""), newItem("T", null))
    }

    @Test fun `proposal operations read as before and after`() {
        val items = listOf(item("a", RoadmapStatus.IN_PROGRESS, notes = "x"), item("b"))
        val ops = readProposals(json("""{"proposals":[{"id":"p1","base_revision":1,"created_at":"x","expires_at":"y","ops":[
            {"op":"update_item","item_id":"a","patch":{"status":"ready","notes":null,"depends_on":["b"]}},
            {"op":"create_item","item":{"title":"New","phase":"Next","links":[{"kind":"issue","repo":"o/r","number":9}]}},
            {"op":"update_item","item_id":"zz","patch":{"title":"T"}}]}]}"""))!!.single().ops
        val update = describeOp(ops[0], items)
        assertEquals("Item a", update.title)
        assertEquals(
            listOf(DiffRow("Status", "In progress", "Ready"), DiffRow("Notes", "x", "—"), DiffRow("Depends on", "—", "Item b")),
            update.rows,
        )
        val create = describeOp(ops[1], items)
        assertTrue(create.create)
        assertEquals(listOf(DiffRow("Status", "—", "Planned"), DiffRow("Phase", "—", "Next"), DiffRow("Links", "—", "o/r#9")), create.rows)
        assertEquals("—", describeOp(ops[2], items).rows[0].before)
    }

    @Test fun `history lines and write failures`() {
        val c = HistoryEntry("c", 1, "x", Author.OWNER, null, null, "item_updated", "a", "Item a", listOf("status", "depends_on"), RoadmapStatus.PLANNED, RoadmapStatus.READY)
        assertEquals("Changed “Item a”: status Planned → Ready, depends on", describeChange(c))
        assertEquals("Added “Item a”", describeChange(c.copy(action = "item_created", fields = emptyList(), fromStatus = null, toStatus = null)))
        assertEquals("Archived the roadmap", describeChange(c.copy(action = "roadmap_archived", itemId = null)))
        val conflict = ApiResult.HttpError(409, null)
        assertEquals(CHANGED_ELSEWHERE, roadmapWriteFailureText(conflict, true))
        assertTrue(roadmapWriteFailureText(conflict, false).contains("archived or a limit"))
        assertTrue(roadmapWriteFailureText(ApiResult.HttpError(400, null), false).contains("invalid"))
        assertTrue(roadmapWriteFailureText(ApiResult.Unreachable(UnreachableReason.TIMEOUT), false).contains("unknown whether"))
        assertTrue(proposalFailureText(conflict).startsWith("Not applied"))
        // A 2xx with an oversized or unreadable answer may have landed.
        assertEquals(NOT_CONFIRMED, roadmapWriteFailureText(ApiResult.InvalidResponse("x"), false))
        assertEquals(NOT_CONFIRMED, proposalFailureText(ApiResult.InvalidResponse("x")))
        assertTrue(proposalFailureText(ApiResult.Unreachable(UnreachableReason.TIMEOUT)).contains("unknown whether it was applied"))
        // Jarvis-written proposal text loses Core's hidden and other format characters, keeps
        // line breaks in notes and the rationale, and says when it held hidden characters.
        val p = readProposals(json("""{"proposals":[{"id":"p1","base_revision":1,"created_at":"x","expires_at":"y","rationale":"evil\u202Etext\nline two",
            "ops":[{"op":"create_item","item":{"title":"Ti\u200Btle","notes":"a\u2066b\n\tc\u034F","phase":"N\u3164ow"}},
                {"op":"update_item","item_id":"a","patch":{"phase":"Now\uFE0F"}},
                {"op":"update_item","item_id":"a","patch":{"notes":"one\ntwo"}}]}]}"""))!!.single()
        assertEquals("eviltext\nline two", p.rationale)
        assertTrue(p.rationaleHidden)
        assertEquals("Title", p.ops[0].fields.title)
        assertEquals("ab\n c", p.ops[0].fields.notes)
        assertEquals("Now", p.ops[0].fields.phase)
        assertTrue(describeOp(p.ops[0], emptyList()).hidden)
        assertEquals("Now", p.ops[1].fields.phase)
        assertTrue(describeOp(p.ops[1], emptyList()).hidden)
        assertEquals("one\ntwo", p.ops[2].fields.notes)
        assertFalse(describeOp(p.ops[2], emptyList()).hidden)
    }

    @Test fun `shown text drops Core's hidden characters and keeps line breaks only in multi-line text`() {
        assertEquals(ShownText("a b c", false), shownText("a\nb\tc"))
        assertEquals(ShownText("a\nb c", false), shownText("a\nb\tc", multiline = true))
        assertEquals(ShownText(null, true), shownText("\u200B"))
        for (hidden in listOf("\u034F", "\u115F", "\u1160", "\u3164", "\uFE00", "\uFE0F", "\u202E", "\u200B", "\uDB40\uDC01", "\r")) {
            assertEquals(hidden, ShownText("ab", true), shownText("a${hidden}b", multiline = true))
        }
    }

    @Test fun `only path-safe ids reach a URL`() {
        assertTrue(isRoadmapId("0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71"))
        for (bad in listOf("", "../x", "a/b", "a?b", "%2e", "a b", "x".repeat(65))) assertFalse(bad, isRoadmapId(bad))
        assertNull(readSnapshot(JsonNull))
    }
}
