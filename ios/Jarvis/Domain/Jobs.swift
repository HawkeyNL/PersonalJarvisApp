import Foundation

// Scheduled jobs (/v1/jobs): Core's contract, response checks, the editor's draft and
// the fixed owner-facing wording, as in desktop jobs.ts. Every write names the revision
// it builds on; run text from Core is display only and always shown as plain text;
// error codes are shown only through the fixed wording below.

enum JobKind: String, Codable, CaseIterable {
    case dependencyReview = "dependency_review"

    var label: String { "Dependency review" }
    var detail: String {
        "Reads the open Dependabot alerts and pull requests of your repositories and puts them in order."
    }
}

enum JobCadence: String, Codable { case daily, weekly }

enum JobRunStatus: String, Decodable {
    case running, ok, partial, failed, missed, interrupted

    var label: String {
        switch self {
        case .running: "Running"
        case .ok: "OK"
        case .partial: "Partial"
        case .failed: "Failed"
        case .missed: "Missed"
        case .interrupted: "Interrupted"
        }
    }
}

struct ScheduledJob: Decodable, Identifiable, Equatable {
    struct Params: Decodable, Equatable { var roadmap_id: String? }

    let id: UUID
    let kind: JobKind
    let cadence: JobCadence
    let weekday: Int?
    let at_minute: Int
    let enabled: Bool
    let revision: Int
    let params: Params?
    let last_slot_at: String?
    let next_run_at: String?

    var roadmapId: String? { params?.roadmap_id }
}

struct JobList: Decodable {
    let status: String
    let jobs: [ScheduledJob]

    /// False when Core's scheduler is off: jobs can be changed, but none runs.
    var configured: Bool { status == "ok" }
}

struct JobRun: Decodable, Identifiable {
    enum Trigger: String, Decodable {
        case schedule, owner
        var label: String { self == .schedule ? "Scheduled" : "Run by you" }
    }

    enum SummarySource: String, Decodable {
        case claude, deterministic
        var label: String { self == .claude ? "Written by Claude" : "Written by Core's template" }
    }

    struct Priority: Decodable { let ref: String; let rank: Int; let reason: String }

    let id: UUID
    let job_id: UUID
    let slot_at: String
    let trigger: Trigger
    let status: JobRunStatus
    let error_code: String?
    let summary: String?
    let summary_source: SummarySource?
    let priorities: [Priority]?
    let started_at: String
    let finished_at: String?

    var ranked: [Priority] { (priorities ?? []).sorted { $0.rank < $1.rank } }
}

struct JobRunList: Decodable { let runs: [JobRun] }

/// One run's report: the run, the facts its priorities point at as plain strings, the
/// repositories that could not be read, and whether entries were left out.
struct JobRunReport: Decodable {
    struct Fact { let ref: String; let title: String; let detail: String }
    struct RepoError { let repo: String; let code: String }

    let run: JobRun
    let facts: [Fact]
    let errors: [RepoError]
    let truncated: Bool

    private enum Keys: String, CodingKey { case facts }

    init(from decoder: Decoder) throws {
        run = try JobRun(from: decoder)
        // Facts that do not fit are skipped; no facts (a failed or missed run) is fine.
        let raw = try? decoder.container(keyedBy: Keys.self).decodeIfPresent(RawFacts.self, forKey: .facts)
        facts = raw?.facts ?? []
        errors = raw?.errors ?? []
        truncated = raw?.truncated ?? false
    }

    private struct RawFacts: Decodable {
        let facts: [Fact]
        let errors: [RepoError]
        let truncated: Bool

