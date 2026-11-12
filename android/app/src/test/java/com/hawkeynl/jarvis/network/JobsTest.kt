package com.hawkeynl.jarvis.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/jobs.test.mjs. Neutral test data only.
class JobsTest {
    private val j1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71"
    private val u1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c72"
    private val job = """{"id":"$j1","kind":"dependency_review","cadence":"weekly","weekday":1,"at_minute":420,"enabled":true,"revision":3,
        "params":{},"last_slot_at":null,"created_at":"2026-11-01T10:00:00Z","updated_at":"2026-11-02T10:00:00Z","next_run_at":"2026-11-16T06:00:00Z"}"""
    private val run = """{"id":"$u1","job_id":"$j1","kind":"dependency_review","slot_at":"2026-11-09T06:00:00Z","trigger":"schedule","status":"ok",
        "error_code":null,"summary":"<b>Two</b> fixes","summary_source":"claude",
        "priorities":[{"ref":"a2","rank":2,"reason":"b"},{"ref":"a1","rank":1,"reason":"a"}],
        "started_at":"2026-11-09T06:00:01Z","finished_at":"2026-11-09T06:01:00Z"}"""

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    /** [base] with one field replaced (or added). */
    private fun withField(base: String, field: String, value: String): String =
        JsonObject(json(base).jsonObject + (field to json(value))).toString()

    @Test fun `job list guard accepts Core's shape and fails closed`() {
        val list = readJobList(json("""{"status":"ok","time_zone":"Europe/Amsterdam","jobs":[$job]}"""))!!
        assertTrue(list.configured)
        assertNull(list.jobs[0].roadmapId)
        assertEquals("r1", readJobList(json("""{"status":"not_configured","jobs":[${withField(job, "params", "{\"roadmap_id\":\"r1\"}")}]}"""))!!.jobs[0].roadmapId)
        assertEquals(false, readJobList(json("""{"status":"not_configured","jobs":[]}"""))!!.configured)
        for (broken in listOf(
            "null", "[]", """{"jobs":[$job]}""", """{"status":"ok"}""",
            """{"status":"ok","jobs":[${withField(job, "kind", "\"free_text\"")}]}""",
            """{"status":"ok","jobs":[${withField(job, "cadence", "\"hourly\"")}]}""",
            """{"status":"ok","jobs":[${withField(job, "weekday", "8")}]}""",
            """{"status":"ok","jobs":[${withField(job, "at_minute", "1440")}]}""",
            """{"status":"ok","jobs":[${withField(job, "revision", "\"3\"")}]}""",
            """{"status":"ok","jobs":[${withField(job, "params", "{\"roadmap_id\":5}")}]}""",
            """{"status":"ok","jobs":[${withField(job, "id", "\"../x\"")}]}""",
        )) assertNull(broken.take(80), readJobList(json(broken)))
    }

    @Test fun `run guards order priorities, keep text as data and fail closed`() {
        val r = readRuns(json("""{"runs":[$run]}"""))!!.single()
        assertEquals(listOf("a1", "a2"), r.priorities.map { it.ref })
        assertEquals("kept verbatim; the page renders it as text", "<b>Two</b> fixes", r.summary)
        val many = (1..21).joinToString(",") { """{"ref":"a$it","rank":$it,"reason":"x"}""" }
        for (broken in listOf(
            withField(run, "status", "\"done\""),
            withField(run, "trigger", "\"cron\""),
            withField(run, "summary_source", "\"gpt\""),
            withField(run, "error_code", "\"Upstream said <b>no</b>\""),
            withField(run, "job_id", "\"j1\""),
            withField(run, "priorities", """[{"ref":"a1","rank":"1","reason":"x"}]"""),
            withField(run, "priorities", "[$many]"),
        )) assertNull(broken.takeLast(80), readRuns(json("""{"runs":[$broken]}""")))
    }

    @Test fun `run detail resolves priorities to facts and keeps repo errors`() {
        val detail = readRunDetail(json(withField(withField(withField(run, "status", "\"partial\""), "error_code", "\"token_needs_dependabot_alerts_read\""), "facts", """{
            "alerts":[{"ref":"a1","repo":"o/r","number":4,"package":"left-pad","severity":"high","patched":"1.3.0","ghsa":"GHSA-x"}],
            "pull_requests":[{"ref":"p1","repo":"o/r","number":9,"package":"serde","from":"1.0.1","to":"1.0.2"}],
            "errors":[{"repo":"o/s","error_code":"token_needs_dependabot_alerts_read"},{"repo":"o/t","error_code":"Bad text"}],
            "truncated":true}""")))!!
        assertEquals(Fact("a1", "left-pad (high)", "o/r#4 · fixed in 1.3.0 · GHSA-x"), detail.facts[0])
        assertEquals("serde 1.0.1 → 1.0.2", detail.facts[1].title)
        assertEquals(listOf(RepoError("o/s", "token_needs_dependabot_alerts_read")), detail.errors)
        assertTrue(detail.truncated)
        // Without facts (a failed or missed run) the detail still reads.
        assertEquals(emptyList<Fact>(), readRunDetail(json(withField(run, "facts", "null")))!!.facts)
    }

