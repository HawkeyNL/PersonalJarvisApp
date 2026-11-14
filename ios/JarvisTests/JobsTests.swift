import XCTest
@testable import Jarvis

/// Mirrors desktop/tests/jobs.test.mjs. Neutral test data only.
final class JobsTests: XCTestCase {
    private let j1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71"
    private let u1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c72"

    private func json(_ object: Any) -> String {
        String(decoding: try! JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }

    private func job(_ overrides: [String: Any] = [:]) -> String {
        json(([
            "id": j1, "kind": "dependency_review", "cadence": "weekly", "weekday": 1, "at_minute": 420, "enabled": true,
            "revision": 3, "params": [String: Any](), "last_slot_at": NSNull(), "next_run_at": "2026-11-16T06:00:00Z",
        ] as [String: Any]).merging(overrides) { $1 })
    }

    private func runJSON(_ overrides: [String: Any] = [:]) -> String {
        json(([
            "id": u1, "job_id": j1, "slot_at": "2026-11-09T06:00:00Z", "trigger": "schedule", "status": "ok",
            "error_code": NSNull(), "summary": "<b>Two</b> fixes", "summary_source": "claude",
            "priorities": [["ref": "a2", "rank": 2, "reason": "b"], ["ref": "a1", "rank": 1, "reason": "a"]] as [[String: Any]],
            "started_at": "2026-11-09T06:00:01Z", "finished_at": "2026-11-09T06:01:00Z",
        ] as [String: Any]).merging(overrides) { $1 })
    }

    private func list(_ json: String) -> JobList? {
        guard let list = try? JSONDecoder().decode(JobList.self, from: Data(json.utf8)), Jobs.valid(list) else { return nil }
        return list
    }

    private func runs(_ json: String) -> JobRunList? {
        guard let runs = try? JSONDecoder().decode(JobRunList.self, from: Data(json.utf8)), Jobs.valid(runs) else { return nil }
        return runs
    }

    func testJobListAcceptsCoresShapeAndFailsClosed() throws {
        let ok = try XCTUnwrap(list(#"{"status":"ok","time_zone":"Europe/Amsterdam","jobs":[\#(job())]}"#))
        XCTAssertTrue(ok.configured)
        XCTAssertNil(ok.jobs[0].roadmapId)
        XCTAssertEqual(ok.unsupported, 0)
        XCTAssertEqual(list(#"{"status":"not_configured","jobs":[\#(job(["params": ["roadmap_id": "r1"]]))]}"#)?.jobs[0].roadmapId, "r1")
        XCTAssertEqual(list(#"{"status":"not_configured","jobs":[]}"#)?.configured, false)
        let shapes: [String] = ["null", "[]", #"{"jobs":[\#(job())]}"#, #"{"status":"ok"}"#, #"{"status":"maybe","jobs":[]}"#,
                                #"{"status":"ok","jobs":{}}"#]
        for json in shapes { XCTAssertNil(list(json), json) }
    }

    func testOneJobTheAppCannotShowNeverFailsTheList() throws {
        // A newer Core's kind or cadence, and entries that break the contract, are left out and counted.
        let fields: [[String: Any]] = [
            ["kind": "weather"], ["cadence": "hourly"], ["weekday": 8], ["month_day": 32], ["every": 0], ["max_runs": 101],
            ["at_minute": 1440], ["revision": "3"], ["params": ["roadmap_id": 5]], ["id": "../x"],
        ]
        let entries = [job(["colour": "blue", "params": ["roadmap_id": "r1", "future": true]])] + fields.map { job($0) } + ["7"]
        let value = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(entries.joined(separator: ","))]}"#))
        XCTAssertEqual(value.jobs.count, 1, "unknown fields are ignored")
        XCTAssertEqual(value.jobs[0].roadmapId, "r1")
        XCTAssertEqual(value.unsupported, fields.count + 1)
        let most = Array(repeating: job(), count: 100).joined(separator: ",")
        XCTAssertEqual(list(#"{"status":"ok","jobs":[\#(most)]}"#)?.jobs.count, 100)
        let many = Array(repeating: job(), count: 101).joined(separator: ",")
        XCTAssertNil(list(#"{"status":"ok","jobs":[\#(many)]}"#), "at most 100 entries, as desktop")
    }

    func testNewKindsReadWithCoresOwnFields() throws {
        let prompt = job([
            "kind": "prompt", "cadence": "monthly", "weekday": NSNull(), "month_day": 1, "every": 6, "start_date": "2099-01-01",
            "max_runs": 2, "run_count": 2, "at_minute": 540, "enabled": false,
            "params": ["title": "Energy prices", "instruction": "Dutch energy price outlook", "notify": false],
            "origin": "jarvis", "owner_id": "u", "conversation_id": "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c79",
            "expires_at": "2026-12-13T08:00:00Z", "failure_count": 3, "paused_reason": "paused_after_failures",
            "created_at": "2026-11-13T08:00:00Z", "updated_at": "2026-11-13T08:00:00Z", "next_run_at": NSNull(),
        ])
        let reminder = job(["kind": "reminder", "cadence": "once", "weekday": NSNull(), "start_date": "2026-12-01", "at_minute": 540,
                            "params": ["title": "Call the bank"], "origin": "owner", "id": "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c73"])
        let disk = job(["kind": "disk_check", "cadence": "daily", "weekday": NSNull(), "params": ["threshold_percent": 85],
                        "id": "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c74"])
        let value = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(prompt),\#(reminder),\#(disk),\#(job(["id": u1]))]}"#))
        XCTAssertEqual(value.unsupported, 0)
        XCTAssertEqual(value.ownerJobs, 2, "neither Jarvis's job nor the dependency review counts")
        let research = value.jobs[0]
        XCTAssertEqual(research.title, "Energy prices")
        XCTAssertEqual(research.conversation_id, UUID(uuidString: "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c79"))
        XCTAssertEqual(Jobs.scheduleText(research), "Every 6 months on the 1st at 09:00, from 1 Jan 2099")
        XCTAssertEqual(Jobs.runsDoneText(research), "2 of 2 runs done")
        XCTAssertEqual(Jobs.originText(research), "Set up by Jarvis · expires 13 Dec")
        XCTAssertEqual(Jobs.stateText(research), "Paused after 3 failed runs")
        XCTAssertEqual(Jobs.stateLabel(research), "Paused after 3 failed runs", "not Finished, though its runs are used up")
        XCTAssertEqual(value.jobs[1].title, "Call the bank")
        XCTAssertNil(Jobs.originText(value.jobs[1]))
        XCTAssertNil(Jobs.runsDoneText(value.jobs[1]))
        XCTAssertEqual(value.jobs[2].title, "Disk check")
        XCTAssertEqual(JobDraft(value.jobs[2]).threshold, 85)
        XCTAssertEqual(Jobs.stateText(value.jobs[3]), "Next run \(Jobs.zonedText("2026-11-16T06:00:00Z"))")
    }

    func testRunsOrderPrioritiesKeepTextAsDataAndSkipWhatTheyCannotRead() throws {
        let first = try XCTUnwrap(runs(#"{"runs":[\#(runJSON())]}"#)?.runs.first)
        XCTAssertEqual(first.ranked.map(\.ref), ["a1", "a2"])
        XCTAssertEqual(first.summary, "<b>Two</b> fixes", "kept verbatim; the page shows it as text")
        XCTAssertEqual(first.summary_source, .claude)
        // A run this app cannot read (a newer status or trigger, a field out of bounds) is left
        // out; the others still show.
        let many = (1...21).map { ["ref": "a\($0)", "rank": $0, "reason": "x"] as [String: Any] }
        let broken: [[String: Any]] = [
            ["status": "done"], ["trigger": "cron"], ["error_code": "Upstream said <b>no</b>"],
            ["job_id": "j1"], ["priorities": [["ref": "a1", "rank": "1", "reason": "x"]]], ["priorities": many],
        ]
        for fields in broken {
            let list = try XCTUnwrap(runs(#"{"runs":[\#(runJSON(fields)),\#(runJSON(["id": j1]))]}"#), "\(fields.keys)")
            XCTAssertEqual(list.runs.map(\.id.uuidString), [j1.uppercased()], "\(fields.keys)")
        }
        // An unknown summary source only drops the label.
        for source in ["gpt", 5] as [Any] {
            let run = try XCTUnwrap(runs(#"{"runs":[\#(runJSON(["summary_source": source]))]}"#)?.runs.first)
            XCTAssertNil(run.summary_source)
            XCTAssertEqual(run.summary, "<b>Two</b> fixes")
        }
        XCTAssertNil(try XCTUnwrap(runs(#"{"runs":[\#(runJSON(["summary_source": NSNull()]))]}"#)?.runs.first).summary_source)
        let tooMany = Array(repeating: runJSON(), count: 51).joined(separator: ",")
        XCTAssertNil(runs(#"{"runs":[\#(tooMany)]}"#), "at most 50 entries")
        for json in ["null", "[]", #"{"runs":{}}"#] { XCTAssertNil(runs(json), json) }
    }

    func testRunReportResolvesFactsAndKeepsRepoErrors() throws {
        let facts: [String: Any] = [
            "alerts": [["ref": "a1", "repo": "o/r", "number": 4, "package": "left-pad", "severity": "high", "patched": "1.3.0",
                        "ghsa": "GHSA-x"] as [String: Any], "junk"] as [Any],
            "pull_requests": [["ref": "p1", "repo": "o/r", "number": 9, "package": "serde", "from": "1.0.1", "to": "1.0.2"] as [String: Any]],
            "errors": [["repo": "o/s", "error_code": "token_needs_dependabot_alerts_read"], ["repo": "o/t", "error_code": "Bad text"]],
            "truncated": true,
        ]
        let detail = runJSON(["status": "partial", "error_code": "token_needs_dependabot_alerts_read", "facts": facts])
        let report = try JSONDecoder().decode(JobRunReport.self, from: Data(detail.utf8))
        XCTAssertEqual(report.facts.map(\.title), ["left-pad (high)", "serde 1.0.1 → 1.0.2"])
        XCTAssertEqual(report.facts[0].detail, "o/r#4 · fixed in 1.3.0 · GHSA-x")
        XCTAssertEqual(report.errors.map(\.repo), ["o/s"])
        XCTAssertTrue(report.truncated)
        // Without facts (a failed or missed run) the report still reads.
        XCTAssertTrue(try JSONDecoder().decode(JobRunReport.self, from: Data(runJSON(["facts": NSNull()]).utf8)).facts.isEmpty)
        // As desktop: a source this app does not know keeps the report, without a label; a status it does not know fails it.
        let unknownSource = try JSONDecoder().decode(JobRunReport.self, from: Data(runJSON(["summary_source": "oracle"]).utf8))
        XCTAssertNil(unknownSource.run.summary_source)
        XCTAssertEqual(unknownSource.run.summary, "<b>Two</b> fixes")
        XCTAssertThrowsError(try JSONDecoder().decode(JobRunReport.self, from: Data(runJSON(["status": "paused"]).utf8)))
    }

    func testErrorCodesGetFixedWording() {
        XCTAssertTrue(Jobs.errorText("token_needs_dependabot_alerts_read").contains("\"Dependabot alerts: Read-only\""))
        XCTAssertTrue(Jobs.errorText("not_configured", kind: .dependencyReview).contains("GitHub token or the repository list"))
        // A reminder or disk check that is not set up gets the general wording, never GitHub's.
        for kind: JobKind? in [.reminder, .diskCheck, nil] {
            XCTAssertEqual(Jobs.errorText("not_configured", kind: kind),
                           "Not set up on Core: something this job needs is turned off on your Home Node.")
        }
        XCTAssertEqual(Jobs.errorText("something_new"), "Core reported an error this app does not know yet.")
    }

    func testScheduleInWordsAndTimeConversion() throws {
        XCTAssertEqual(Jobs.timeText(420), "07:00")
        XCTAssertEqual(Jobs.timeText(1439), "23:59")
        let weekly = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["weekday": 7, "at_minute": 1290]))]}"#)?.jobs[0])
        XCTAssertEqual(Jobs.scheduleText(weekly), "Every Sunday at 21:30")
        let daily = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["cadence": "daily", "weekday": NSNull(), "at_minute": 0]))]}"#)?.jobs[0])
        XCTAssertEqual(Jobs.scheduleText(daily), "Daily at 00:00")
        var draft = JobDraft()
        draft.weekday = 5; draft.every = 2; draft.minute = 480
        XCTAssertEqual(Jobs.scheduleText(draft), "Every 2 weeks on Friday at 08:00")
        draft.cadence = .daily; draft.every = 3; draft.minute = 420
        XCTAssertEqual(Jobs.scheduleText(draft), "Every 3 days at 07:00")
        draft.cadence = .monthly; draft.every = 1; draft.monthDay = 31; draft.minute = 0
        XCTAssertEqual(Jobs.scheduleText(draft), "Monthly on the 31st at 00:00")
        draft.monthDay = 1; draft.every = 6; draft.minute = 540
        XCTAssertEqual(Jobs.scheduleText(draft), "Every 6 months on the 1st at 09:00")
        // A repeating job names its start date only while it is ahead.
        draft.startDate = "2099-01-01"
        XCTAssertEqual(Jobs.scheduleText(draft), "Every 6 months on the 1st at 09:00, from 1 Jan 2099")
        draft.startDate = "2020-01-01"
        XCTAssertEqual(Jobs.scheduleText(draft), "Every 6 months on the 1st at 09:00")
        let year = Jobs.today().prefix(4)
        draft.cadence = .once; draft.startDate = "\(year)-12-01"; draft.minute = 540
        XCTAssertEqual(Jobs.scheduleText(draft), "Once on 1 Dec at 09:00")
        draft.startDate = "2099-12-01"
        XCTAssertEqual(Jobs.scheduleText(draft), "Once on 1 Dec 2099 at 09:00")
        draft.startDate = nil
        XCTAssertEqual(Jobs.scheduleText(draft), "Once at 09:00")
        XCTAssertEqual([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31].map(Jobs.ordinal),
                       ["1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"])
        // 06:00 UTC in November is 07:00 in Amsterdam.
        XCTAssertTrue(Jobs.zonedText("2026-11-16T06:00:00Z").hasSuffix("07:00"))
        XCTAssertEqual(Jobs.zonedText("nope"), "an unknown time")
        for minute in [0, 59, 120, 150, 420, 1439] { XCTAssertEqual(Jobs.minute(of: Jobs.date(minute: minute)), minute) }
        for day in ["2026-03-29", "2026-10-25", "2028-02-29"] { XCTAssertEqual(Jobs.date(day: day).map(Jobs.day(of:)), day) }
        for day in ["2026-02-30", "2026-1-05", "nope", ""] { XCTAssertNil(Jobs.date(day: day), day) }
    }

    func testTheEditorSendsOnlyWhatChangedAndNeverADailyWeekday() throws {
        let j = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job())]}"#)?.jobs[0])
        XCTAssertNil(Jobs.patch(j, JobDraft(j)))
        var draft = JobDraft(j)
        draft.cadence = .daily
        XCTAssertEqual(Jobs.patch(j, draft), JobPatch(cadence: .daily))
        draft = JobDraft(j)
        draft.weekday = 3; draft.minute = 495; draft.enabled = false
        XCTAssertEqual(Jobs.patch(j, draft), JobPatch(weekday: 3, at_minute: 495, enabled: false))
        draft = JobDraft(j)
        draft.roadmapId = "r1"
        XCTAssertEqual(Jobs.patch(j, draft), JobPatch(params: JobParamsBody(roadmap_id: "r1")))
        let linked = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["params": ["roadmap_id": "r1"]]))]}"#)?.jobs[0])
        draft = JobDraft(linked)
        draft.roadmapId = nil
        let clear = try XCTUnwrap(Jobs.patch(linked, draft))
        XCTAssertEqual(String(decoding: try JSONEncoder().encode(clear), as: UTF8.self), #"{"params":{}}"#, "empty params clear the roadmap")
        let daily = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["cadence": "daily", "weekday": NSNull()]))]}"#)?.jobs[0])
        draft = JobDraft(daily)
        draft.cadence = .weekly
        XCTAssertEqual(Jobs.patch(daily, draft), JobPatch(cadence: .weekly, weekday: 1))
        draft = JobDraft()
        draft.cadence = .daily; draft.weekday = 4
        let body = Jobs.newJob(.dependencyReview, draft)
        XCTAssertNil(body.weekday)
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: Any]
        XCTAssertEqual(json?.keys.sorted(), ["at_minute", "cadence", "enabled", "kind", "params"])
    }

    private func keys(_ body: some Encodable) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: Any])
    }