        private enum Keys: String, CodingKey { case alerts, pull_requests, errors, truncated }
        private typealias Fields = [String: Plain]

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: Keys.self)
            func list(_ key: Keys) -> [Fields] {
                ((try? container.decode([Lenient<Fields>].self, forKey: key)) ?? []).prefix(500).compactMap(\.value)
            }
            var facts: [Fact] = []
            for alert in list(.alerts) {
                guard let ref = Self.ref(alert) else { continue }
                let text = { (key: String) in alert[key]?.text ?? "" }
                let severity = text("severity")
                let title = [text("package"), severity.isEmpty ? "" : "(\(severity))"].filter { !$0.isEmpty }.joined(separator: " ")
                let fixed = text("patched").isEmpty ? "no fixed version yet" : "fixed in \(text("patched"))"
                facts.append(Fact(ref: ref, title: title.isEmpty ? "Alert" : title,
                                  detail: ["\(text("repo"))#\(text("number"))", fixed, text("ghsa")].filter { !$0.isEmpty }.joined(separator: " · ")))
            }
            for pull in list(.pull_requests) {
                guard let ref = Self.ref(pull) else { continue }
                let text = { (key: String) in pull[key]?.text ?? "" }
                let bump = !text("package").isEmpty ? "\(text("package")) \(text("from")) → \(text("to"))"
                    : !text("group").isEmpty ? "\(text("group")) group" : "Pull request"
                facts.append(Fact(ref: ref, title: bump, detail: "\(text("repo"))#\(text("number"))"))
            }
            self.facts = facts
            errors = Array(list(.errors).compactMap { entry -> RepoError? in
                guard let repo = entry["repo"], repo.isString, !repo.tooLong, let code = entry["error_code"],
                      code.isString, Jobs.isCode(code.text) else { return nil }
                return RepoError(repo: repo.text, code: code.text)
            }.prefix(100))
            truncated = (try? container.decode(Bool.self, forKey: .truncated)) == true
        }

        private static func ref(_ fields: Fields) -> String? {
            guard let ref = fields["ref"], ref.isString, ref.text.count <= 16 else { return nil }
            return ref.text
        }
    }
}

/// A fact field as display text: a string of at most 200 characters or a number;
/// anything else is empty.
private struct Plain: Decodable {
    let text: String
    let isString: Bool
    let tooLong: Bool

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let string = try? container.decode(String.self) {
            isString = true
            tooLong = string.count > 200
            text = tooLong ? "" : string
        } else if let number = try? container.decode(Double.self), number.isFinite {
            isString = false
            tooLong = false
            text = number == number.rounded() && abs(number) < 1e15 ? String(Int64(number)) : String(number)
        } else {
            isString = false
            tooLong = false
            text = ""
        }
    }
}

/// An array element that is skipped instead of failing the whole array.
struct Lenient<Value: Decodable>: Decodable {
    let value: Value?
    init(from decoder: Decoder) throws { value = try? Value(from: decoder) }
}

/// The schedule editor's state: the time is minutes after midnight in Europe/Amsterdam.
struct JobDraft: Equatable {
    var cadence = JobCadence.weekly
    var weekday = 1
    var minute = 7 * 60
    var enabled = true
    var roadmapId: String?

    init() {}

    init(_ job: ScheduledJob) {
        cadence = job.cadence
        weekday = job.weekday ?? 1
        minute = job.at_minute
        enabled = job.enabled
        roadmapId = job.roadmapId
    }
}

/// `params` names the roadmap; empty clears it.
struct JobParamsBody: Encodable, Equatable { var roadmap_id: String? }

struct NewJob: Encodable, Equatable {
    let kind: JobKind
    let cadence: JobCadence
    /// Weekly only: Core refuses a weekday on a daily job.
    let weekday: Int?
    let at_minute: Int
    let enabled: Bool
    let params: JobParamsBody
}

/// Only what changed; absent fields are left as they are.
struct JobPatch: Encodable, Equatable {
    var cadence: JobCadence?
    var weekday: Int?
    var at_minute: Int?
    var enabled: Bool?
    var params: JobParamsBody?
}

enum Jobs {
    static let timeZoneName = "Europe/Amsterdam"
    static let timeZone = TimeZone(identifier: timeZoneName) ?? .current
    static let weekdays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    /// The runs read per job: the newest 20.
    static let runsShown = 20

