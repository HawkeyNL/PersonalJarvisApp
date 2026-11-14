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

/// A status line on a tinted card; `warn` for the scheduler-off notice. Other pages use it too.
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

private func jobSymbol(_ kind: JobKind) -> String {
    switch kind {
    case .prompt: "magnifyingglass"
    case .reminder: "bell"
    case .diskCheck: "internaldrive"
    case .dependencyReview: "shippingbox"
    }
}

// MARK: - Jobs

/// The owner's scheduled jobs: one row each with its schedule, next run and newest
/// run, and a new job in a sheet.
struct JobsPage: View {
    @ObservedObject var model: JarvisAppModel
    let navigate: (HubRoute) -> Void
    @State private var list: Availability<JobList> = .loading
    /// The newest run per job; an error when its runs could not be read.
    @State private var last: [UUID: Availability<JobRun?>] = [:]
    @State private var open: UUID?
    @State private var creating = false
    /// The kinds there was room for when "New job" was tapped: a reload behind the sheet,
    /// failed or not, never empties it.
    @State private var newKinds: [JobKind] = []
    /// A read already in flight must not overwrite a newer answer.
    @State private var loads = 0

    var body: some View {
        PageScaffold(title: "Jobs", subtitle: "Work Core does by itself on a schedule you set, in \(Jobs.timeZoneName) time.") {
            if Jobs.zoneAvailable {
                Loaded(source: list, title: "Jobs", symbol: "clock", retry: load) { value in content(value) }
            } else {
                UnavailableState(title: "Could not show jobs", kind: .error, detail: Jobs.zoneUnavailable, symbol: "clock")
            }
        }
        .navigationDestination(item: $open) { id in JobPage(model: model, id: id, navigate: navigate) }
        .sheet(isPresented: $creating) {
            NewJobSheet(model: model, kinds: newKinds, created: { await load() })
        }
        .refreshable { await load() }
        .task { await load() }
    }