    private func sorted(_ body: some Encodable) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return String(decoding: try encoder.encode(body), as: UTF8.self)
    }

    /// Core's own fields: a body that names one is refused.
    private let serverSet: Set<String> = ["id", "revision", "origin", "owner_id", "conversation_id", "expires_at", "run_count",
                                          "failure_count", "paused_reason", "created_at", "updated_at", "next_run_at", "last_slot_at"]

    func testNewKindsSendOnlyTheirOwnFields() throws {
        var draft = JobDraft()
        draft.title = "  AI news "; draft.instruction = "Summarize this week's AI news"; draft.weekday = 3
        // A one-time job's leftovers (every, run limit) and other kinds' fields are never sent.
        draft.text = "ignored"; draft.threshold = 90; draft.roadmapId = "r1"
        let prompt = try keys(Jobs.newJob(.prompt, draft))
        XCTAssertEqual(prompt.keys.sorted(), ["at_minute", "cadence", "enabled", "kind", "params", "weekday"])
        XCTAssertEqual(try sorted(Jobs.newJob(.prompt, draft).params), #"{"instruction":"Summarize this week's AI news","notify":true,"title":"AI news"}"#)

        draft.cadence = .once; draft.startDate = "2026-12-01"; draft.every = 3; draft.maxRuns = 4; draft.text = ""
        let reminder = try keys(Jobs.newJob(.reminder, draft))
        XCTAssertEqual(reminder.keys.sorted(), ["at_minute", "cadence", "enabled", "kind", "params", "start_date"])
        XCTAssertEqual(try sorted(Jobs.newJob(.reminder, draft).params), #"{"title":"AI news"}"#, "no text: none sent")

        draft.cadence = .monthly; draft.monthDay = 1; draft.every = 6; draft.maxRuns = 2; draft.notify = false
        let monthly = try keys(Jobs.newJob(.prompt, draft))
        XCTAssertEqual(monthly.keys.sorted(), ["at_minute", "cadence", "enabled", "every", "kind", "max_runs", "month_day", "params", "start_date"])
        XCTAssertTrue(try sorted(Jobs.newJob(.prompt, draft).params).contains(#""notify":false"#))

        let disk = try keys(Jobs.newJob(.diskCheck, draft))
        XCTAssertEqual(try sorted(Jobs.newJob(.diskCheck, draft).params), #"{"threshold_percent":90}"#)
        // A dependency review keeps its old schedules: no every, start date or run limit.
        draft.cadence = .weekly
        XCTAssertEqual(try keys(Jobs.newJob(.dependencyReview, draft)).keys.sorted(), ["at_minute", "cadence", "enabled", "kind", "params", "weekday"])
        for body in [prompt, reminder, monthly, disk] { XCTAssertTrue(serverSet.isDisjoint(with: body.keys)) }
    }

    func testChangesToNewKindsSendWholeParamsAndNullsToClear() throws {
        let stored = job([
            "kind": "prompt", "cadence": "weekly", "weekday": 5, "every": 2, "start_date": "2026-11-13", "max_runs": 4, "run_count": 1,
            "params": ["title": "AI news", "instruction": "Summarize"], "origin": "jarvis", "conversation_id": u1,
            "expires_at": "2026-12-13T08:00:00Z", "failure_count": 3, "paused_reason": "paused_after_failures", "enabled": false,
        ])
        let j = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(stored)]}"#)?.jobs[0])
        XCTAssertNil(Jobs.patch(j, JobDraft(j)), "a stored prompt without notify reads as notify on")
        var draft = JobDraft(j)
        draft.title = "AI news, weekly"
        XCTAssertEqual(Jobs.patch(j, draft), JobPatch(params: JobParamsBody(title: "AI news, weekly", instruction: "Summarize", notify: true)))
        draft = JobDraft(j)
        draft.startDate = nil; draft.maxRuns = nil
        let clear = try XCTUnwrap(Jobs.patch(j, draft))
        XCTAssertEqual(try sorted(clear), #"{"max_runs":null,"start_date":null}"#)
        // Core sets `every` back to 1 when the cadence changes: only another value is sent.
        draft = JobDraft(j)
        draft.cadence = .monthly; draft.every = 1
        XCTAssertEqual(Jobs.patch(j, draft), JobPatch(cadence: .monthly, month_day: 1))
        draft.every = 2
        XCTAssertEqual(Jobs.patch(j, draft), JobPatch(cadence: .monthly, month_day: 1, every: 2))
        // To a one-time job: no run limit, every 1.
        draft = JobDraft(j)
        draft.cadence = .once; draft.startDate = "2026-12-01"
        XCTAssertEqual(Jobs.patch(j, draft), JobPatch(cadence: .once, start_date: .some("2026-12-01"), max_runs: .some(nil)))
        draft = JobDraft(j)
        draft.enabled = true
        let enable = try XCTUnwrap(Jobs.patch(j, draft))
        XCTAssertEqual(enable, JobPatch(enabled: true))
        XCTAssertTrue(serverSet.isDisjoint(with: try keys(enable).keys))
        XCTAssertTrue(serverSet.isDisjoint(with: try keys(JobPatch(cadence: .daily, weekday: 1, month_day: 1, every: 2, start_date: .some(nil),
                                                                   max_runs: .some(3), at_minute: 1, enabled: true, params: JobParamsBody())).keys))
    }

    func testTheEditorExplainsCoresBoundsEarly() {
        var draft = JobDraft()
        XCTAssertEqual(Jobs.issue(.prompt, draft), "Give it a title of 1 to 60 characters on one line.")
        draft.title = String(repeating: "a", count: 61)
        XCTAssertNotNil(Jobs.issue(.reminder, draft))
        draft.title = "Two\nlines"
        XCTAssertNotNil(Jobs.issue(.reminder, draft))
        draft.title = "Hid\u{200B}den"
        XCTAssertNotNil(Jobs.issue(.reminder, draft))
        draft.title = "Call the bank"
        XCTAssertNil(Jobs.issue(.reminder, draft), "a reminder's text is optional")
        draft.text = String(repeating: "a", count: 501)
        XCTAssertEqual(Jobs.issue(.reminder, draft), "The text can be at most 500 characters on one line.")
        XCTAssertEqual(Jobs.issue(.prompt, draft), "Say what to research in 1 to 500 characters on one line.")
        draft.instruction = "Dutch energy price outlook"
        XCTAssertNil(Jobs.issue(.prompt, draft))
        draft.cadence = .once
        XCTAssertEqual(Jobs.issue(.prompt, draft), "Pick the day it runs.")
        draft.startDate = Jobs.today(adding: -1)
        XCTAssertEqual(Jobs.issue(.prompt, draft), "Pick a day from today on.")
        draft.enabled = false
        XCTAssertNil(Jobs.issue(.prompt, draft), "a paused one-time job may be in the past")
        draft.startDate = Jobs.today(adding: Jobs.maxStartDays + 1)
        XCTAssertEqual(Jobs.issue(.prompt, draft), "Pick a date at most two years ahead.")
        draft.cadence = .weekly; draft.startDate = nil; draft.every = 105
        XCTAssertEqual(Jobs.issue(.diskCheck, draft), "Repeat every 1 to 104 weeks.")
        draft.cadence = .daily; draft.every = 366
        XCTAssertEqual(Jobs.issue(.diskCheck, draft), "Repeat every 1 to 365 days.")
        draft.cadence = .monthly; draft.every = 25
        XCTAssertEqual(Jobs.issue(.diskCheck, draft), "Repeat every 1 to 24 months.")
        draft.cadence = .weekly; draft.every = 104; draft.maxRuns = 101
        XCTAssertEqual(Jobs.issue(.diskCheck, draft), "Stop after 1 to 100 runs, or leave it empty.")
        draft.maxRuns = 100; draft.threshold = 49
        XCTAssertEqual(Jobs.issue(.diskCheck, draft), "Pick a threshold from 50 to 99 percent.")
        XCTAssertNil(Jobs.issue(.dependencyReview, JobDraft()))
    }

    func testRefusalsGetFixedWording() {
        XCTAssertEqual(Jobs.runNowFailureText(JarvisAPIError.rejected(status: 429, message: nil)), Jobs.runNowRefused)
        XCTAssertTrue(Jobs.runNowFailureText(JarvisAPIError.rejected(status: 409, message: nil)).contains("scheduler is off"))
        // A 409 on a change is always a revision conflict; on a create, a second review or no room.
        XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 409, message: nil), update: true), Jobs.changedElsewhere)
        XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 409, message: nil), update: false),
                       "Core refused this: a dependency review already exists.")
        XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 409, message: "raw"), update: false, kind: .reminder),
                       "Core refused this. You have 10 jobs, the most Core keeps. Delete one to add another.")
        for kind in JobKind.allCases {
            XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 400, message: "raw"), update: false, kind: kind), Jobs.invalidText)
        }
        XCTAssertTrue(Jobs.writeFailureText(JarvisAPIError.rejected(status: 503, message: nil), update: true).hasPrefix("Not saved: Core could not check"))
        XCTAssertTrue(Jobs.writeFailureText(JarvisAPIError.timedOut, update: true).contains("unknown whether the change arrived"))
        XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 405, message: nil), update: true),
                       "Not saved. Needs Jarvis Core v0.0.87 or later. Update Core on your Home Node.")
        // A Core before v0.0.87 refuses a kind it does not know with 422; on a change it stays generic.
        XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 422, message: "raw"), update: false, kind: .reminder),
                       "Not saved: your Core does not know this kind of job yet. Update Core on your Home Node.")
        XCTAssertTrue(Jobs.writeFailureText(JarvisAPIError.rejected(status: 422, message: nil), update: true)
            .hasPrefix("Not saved. Core returned an answer"))
        // A 2xx answer this app could not read: Core may have made the change.
        for error in [JarvisAPIError.invalidResponse, .responseTooLarge] {
            XCTAssertEqual(Jobs.writeFailureText(error, update: true), Jobs.notConfirmed)
            XCTAssertEqual(Jobs.runNowFailureText(error), Jobs.notConfirmed)
            XCTAssertEqual(Jobs.deleteFailureText(error), Jobs.notConfirmed)
        }
        XCTAssertEqual(Jobs.deleteFailureText(JarvisAPIError.rejected(status: 409, message: nil)), Jobs.changedElsewhere)
        XCTAssertTrue(Jobs.deleteFailureText(JarvisAPIError.unreachable).contains("unknown whether the job was deleted"))
    }

    func testResearchCodesAndReportGetTheirOwnWording() throws {
        XCTAssertTrue(Jobs.errorText("not_configured", kind: .prompt).contains("Research is off"))
        XCTAssertTrue(Jobs.errorText("timed_out", kind: .prompt).contains("5 minutes"))
        XCTAssertTrue(Jobs.errorText("timed_out", kind: .reminder).contains("15 minutes"))
        for code in ["private_instruction", "holdings_unavailable", "research_unavailable", "conversation_unavailable",
                     "disk_unavailable", "internal"] {
            XCTAssertNotEqual(Jobs.errorText(code, kind: .prompt), Jobs.errorText("something_new"), code)
        }
        let facts: [String: Any] = [
            "conversation_id": "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c79", "message_id": "m1",
            "sources": ["https://example.com/a", "http://example.com/b", "https://user:pw@example.com/c", "javascript:alert(1)",
                        "https://example.com/a", 5, "https://example.org/e f"] as [Any],
        ]
        let detail = runJSON(["summary": "**Prices** rise", "summary_source": "web", "priorities": [Any](), "facts": facts])
        let report = try JSONDecoder().decode(JobRunReport.self, from: Data(detail.utf8))
        XCTAssertEqual(report.sources.map(\.url.absoluteString), ["https://example.com/a"], "https only, distinct, ASCII host, no user info")
        XCTAssertEqual(report.conversationId, UUID(uuidString: "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c79"))
        XCTAssertEqual(report.run.summary, "**Prices** rise", "kept verbatim; the page shows it as text")
        XCTAssertEqual(report.run.summary_source?.label, "From a web search")
        let many = (1...25).map { "https://example.com/\($0)" }
        let capped = try JSONDecoder().decode(JobRunReport.self, from: Data(runJSON(["facts": ["sources": many]]).utf8))
        XCTAssertEqual(capped.sources.count, 20)
        let bad = try JSONDecoder().decode(JobRunReport.self, from: Data(runJSON(["facts": ["conversation_id": "../x"]]).utf8))
        XCTAssertNil(bad.conversationId)
    }

    /// Desktop's wording (jobs.ts), word for word.
    func testStateCountAndNotesUseDesktopWording() throws {
        let done = job(["kind": "reminder", "cadence": "once", "weekday": NSNull(), "start_date": "2026-11-01", "enabled": false,
                        "last_slot_at": "2026-11-01T08:00:00Z", "params": ["title": "Call"], "conversation_id": u1])
        let once = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(done)]}"#)?.jobs[0])
        XCTAssertEqual(Jobs.stateLabel(once), "Finished")
        XCTAssertEqual(Jobs.stateText(once), "Finished")
        XCTAssertEqual(Jobs.deleteNote(once), "Its runs are deleted too. Its conversation stays in chat.")
        let used = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["enabled": false, "max_runs": 3, "run_count": 3]))]}"#)?.jobs[0])
        XCTAssertEqual(Jobs.stateLabel(used), "Finished")
        let paused = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["enabled": false]))]}"#)?.jobs[0])
        XCTAssertEqual(Jobs.stateLabel(paused), "Paused")
        XCTAssertEqual(Jobs.deleteNote(paused), "Its runs are deleted too.")
        XCTAssertEqual(Jobs.stateLabel(try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job())]}"#)?.jobs[0])), "Enabled")
        XCTAssertEqual(Jobs.countText(3, unsupported: 0), "3 of 10 jobs")
        XCTAssertEqual(Jobs.countText(3, unsupported: 1), "3 of 10 jobs, not counting any this app cannot show")
        XCTAssertEqual(Jobs.unsupportedText(1), "1 job this app cannot show yet. Update the app to see it.")
        XCTAssertEqual(Jobs.unsupportedText(2), "2 jobs this app cannot show yet. Update the app to see them.")
        XCTAssertEqual(Jobs.errorText("internal"), "Something went wrong in Core during the run.")
        XCTAssertEqual(Jobs.errorText("not_configured", kind: .prompt), "Not searched: Research is off, or no subscription worker can run it.")
        XCTAssertEqual(JobKind.reminder.detail, "Posts your text to the job's conversation and sends you a push with the title.")
        XCTAssertTrue(Jobs.zoneAvailable)
        XCTAssertEqual(Jobs.zoneUnavailable, "This device cannot read the Europe/Amsterdam time zone, so jobs cannot be shown.")
        XCTAssertEqual(Jobs.privacyHint,
                       "It leaves your Home Node as a web search, so leave out personal or financial details and symbols you hold.")
    }
}

