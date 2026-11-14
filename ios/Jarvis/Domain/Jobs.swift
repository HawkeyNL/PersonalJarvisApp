import Foundation

// Scheduled jobs (/v1/jobs): Core's contract, response checks, the editor's draft and
// the fixed owner-facing wording, as in desktop jobs.ts. Every write names the revision
// it builds on; run text from Core is display only and always shown as plain text;
// error codes are shown only through the fixed wording below.

enum JobKind: String, Codable, CaseIterable {
    case prompt, reminder
    case diskCheck = "disk_check"
    case dependencyReview = "dependency_review"

    var label: String {
        switch self {
        case .prompt: "Research"
        case .reminder: "Reminder"
        case .diskCheck: "Disk check"
        case .dependencyReview: "Dependency review"
        }
    }

    var detail: String {
        switch self {
        case .prompt: "Searches the web for your instruction and posts the answer to the job's conversation."
        case .reminder: "Posts your text to the job's conversation and sends you a push with the title."
        case .diskCheck: "Tells you when a Home Node disk is fuller than the threshold. It changes nothing on the host."
        case .dependencyReview: "Reads the open Dependabot alerts and pull requests of your repositories and puts them in order."
        }
    }
}

enum JobCadence: String, Codable, CaseIterable {
    case once, daily, weekly, monthly

    var label: String {
        switch self {
        case .once: "Once"
        case .daily: "Daily"
        case .weekly: "Weekly"
        case .monthly: "Monthly"
        }
    }

    /// `every` counts these: "Every 2 weeks".
    var unit: String {
        switch self {
        case .once, .daily: "days"
        case .weekly: "weeks"
        case .monthly: "months"
        }
    }

    /// Core's bounds for `every`: 1 for a one-time job.
    var everyRange: ClosedRange<Int> {
        switch self {
        case .once: 1...1
        case .daily: 1...365
        case .weekly: 1...104
        case .monthly: 1...24
        }
    }
}

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

/// A job as Core lists it. Fields an older Core leaves out are optional; fields this app
/// does not know are ignored.
struct ScheduledJob: Decodable, Identifiable, Equatable {
    struct Params: Decodable, Equatable {
        var roadmap_id: String?
        var title: String?
        var instruction: String?
        var text: String?
        var notify: Bool?
        var threshold_percent: Int?
    }

    let id: UUID
    let kind: JobKind
    let cadence: JobCadence
    let weekday: Int?
    let month_day: Int?
    let every: Int?
    let start_date: String?
    let max_runs: Int?
    let at_minute: Int
    let enabled: Bool
    let revision: Int
    let params: Params?
    let last_slot_at: String?
    let next_run_at: String?
    let run_count: Int?
    /// `owner` or `jarvis`; Core's, never sent back.
    let origin: String?
    let conversation_id: UUID?
    let expires_at: String?
    let failure_count: Int?
    let paused_reason: String?

    var roadmapId: String? { params?.roadmap_id }
    /// The owner's title, or the kind's name for a kind without one.
    var title: String { params?.title ?? kind.label }
    var byJarvis: Bool { origin == "jarvis" }
    var pausedAfterFailures: Bool { !enabled && paused_reason == "paused_after_failures" }
}

struct JobList: Decodable {
    let status: String
    let jobs: [ScheduledJob]
    /// Entries this app cannot show: a kind or cadence from a newer Core, or a job that
    /// breaks the contract. They are left out instead of failing the whole list.
    let unsupported: Int

    private enum Keys: String, CodingKey { case status, jobs }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        status = try container.decode(String.self, forKey: .status)
        let entries = try container.decode([Lenient<ScheduledJob>].self, forKey: .jobs)
        jobs = entries.compactMap(\.value).filter(Jobs.valid)
        unsupported = entries.count - jobs.count
    }

    /// False when Core's scheduler is off: jobs can be changed, but none runs.
    var configured: Bool { status == "ok" }

    /// The owner's jobs that count towards Core's limit of 10: not the dependency review,
    /// not Jarvis's own.
    var ownerJobs: Int { jobs.filter { !$0.byJarvis && $0.kind != .dependencyReview }.count }
}

