package com.hawkeynl.jarvis.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val today = LocalDate.of(2026, 11, 13)
    private val prompt = """{"id":"$u1","kind":"prompt","cadence":"monthly","weekday":null,"month_day":1,"every":6,"start_date":"2027-01-01",
        "max_runs":2,"at_minute":540,"enabled":true,"revision":2,"params":{"title":"Energy prices","instruction":"Dutch energy price outlook","notify":false},
        "last_slot_at":null,"run_count":0,"origin":"owner","owner_id":"x","conversation_id":null,"expires_at":null,"failure_count":0,
        "paused_reason":null,"created_at":"2026-11-01T10:00:00Z","updated_at":"2026-11-01T10:00:00Z","next_run_at":"2027-01-01T08:00:00Z"}"""

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    /** [base] with one field replaced (or added). */
    private fun withField(base: String, field: String, value: String): String =
        JsonObject(json(base).jsonObject + (field to json(value))).toString()

    @Test fun `job list guard accepts Core's shape and fails closed`() {
        val list = readJobList(json("""{"status":"ok","time_zone":"Europe/Amsterdam","jobs":[$job]}"""))!!
        assertTrue(list.configured)
        assertNull(list.jobs[0].params.roadmapId)
        assertEquals("r1", readJobList(json("""{"status":"not_configured","jobs":[${withField(job, "params", "{\"roadmap_id\":\"r1\"}")}]}"""))!!.jobs[0].params.roadmapId)
        assertEquals(false, readJobList(json("""{"status":"not_configured","jobs":[]}"""))!!.configured)
        for (broken in listOf("null", "[]", """{"jobs":[$job]}""", """{"status":"ok"}""", """{"status":"ok","jobs":{}}""")) {
            assertNull(broken.take(80), readJobList(json(broken)))
        }
    }

    @Test fun `one job this build cannot read never fails the list`() {
        for (field in listOf(
            "kind" to "\"free_text\"", "cadence" to "\"hourly\"", "weekday" to "8", "at_minute" to "1440", "revision" to "\"3\"",
            "params" to "{\"roadmap_id\":5}", "id" to "\"../x\"", "origin" to "\"someone\"", "every" to "0",
        )) {
            val list = readJobList(json("""{"status":"ok","jobs":[$job,${withField(job, field.first, field.second)}]}"""))!!
            assertEquals(field.first, 1, list.jobs.size)
            assertEquals(field.first, 1, list.unsupported)
        }
        // Unknown fields, a newer pause reason and an older Core's missing fields still read.
        val newer = withField(withField(withField(prompt, "colour", "\"red\""), "params", """{"title":"T","instruction":"I","later":1}"""), "paused_reason", "\"other\"")
        val read = readJobList(json("""{"status":"ok","jobs":[$newer,$job]}"""))!!
        assertEquals(0, read.unsupported)
        assertEquals("T", read.jobs[0].params.title)
        assertFalse(read.jobs[0].pausedAfterFailures)
        assertEquals(1, read.jobs[1].every)
        assertFalse(read.jobs[1].byJarvis)
    }

    @Test fun `new kinds read with Core's v2 fields`() {
        val p = readJob(json(prompt))!!
        assertEquals(JobKind.PROMPT, p.kind)
        assertEquals(Cadence.MONTHLY, p.cadence)
        assertEquals(1, p.monthDay)
        assertEquals(6, p.every)
        assertEquals("2027-01-01", p.startDate)
        assertEquals(2, p.maxRuns)
        assertEquals(false, p.params.notify)
        val jarvis = readJob(json(withField(withField(withField(withField(withField(prompt, "origin", "\"jarvis\""), "expires_at", "\"2026-12-13T10:00:00Z\""),
            "conversation_id", "\"$j1\""), "paused_reason", "\"paused_after_failures\""), "failure_count", "3")))!!
        assertTrue(jarvis.byJarvis)
        assertEquals(j1, jarvis.conversationId)
        assertTrue(jarvis.pausedAfterFailures)
        assertNull("a conversation id is a UUID", readJob(json(withField(prompt, "conversation_id", "\"../x\""))))
        val disk = readJob(json("""{"id":"$j1","kind":"disk_check","cadence":"daily","at_minute":360,"enabled":true,"revision":1,"params":{"threshold_percent":85}}"""))!!
        assertEquals(85, disk.params.thresholdPercent)
        assertEquals(JobKind.REMINDER, readJob(json("""{"id":"$j1","kind":"reminder","cadence":"once","start_date":"2026-12-01","at_minute":540,"enabled":true,"revision":1,"params":{"title":"Call the bank"}}"""))!!.kind)
    }

    @Test fun `run guards order priorities, keep text as data and skip runs that do not read`() {
        val r = readRuns(json("""{"runs":[$run]}"""))!!.single()
        assertEquals(JobKind.DEPENDENCY_REVIEW, r.kind)
        assertNull("an unknown source reads as none", readRuns(json("""{"runs":[${withField(run, "summary_source", "\"gpt\"")}]}"""))!!.single().summarySource)
        assertEquals("web", readRuns(json("""{"runs":[${withField(run, "summary_source", "\"web\"")}]}"""))!!.single().summarySource)
        assertNull(readRuns(json("""{"runs":{}}""")))
        assertEquals(listOf("a1", "a2"), r.priorities.map { it.ref })
        assertEquals("kept verbatim; the page renders it as text", "<b>Two</b> fixes", r.summary)
        val many = (1..21).joinToString(",") { """{"ref":"a$it","rank":$it,"reason":"x"}""" }
        for (broken in listOf(
            withField(run, "status", "\"done\""),
            withField(run, "trigger", "\"cron\""),
            withField(run, "error_code", "\"Upstream said <b>no</b>\""),
            withField(run, "job_id", "\"j1\""),
            withField(run, "priorities", """[{"ref":"a1","rank":"1","reason":"x"}]"""),
            withField(run, "priorities", "[$many]"),
        )) assertEquals(broken.takeLast(80), listOf(r), readRuns(json("""{"runs":[$broken,$run]}""")))
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

    @Test fun `a research run names its conversation and only https sources`() {
        val research = withField(withField(withField(run, "kind", "\"prompt\""), "summary_source", "\"web\""), "facts", """{
            "conversation_id":"$j1","message_id":"$u1",
            "sources":["https://example.com/a","http://example.com/b","javascript:alert(1)","https://user@example.com/c",5,"https://example.com/a","https://ex ample.com"]}""")
        val detail = readRunDetail(json(research))!!
        assertEquals(j1, detail.conversationId)
        assertEquals(u1, detail.messageId)
        assertEquals(listOf("https://example.com/a"), detail.sources)
        assertEquals(JobKind.PROMPT, detail.run.kind)
        assertNull(readRunDetail(json(withField(research, "facts", """{"conversation_id":"../x"}""")))!!.conversationId)
    }

    @Test fun `error codes get fixed owner-facing wording`() {
        assertTrue(jobErrorText("token_needs_dependabot_alerts_read").contains("\"Dependabot alerts: Read-only\""))
        assertTrue(jobErrorText("not_configured", JobKind.DEPENDENCY_REVIEW).contains("GitHub token or the repository list"))
        // Reminders and disk checks (and an unknown kind) use desktop's generic wording, never GitHub's.
        for (kind in listOf(JobKind.REMINDER, JobKind.DISK_CHECK, null)) {
            assertEquals("Not set up on Core: something this job needs is turned off on your Home Node.", jobErrorText("not_configured", kind))
        }
        assertEquals("Core reported an error this app does not know yet.", jobErrorText("something_new"))
        assertEquals("Core reported an error this app does not know yet.", jobErrorText("toString"))
        // The same code reads per kind.
        assertEquals("Not searched: Research is off, or no subscription worker can run it.", jobErrorText("not_configured", JobKind.PROMPT))
        assertEquals("The web search took longer than 5 minutes and was stopped.", jobErrorText("timed_out", JobKind.PROMPT))
        assertEquals("No Home Node disk could be read.", jobErrorText("disk_unavailable", JobKind.DISK_CHECK))
        assertEquals("Something went wrong in Core during the run.", jobErrorText("internal", JobKind.PROMPT))
        assertTrue(jobErrorText("timed_out").contains("15 minutes"))
        for (code in listOf("private_instruction", "holdings_unavailable", "research_unavailable", "conversation_unavailable", "disk_unavailable", "internal")) {
            assertFalse(code, jobErrorText(code, JobKind.PROMPT).contains("does not know"))
        }
        assertEquals("From a web search", summarySourceLabel("web"))
    }

    @Test fun `schedule text and time conversion`() {
        assertEquals("07:00", minuteToTime(420))
        assertEquals("23:59", minuteToTime(1439))
        assertEquals(450, timeToMinute("07:30"))
        for (bad in listOf("", "24:00", "7:30", "07:60")) assertNull(bad, timeToMinute(bad))
        // 06:00 UTC in November is 07:00 in Amsterdam.
        assertTrue(zonedText("2026-11-16T06:00:00Z").contains("07:00"))
        assertEquals("an unknown time", zonedText("nope"))
    }

    @Test fun `the schedule reads in words`() {
        val p = readJob(json(prompt))!!
        assertEquals("Every 6 months on the 1st at 09:00, from 1 Jan 2027", scheduleText(p, today))
        assertEquals("Every 6 months on the 1st at 09:00", scheduleText(p, LocalDate.of(2027, 2, 1)))
        assertEquals("Once on 1 Dec at 09:00", scheduleText(p.copy(cadence = Cadence.ONCE, every = 1, startDate = "2026-12-01"), today))
        assertEquals("Once at 09:00", scheduleText(p.copy(cadence = Cadence.ONCE, every = 1, startDate = null), today))
        assertEquals("Every 2 weeks on Friday at 08:00", scheduleText(p.copy(cadence = Cadence.WEEKLY, weekday = 5, every = 2, startDate = null, atMinute = 480), today))
        assertEquals("Every Sunday at 21:30", scheduleText(p.copy(cadence = Cadence.WEEKLY, weekday = 7, every = 1, startDate = null, atMinute = 1290), today))
        assertEquals("Daily at 00:00", scheduleText(p.copy(cadence = Cadence.DAILY, every = 1, startDate = null, atMinute = 0), today))
        assertEquals("Every 3 days at 00:00", scheduleText(p.copy(cadence = Cadence.DAILY, every = 3, startDate = null, atMinute = 0), today))
        assertEquals("Monthly on the 31st at 09:00", scheduleText(p.copy(monthDay = 31, every = 1, startDate = null), today))
        assertEquals("2 of 2 runs done", runsText(p.copy(runCount = 2)))
        assertEquals("1 of 1 runs done", runsText(p.copy(runCount = 3, maxRuns = 1)))
        assertNull(runsText(p.copy(maxRuns = null)))
        val jarvis = p.copy(byJarvis = true, expiresAt = "2026-12-13T10:00:00Z")
        assertEquals("Set up by Jarvis · expires 13 Dec", originText(jarvis))
        assertEquals("no year, as desktop", "Set up by Jarvis · expires 5 Jan", originText(jarvis.copy(expiresAt = "2027-01-05T10:00:00Z")))
        assertNull(originText(p))
        assertEquals("Paused after 3 failed runs", stateText(p.copy(enabled = false, pausedAfterFailures = true, failureCount = 5)))
        assertEquals("Paused", stateText(p.copy(enabled = false)))
        // Core disables a job that is done: a one-time job after its slot, or one at its run limit.
        assertEquals("Finished", stateText(p.copy(enabled = false, cadence = Cadence.ONCE, lastSlotAt = "2026-12-01T08:00:00Z")))
        assertEquals("Finished", stateText(p.copy(enabled = false, runCount = 2)))
        assertEquals("Finished", nextRunText(p.copy(enabled = false, runCount = 2)))
        assertEquals("Paused", nextRunText(p.copy(enabled = false)))
        assertEquals("Not scheduled", nextRunText(p.copy(nextRunAt = null)))
        assertEquals("Core paused this job after 3 failed runs in a row. Check the latest run, change the job if needed, then enable it again.", PAUSED_AFTER_FAILURES_NOTE)
        assertEquals("Its runs are deleted too.", deleteNote(p))
        assertEquals("Its runs are deleted too. Its conversation stays in chat.", deleteNote(p.copy(conversationId = j1)))
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(::ordinal))
        val review = readJobList(json("""{"status":"ok","jobs":[$job]}"""))!!.jobs.single()
        assertEquals("1 of 10 jobs", jobCountText(listOf(p, jarvis, review)))
        assertEquals("1 of 10 jobs, not counting any this app cannot show", jobCountText(listOf(p), unsupported = 2))
        assertEquals("1 job this app cannot show yet. Update the app to see it.", unsupportedText(1))
        assertEquals("2 jobs this app cannot show yet. Update the app to see them.", unsupportedText(2))
        assertEquals("Searches the web for your instruction and posts the answer to the job's conversation.", JobKind.PROMPT.detail)
        assertEquals("Energy prices", jobTitle(p))
        assertEquals("Dependency review", jobTitle(review))
    }

    @Test fun `the editor checks Core's bounds`() {
        val ok = newDraft(JobKind.PROMPT).copy(title = "AI news", instruction = "Summarize this week's AI news")
        assertNull(draftError(ok, today))
        for ((draft, why) in listOf(
            ok.copy(title = "") to "title", ok.copy(title = "x".repeat(61)) to "title", ok.copy(title = "a\u200Bb") to "title",
            ok.copy(instruction = "x".repeat(501)) to "instruction", ok.copy(time = "25:00") to "time",
            ok.copy(cadence = Cadence.WEEKLY, every = "105") to "weeks", ok.copy(cadence = Cadence.MONTHLY, every = "25") to "months",
            ok.copy(cadence = Cadence.DAILY, every = "0") to "days", ok.copy(cadence = Cadence.ONCE) to "once needs a day",
            ok.copy(startDate = "2026-1-1") to "date format", ok.copy(startDate = "2029-01-01") to "two years",
            ok.copy(cadence = Cadence.ONCE, startDate = "2026-11-12") to "past", ok.copy(maxRuns = "101") to "max runs",
            newDraft(JobKind.DISK_CHECK).copy(threshold = "49") to "threshold", newDraft(JobKind.DISK_CHECK).copy(threshold = "100") to "threshold",
            newDraft(JobKind.REMINDER).copy(title = "T", text = "x".repeat(501)) to "text",
            newDraft(JobKind.DEPENDENCY_REVIEW).copy(cadence = Cadence.MONTHLY) to "review cadence",
        )) assertTrue(why, draftError(draft, today) != null)
        assertNull(draftError(ok.copy(cadence = Cadence.ONCE, startDate = "2026-11-13"), today))
        assertNull(draftError(ok.copy(cadence = Cadence.ONCE, startDate = "2026-11-12", enabled = false), today))
        assertNull("a run limit is ignored on a one-time job", draftError(ok.copy(cadence = Cadence.ONCE, startDate = "2026-12-01", maxRuns = "x"), today))
        assertNull(draftError(newDraft(JobKind.REMINDER).copy(title = "Call the bank"), today))
        assertNull(draftError(newDraft(JobKind.DISK_CHECK), today))
    }

    private val clientFields = setOf("kind", "cadence", "weekday", "month_day", "every", "start_date", "max_runs", "at_minute", "enabled", "params")

    @Test fun `create bodies match Core's examples and never carry its fields`() {
        val research = newJob(newDraft(JobKind.PROMPT).copy(title = "AI news", instruction = "Summarize this week's AI news", time = "08:00"), today)!!
        assertEquals(json("""{"kind":"prompt","cadence":"weekly","weekday":1,"at_minute":480,"enabled":true,
            "params":{"title":"AI news","instruction":"Summarize this week's AI news","notify":true}}"""), research)
        val reminder = newJob(newDraft(JobKind.REMINDER).copy(title = "Call the bank", text = "Ask about the new card.", cadence = Cadence.ONCE,
            startDate = "2026-12-01", every = "4", maxRuns = "3", time = "09:00"), today)!!
        assertEquals(json("""{"kind":"reminder","cadence":"once","start_date":"2026-12-01","at_minute":540,"enabled":true,
            "params":{"title":"Call the bank","text":"Ask about the new card."}}"""), reminder)
        val disk = newJob(newDraft(JobKind.DISK_CHECK).copy(cadence = Cadence.DAILY, threshold = "85", time = "06:00"), today)!!
        assertEquals(json("""{"kind":"disk_check","cadence":"daily","at_minute":360,"enabled":true,"params":{"threshold_percent":85}}"""), disk)
        val energy = newJob(newDraft(JobKind.PROMPT).copy(title = "Energy prices", instruction = "Dutch energy price outlook", notify = false,
            cadence = Cadence.MONTHLY, every = "6", startDate = "2027-01-01", maxRuns = "2", time = "09:00"), today)!!
        assertEquals(json("""{"kind":"prompt","cadence":"monthly","month_day":1,"every":6,"start_date":"2027-01-01","max_runs":2,"at_minute":540,
            "enabled":true,"params":{"title":"Energy prices","instruction":"Dutch energy price outlook","notify":false}}"""), energy)
        for (body in listOf(research, reminder, disk, energy)) assertTrue(body.keys.toString(), clientFields.containsAll(body.keys))
        // Typed text goes trimmed; a blank reminder text is left out.
        assertEquals(json("""{"title":"AI news","instruction":"Summarize"}"""), JsonObject(newJob(newDraft(JobKind.PROMPT).copy(title = " AI news ", instruction = " Summarize "), today)!!
            .getValue("params").jsonObject.filterKeys { it != "notify" }))
        assertEquals(json("""{"title":"Call"}"""), newJob(newDraft(JobKind.REMINDER).copy(title = "Call ", text = "  "), today)!!.getValue("params"))
        assertNull(newJob(newDraft(JobKind.PROMPT), today))
    }

    @Test fun `a change sends only what changed and never Core's fields`() {
        val p = readJob(json(prompt))!!
        assertNull(jobPatch(p, toDraft(p), today))
        assertEquals("params go whole", json("""{"params":{"title":"Energy prices","instruction":"Dutch energy price outlook","notify":true}}"""),
            jobPatch(p, toDraft(p).copy(notify = true), today))
        assertEquals("cleared fields go as null", json("""{"start_date":null,"max_runs":null}"""), jobPatch(p, toDraft(p).copy(startDate = "", maxRuns = ""), today))
        assertEquals("every falls back to 1 when the cadence changes", json("""{"cadence":"weekly","weekday":1,"every":6}"""),
            jobPatch(p, toDraft(p).copy(cadence = Cadence.WEEKLY), today))
        assertEquals(json("""{"cadence":"daily"}"""), jobPatch(p, toDraft(p).copy(cadence = Cadence.DAILY, every = "1"), today))
        assertEquals("a one-time job has no run limit", json("""{"cadence":"once","start_date":"2026-12-01","max_runs":null}"""),
            jobPatch(p, toDraft(p).copy(cadence = Cadence.ONCE, startDate = "2026-12-01"), today))
        assertEquals(json("""{"month_day":15}"""), jobPatch(p, toDraft(p).copy(monthDay = 15), today))
        assertNull("invalid drafts send nothing", jobPatch(p, toDraft(p).copy(every = "x"), today))
        val changed = jobPatch(p.copy(enabled = false, pausedAfterFailures = true, byJarvis = true, runCount = 2),
            toDraft(p).copy(enabled = true, title = "New", cadence = Cadence.DAILY, every = "2", startDate = "2026-12-01", maxRuns = "5", time = "10:00"), today)!!
        assertTrue(changed.keys.toString(), clientFields.containsAll(changed.keys))
        val disk = readJob(json("""{"id":"$j1","kind":"disk_check","cadence":"daily","at_minute":360,"enabled":true,"revision":1,"params":{"threshold_percent":85}}"""))!!
        assertEquals(json("""{"params":{"threshold_percent":90}}"""), jobPatch(disk, toDraft(disk).copy(threshold = "90"), today))
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
        assertEquals(json("""{"cadence":"daily"}"""), jobPatch(j, toDraft(j).copy(cadence = Cadence.DAILY)))
        assertEquals(json("""{"weekday":3,"at_minute":495,"enabled":false}"""), jobPatch(j, toDraft(j).copy(weekday = 3, time = "08:15", enabled = false)))
        assertEquals(json("""{"params":{"roadmap_id":"r1"}}"""), jobPatch(j, toDraft(j).copy(roadmapId = "r1")))
        val linked = j.copy(params = JobParams(roadmapId = "r1"))
        assertEquals("empty params clear the roadmap", json("""{"params":{}}"""), jobPatch(linked, toDraft(linked).copy(roadmapId = null)))
        val daily = j.copy(cadence = Cadence.DAILY, weekday = null)
        assertEquals(json("""{"cadence":"weekly","weekday":1}"""), jobPatch(daily, toDraft(daily).copy(cadence = Cadence.WEEKLY)))
        // An older Core refuses fields it does not know: a review's body stays as it was.
        assertEquals(
            json("""{"kind":"dependency_review","cadence":"daily","at_minute":420,"enabled":true,"params":{}}"""),
            newJob(newDraft(JobKind.DEPENDENCY_REVIEW).copy(cadence = Cadence.DAILY, weekday = 4, every = "3", startDate = "2026-12-01", maxRuns = "2")),
        )
        assertNull(newJob(newDraft(JobKind.DEPENDENCY_REVIEW).copy(time = "")))
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
        assertEquals("Core refused this: a dependency review already exists.", writeFailureText(ApiResult.HttpError(409, null), update = false))
        // On a create of the other kinds a 409 means no room for another job.
        assertEquals("Core refused this. You have 10 jobs, the most Core keeps. Delete one to add another.",
            writeFailureText(ApiResult.HttpError(409, null), update = false, JobKind.REMINDER))
        assertEquals(CHANGED_ELSEWHERE, writeFailureText(ApiResult.HttpError(409, null), update = true, JobKind.REMINDER))
        for (kind in JobKind.entries) assertEquals(INVALID_TEXT, writeFailureText(ApiResult.HttpError(400, null), update = false, kind))
        assertEquals("Not saved. ${jobFailureText(JobFailure.FAILED)}", writeFailureText(ApiResult.HttpError(422, null), update = false, JobKind.PROMPT))
        assertTrue(writeFailureText(ApiResult.HttpError(503, null), update = true).startsWith("Not saved: Core could not check this just now"))
        assertTrue(jobDeleted(ApiResult.Success(Unit)))
        assertTrue("already gone counts as deleted", jobDeleted(ApiResult.HttpError(404, null)))
        assertFalse(jobDeleted(ApiResult.HttpError(409, null)))
        assertEquals(CHANGED_ELSEWHERE, deleteFailureText(ApiResult.HttpError(409, null)))
        assertEquals(NOT_CONFIRMED, deleteFailureText(ApiResult.InvalidResponse("x")))
        // A 2xx with an oversized or unreadable answer may have landed.
        assertEquals(NOT_CONFIRMED, writeFailureText(ApiResult.InvalidResponse("x"), update = true))
        assertEquals(NOT_CONFIRMED, writeFailureText(ApiResult.InvalidResponse("x"), update = false))
        assertEquals(NOT_CONFIRMED, runNowFailureText(ApiResult.InvalidResponse("x")))
        // A call refused before it was sent is not "not confirmed": nothing reached Core.
        val refused = ApiResult.InvalidResponse(INVALID_JOB_ID)
        assertEquals(NOT_SENT, writeFailureText(refused, update = true))
        assertEquals(NOT_SENT, deleteFailureText(refused))
        assertEquals(NOT_SENT, runNowFailureText(refused))
    }

    @Test fun `a 2xx whose job does not read is not confirmed, never saved`() {
        val p = readJob(json(prompt))!!
        assertEquals("Saved.", writeNotice(ApiResult.Success(p), update = true, JobKind.PROMPT, "Saved."))
        assertEquals("Paused.", writeNotice(ApiResult.Success(p), update = true, JobKind.PROMPT, "Paused."))
        assertEquals(NOT_CONFIRMED, writeNotice(ApiResult.Success(null), update = true, JobKind.PROMPT, "Saved."))
        assertEquals(CHANGED_ELSEWHERE, writeNotice(ApiResult.HttpError(409, null), update = true, JobKind.PROMPT, "Saved."))
        // A wrong-shaped job answer reads as null, so it lands in the branch above.
        assertNull(readJob(json("""{"id":"$j1","ok":true}""")))
    }

    @Test fun `only a Core UUID can go into a jobs URL`() {
        assertTrue(isJobId(j1))
        for (bad in listOf("", "../x", "$j1/run", "$j1?x=1", "j1")) assertEquals(bad, false, isJobId(bad))
    }
}