/// Job writes through the app model against a fixture Core: the lock and session gates.
@MainActor
final class JobWriteModelTests: XCTestCase {
    private func signedIn() -> JarvisAppModel {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [JobCoreProtocol.self]
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: URLSession(configuration: configuration))
        return JarvisAppModel(signedInChat: ChatService(api: api, tokenLoader: { "fixture-session-not-a-secret" }), api: api, realtime: false)
    }

    func testALockedAppSendsNoJobWrite() async {
        JobCoreProtocol.reset(status: 202)
        let model = signedIn()
        model.lockWhenBackgrounded()
        do {
            try await model.runJobNow(UUID())
            XCTFail("sent while locked")
        } catch {}
        XCTAssertEqual(JobCoreProtocol.requests(), [])
    }

    func testA401SignsOutOnlyTheSessionItWasSentFor() async throws {
        JobCoreProtocol.reset(status: 401)
        let model = signedIn()
        do { try await model.runJobNow(UUID()) } catch {}
        XCTAssertFalse(model.isAuthenticated)

        // The 401 arrives after a lock started a new presentation: the session stays.
        JobCoreProtocol.reset(status: 401, delay: 0.5)
        let later = signedIn()
        let write = Task { try await later.runJobNow(UUID()) }
        var tries = 0
        while JobCoreProtocol.requests().isEmpty, tries < 300 { tries += 1; try await Task.sleep(nanoseconds: 10_000_000) }
        later.lockWhenBackgrounded()
        _ = await write.result
        XCTAssertEqual(JobCoreProtocol.requests().count, 1)
        XCTAssertTrue(later.isAuthenticated, "a late 401 from before the lock keeps the session")
    }

    func testDeleteNamesTheRevisionAndIsGatedLikeEveryWrite() async throws {
        let id = UUID()
        JobCoreProtocol.reset(status: 200)
        let model = signedIn()
        try await model.deleteJob(id, revision: 4)
        XCTAssertEqual(JobCoreProtocol.requests(), ["DELETE /v1/jobs/\(id.uuidString.lowercased())?expected_revision=4"])

        JobCoreProtocol.reset(status: 200)
        model.lockWhenBackgrounded()
        do {
            try await model.deleteJob(id, revision: 4)
            XCTFail("sent while locked")
        } catch {}
        XCTAssertEqual(JobCoreProtocol.requests(), [])
    }

    private func jobBody(_ id: UUID) -> String {
        #"{"id":"\#(id.uuidString.lowercased())","kind":"reminder","cadence":"daily","at_minute":420,"enabled":false,"revision":2,"params":{"title":"Call"}}"#
    }

    func testAnUnreadableOrWrongShaped2xxWriteIsNotConfirmed() async throws {
        let id = UUID()
        // Not JSON, empty, JSON that is not a job, and another job than the one changed.
        for body in ["<html>ok</html>", "", "{}", #"{"status":"ok"}"#, jobBody(UUID())] {
            JobCoreProtocol.reset(status: 200, body: body)
            let model = signedIn()
            do {
                try await model.updateJob(id, revision: 1, patch: JobPatch(enabled: false))
                XCTFail("not a confirmation: \(body)")
            } catch {
                XCTAssertEqual(Jobs.writeFailureText(error, update: true), Jobs.notConfirmed, body)
            }
            XCTAssertEqual(JobCoreProtocol.requests().count, 1)
        }
        for body in ["{}", #"{"id":"x"}"#] {
            JobCoreProtocol.reset(status: 201, body: body)
            do {
                try await signedIn().createJob(Jobs.newJob(.reminder, JobDraft()))
                XCTFail("not a confirmation: \(body)")
            } catch {
                XCTAssertEqual(Jobs.writeFailureText(error, update: false, kind: .reminder), Jobs.notConfirmed, body)
            }
        }
        // The job itself confirms the write.
        JobCoreProtocol.reset(status: 200, body: jobBody(id))
        try await signedIn().updateJob(id, revision: 1, patch: JobPatch(enabled: false))
        JobCoreProtocol.reset(status: 201, body: jobBody(UUID()))
        try await signedIn().createJob(Jobs.newJob(.reminder, JobDraft()))
    }
}

private final class JobCoreProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var log: [String] = []
    private static var status = 202
    private static var delay: TimeInterval = 0
    private static var body = "{}"

    static func reset(status: Int, delay: TimeInterval = 0, body: String = "{}") {
        lock.withLock { log = []; self.status = status; self.delay = delay; self.body = body }
    }
    static func requests() -> [String] { lock.withLock { log } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, delay, body) = Self.lock.withLock {
            Self.log.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")" + (request.url?.query.map { "?\($0)" } ?? ""))
            return (Self.status, Self.delay, Self.body)
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [self] in
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() { }
}