struct JobRun: Decodable, Identifiable {
    enum Trigger: String, Decodable {
        case schedule, owner
        var label: String { self == .schedule ? "Scheduled" : "Run by you" }
    }

    enum SummarySource: String, Decodable {
        case claude, deterministic, web
        var label: String {
            switch self {
            case .claude: "Written by Claude"
            case .deterministic: "Written by Core's template"
            case .web: "From a web search"
            }
        }
    }

    struct Priority: Decodable { let ref: String; let rank: Int; let reason: String }

    let id: UUID
    let job_id: UUID
    let slot_at: String
    let trigger: Trigger
    let status: JobRunStatus
    let error_code: String?
    let summary: String?
    /// Only a label: a source this app does not know shows none.
    private let source: Lenient<String>?
    let priorities: [Priority]?
    let started_at: String
    let finished_at: String?

    var summary_source: SummarySource? { source?.value.flatMap(SummarySource.init(rawValue:)) }
    var ranked: [Priority] { (priorities ?? []).sorted { $0.rank < $1.rank } }

    private enum CodingKeys: String, CodingKey {
        case id, job_id, slot_at, trigger, status, error_code, summary, source = "summary_source", priorities, started_at, finished_at
    }
}

struct JobRunList: Decodable {
    /// The runs this app can read; one it cannot (a newer status or trigger, a field out of
    /// bounds) is left out instead of failing the list.
    let runs: [JobRun]
    /// The entries Core sent, readable or not.
    let count: Int

    private enum Keys: String, CodingKey { case runs }

    init(from decoder: Decoder) throws {
        let entries = try decoder.container(keyedBy: Keys.self).decode([Lenient<JobRun>].self, forKey: .runs)
        runs = entries.compactMap(\.value).filter(Jobs.valid)
        count = entries.count
    }
}

/// One run's report: the run, the facts its priorities point at as plain strings, the
/// repositories that could not be read, whether entries were left out, and for a research
/// run its conversation and https sources.
struct JobRunReport: Decodable {
    struct Fact { let ref: String; let title: String; let detail: String }
    struct RepoError { let repo: String; let code: String }

    let run: JobRun
    let facts: [Fact]
    let errors: [RepoError]
    let truncated: Bool
    let conversationId: UUID?
    let sources: [WebAnswer.Source]

    private enum Keys: String, CodingKey { case facts }

    init(from decoder: Decoder) throws {
        run = try JobRun(from: decoder)
        // Facts that do not fit are skipped; no facts (a failed or missed run) is fine.
        let raw = try? decoder.container(keyedBy: Keys.self).decodeIfPresent(RawFacts.self, forKey: .facts)
        facts = raw?.facts ?? []
        errors = raw?.errors ?? []
        truncated = raw?.truncated ?? false
        conversationId = raw?.conversationId
        sources = raw?.sources ?? []
    }

    private struct RawFacts: Decodable {
        let facts: [Fact]
        let errors: [RepoError]
        let truncated: Bool
        let conversationId: UUID?
        let sources: [WebAnswer.Source]

        private enum Keys: String, CodingKey { case alerts, pull_requests, errors, truncated, conversation_id, sources }
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
            conversationId = (try? container.decode(String.self, forKey: .conversation_id)).flatMap(UUID.init(uuidString:))
            // Up to 20 distinct https links with an ASCII host, as in a chat answer's Sources.
            let links = ((try? container.decode([Lenient<String>].self, forKey: .sources)) ?? []).prefix(20)
            var seen = Set<URL>()
            sources = links.compactMap { link in
                link.value.flatMap { $0.count <= 2000 ? WebAnswer.httpsSource(Substring($0)) : nil }
            }.filter { seen.insert($0.url).inserted }
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

/// The editor's state: the time is minutes after midnight and the start date a day in
/// Europe/Amsterdam. Only the fields of the job's kind and cadence are sent.
struct JobDraft: Equatable {
    var cadence = JobCadence.weekly
    var weekday = 1
    var monthDay = 1
    var every = 1
    /// `YYYY-MM-DD`; nil: none. A one-time job needs one.
    var startDate: String?
    /// Nil: no limit.
    var maxRuns: Int?
    var minute = 7 * 60
    var enabled = true
    var roadmapId: String?
    var title = ""
    var instruction = ""
    var text = ""
    var notify = true
    var threshold = 80

