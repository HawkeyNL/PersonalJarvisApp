import Foundation

// Jobs a chat answer proposed (`job_proposals` on `assistant.completed`, see "From chat" in
// Core's docs/SCHEDULED_JOBS.md), as in desktop jobProposals.ts. Each body is a checked
// `POST /v1/jobs` body; nothing exists on Core until the owner taps Add (the body as is) or
// saves an edited one. Cards live in memory only, for the conversation on screen.

/// One proposal: Core's id, its body exactly as sent, and the editor's draft for it.
/// Decoding fails (and the proposal is skipped) for a body this app would not send itself.
struct JobProposal: Decodable, Equatable {
    let id: UUID
    let body: NewJob
    let draft: JobDraft

    init(id: UUID, body: NewJob, draft: JobDraft) {
        self.id = id
        self.body = body
        self.draft = draft
    }

    private enum Keys: String, CodingKey { case id, body }
    private enum BodyKeys: String, CodingKey { case params }
    // What a chat marker can carry (no `enabled`, no roadmap): the card shows all of it,
    // so what is added is what was shown.
    private static let bodyKeys: Set<String> = ["kind", "cadence", "weekday", "month_day", "every", "start_date", "max_runs", "at_minute", "params"]
    private static let paramKeys: Set<String> = ["title", "instruction", "text", "notify", "threshold_percent"]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        id = try container.decode(UUID.self, forKey: .id)
        let body = try container.superDecoder(forKey: .body)
        try requireKnownKeys(body, Self.bodyKeys)
        try requireKnownKeys(body.container(keyedBy: BodyKeys.self).superDecoder(forKey: .params), Self.paramKeys)
        self.body = try NewJob(from: body)
        guard let draft = JobProposals.draft(self.body) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Not a job this app would add"))
        }
        self.draft = draft
    }
}

/// A proposal under the answer that carried it.
struct ProposalCard: Identifiable, Equatable {
    enum State: Equatable { case open, adding, added(UUID), unconfirmed }

    /// After an edited Add that may have landed, the edited job.
    var proposal: JobProposal
    let conversationId: UUID
    /// The answer's canonical message id.
    let messageId: String
    var state = State.open
    var error: String?

    var id: UUID { proposal.id }
}

enum JobProposals {
    static let maxShown = 3

    // The card's fixed wording, in one place.
    static let added = "Added to Jobs"
    static let viewJob = "View job"
    /// After an unreadable 2xx or no answer: chat reloads nothing, the owner checks Jobs.
    static let notConfirmed = "Not confirmed: it may have been added."
    static let checkJobs = "Check Jobs"
    static let invalid = "Core refused this job as invalid, for example a time that has passed. Edit it and try again."
    static let noPush = " · No push"
    static let editorTitle = "Proposed job"
    static let add = "Add"
    static let edit = "Edit"
    static let dismiss = "Dismiss"

    /// The proposals of an `assistant.completed` payload: the first 3 entries, each shown
    /// once. One this app cannot read was already skipped, never failing the message.
    static func read(_ payload: RealtimePayload) -> [JobProposal] {
        var out: [JobProposal] = []
        for entry in (payload.job_proposals?.value ?? []).prefix(maxShown) {
            if let proposal = entry.value, !out.contains(where: { $0.id == proposal.id }) { out.append(proposal) }
        }
        return out
    }

    /// The editor's draft for a body, with Core's defaults for what it leaves out; nil when
    /// the kind cannot be proposed, a param or schedule field does not belong to its kind or
    /// cadence (Core refuses those), or the job breaks a rule the editor checks.
    static func draft(_ body: NewJob) -> JobDraft? {
        let p = body.params
        let paramsFit = switch body.kind {
        case .prompt: p.text == nil && p.threshold_percent == nil
        case .reminder: p.instruction == nil && p.notify == nil && p.threshold_percent == nil
        case .diskCheck: p.title == nil && p.instruction == nil && p.text == nil && p.notify == nil
        case .dependencyReview: false
        }
        let cadence = body.cadence ?? .weekly
        guard paramsFit, p.roadmap_id == nil, body.weekday == nil || cadence == .weekly, body.month_day == nil || cadence == .monthly,
              body.max_runs == nil || cadence != .once else { return nil }
        var draft = JobDraft()
        draft.cadence = body.cadence ?? .weekly
        draft.weekday = body.weekday ?? 1
        draft.monthDay = body.month_day ?? 1
        draft.every = body.every ?? 1
        draft.startDate = body.start_date
        draft.maxRuns = body.max_runs
        draft.minute = body.at_minute ?? 7 * 60
        draft.title = body.params.title ?? ""
        draft.instruction = body.params.instruction ?? ""
        draft.text = body.params.text ?? ""
        draft.notify = body.params.notify ?? true
        draft.threshold = body.params.threshold_percent ?? 80
        guard (1...7).contains(draft.weekday), (1...31).contains(draft.monthDay), (0...1439).contains(draft.minute),
              draft.startDate.map({ Jobs.date(day: $0) != nil }) ?? true, Jobs.issue(body.kind, draft) == nil else { return nil }
        return draft
    }

