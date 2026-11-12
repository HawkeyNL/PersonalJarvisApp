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
        XCTAssertEqual(list(#"{"status":"not_configured","jobs":[\#(job(["params": ["roadmap_id": "r1"]]))]}"#)?.jobs[0].roadmapId, "r1")
        XCTAssertEqual(list(#"{"status":"not_configured","jobs":[]}"#)?.configured, false)
        let shapes: [String] = ["null", "[]", #"{"jobs":[\#(job())]}"#, #"{"status":"ok"}"#, #"{"status":"maybe","jobs":[]}"#]
        let fields: [[String: Any]] = [
            ["kind": "free_text"], ["cadence": "hourly"], ["weekday": 8], ["at_minute": 1440], ["revision": "3"],
            ["params": ["roadmap_id": 5]], ["id": "../x"],
        ]
        let broken = shapes + fields.map { #"{"status":"ok","jobs":[\#(job($0))]}"# }
        for json in broken { XCTAssertNil(list(json), json) }
    }

    func testRunsOrderPrioritiesKeepTextAsDataAndFailClosed() throws {
        let first = try XCTUnwrap(runs(#"{"runs":[\#(runJSON())]}"#)?.runs.first)
        XCTAssertEqual(first.ranked.map(\.ref), ["a1", "a2"])
        XCTAssertEqual(first.summary, "<b>Two</b> fixes", "kept verbatim; the page shows it as text")
        let many = (1...21).map { ["ref": "a\($0)", "rank": $0, "reason": "x"] as [String: Any] }
        let broken: [[String: Any]] = [
            ["status": "done"], ["trigger": "cron"], ["summary_source": "gpt"], ["error_code": "Upstream said <b>no</b>"],
            ["job_id": "j1"], ["priorities": [["ref": "a1", "rank": "1", "reason": "x"]]], ["priorities": many],
        ]
        for fields in broken { XCTAssertNil(runs(#"{"runs":[\#(runJSON(fields))]}"#), "\(fields.keys)") }
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
    }

    func testErrorCodesGetFixedWording() {
        XCTAssertTrue(Jobs.errorText("token_needs_dependabot_alerts_read").contains("\"Dependabot alerts: Read-only\""))
        XCTAssertTrue(Jobs.errorText("not_configured").contains("GitHub token or the repository list"))
        XCTAssertEqual(Jobs.errorText("something_new"), "Core reported an error this app does not know yet.")
    }

    func testScheduleTextAndTimeConversion() throws {
        XCTAssertEqual(Jobs.timeText(420), "07:00")
        XCTAssertEqual(Jobs.timeText(1439), "23:59")
        let weekly = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["weekday": 7, "at_minute": 1290]))]}"#)?.jobs[0])
        XCTAssertEqual(Jobs.cadenceText(weekly), "Every Sunday at 21:30 (Europe/Amsterdam)")
        let daily = try XCTUnwrap(list(#"{"status":"ok","jobs":[\#(job(["cadence": "daily", "weekday": NSNull(), "at_minute": 0]))]}"#)?.jobs[0])
        XCTAssertEqual(Jobs.cadenceText(daily), "Daily at 00:00 (Europe/Amsterdam)")
        // 06:00 UTC in November is 07:00 in Amsterdam.
        XCTAssertTrue(Jobs.zonedText("2026-11-16T06:00:00Z").hasSuffix("07:00"))
        XCTAssertEqual(Jobs.zonedText("nope"), "an unknown time")
        for minute in [0, 59, 120, 150, 420, 1439] { XCTAssertEqual(Jobs.minute(of: Jobs.date(minute: minute)), minute) }
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

    func testRefusalsGetFixedWording() {
        XCTAssertEqual(Jobs.runNowFailureText(JarvisAPIError.rejected(status: 429, message: nil)), Jobs.runNowRefused)
        XCTAssertTrue(Jobs.runNowFailureText(JarvisAPIError.rejected(status: 409, message: nil)).contains("scheduler is off"))
        // A 409 on a change is always a revision conflict; only a create can mean "exists".
        XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 409, message: nil), update: true), Jobs.changedElsewhere)
        XCTAssertTrue(Jobs.writeFailureText(JarvisAPIError.rejected(status: 409, message: nil), update: false).contains("already exists"))
        XCTAssertTrue(Jobs.writeFailureText(JarvisAPIError.timedOut, update: true).contains("unknown whether the change arrived"))
        XCTAssertEqual(Jobs.writeFailureText(JarvisAPIError.rejected(status: 405, message: nil), update: true),
                       "Not saved. Needs Jarvis Core v0.0.80 or later. Update Core on your Home Node.")
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
}

private final class JobCoreProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var log: [String] = []
    private static var status = 202
    private static var delay: TimeInterval = 0

    static func reset(status: Int, delay: TimeInterval = 0) {
        lock.withLock { log = []; self.status = status; self.delay = delay }
    }
    static func requests() -> [String] { lock.withLock { log } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, delay) = Self.lock.withLock {
            Self.log.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")")
            return (Self.status, Self.delay)
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [self] in
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("{}".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() { }
}
