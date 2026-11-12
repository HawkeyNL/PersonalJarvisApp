import SwiftUI

// Scheduled jobs (desktop Jobs.vue, Job.vue, JobRun.vue, JobForm.vue): the jobs with
// their schedule and newest run, one job's editor and runs, and a run's report. All
// content comes from Core; its text is shown verbatim, never as Markdown.

extension JobRunStatus {
    var badge: Badge.Style {
        switch self {
        case .running: .accent
        case .ok: .positive
        case .partial, .interrupted: .negative
        case .failed: .danger
        case .missed: .neutral
        }
    }

    var tone: Tone {
        switch self {
        case .ok: .ok
        case .partial, .interrupted: .warn
        case .failed: .error
        case .running, .missed: .idle
        }
    }
}

/// A status line on a tinted card; `warn` for the scheduler-off notice. Roadmaps use it too.
struct JobNotice: View {
    let text: String
    var style = Badge.Style.accent

    var body: some View {
        Text(verbatim: text)
            .font(JV.font(13))
            .foregroundStyle(style == .accent ? JV.textPrimary : style.foreground)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, JV.Space.md)
            .padding(.vertical, JV.Space.sm)
            .background(style.background, in: RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous))
    }
}

// MARK: - Jobs

/// The owner's scheduled jobs: one row each with its schedule, next run and newest
/// run; a kind without a job can be set up here.
struct JobsPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var list: Availability<JobList> = .loading
    /// The newest run per job; an error when its runs could not be read.
    @State private var last: [UUID: Availability<JobRun?>] = [:]
    @State private var open: UUID?
    @State private var creating: JobKind?
    @State private var busy = false
    @State private var error: String?
    /// A read already in flight must not overwrite a newer answer.
    @State private var loads = 0

    var body: some View {
        PageScaffold(title: "Jobs", subtitle: "Work Core does by itself on a schedule you set.") {
            // Above the list: after a refused set-up the reload may remove its card.
            if let error { JobNotice(text: error, style: .danger) }
            Loaded(source: list, title: "Jobs", symbol: "clock", retry: load) { value in
                if !value.configured { JobNotice(text: Jobs.notConfigured, style: .negative) }
                if !value.jobs.isEmpty {
                    ActivityList(rows: value.jobs.map(row)) { open = UUID(uuidString: $0.id) }
                }
                ForEach(JobKind.allCases.filter { kind in !value.jobs.contains { $0.kind == kind } }, id: \.self) { kind in
                    setUp(kind)
                }
            }
        }
        .navigationDestination(item: $open) { id in JobPage(model: model, id: id) }
        .refreshable { await load() }
        .task { await load() }
        .onChange(of: error) { _, error in
            if let error { AccessibilityNotification.Announcement(error).post() }
        }
    }

    private func row(_ job: ScheduledJob) -> ActivityRow {
        let next = !job.enabled ? "Paused" : job.next_run_at.map { "Next run \(Jobs.zonedText($0))" } ?? "Not scheduled"
        let newest: String
        switch last[job.id] {
        case .ok(let run?)?: newest = "Last run \(Jobs.zonedText(run.started_at)): \(run.status.label)"
        case .ok(nil)?: newest = "Not run yet"
        default: newest = "Last run could not be loaded"
        }
        return ActivityRow(id: job.id.uuidString, symbol: "clock", title: job.kind.label,
                           detail: [Jobs.cadenceText(job), next, newest].joined(separator: " · "),
                           tone: job.enabled ? .ok : .idle, toneLabel: job.enabled ? "Enabled" : "Paused")
    }

    private func setUp(_ kind: JobKind) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            Text(verbatim: kind.label).font(JV.font(18, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Text(verbatim: kind.detail).font(JV.font(13)).foregroundStyle(JV.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if creating == kind {
                JobForm(model: model, kind: kind, initial: JobDraft(), busy: busy, submit: "Set up",
                        cancel: { creating = nil }) { draft in Task { await create(kind, draft) } }
            } else {
                Button("Set up") { creating = kind; error = nil }
                    .buttonStyle(JVButtonStyle(kind: .primary))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
    }

    private func create(_ kind: JobKind, _ draft: JobDraft) async {
        guard !busy else { return }
        busy = true
        error = nil
        do {
            try await model.createJob(Jobs.newJob(kind, draft))
            creating = nil
        } catch {
            self.error = Jobs.writeFailureText(error, update: false)
        }
        await load()
        busy = false
    }

    private func load() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead("/v1/jobs", as: JobList.self).checked(Jobs.valid)
        var newest: [UUID: Availability<JobRun?>] = [:]
        // ponytail: one runs read per job (at most one job per kind, one kind today).
        for job in result.value?.jobs ?? [] {
            newest[job.id] = await model.coreRead(Jobs.runsPath(job.id), as: JobRunList.self).checked(Jobs.valid).map(\.runs.first)
        }
        guard !Task.isCancelled, mine == loads else { return }
        list = result
        last = newest
    }
}

// MARK: - Job

/// One job: its schedule editor, Run now and the newest runs. Every write names the
/// revision it was made on; a 409 reloads and says so.
struct JobPage: View {
    @ObservedObject var model: JarvisAppModel
    let id: UUID
    /// `.ok(nil)`: Core does not list this job (any more).
    @State private var job: Availability<ScheduledJob?> = .loading
    @State private var configured = true
    @State private var runs: Availability<[JobRun]> = .loading
    @State private var notice: String?
    @State private var busy = false
    @State private var open: UUID?
    /// A read already in flight must not overwrite a newer answer.
    @State private var loads = 0

    private var found: ScheduledJob? { job.value ?? nil }

    var body: some View {
        PageScaffold(title: found?.kind.label ?? "Job", subtitle: found?.kind.detail ?? "") {
            if let notice { JobNotice(text: notice) }
            if !configured { JobNotice(text: Jobs.notConfigured, style: .negative) }
            Loaded(source: job, title: "Job", symbol: "clock", retry: load) { value in
                if let value {
                    schedule(value)
                    runList
                } else {
                    UnavailableState(title: "Could not load this job", kind: .error, detail: Jobs.notFound, symbol: "clock",
                                     action: UnavailableState.Action(label: "Try again", run: load))
                }
            }
        }
        .toolbar {
            if let found {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Run now") { Task { await runNow(found) } }
                        .disabled(busy || !configured)
                }
            }
        }
        .navigationDestination(item: $open) { id in JobRunPage(model: model, id: id) }
        .refreshable { await load() }
        .task { await load() }
        .onChange(of: notice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    private func schedule(_ job: ScheduledJob) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            TitleBadgeRow(title: "Schedule", badge: Badge(text: job.enabled ? "Enabled" : "Paused", style: job.enabled ? .positive : .neutral))
            Text(verbatim: Jobs.cadenceText(job) + "." + (job.enabled ? job.next_run_at.map { " Next run \(Jobs.zonedText($0))." } ?? "" : ""))
                .font(JV.font(13)).foregroundStyle(JV.textSecondary).fixedSize(horizontal: false, vertical: true)
            // A new revision (a change here or elsewhere) resets the editor to Core's state.
            JobForm(model: model, kind: job.kind, initial: JobDraft(job), busy: busy, submit: "Save") { draft in
                Task { await save(job, draft) }
            }
            .id(job.revision)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
    }

    private var runList: some View {
        ListSection(title: "Runs") {
            Loaded(source: runs, title: "Runs", symbol: "clock", rows: 2, retry: load) { list in
                ActivityList(rows: list.map { run in
                    let detail = run.error_code.map(Jobs.errorText) ?? (run.ranked.isEmpty ? "" : "\(run.ranked.count) priorities")
                    return ActivityRow(id: run.id.uuidString, symbol: "clock", title: Jobs.zonedText(run.started_at),
                                       detail: [run.trigger.label, detail].filter { !$0.isEmpty }.joined(separator: " · "),
                                       tone: run.status.tone, toneLabel: run.status.label)
                }, emptyText: "No runs yet.") { open = UUID(uuidString: $0.id) }
                if !list.isEmpty {
                    Text("The 20 newest runs. Core keeps runs for 180 days.")
                        .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                }
            }
        }
    }

    private func save(_ job: ScheduledJob, _ draft: JobDraft) async {
        guard !busy else { return }
        guard let patch = Jobs.patch(job, draft) else { notice = "Nothing changed."; return }
        busy = true
        notice = nil
        do {
            try await model.updateJob(job.id, revision: job.revision, patch: patch)
            await load()
            notice = "Saved."
        } catch {
            await load()
            notice = Jobs.writeFailureText(error, update: true)
        }
        busy = false
    }

    private func runNow(_ job: ScheduledJob) async {
        guard !busy else { return }
        busy = true
        notice = nil
        do {
            try await model.runJobNow(job.id)
            notice = "Started. The run shows up below when it ends; pull down to refresh."
        } catch {
            notice = Jobs.runNowFailureText(error)
        }
        busy = false
        await load()
    }

    private func load() async {
        loads += 1
        let mine = loads
        // Core has no single-job read; the list holds at most one job per kind.
        async let list = model.coreRead("/v1/jobs", as: JobList.self)
        async let runs = model.coreRead(Jobs.runsPath(id), as: JobRunList.self)
        let (jobs, history) = (await list.checked(Jobs.valid), await runs.checked(Jobs.valid))
        guard !Task.isCancelled, mine == loads else { return }
        if let value = jobs.value { configured = value.configured }
        job = jobs.map { list in list.jobs.first { $0.id == id } }
        self.runs = history.map(\.runs)
    }
}

// MARK: - Run report

/// One run's report: status, error, summary and the ranked priorities. The summary and
/// reasons are Core's display text, shown as plain text only.
struct JobRunPage: View {
    @ObservedObject var model: JarvisAppModel
    let id: UUID
    @State private var report: Availability<JobRunReport> = .loading

    var body: some View {
        PageScaffold(title: "Run report", subtitle: report.value?.run.status.label ?? "") {
            Loaded(source: report, title: "Run report", symbol: "clock", retry: load) { report in
                content(report)
            }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    @ViewBuilder private func content(_ report: JobRunReport) -> some View {
        let run = report.run
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            TitleBadgeRow(title: "Run", badge: Badge(text: run.status.label, style: run.status.badge))
            TileGrid(tiles: [
                TileData(label: "How", value: run.trigger.label),
                TileData(label: "Started", value: Jobs.zonedText(run.started_at)),
                TileData(label: "Ended", value: run.finished_at.map(Jobs.zonedText) ?? (run.status == .running ? "Still running" : "—")),
            ])
            if run.status == .missed {
                Text("Core was down at this slot for more than a day, so it did not run.").font(JV.font(13)).foregroundStyle(JV.textPrimary)
            }
            if let code = run.error_code { JobNotice(text: Jobs.errorText(code), style: .danger) }
        }
        if !report.errors.isEmpty {
            ListSection(title: "Repositories that could not be read") {
                VStack(alignment: .leading, spacing: JV.Space.xs) {
                    ForEach(Array(report.errors.enumerated()), id: \.offset) { _, error in
                        Text(verbatim: "\(error.repo): \(Jobs.errorText(error.code))").font(JV.font(13)).foregroundStyle(JV.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .jvCard(padding: JV.Space.md)
            }
        }
        if let summary = run.summary {
            ListSection(title: "Summary") {
                VStack(alignment: .leading, spacing: JV.Space.xs) {
                    if let source = run.summary_source {
                        Badge(text: source.label, style: source == .claude ? .accent : .neutral, dot: false)
                    }
                    Text(verbatim: summary).font(JV.font(14)).foregroundStyle(JV.textPrimary).textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .jvCard(padding: JV.Space.md)
            }
        }
        if !run.ranked.isEmpty {
            ListSection(title: "Priorities") { priorities(run.ranked, facts: report.facts) }
        } else if run.status == .ok || run.status == .partial {
            UnavailableState(title: "No open alerts in the repositories Core could read.", kind: .empty, symbol: "checkmark.circle")
        }
        if report.truncated {
            Text("Some entries were left out: a full page from GitHub, an alert Core could not read, or the size limit.")
                .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
        }
    }

    private func priorities(_ ranked: [JobRun.Priority], facts: [JobRunReport.Fact]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(ranked.enumerated()), id: \.offset) { index, priority in
                let fact = facts.first { $0.ref == priority.ref }
                if index > 0 { ListDivider(inset: 0) }
                HStack(alignment: .firstTextBaseline, spacing: JV.Space.sm) {
                    Text(verbatim: "\(priority.rank)").font(JV.number(13)).foregroundStyle(JV.textSecondary).frame(minWidth: 24, alignment: .trailing)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: fact?.title ?? priority.ref).font(JV.font(15, .medium)).foregroundStyle(JV.textPrimary)
                        if let detail = fact?.detail, !detail.isEmpty {
                            Text(verbatim: detail).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                        }
                        Text(verbatim: priority.reason).font(JV.font(13)).foregroundStyle(JV.textPrimary)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, JV.Space.md)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(Text(verbatim: "Priority \(priority.rank): \(fact?.title ?? priority.ref). \(priority.reason)"))
            }
        }
        .jvCard(padding: 0)
    }

    private func load() async {
        let result = await model.coreRead(Jobs.runPath(id), as: JobRunReport.self).checked { Jobs.valid($0.run) }
        guard !Task.isCancelled else { return }
        report = result
    }
}

// MARK: - Editor

/// The schedule editor, for a new job and an existing one: daily or weekly, the weekday,
/// a time in Europe/Amsterdam, enabled, and for a dependency review the roadmap its runs
/// propose upgrades to.
struct JobForm: View {
    @ObservedObject var model: JarvisAppModel
    let kind: JobKind
    let busy: Bool
    let submit: String
    var cancel: (() -> Void)?
    let save: (JobDraft) -> Void
    @State private var draft: JobDraft
    /// Nil while loading or when the roadmaps could not be read.
    @State private var roadmaps: [Roadmap]?

    init(model: JarvisAppModel, kind: JobKind, initial: JobDraft, busy: Bool, submit: String,
         cancel: (() -> Void)? = nil, save: @escaping (JobDraft) -> Void) {
        self.model = model
        self.kind = kind
        self.busy = busy
        self.submit = submit
        self.cancel = cancel
        self.save = save
        _draft = State(initialValue: initial)
    }

    private var time: Binding<Date> {
        Binding(get: { Jobs.date(minute: draft.minute) }, set: { draft.minute = Jobs.minute(of: $0) })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            LabeledContent("Repeats") {
                Picker("Repeats", selection: $draft.cadence) {
                    Text("Weekly").tag(JobCadence.weekly)
                    Text("Daily").tag(JobCadence.daily)
                }
                .labelsHidden()
            }
            if draft.cadence == .weekly {
                LabeledContent("Day") {
                    Picker("Day", selection: $draft.weekday) {
                        ForEach(1...7, id: \.self) { day in Text(Jobs.weekdays[day - 1]).tag(day) }
                    }
                    .labelsHidden()
                }
            }
            LabeledContent("Time") {
                DatePicker("Time", selection: time, displayedComponents: .hourAndMinute)
                    .labelsHidden()
                    .environment(\.timeZone, Jobs.timeZone)
                    .accessibilityHint("\(Jobs.timeZoneName) time")
            }
            Text(verbatim: "\(Jobs.timeZoneName) time").font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
            if kind == .dependencyReview { roadmapPicker }
            Toggle(isOn: $draft.enabled) {
                Text(draft.enabled ? "Enabled: runs on this schedule" : "Paused: runs only when you run it")
            }
            HStack(spacing: JV.Space.xs) {
                Spacer(minLength: 0)
                if let cancel {
                    Button("Cancel", action: cancel).buttonStyle(JVButtonStyle(kind: .secondary)).disabled(busy)
                }
                Button(submit) { save(draft) }.buttonStyle(JVButtonStyle(kind: .primary)).disabled(busy)
            }
        }
        .font(JV.font(15))
        .foregroundStyle(JV.textPrimary)
        .task {
            guard kind == .dependencyReview else { return }
            let result = await model.coreRead("/v1/roadmaps", as: RoadmapList.self).checked(Roadmaps.valid)
            guard !Task.isCancelled else { return }
            roadmaps = result.value?.roadmaps.filter { $0.archived_at == nil }
        }
    }

    /// A roadmap id as Core writes it in a job's params.
    private static func key(_ roadmap: Roadmap) -> String { roadmap.id.uuidString.lowercased() }

    @ViewBuilder private var roadmapPicker: some View {
        VStack(alignment: .leading, spacing: JV.Space.xxs) {
            LabeledContent("Propose upgrades to a roadmap (optional)") {
                Picker("Roadmap", selection: $draft.roadmapId) {
                    Text("No roadmap").tag(String?.none)
                    if let current = draft.roadmapId, !(roadmaps ?? []).contains(where: { Self.key($0) == current }) {
                        Text(roadmaps == nil ? "The roadmap set now" : "A roadmap that is gone or archived").tag(String?.some(current))
                    }
                    ForEach(roadmaps ?? []) { roadmap in Text(verbatim: roadmap.title).tag(String?.some(Self.key(roadmap))) }
                }
                .labelsHidden()
                .disabled(roadmaps == nil && draft.roadmapId == nil)
            }
            Text("Each run then proposes one item per fixable alert. You accept or reject the proposal on the roadmap.")
                .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