    init() {}

    init(_ job: ScheduledJob) {
        cadence = job.cadence
        weekday = job.weekday ?? 1
        monthDay = job.month_day ?? 1
        every = job.every ?? 1
        startDate = job.start_date
        maxRuns = job.max_runs
        minute = job.at_minute
        enabled = job.enabled
        roadmapId = job.roadmapId
        title = job.params?.title ?? ""
        instruction = job.params?.instruction ?? ""
        text = job.params?.text ?? ""
        notify = job.params?.notify ?? true
        threshold = job.params?.threshold_percent ?? 80
    }
}

/// The kind's fields only; empty clears the roadmap of a dependency review.
struct JobParamsBody: Encodable, Equatable {
    var roadmap_id: String?
    var title: String?
    var instruction: String?
    var text: String?
    var notify: Bool?
    var threshold_percent: Int?
}

/// Only fields the owner sets: Core refuses a body that names one of its own (origin,
/// run_count, conversation_id, …).
struct NewJob: Encodable, Equatable {
    let kind: JobKind
    let cadence: JobCadence
    /// Weekly only; Core refuses a weekday on another cadence.
    let weekday: Int?
    /// Monthly only.
    let month_day: Int?
    /// Above 1 only.
    let every: Int?
    let start_date: String?
    let max_runs: Int?
    let at_minute: Int
    let enabled: Bool
    let params: JobParamsBody
}

/// Only what changed; absent fields are left as they are.
struct JobPatch: Encodable, Equatable {
    var cadence: JobCadence?
    var weekday: Int?
    var month_day: Int?
    var every: Int?
    /// `.some(nil)` sends null, which clears it.
    var start_date: String??
    var max_runs: Int??
    var at_minute: Int?
    var enabled: Bool?
    var params: JobParamsBody?

    private enum Keys: String, CodingKey { case cadence, weekday, month_day, every, start_date, max_runs, at_minute, enabled, params }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: Keys.self)
        try container.encodeIfPresent(cadence, forKey: .cadence)
        try container.encodeIfPresent(weekday, forKey: .weekday)
        try container.encodeIfPresent(month_day, forKey: .month_day)
        try container.encodeIfPresent(every, forKey: .every)
        if let start_date { try container.encode(start_date, forKey: .start_date) }
        if let max_runs { try container.encode(max_runs, forKey: .max_runs) }
        try container.encodeIfPresent(at_minute, forKey: .at_minute)
        try container.encodeIfPresent(enabled, forKey: .enabled)
        try container.encodeIfPresent(params, forKey: .params)
    }
}

enum Jobs {
    static let timeZoneName = "Europe/Amsterdam"
    private static let zone = TimeZone(identifier: timeZoneName)
    /// False when this device cannot read the schedule's zone: the Jobs page then shows no
    /// times at all rather than wrong ones (fail closed); `timeZone` is never shown then.
    static let zoneAvailable = zone != nil
    static let timeZone = zone ?? .gmt
    static let zoneUnavailable = "This device cannot read the Europe/Amsterdam time zone, so jobs cannot be shown."
    static let weekdays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    /// The runs read per job: the newest 20.
    static let runsShown = 20
    /// Core keeps at most this many jobs of the owner's, apart from the dependency review.
    static let maxOwnerJobs = 10
    /// A start date is at most two years ahead (Core: 2 × 366 days).
    static let maxStartDays = 732

    static let notConfigured =
        "Core's scheduler is off: it could not read the Europe/Amsterdam time zone on your Home Node. Jobs can be changed, but none runs."
    static let runNowRefused = "Not started: a job runs on request at most once an hour, and not while a run is queued or running."
    static let changedElsewhere = "Changed elsewhere, reloaded."
    static let notFound = "This does not exist on your Home Node (any more)."
    static let notConfirmed = "Not confirmed; reloaded to check."
    static let full = "You have 10 jobs, the most Core keeps. Delete one to add another."
    static let pausedAfterFailuresText = "Paused after 3 failed runs"
    static let pausedNotice =
        "Core paused this job after 3 failed runs in a row. Check the latest run, change the job if needed, then enable it again."
    static let runNowStarted = "Started. Refresh to see the run when it ends."
    static let privacyHint =
        "It leaves your Home Node as a web search, so leave out personal or financial details and symbols you hold."