    @Test fun `error codes get fixed owner-facing wording`() {
        assertTrue(jobErrorText("token_needs_dependabot_alerts_read").contains("\"Dependabot alerts: Read-only\""))
        assertTrue(jobErrorText("not_configured").contains("GitHub token or the repository list"))
        assertEquals("Core reported an error this app does not know yet.", jobErrorText("something_new"))
        assertEquals("Core reported an error this app does not know yet.", jobErrorText("toString"))
    }

    @Test fun `schedule text and time conversion`() {
        assertEquals("07:00", minuteToTime(420))
        assertEquals("23:59", minuteToTime(1439))
        assertEquals(450, timeToMinute("07:30"))
        for (bad in listOf("", "24:00", "7:30", "07:60")) assertNull(bad, timeToMinute(bad))
        assertEquals("Every Sunday at 21:30 (Europe/Amsterdam)", cadenceText(daily = false, weekday = 7, atMinute = 1290))
        assertEquals("Daily at 00:00 (Europe/Amsterdam)", cadenceText(daily = true, weekday = null, atMinute = 0))
        // 06:00 UTC in November is 07:00 in Amsterdam.
        assertTrue(zonedText("2026-11-16T06:00:00Z").contains("07:00"))
        assertEquals("an unknown time", zonedText("nope"))
    }

    @Test fun `a typed time reads with or without its colon`() {
        assertEquals("07:30", typedTime("07:30"))
        assertEquals("07:30", typedTime("7:30"))
        assertEquals("07:30", typedTime("0730"))
        assertEquals("07:30", typedTime(" 730 "))
        assertEquals("23:59", typedTime("2359"))
        for (bad in listOf("", "7", "73", "7:3", "24:00", "2400", "07:60", "12345", "ab:cd", "-1:30", "07:30x")) assertNull(bad, typedTime(bad))
    }

    @Test fun `the editor sends only what changed and never a daily weekday`() {
        val j = readJobList(json("""{"status":"ok","jobs":[$job]}"""))!!.jobs.single()
        assertNull(jobPatch(j, toDraft(j)))
        assertEquals(json("""{"cadence":"daily"}"""), jobPatch(j, toDraft(j).copy(daily = true)))
        assertEquals(json("""{"weekday":3,"at_minute":495,"enabled":false}"""), jobPatch(j, toDraft(j).copy(weekday = 3, time = "08:15", enabled = false)))
        assertEquals(json("""{"params":{"roadmap_id":"r1"}}"""), jobPatch(j, toDraft(j).copy(roadmapId = "r1")))
        val linked = j.copy(roadmapId = "r1")
        assertEquals("empty params clear the roadmap", json("""{"params":{}}"""), jobPatch(linked, toDraft(linked).copy(roadmapId = null)))
        val daily = j.copy(daily = true, weekday = null)
        assertEquals(json("""{"cadence":"weekly","weekday":1}"""), jobPatch(daily, toDraft(daily).copy(daily = false)))
        assertEquals(
            json("""{"kind":"dependency_review","cadence":"daily","at_minute":420,"enabled":true,"params":{}}"""),
            newJob(JobKind.DEPENDENCY_REVIEW, newDraft().copy(daily = true, weekday = 4)),
        )
        assertNull(newJob(JobKind.DEPENDENCY_REVIEW, newDraft().copy(time = "")))
    }

    @Test fun `an older Core and refusals get fixed wording`() {
        assertEquals(JobFailure.NO_API, jobFailureOf(ApiResult.HttpError(404, null), list = true))
        assertEquals(JobFailure.NOT_FOUND, jobFailureOf(ApiResult.HttpError(404, null)))
        assertTrue(jobFailureText(JobFailure.NO_API).startsWith(JOBS_NEED_CORE))
        assertEquals("Needs Jarvis Core v0.0.80 or later.", JOBS_NEED_CORE)
        assertEquals(RUN_NOW_REFUSED, runNowFailureText(ApiResult.HttpError(429, null)))
        assertTrue(runNowFailureText(ApiResult.HttpError(409, null)).contains("scheduler is off"))
        // A 409 on a change is always a revision conflict; only a create can mean "exists".
        assertEquals(CHANGED_ELSEWHERE, writeFailureText(ApiResult.HttpError(409, null), update = true))
        assertTrue(writeFailureText(ApiResult.HttpError(409, null), update = false).contains("already exists"))
    }

    @Test fun `only a Core UUID can go into a jobs URL`() {
        assertTrue(isJobId(j1))
        for (bad in listOf("", "../x", "$j1/run", "$j1?x=1", "j1")) assertEquals(bad, false, isJobId(bad))
    }
}