    static let notConfigured =
        "Core's scheduler is off: it could not read the Europe/Amsterdam time zone on your Home Node. Jobs can be changed, but none runs."
    static let runNowRefused = "Not started: a job runs on request at most once an hour, and not while a run is queued or running."
    static let changedElsewhere = "Changed elsewhere, reloaded."
    static let notFound = "This does not exist on your Home Node (any more)."

    static func runsPath(_ job: UUID) -> String { "/v1/jobs/\(job.uuidString.lowercased())/runs?limit=\(runsShown)" }
    static func runPath(_ run: UUID) -> String { "/v1/jobs/runs/\(run.uuidString.lowercased())" }

    // MARK: Response checks: anything unexpected reads as failed (fail closed).

    static func isCode(_ text: String) -> Bool {
        (1...64).contains(text.utf8.count) && text.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 95 }
    }

    private static func fits(_ text: String?, _ max: Int) -> Bool { (text?.count ?? 0) <= max }

    static func valid(_ job: ScheduledJob) -> Bool {
        (job.weekday.map { (1...7).contains($0) } ?? true) && (0...1439).contains(job.at_minute) && job.revision >= 0
            && fits(job.roadmapId, 64) && fits(job.last_slot_at, 64) && fits(job.next_run_at, 64)
    }

    static func valid(_ list: JobList) -> Bool {
        (list.status == "ok" || list.status == "not_configured") && list.jobs.count <= 50 && list.jobs.allSatisfy(valid)
    }

    static func valid(_ run: JobRun) -> Bool {
        let priorities = run.priorities ?? []
        return fits(run.slot_at, 64) && fits(run.started_at, 64) && fits(run.finished_at, 64)
            && (run.error_code.map(isCode) ?? true) && fits(run.summary, 4000) && priorities.count <= 20
            && priorities.allSatisfy { $0.ref.count <= 16 && $0.rank >= 0 && $0.reason.count <= 600 }
    }

    static func valid(_ list: JobRunList) -> Bool { list.runs.count <= 50 && list.runs.allSatisfy(valid) }

    // MARK: Schedule and editor

    static func timeText(_ minute: Int) -> String { String(format: "%02d:%02d", minute / 60, minute % 60) }

    static func cadenceText(_ job: ScheduledJob) -> String {
        let at = "\(timeText(job.at_minute)) (\(timeZoneName))"
        return job.cadence == .daily ? "Daily at \(at)" : "Every \(weekdays[min(max((job.weekday ?? 1) - 1, 0), 6)]) at \(at)"
    }

    private static let zoned: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.timeZone = timeZone
        formatter.dateFormat = "EEE d MMM, HH:mm"
        return formatter
    }()

    /// A moment from Core, shown in the schedule's zone.
    static func zonedText(_ iso: String) -> String { parseCoreDate(iso).map { zoned.string(from: $0) } ?? "an unknown time" }

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    /// The time picker's date for a minute of the day; a fixed winter day, so no DST gap.
    static func date(minute: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 1, day: 5, hour: minute / 60, minute: minute % 60)) ?? Date()
    }

    static func minute(of date: Date) -> Int {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }

    static func newJob(_ kind: JobKind, _ draft: JobDraft) -> NewJob {
        NewJob(kind: kind, cadence: draft.cadence, weekday: draft.cadence == .weekly ? draft.weekday : nil,
               at_minute: draft.minute, enabled: draft.enabled, params: JobParamsBody(roadmap_id: draft.roadmapId))
    }

    /// Only what changed, so an unchanged editor sends nothing (nil). A weekday is sent
    /// only for a weekly job.
    static func patch(_ job: ScheduledJob, _ draft: JobDraft) -> JobPatch? {
        var patch = JobPatch()
        if draft.cadence != job.cadence { patch.cadence = draft.cadence }
        if draft.cadence == .weekly && draft.weekday != job.weekday { patch.weekday = draft.weekday }
        if draft.minute != job.at_minute { patch.at_minute = draft.minute }
        if draft.enabled != job.enabled { patch.enabled = draft.enabled }
        if draft.roadmapId != job.roadmapId { patch.params = JobParamsBody(roadmap_id: draft.roadmapId) }
        return patch == JobPatch() ? nil : patch
    }

    // MARK: Fixed wording

    private static let errorTexts: [String: String] = [
        "not_configured": "Not set up on Core: the GitHub token or the repository list (JARVIS_GITHUB_REPOS) is missing on your Home Node.",
        "timed_out": "The run took longer than 15 minutes and was stopped.",
        "interrupted": "Core stopped during the run, so it did not finish.",
        "invalid_result": "Core refused to store the result of this run.",
        "token_needs_dependabot_alerts_read":
            "GitHub refused the Dependabot alerts. Give the GitHub token the \"Dependabot alerts: Read-only\" permission for the repository, and turn on Dependabot alerts in its settings.",
        "github_access_refused": "GitHub refused the list of pull requests. The repository's alerts were kept.",
        "github_token_refused": "GitHub refused the token. Check that it is valid and has not expired.",
        "github_rate_limited": "GitHub's rate limit was reached. The next run tries again.",
        "github_answer_too_large": "A GitHub answer was larger than 1 MiB and was not read.",
        "github_redirect_refused": "GitHub redirected to another host, which Core refuses.",
        "github_unavailable": "GitHub could not be reached or answered with an error.",
        "roadmap_unavailable": "The roadmap is gone or archived, so no proposal was made.",
        "roadmap_changed": "The roadmap changed while the run read it. The next run proposes again.",
        "roadmap_limit": "The roadmap holds its maximum of items or pending proposals, so no proposal was made.",
        "roadmap_proposal_failed": "The roadmap proposal could not be made.",
    ]

    /// The owner-facing text for an error code; never the code's own words.
    static func errorText(_ code: String) -> String { errorTexts[code] ?? "Core reported an error this app does not know yet." }

    static func status(_ error: Error) -> Int? {
        switch error as? JarvisAPIError {
        case .unauthorized?: 401
        case let .rejected(status, _)?: status
        default: nil
        }
    }

    static func isNetwork(_ error: Error) -> Bool {
        let error = error as? JarvisAPIError
        return error == .unreachable || error == .timedOut
    }

    /// Why a write failed, in desktop's words.
    static func failureText(_ error: Error) -> String {
        if isNetwork(error) { return "No answer from your Home Node. Check the connection." }
        switch status(error) {
        case 401: return "Your session is not valid. Sign in again from Settings."
        case 404: return notFound
        case 405, 501: return "Needs Jarvis Core v0.0.80 or later. Update Core on your Home Node."
        case 503: return "Core could not read the jobs just now. Try again in a minute."
        default: return "Core returned an answer this app does not understand, or an error. Try again later."
        }
    }

    /// The notice after a refused "Run now".
    static func runNowFailureText(_ error: Error) -> String {
        if status(error) == 429 { return runNowRefused }
        if status(error) == 409 { return "Not started: Core's scheduler is off, so no job runs." }
        if isNetwork(error) { return "No answer from your Home Node. It is unknown whether the run was queued; reloaded to check." }
        return "Not started. \(failureText(error))"
    }

    /// The notice after a failed write. A 409 on a change is a revision conflict; on a
    /// create, a second job of the same kind.
    static func writeFailureText(_ error: Error, update: Bool) -> String {
        if status(error) == 409 { return update ? changedElsewhere : "Core refused this: a job of this kind already exists." }
        if status(error) == 400 { return "Core refused this as invalid, for example a roadmap that is archived. Check the fields and try again." }
        if isNetwork(error) { return "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check." }
        return "Not saved. \(failureText(error))"
    }
}

extension Availability {
    /// A read that decoded but breaks the contract reads as failed (fail closed).
    func checked(_ valid: (Value) -> Bool) -> Availability {
        if case let .ok(value) = self, !valid(value) { return .error(.failed) }
        return self
    }
}