    static func jobPath(_ job: UUID) -> String { "/v1/jobs/\(job.uuidString.lowercased())" }
    static func runsPath(_ job: UUID) -> String { "\(jobPath(job))/runs?limit=\(runsShown)" }
    static func runPath(_ run: UUID) -> String { "/v1/jobs/runs/\(run.uuidString.lowercased())" }

    // MARK: Response checks: anything unexpected reads as failed (fail closed).

    static func isCode(_ text: String) -> Bool {
        (1...64).contains(text.utf8.count) && text.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 95 }
    }

    private static func fits(_ text: String?, _ max: Int) -> Bool { (text?.count ?? 0) <= max }
    private static func within(_ value: Int?, _ range: ClosedRange<Int>) -> Bool { value.map { range.contains($0) } ?? true }

    /// A job that breaks the contract is left out of the list (see `JobList.unsupported`).
    static func valid(_ job: ScheduledJob) -> Bool {
        let schedule = within(job.weekday, 1...7) && within(job.month_day, 1...31) && within(job.every, 1...365)
            && within(job.max_runs, 1...100) && (0...1439).contains(job.at_minute) && fits(job.start_date, 10)
        let counters = job.revision >= 0 && within(job.run_count, 0...100_000) && within(job.failure_count, 0...100_000)
        let params = fits(job.roadmapId, 64) && fits(job.params?.title, 120) && fits(job.params?.instruction, 1000)
            && fits(job.params?.text, 1000) && within(job.params?.threshold_percent, 1...100)
        let times = fits(job.last_slot_at, 64) && fits(job.next_run_at, 64) && fits(job.expires_at, 64)
            && fits(job.origin, 64) && fits(job.paused_reason, 64)
        return schedule && counters && params && times
    }

    static func valid(_ list: JobList) -> Bool {
        (list.status == "ok" || list.status == "not_configured") && list.jobs.count + list.unsupported <= 100
    }

    static func valid(_ run: JobRun) -> Bool {
        let priorities = run.priorities ?? []
        return fits(run.slot_at, 64) && fits(run.started_at, 64) && fits(run.finished_at, 64)
            && (run.error_code.map(isCode) ?? true) && fits(run.summary, 4000) && priorities.count <= 20
            && priorities.allSatisfy { $0.ref.count <= 16 && $0.rank >= 0 && $0.reason.count <= 600 }
    }

    static func valid(_ list: JobRunList) -> Bool { list.count <= 50 }

    // MARK: Schedule in words

    static func timeText(_ minute: Int) -> String { String(format: "%02d:%02d", minute / 60, minute % 60) }

    /// 1st, 2nd, 3rd, 4th, 11th, 21st, …
    static func ordinal(_ day: Int) -> String {
        let suffix = (11...13).contains(day % 100) ? "th" : [1: "st", 2: "nd", 3: "rd"][day % 10] ?? "th"
        return "\(day)\(suffix)"
    }

    /// "Every 2 weeks on Friday at 08:00", "Once on 1 Dec at 09:00", "Monthly on the 1st at
    /// 09:00", plus ", from 1 Jan 2027" while a repeating job's start date is ahead.
    static func scheduleText(_ job: ScheduledJob) -> String {
        scheduleText(JobDraft(job))
    }

    static func scheduleText(_ draft: JobDraft) -> String {
        let at = " at \(timeText(draft.minute))"
        let every = draft.every
        let base: String
        switch draft.cadence {
        case .once:
            return draft.startDate.map { "Once on \(dayLabel($0) ?? $0)\(at)" } ?? "Once\(at)"
        case .daily:
            base = every > 1 ? "Every \(every) days" : "Daily"
        case .weekly:
            let day = weekdays[min(max(draft.weekday - 1, 0), 6)]
            base = every > 1 ? "Every \(every) weeks on \(day)" : "Every \(day)"
        case .monthly:
            base = (every > 1 ? "Every \(every) months" : "Monthly") + " on the \(ordinal(draft.monthDay))"
        }
        let from = draft.startDate.map { $0 > today() ? ", from \(dayLabel($0) ?? $0)" : "" } ?? ""
        return base + at + from
    }

    /// "2 of 5 runs done" for a job with a run limit.
    static func runsDoneText(_ job: ScheduledJob) -> String? {
        guard let most = job.max_runs else { return nil }
        return "\(min(job.run_count ?? 0, most)) of \(most) runs done"
    }

    /// "Set up by Jarvis · expires 13 Dec" for a job Jarvis made for itself.
    static func originText(_ job: ScheduledJob) -> String? {
        guard job.byJarvis else { return nil }
        guard let expires = job.expires_at.flatMap(parseCoreDate) else { return "Set up by Jarvis" }
        return "Set up by Jarvis · expires \(shortDay.string(from: expires))"
    }

    /// A one-time job after its slot, or a job that reached its run limit: Core disabled it
    /// because it is done, not paused.
    static func isFinished(_ job: ScheduledJob) -> Bool {
        !job.enabled && ((job.cadence == .once && job.last_slot_at != nil) || job.max_runs.map { (job.run_count ?? 0) >= $0 } == true)
    }

    /// The job's state badge.
    static func stateLabel(_ job: ScheduledJob) -> String {
        if job.enabled { return "Enabled" }
        if job.pausedAfterFailures { return pausedAfterFailuresText }
        return isFinished(job) ? "Finished" : "Paused"
    }

    /// Its next run, or its state when it does not run.
    static func stateText(_ job: ScheduledJob) -> String {
        if !job.enabled { return stateLabel(job) }
        return job.next_run_at.map { "Next run \(zonedText($0))" } ?? "Not scheduled"
    }

    /// "3 of 10 jobs"; jobs this app cannot show may count towards Core's limit too.
    static func countText(_ owned: Int, unsupported: Int) -> String {
        "\(owned) of \(maxOwnerJobs) jobs" + (unsupported > 0 ? ", not counting any this app cannot show" : "")
    }

    static func unsupportedText(_ count: Int) -> String {
        "\(count == 1 ? "1 job" : "\(count) jobs") this app cannot show yet. Update the app to see \(count == 1 ? "it" : "them")."
    }

    static func deleteNote(_ job: ScheduledJob) -> String {
        job.conversation_id != nil ? "Its runs are deleted too. Its conversation stays in chat." : "Its runs are deleted too."
    }

    private static func formatter(_ format: String, locale: String = "en_GB") -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: locale)
        formatter.calendar = calendar
        formatter.timeZone = timeZone
        formatter.dateFormat = format
        return formatter
    }

    private static let zoned = formatter("EEE d MMM, HH:mm")
    private static let shortDay = formatter("d MMM")
    private static let longDay = formatter("d MMM yyyy")
    private static let isoDay = formatter("yyyy-MM-dd", locale: "en_US_POSIX")

    /// A moment from Core, shown in the schedule's zone.
    static func zonedText(_ iso: String) -> String { parseCoreDate(iso).map { zoned.string(from: $0) } ?? "an unknown time" }

    /// "1 Dec", with the year when it is not this year.
    static func dayLabel(_ day: String) -> String? {
        guard let parsed = date(day: day) else { return nil }
        return (day.prefix(4) == today().prefix(4) ? shortDay : longDay).string(from: parsed)
    }

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

    /// A `YYYY-MM-DD` day in Europe/Amsterdam as the date picker's date; nil when it is not one.
    static func date(day: String) -> Date? {
        guard day.count == 10, let parsed = isoDay.date(from: day), isoDay.string(from: parsed) == day else { return nil }
        return parsed
    }

    static func day(of date: Date) -> String { isoDay.string(from: date) }

    /// Today in Europe/Amsterdam, `YYYY-MM-DD`; `adding` days later.
    static func today(adding days: Int = 0, now: Date = Date()) -> String {
        day(of: calendar.date(byAdding: .day, value: days, to: now) ?? now)
    }

    // MARK: Editor

    /// The kind's params from the editor, trimmed. A reminder without text sends none.
    static func params(_ kind: JobKind, _ draft: JobDraft) -> JobParamsBody {
        let trim = { (text: String) in text.trimmingCharacters(in: .whitespacesAndNewlines) }
        switch kind {
        case .dependencyReview: return JobParamsBody(roadmap_id: draft.roadmapId)
        case .prompt: return JobParamsBody(title: trim(draft.title), instruction: trim(draft.instruction), notify: draft.notify)
        case .reminder: return JobParamsBody(title: trim(draft.title), text: trim(draft.text).isEmpty ? nil : trim(draft.text))
        case .diskCheck: return JobParamsBody(threshold_percent: draft.threshold)
        }
    }

    /// The create body: a weekday only for a weekly job, a month day only for a monthly
    /// one, no `every` or run limit for a one-time job, and a dependency review only daily
    /// or weekly, as before.
    static func newJob(_ kind: JobKind, _ draft: JobDraft) -> NewJob {
        let once = draft.cadence == .once
        let review = kind == .dependencyReview
        return NewJob(kind: kind, cadence: draft.cadence,
                      weekday: draft.cadence == .weekly ? draft.weekday : nil,
                      month_day: draft.cadence == .monthly ? draft.monthDay : nil,
                      every: once || review || draft.every == 1 ? nil : draft.every,
                      start_date: review ? nil : draft.startDate,
                      max_runs: once || review ? nil : draft.maxRuns,
                      at_minute: draft.minute, enabled: draft.enabled, params: params(kind, draft))
    }

    /// Only what changed, so an unchanged editor sends nothing (nil). Changed params are
    /// sent whole: Core replaces them.
    static func patch(_ job: ScheduledJob, _ draft: JobDraft) -> JobPatch? {
        let body = newJob(job.kind, draft)
        var patch = JobPatch()
        if draft.cadence != job.cadence { patch.cadence = draft.cadence }
        if let weekday = body.weekday, weekday != job.weekday { patch.weekday = weekday }
        if let day = body.month_day, day != job.month_day { patch.month_day = day }
        // Core sets `every` back to 1 when the cadence changes.
        let every = body.every ?? 1
        if every != (draft.cadence == job.cadence ? job.every ?? 1 : 1) { patch.every = every }
        if body.start_date != job.start_date { patch.start_date = .some(body.start_date) }
        if body.max_runs != job.max_runs { patch.max_runs = .some(body.max_runs) }
        if draft.minute != job.at_minute { patch.at_minute = draft.minute }
        if draft.enabled != job.enabled { patch.enabled = draft.enabled }
        if body.params != params(job.kind, JobDraft(job)) { patch.params = body.params }
        return patch == JobPatch() ? nil : patch
    }

    /// Why the editor cannot be saved yet, in Core's bounds; nil when Core should take it.
    static func issue(_ kind: JobKind, _ draft: JobDraft) -> String? {
        let line = { (text: String, max: Int) in Roadmaps.validText(text.trimmingCharacters(in: .whitespacesAndNewlines), max: max) }
        if kind == .prompt || kind == .reminder, !line(draft.title, 60) { return "Give it a title of 1 to 60 characters on one line." }
        if kind == .prompt, !line(draft.instruction, 500) { return "Say what to research in 1 to 500 characters on one line." }
        if kind == .reminder, !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !line(draft.text, 500) {
            return "The text can be at most 500 characters on one line."
        }
        if kind == .diskCheck, !(50...99).contains(draft.threshold) { return "Pick a threshold from 50 to 99 percent." }
        if kind != .dependencyReview {
            let every = draft.cadence.everyRange
            if !every.contains(draft.every) { return "Repeat every 1 to \(every.upperBound) \(draft.cadence.unit)." }
            if let most = draft.maxRuns, !(1...100).contains(most) { return "Stop after 1 to 100 runs, or leave it empty." }
            if draft.cadence == .once, draft.startDate == nil { return "Pick the day it runs." }
            if let start = draft.startDate, start > today(adding: maxStartDays) { return "Pick a date at most two years ahead." }
            if draft.cadence == .once, draft.enabled, let start = draft.startDate, start < today() {
                return "Pick a day from today on."
            }
        }
        return nil
    }

    // MARK: Fixed wording

    private static let errorTexts: [String: String] = [
        "not_configured": "Not set up on Core: something this job needs is turned off on your Home Node.",
        "timed_out": "The run took longer than 15 minutes and was stopped.",
        "interrupted": "Core stopped during the run, so it did not finish.",
        "invalid_result": "Core refused to store the result of this run.",
        "internal": "Something went wrong in Core during the run.",
        "private_instruction":
            "Not searched: the title or instruction may hold personal or financial details or a symbol you hold. Change the job to leave them out.",
        "holdings_unavailable": "Not searched: Core could not read your holdings to check the instruction for private details.",
        "research_unavailable": "The web search did not run or was stopped.",
        "conversation_unavailable": "The answer could not be stored in the job's conversation.",
        "disk_unavailable": "No Home Node disk could be read.",
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

    /// Where a code means something else for one kind of job.
    private static let kindErrorTexts: [JobKind: [String: String]] = [
        .dependencyReview: [
            "not_configured": "Not set up on Core: the GitHub token or the repository list (JARVIS_GITHUB_REPOS) is missing on your Home Node.",
        ],
        .prompt: [
            "not_configured": "Not searched: Research is off, or no subscription worker can run it.",
            "timed_out": "The web search took longer than 5 minutes and was stopped.",
        ],
    ]

    /// The owner-facing text for an error code of a run of `kind`; never the code's own words.
    static func errorText(_ code: String, kind: JobKind? = nil) -> String {
        if let kind, let text = kindErrorTexts[kind]?[code] { return text }
        return errorTexts[code] ?? "Core reported an error this app does not know yet."
    }

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
        case 405, 501: return "Needs Jarvis Core v0.0.87 or later. Update Core on your Home Node."
        case 503: return "Core could not read the jobs just now. Try again in a minute."
        default: return "Core returned an answer this app does not understand, or an error. Try again later."
        }
    }

    /// A 2xx answer this app could not read: Core may have made the change.
    static func isUnreadable(_ error: Error) -> Bool {
        let error = error as? JarvisAPIError
        return error == .invalidResponse || error == .responseTooLarge
    }

    /// The notice after a refused "Run now".
    static func runNowFailureText(_ error: Error) -> String {
        if isUnreadable(error) { return notConfirmed }
        if status(error) == 429 { return runNowRefused }
        if status(error) == 409 { return "Not started: Core's scheduler is off, so no job runs." }
        if isNetwork(error) { return "No answer from your Home Node. It is unknown whether the run was queued; reloaded to check." }
        return "Not started. \(failureText(error))"
    }

    /// A create Core refused as unreadable (422): a Core before v0.0.87 knows only the
    /// dependency review and refuses a body with a kind it does not know.
    static let kindNotKnown = "Not saved: your Core does not know this kind of job yet. Update Core on your Home Node."

    static let invalidText =
        "Core refused this as invalid: for example a one-time job that is not in the future, an archived roadmap, or a research title or instruction that may hold personal or financial details. Check the fields and try again."

    /// The notice after a failed write. A 409 on a change is a revision conflict; on a
    /// create, no room: a second dependency review, or the owner's 10 jobs.
    static func writeFailureText(_ error: Error, update: Bool, kind: JobKind = .dependencyReview) -> String {
        if isUnreadable(error) { return notConfirmed }
        if status(error) == 409 {
            if update { return changedElsewhere }
            return kind == .dependencyReview ? "Core refused this: a dependency review already exists." : "Core refused this. \(full)"
        }
        if status(error) == 400 { return invalidText }
        if !update, status(error) == 422 { return kindNotKnown }
        if status(error) == 503 {
            return "Not saved: Core could not check this just now (its database or your holdings could not be read). Try again in a minute."
        }
        if isNetwork(error) { return "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check." }
        return "Not saved. \(failureText(error))"
    }

    /// The notice after a failed delete; a job already gone counts as deleted.
    static func deleteFailureText(_ error: Error) -> String {
        if isUnreadable(error) { return notConfirmed }
        if status(error) == 409 { return changedElsewhere }
        if isNetwork(error) { return "No answer from your Home Node. It is unknown whether the job was deleted; reloaded to check." }
        return "Not deleted. \(failureText(error))"
    }
}

extension Availability {
    /// A read that decoded but breaks the contract reads as failed (fail closed).
    func checked(_ valid: (Value) -> Bool) -> Availability {
        if case let .ok(value) = self, !valid(value) { return .error(.failed) }
        return self
    }
}