    /// `cards` plus the proposals not shown before (`seen`, which this updates).
    static func adding(_ proposals: [JobProposal], to cards: [ProposalCard], seen: inout Set<UUID>,
                       conversation: UUID, message: String) -> [ProposalCard] {
        let fresh = proposals.filter { seen.insert($0.id).inserted }
        return cards + fresh.map { ProposalCard(proposal: $0, conversationId: conversation, messageId: message) }
    }

    // MARK: Wording

    static func title(_ proposal: JobProposal) -> String {
        proposal.draft.title.isEmpty ? proposal.body.kind.label : proposal.draft.title
    }

    /// All of the schedule Add sends: its run limit, and for research without a push, that.
    static func schedule(_ proposal: JobProposal) -> String {
        let draft = proposal.draft
        let runs = draft.maxRuns.map { ", \($0) \($0 == 1 ? "run" : "runs")" } ?? ""
        let push = proposal.body.kind == .prompt && !draft.notify ? noPush : ""
        return Jobs.scheduleText(draft) + runs + push
    }

    /// The whole instruction, text or threshold (Core keeps them to one line); nil when none.
    static func detail(_ proposal: JobProposal) -> String? {
        switch proposal.body.kind {
        case .prompt: return proposal.draft.instruction
        case .reminder: return proposal.draft.text.isEmpty ? nil : proposal.draft.text
        default: return "Threshold \(proposal.draft.threshold)%"
        }
    }

    // MARK: Add

    /// Starts an Add: the card, now `adding`, when it is open and the app unlocked; nil
    /// otherwise (locked, gone, or a second tap while one runs).
    static func begin(_ id: UUID, in cards: inout [ProposalCard], unlocked: Bool) -> ProposalCard? {
        guard unlocked, let index = cards.firstIndex(where: { $0.id == id }), cards[index].state == .open else { return nil }
        cards[index].state = .adding
        cards[index].error = nil
        return cards[index]
    }

    /// Ends an Add. A result for a session or conversation the owner has left (`current`
    /// false) is dropped, and so is one for a dismissed card. Once an edited job may exist,
    /// the card shows it.
    static func finish(_ id: UUID, in cards: inout [ProposalCard], result: Result<ScheduledJob, Error>,
                       edited: (body: NewJob, draft: JobDraft)? = nil, current: Bool) {
        guard current, let index = cards.firstIndex(where: { $0.id == id }) else { return }
        let card = cards[index]
        let (state, error) = outcome(result, kind: edited?.body.kind ?? card.proposal.body.kind)
        cards[index].state = state
        cards[index].error = error
        if let edited, state != .open { cards[index].proposal = JobProposal(id: card.id, body: edited.body, draft: edited.draft) }
    }

    /// Fails closed: only an HTTP refusal (4xx) lets the owner try again. Anything else (a
    /// 2xx this app cannot read, no answer, any 5xx: Core's 503 also covers a write that timed
    /// out after it may have landed) is not confirmed, and Add stays off: the job may exist.
    static func outcome(_ result: Result<ScheduledJob, Error>, kind: JobKind) -> (ProposalCard.State, String?) {
        switch result {
        case let .success(job): return (.added(job.id), nil)
        case let .failure(error):
            guard let status = Jobs.status(error), (400..<500).contains(status) else { return (.unconfirmed, notConfirmed) }
            return (.open, status == 400 ? invalid : Jobs.writeFailureText(error, update: false, kind: kind))
        }
    }
}