    @ViewBuilder private func content(_ value: JobList) -> some View {
        if !value.configured { JobNotice(text: Jobs.notConfigured, style: .negative) }
        if value.unsupported > 0 { JobNotice(text: Jobs.unsupportedText(value.unsupported), style: .negative) }
        ActivityList(rows: value.jobs.map(row), emptyText: "No jobs yet.") { open = UUID(uuidString: $0.id) }
        VStack(alignment: .leading, spacing: JV.Space.xs) {
            Text(verbatim: Jobs.countText(value.ownerJobs, unsupported: value.unsupported))
                .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
            if value.ownerJobs >= Jobs.maxOwnerJobs {
                Text(verbatim: Jobs.full).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button("New job") { newKinds = kinds(value); creating = true }
                .buttonStyle(JVButtonStyle(kind: .primary))
                .disabled(kinds(value).isEmpty)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The kinds there is room for: one dependency review, and 10 jobs of the others.
    private func kinds(_ value: JobList) -> [JobKind] {
        let review = value.jobs.contains { $0.kind == .dependencyReview }
        let full = value.ownerJobs >= Jobs.maxOwnerJobs
        return JobKind.allCases.filter { $0 == .dependencyReview ? !review : !full }
    }

    private func row(_ job: ScheduledJob) -> ActivityRow {
        let newest: String
        switch last[job.id] {
        case .ok(let run?)?: newest = "Last run \(Jobs.zonedText(run.started_at)): \(run.status.label)"
        case .ok(nil)?: newest = "Not run yet"
        default: newest = "Last run could not be loaded"
        }
        let kind = job.title == job.kind.label ? nil : job.kind.label
        let parts = [kind, Jobs.scheduleText(job), Jobs.runsDoneText(job), Jobs.stateText(job), newest, Jobs.originText(job)]
        return ActivityRow(id: job.id.uuidString, symbol: jobSymbol(job.kind), title: job.title,
                           detail: parts.compactMap { $0 }.joined(separator: " · "),
                           tone: job.enabled ? .ok : job.pausedAfterFailures ? .error : .idle,
                           toneLabel: Jobs.stateLabel(job))
    }

    private func load() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead("/v1/jobs", as: JobList.self).checked(Jobs.valid)
        let jobs = result.value?.jobs ?? []
        // ponytail: one runs read per job, side by side (at most 14 jobs); a newest-run field in Core's list if this gets slow.
        let model = self.model
        let newest = await withTaskGroup(of: (UUID, Availability<JobRun?>).self) { group in
            for job in jobs {
                group.addTask {
                    (job.id, await model.coreRead(Jobs.runsPath(job.id), as: JobRunList.self).checked(Jobs.valid).map(\.runs.first))
                }
            }
            var found: [UUID: Availability<JobRun?>] = [:]
            for await (id, run) in group { found[id] = run }
            return found
        }
        guard !Task.isCancelled, mine == loads else { return }
        // As on desktop: a failed reload keeps the list already shown.
        guard result.value != nil || list.value == nil else { return }
        list = result
        last = newest
    }
}

/// A new job: its kind, then the kind's fields and schedule. Chat opens it on a proposed
/// job (prefilled, with its own `write`).
struct NewJobSheet: View {
    @ObservedObject var model: JarvisAppModel
    let kinds: [JobKind]
    let title: String
    let initial: JobDraft
    let submit: String
    let created: () async -> Void
    /// Sends the job instead of a plain create: the notice to show, or nil when the sheet closes.
    let write: ((NewJob) async -> String?)?
    @Environment(\.dismiss) private var dismiss
    @State private var kind: JobKind
    @State private var busy = false
    @State private var error: String?

    init(model: JarvisAppModel, kinds: [JobKind], title: String = "New job", initial: JobDraft = JobDraft(), submit: String = "Set up",
         created: @escaping () async -> Void = {}, write: ((NewJob) async -> String?)? = nil) {
        self.model = model
        self.kinds = kinds
        self.title = title
        self.initial = initial
        self.submit = submit
        self.created = created
        self.write = write
        _kind = State(initialValue: kinds.first ?? .prompt)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: JV.Space.md) {
                    LabeledContent("Kind") {
                        Picker("Kind", selection: $kind) {
                            ForEach(kinds, id: \.self) { kind in Text(kind.label).tag(kind) }
                        }
                        .labelsHidden()
                        .disabled(busy)
                    }
                    Text(verbatim: kind.detail).font(JV.font(13)).foregroundStyle(JV.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let error { JobNotice(text: error, style: .danger) }
                    // A new kind starts from a fresh editor.
                    JobForm(model: model, kind: kind, initial: initial, busy: busy, submit: submit, creating: true,
                            cancel: { dismiss() }) { draft in Task { await create(draft) } }
                        .id(kind)
                }
                .font(JV.font(15))
                .foregroundStyle(JV.textPrimary)
                .padding(JV.Space.md)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled(busy)
        .privacyCover()
        .onChange(of: error) { _, error in
            if let error { AccessibilityNotification.Announcement(error).post() }
        }
    }

    private func create(_ draft: JobDraft) async {
        guard !busy else { return }
        busy = true
        error = nil
        if let write {
            error = await write(Jobs.newJob(kind, draft))
            if error == nil { dismiss() }
        } else {
            do {
                try await model.createJob(Jobs.newJob(kind, draft))
                dismiss()
            } catch {
                self.error = Jobs.writeFailureText(error, update: false, kind: kind)
            }
        }
        await created()
        busy = false
    }
}

// MARK: - Job

/// One job: what it does and when, enable or pause, Run now, delete, its editor and the
/// newest runs. Every write names the revision it was made on; a 409 reloads and says so.
struct JobPage: View {
    @ObservedObject var model: JarvisAppModel
    let id: UUID
    let navigate: (HubRoute) -> Void
    /// `.ok(nil)`: Core does not list this job (any more).
    @State private var job: Availability<ScheduledJob?> = .loading
    @State private var configured = true
    @State private var runs: Availability<[JobRun]> = .loading
    @State private var notice: String?
    @State private var busy = false
    @State private var open: UUID?
    @State private var deleting = false
    /// A read already in flight must not overwrite a newer answer.
    @State private var loads = 0
    @Environment(\.dismiss) private var dismiss

    private var found: ScheduledJob? { job.value ?? nil }

    var body: some View {
        PageScaffold(title: found?.title ?? "Job", subtitle: found?.kind.detail ?? "") {
            if let notice { JobNotice(text: notice) }
            if !configured { JobNotice(text: Jobs.notConfigured, style: .negative) }
            Loaded(source: job, title: "Job", symbol: "clock", retry: load) { value in
                if let value {
                    summary(value)
                    editor(value)
                    runList(value.kind)
                } else {
                    UnavailableState(title: "Could not load this job", kind: .error, detail: Jobs.notFound, symbol: "clock",
                                     action: UnavailableState.Action(label: "Try again", run: load))
                }
            }
        }
        .toolbar {
            if found != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) { notice = nil; deleting = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel("Delete job")
                        .disabled(busy)
                }
            }
        }
        .confirmationDialog("Delete this job?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { if let found { Task { await delete(found) } } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(verbatim: found.map(Jobs.deleteNote) ?? "")
        }
        .navigationDestination(item: $open) { run in JobRunPage(model: model, id: run, kind: found?.kind, navigate: navigate) }
        .refreshable { await load() }
        .task { await load() }
        .onChange(of: notice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    private func summary(_ job: ScheduledJob) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            TitleBadgeRow(title: job.kind.label, badge: Badge(text: Jobs.stateLabel(job),
                                                              style: job.enabled ? .positive : job.pausedAfterFailures ? .danger : .neutral))
            let lines = [Jobs.scheduleText(job), Jobs.runsDoneText(job), Jobs.stateText(job), Jobs.originText(job)]
            Text(verbatim: lines.compactMap { $0 }.joined(separator: "\n"))
                .font(JV.font(13)).foregroundStyle(JV.textSecondary).fixedSize(horizontal: false, vertical: true)
            if job.pausedAfterFailures {
                JobNotice(text: Jobs.pausedNotice, style: .danger)
            }
            HStack(spacing: JV.Space.xs) {
                Button("Run now") { Task { await runNow(job) } }
                    .buttonStyle(JVButtonStyle(kind: .secondary))
                    .disabled(busy || !configured)
                Button(job.enabled ? "Pause" : "Enable") { Task { await setEnabled(job, !job.enabled) } }
                    .buttonStyle(JVButtonStyle(kind: .secondary))
                    .disabled(busy)
            }
            if let conversation = job.conversation_id {
                PanelButton(title: "Open in chat", symbol: "bubble.left") { openConversation(conversation) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
    }

    private func editor(_ job: ScheduledJob) -> some View {
        ListSection(title: "Change") {
            // A new revision (a change here or elsewhere) resets the editor to Core's state.
            JobForm(model: model, kind: job.kind, initial: JobDraft(job), busy: busy, submit: "Save", creating: false) { draft in
                Task { await save(job, draft) }
            }
            .id(job.revision)
            .frame(maxWidth: .infinity, alignment: .leading)
            .jvCard(padding: JV.Space.md)
        }
    }

    private func runList(_ kind: JobKind) -> some View {
        ListSection(title: "Runs") {
            Loaded(source: runs, title: "Runs", symbol: "clock", rows: 2, retry: load) { list in
                ActivityList(rows: list.map { run in
                    let detail = run.error_code.map { Jobs.errorText($0, kind: kind) } ?? (run.ranked.isEmpty ? "" : "\(run.ranked.count) priorities")
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

    private func openConversation(_ id: UUID) {
        Task { await model.openConversation(id) }
        navigate(.chat)
    }

    private func save(_ job: ScheduledJob, _ draft: JobDraft) async {
        guard !busy else { return }
        guard let patch = Jobs.patch(job, draft) else { notice = "Nothing changed."; return }
        await write(job, patch, done: "Saved.")
    }

    private func setEnabled(_ job: ScheduledJob, _ enabled: Bool) async {
        guard !busy else { return }
        await write(job, JobPatch(enabled: enabled), done: enabled ? "Enabled." : "Paused.")
    }

    private func write(_ job: ScheduledJob, _ patch: JobPatch, done: String) async {
        busy = true
        notice = nil
        do {
            try await model.updateJob(job.id, revision: job.revision, patch: patch)
            await load()
            notice = done
        } catch {
            await load()
            notice = Jobs.writeFailureText(error, update: true, kind: job.kind)
        }
        busy = false
    }

    private func runNow(_ job: ScheduledJob) async {
        guard !busy else { return }
        busy = true
        notice = nil
        do {
            try await model.runJobNow(job.id)
            notice = Jobs.runNowStarted
        } catch {
            notice = Jobs.runNowFailureText(error)
        }
        busy = false
        await load()
    }

    private func delete(_ job: ScheduledJob) async {
        guard !busy else { return }
        busy = true
        notice = nil
        do {
            try await model.deleteJob(job.id, revision: job.revision)
            busy = false
            dismiss()
            return
        } catch let error where Jobs.status(error) == 404 {
            busy = false
            dismiss()
            return
        } catch {
            notice = Jobs.deleteFailureText(error)
        }
        busy = false
        await load()
    }

    private func load() async {
        loads += 1
        let mine = loads
        // Core has no single-job read.
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

/// One run's report: status, error, summary, a research run's sources and conversation,
/// and a dependency review's ranked priorities. The summary and reasons are Core's display
/// text, shown as plain text only.
struct JobRunPage: View {
    @ObservedObject var model: JarvisAppModel
    let id: UUID
    /// The job's kind, for the wording of its error codes; nil when the job is gone.
    let kind: JobKind?
    let navigate: (HubRoute) -> Void
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
            if let code = run.error_code { JobNotice(text: Jobs.errorText(code, kind: kind), style: .danger) }
            if let conversation = report.conversationId {
                PanelButton(title: "Open in chat", symbol: "bubble.left") {
                    Task { await model.openConversation(conversation) }
                    navigate(.chat)
                }
            }
        }
        if !report.errors.isEmpty {
            ListSection(title: "Repositories that could not be read") {
                VStack(alignment: .leading, spacing: JV.Space.xs) {
                    ForEach(Array(report.errors.enumerated()), id: \.offset) { _, error in
                        Text(verbatim: "\(error.repo): \(Jobs.errorText(error.code, kind: kind))").font(JV.font(13)).foregroundStyle(JV.textPrimary)
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
        if !report.sources.isEmpty {
            ListSection(title: "Sources") { sources(report.sources) }
        }
        if !run.ranked.isEmpty {
            ListSection(title: "Priorities") { priorities(run.ranked, facts: report.facts) }
        } else if kind == .dependencyReview && (run.status == .ok || run.status == .partial) {
            UnavailableState(title: "No open alerts in the repositories Core could read.", kind: .empty, symbol: "checkmark.circle")
        }
        if report.truncated {
            Text("Some entries were left out: a full page from GitHub, an alert Core could not read, or the size limit.")
                .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
        }
    }

    /// Validated https links only (WebAnswer); Link opens them in the browser. Host and short
    /// path, never a title from the page.
    private func sources(_ sources: [WebAnswer.Source]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(sources.enumerated()), id: \.offset) { index, source in
                if index > 0 { ListDivider(inset: 0) }
                Link(destination: source.url) {
                    Text(verbatim: source.host + source.shortPath)
                        .font(JV.font(14)).foregroundStyle(JV.accentText).lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, JV.Space.md)
                        .padding(.vertical, 10)
                }
                .accessibilityLabel(Text(verbatim: source.host))
                .accessibilityHint("Opens in the browser")
            }
        }
        .jvCard(padding: 0)
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

/// The editor, for a new job and an existing one: the kind's fields, how often it runs
/// (once, daily, weekly or monthly, every so many, from a day, up to a number of runs), a
/// time in Europe/Amsterdam, and for a dependency review the roadmap its runs propose
/// upgrades to. Enabled is set here only for a new job; the job page pauses and enables.
struct JobForm: View {
    @ObservedObject var model: JarvisAppModel
    let kind: JobKind
    let busy: Bool
    let submit: String
    let creating: Bool
    var cancel: (() -> Void)?
    let save: (JobDraft) -> Void
    @State private var draft: JobDraft
    @State private var issue: String?
    /// Nil while loading or when the roadmaps could not be read.
    @State private var roadmaps: [Roadmap]?

    init(model: JarvisAppModel, kind: JobKind, initial: JobDraft, busy: Bool, submit: String, creating: Bool,
         cancel: (() -> Void)? = nil, save: @escaping (JobDraft) -> Void) {
        self.model = model
        self.kind = kind
        self.busy = busy
        self.submit = submit
        self.creating = creating
        self.cancel = cancel
        self.save = save
        _draft = State(initialValue: initial)
    }

    private var cadences: [JobCadence] { kind == .dependencyReview ? [.weekly, .daily] : JobCadence.allCases }

    private var time: Binding<Date> {
        Binding(get: { Jobs.date(minute: draft.minute) }, set: { draft.minute = Jobs.minute(of: $0) })
    }

    /// The start day; a day is set as soon as the picker shows.
    private var start: Binding<Date> {
        Binding(get: { draft.startDate.flatMap(Jobs.date(day:)) ?? Jobs.date(day: Jobs.today(adding: 1)) ?? Date() },
                set: { draft.startDate = Jobs.day(of: $0) })
    }

    private var startRange: ClosedRange<Date> {
        let latest = Jobs.date(day: Jobs.today(adding: Jobs.maxStartDays)) ?? Date()
        let earliest = draft.cadence == .once ? Jobs.date(day: Jobs.today()) ?? Date() : Date.distantPast
        return min(earliest, latest)...latest
    }

    private var laterStart: Binding<Bool> {
        Binding(get: { draft.startDate != nil }, set: { draft.startDate = $0 ? Jobs.today(adding: 1) : nil })
    }

    private var runLimit: Binding<Bool> {
        Binding(get: { draft.maxRuns != nil }, set: { draft.maxRuns = $0 ? 1 : nil })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            fields
            schedule
            if creating {
                Toggle(isOn: $draft.enabled) {
                    Text(draft.enabled ? "Enabled: runs on this schedule" : "Paused: runs only when you run it")
                }
            }
            if let issue { Text(verbatim: issue).font(JV.font(13)).foregroundStyle(JV.dangerText).fixedSize(horizontal: false, vertical: true) }
            HStack(spacing: JV.Space.xs) {
                Spacer(minLength: 0)
                if let cancel {
                    Button("Cancel", action: cancel).buttonStyle(JVButtonStyle(kind: .secondary)).disabled(busy)
                }
                Button(submit) {
                    issue = Jobs.issue(kind, draft)
                    if issue == nil { save(draft) }
                }
                .buttonStyle(JVButtonStyle(kind: .primary)).disabled(busy)
            }
        }
        .font(JV.font(15))
        .foregroundStyle(JV.textPrimary)
        .onChange(of: draft.cadence) { _, cadence in
            // Core sets `every` back to 1 when the cadence changes; a one-time job needs a day.
            draft.every = 1
            if cadence == .once && draft.startDate == nil { draft.startDate = Jobs.today(adding: 1) }
        }
        .onChange(of: issue) { _, issue in
            if let issue { AccessibilityNotification.Announcement(issue).post() }
        }
        .task {
            guard kind == .dependencyReview else { return }
            let result = await model.coreRead("/v1/roadmaps", as: RoadmapList.self).checked(Roadmaps.valid)
            guard !Task.isCancelled else { return }
            roadmaps = result.value?.roadmaps.filter { $0.archived_at == nil }
        }
    }

    @ViewBuilder private var fields: some View {
        switch kind {
        case .prompt:
            TextField("Title", text: $draft.title).textFieldStyle(.roundedBorder)
            TextField("What to research", text: $draft.instruction, axis: .vertical).lineLimit(2...6).textFieldStyle(.roundedBorder)
            caption(Jobs.privacyHint)
            Toggle("Send me a push when a run ends", isOn: $draft.notify)
        case .reminder:
            TextField("Title", text: $draft.title).textFieldStyle(.roundedBorder)
            TextField("Text (optional)", text: $draft.text, axis: .vertical).lineLimit(2...6).textFieldStyle(.roundedBorder)
        case .diskCheck:
            Stepper(value: $draft.threshold, in: 50...99) { Text(verbatim: "Tell me at \(draft.threshold)% full") }
        case .dependencyReview:
            roadmapPicker
        }
    }

    @ViewBuilder private var schedule: some View {
        LabeledContent("Repeats") {
            Picker("Repeats", selection: $draft.cadence) {
                ForEach(cadences, id: \.self) { cadence in Text(cadence.label).tag(cadence) }
            }
            .labelsHidden()
        }
        if draft.cadence == .once {
            DatePicker("Day", selection: start, in: startRange, displayedComponents: .date)
                .environment(\.timeZone, Jobs.timeZone)
        } else if kind != .dependencyReview {
            Stepper(value: $draft.every, in: draft.cadence.everyRange) {
                Text(verbatim: draft.every == 1 ? "Every \(draft.cadence.unit.dropLast())" : "Every \(draft.every) \(draft.cadence.unit)")
            }
        }
        if draft.cadence == .weekly {
            LabeledContent("Day") {
                Picker("Day", selection: $draft.weekday) {
                    ForEach(1...7, id: \.self) { day in Text(Jobs.weekdays[day - 1]).tag(day) }
                }
                .labelsHidden()
            }
        }
        if draft.cadence == .monthly {
            LabeledContent("Day of the month") {
                Picker("Day of the month", selection: $draft.monthDay) {
                    ForEach(1...31, id: \.self) { day in Text(Jobs.ordinal(day)).tag(day) }
                }
                .labelsHidden()
            }
            if draft.monthDay > 28 { caption("A shorter month runs on its last day.") }
        }
        LabeledContent("Time") {
            DatePicker("Time", selection: time, displayedComponents: .hourAndMinute)
                .labelsHidden()
                .environment(\.timeZone, Jobs.timeZone)
                .accessibilityHint("\(Jobs.timeZoneName) time")
        }
        if draft.cadence != .once && kind != .dependencyReview {
            Toggle("Start on a later day", isOn: laterStart)
            if draft.startDate != nil {
                DatePicker("Start", selection: start, in: startRange, displayedComponents: .date)
                    .environment(\.timeZone, Jobs.timeZone)
            }
            Toggle("Stop after a number of runs", isOn: runLimit)
            if let most = draft.maxRuns {
                Stepper(value: Binding(get: { most }, set: { draft.maxRuns = $0 }), in: 1...100) {
                    Text(verbatim: "Stop after \(most) \(most == 1 ? "run" : "runs")")
                }
            }
        }
        caption("\(Jobs.scheduleText(draft)), \(Jobs.timeZoneName) time.")
    }

    private func caption(_ text: String) -> some View {
        Text(verbatim: text).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
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
            caption("Each run then proposes one item per fixable alert. You accept or reject the proposal on the roadmap.")
        }
    }
}
