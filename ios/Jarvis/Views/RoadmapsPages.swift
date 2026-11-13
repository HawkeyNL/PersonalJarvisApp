import SwiftUI

// Roadmaps (desktop Roadmaps.vue, Roadmap.vue, ItemSheet.vue): the owner's roadmaps with
// their status tally, one roadmap's phases as a rail with what it is waiting on, and one
// item's sheet. All content comes from Core; its text is shown verbatim, never as Markdown.

extension RoadmapStatus {
    /// Badge tone; the label always carries the meaning too.
    var badge: Badge.Style {
        switch self {
        case .live: .positive
        case .ready: .accent
        case .inProgress: .negative
        case .planned: .neutral
        case .blocked: .danger
        }
    }
}

/// Items per status as badges ("2 live"), stacked when they do not fit on one line.
private struct TallyChips: View {
    let tally: [Roadmaps.StatusCount]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) { chips }
            VStack(alignment: .leading, spacing: 6) { chips }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: "Items per status: " + tally.map(text).joined(separator: ", ")))
    }

    private var chips: some View {
        ForEach(tally, id: \.status) { count in Badge(text: text(count), style: count.status.badge) }
    }

    private func text(_ count: Roadmaps.StatusCount) -> String { "\(count.count) \(count.status.label.lowercased())" }
}

private func itemCount(_ count: Int) -> String { "\(count) \(count == 1 ? "item" : "items")" }

// MARK: - Roadmaps

/// The owner's roadmaps: one card each with its status tally. Opening a card shows the
/// roadmap. Content comes from Core only; this app ships none.
struct RoadmapsPage: View {
    private struct Card: Identifiable {
        let roadmap: Roadmap
        /// Nil when its items could not be loaded.
        let items: [RoadmapItem]?
        var id: UUID { roadmap.id }
    }

    @ObservedObject var model: JarvisAppModel
    @State private var cards: Availability<[Card]> = .loading
    @State private var open: UUID?
    @State private var creating = false
    /// A read already in flight must not overwrite a newer answer.
    @State private var loads = 0

    private var full: Bool { (cards.value?.count ?? 0) >= Roadmaps.maxRoadmaps }

    var body: some View {
        PageScaffold(title: "Roadmaps", subtitle: "Your plans: what is live, ready, in progress, planned or blocked.") {
            if full {
                Text("You have 20 roadmaps, the most Core keeps open. Archive one to start another.")
                    .font(JV.font(13)).foregroundStyle(JV.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            Loaded(source: cards, title: "Roadmaps", symbol: "map", retry: load) { cards in
                if cards.isEmpty {
                    UnavailableState(title: "No roadmaps yet", kind: .empty,
                                     detail: "Start one for a project: its phases, its items and what each is waiting on.", symbol: "map")
                }
                ForEach(cards) { card in
                    Button { open = card.id } label: { cardView(card) }
                        .buttonStyle(.plain)
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("New roadmap") { creating = true }.disabled(full || cards.value == nil)
            }
        }
        .sheet(isPresented: $creating) { NewRoadmapSheet(model: model, created: load) }
        .navigationDestination(item: $open) { id in RoadmapPage(model: model, id: id) }
        .refreshable { await load() }
        .task { await load() }
    }

    private func cardView(_ card: Card) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.xs) {
            HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                Text(verbatim: card.roadmap.title)
                    .font(JV.font(18, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(JV.textSecondary)
                    .accessibilityHidden(true)
            }
            if let description = card.roadmap.description, !description.isEmpty {
                Text(verbatim: description).font(JV.font(13)).foregroundStyle(JV.textSecondary).lineLimit(2)
            }
            if let items = card.items, !items.isEmpty { TallyChips(tally: Roadmaps.tally(items)) }
            Text(verbatim: card.items.map { $0.isEmpty ? "No items yet" : itemCount($0.count) } ?? "Items could not be loaded")
                .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        loads += 1
        let mine = loads
        let list = await model.coreRead("/v1/roadmaps", as: RoadmapList.self).checked(Roadmaps.valid)
        let roadmaps = list.value?.roadmaps ?? []
        // ponytail: one detail read per card, side by side, for the first 20 (Core keeps at most 20 open; the list
        // check allows 200); a summary route in Core if this gets slow.
        let model = self.model
        let details = await withTaskGroup(of: (Int, [RoadmapItem]?).self) { group in
            for (index, roadmap) in roadmaps.prefix(Roadmaps.maxRoadmaps).enumerated() {
                group.addTask {
                    (index, await model.coreRead(Roadmaps.path(roadmap.id), as: RoadmapSnapshot.self).checked(Roadmaps.valid).value?.items)
                }
            }
            var found: [Int: [RoadmapItem]] = [:]
            for await (index, read) in group { found[index] = read }
            return found
        }
        let loaded = roadmaps.enumerated().map { Card(roadmap: $0.element, items: details[$0.offset]) }
        guard !Task.isCancelled, mine == loads else { return }
        // A failed reload keeps the cards already on screen.
        if list.value != nil || cards.value == nil { cards = list.map { _ in loaded } }
    }
}

/// A new roadmap: title, optional description and its phases, one per line.
private struct NewRoadmapSheet: View {
    @ObservedObject var model: JarvisAppModel
    let created: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var description = ""
    @State private var phases = Roadmaps.defaultPhases.joined(separator: "\n")
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("Title", text: $title) } header: { listHeader("Title") }
                    .instrumentRows()
                Section { TextEditor(text: $description).frame(minHeight: 66).accessibilityLabel("Description") } header: {
                    listHeader("Description (optional)")
                }
                .instrumentRows()
                Section { TextEditor(text: $phases).frame(minHeight: 88).accessibilityLabel("Phases") } header: {
                    listHeader("Phases")
                } footer: { listFooter("One per line, in order. Items sit in one phase or none.") }
                .instrumentRows()
                if let error {
                    Section { Text(verbatim: error).foregroundStyle(JV.dangerText) }.instrumentRows()
                }
            }
            .font(JV.font(15))
            .instrumentList()
            .navigationTitle("New roadmap")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) }
                ToolbarItem(placement: .confirmationAction) { Button("Create") { Task { await create() } }.disabled(busy) }
            }
        }
        .interactiveDismissDisabled(busy)
        .privacyCover()
        .onChange(of: error) { _, error in
            if let error { AccessibilityNotification.Announcement(error).post() }
        }
    }

    private func create() async {
        guard !busy else { return }
        if let issue = Roadmaps.newRoadmapIssue(title: title, description: description, phases: phases) { error = issue; return }
        guard let parsed = Roadmaps.parsePhases(phases) else { return }
        let summary = description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : description
        busy = true
        error = nil
        do {
            try await model.createRoadmap(NewRoadmap(title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                                                     description: summary, phases: parsed))
            await created()
            dismiss()
        } catch {
            self.error = Roadmaps.writeFailureText(error, revisionMoved: false)
            await created()
        }
        busy = false
    }
}

// MARK: - Roadmap

/// One roadmap: what it is waiting on, an add field and its phases as a rail; an item
/// opens in a sheet. Every write names the revision it was made on; a 409 reloads and
/// says so.
struct RoadmapPage: View {
    /// The open item sheet. A failed write reopens it on Core's state (a new `key`).
    struct SheetState: Identifiable {
        let id: String
        let revision: Int
        var key = 0
        var notice: String?
    }

    @ObservedObject var model: JarvisAppModel
    let id: UUID
    @State private var snapshot: Availability<RoadmapSnapshot> = .loading
    @State private var notice: String?
    @State private var busy = false
    @State private var sheet: SheetState?
    @State private var newTitle = ""
    @State private var newPhase: String?
    @State private var addError: String?
    @FocusState private var addFocused: Bool
    /// A read already in flight must not overwrite a newer answer.
    @State private var loads = 0

    private var found: RoadmapSnapshot? { snapshot.value }

    var body: some View {
        PageScaffold(title: found?.roadmap.title ?? "Roadmap", subtitle: found?.roadmap.archived_at == nil ? "" : "Archived") {
            if let notice, sheet == nil { JobNotice(text: notice) }
            Loaded(source: snapshot, title: "Roadmap", symbol: "map", retry: load) { value in content(value) }
        }
        .sheet(item: $sheet) { state in itemSheet(state) }
        .refreshable { await load() }
        .task { await load() }
        .onChange(of: notice) { _, notice in
            if let notice, sheet == nil { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    @ViewBuilder private func content(_ value: RoadmapSnapshot) -> some View {
        let roadmap = value.roadmap
        let groups = Roadmaps.groupByPhase(roadmap.phases, value.items)
        // The first phase with open work is "now" on the rail.
        let now = groups.first { $0.phase != nil && $0.items.contains { !$0.status.isDone } }?.phase
        if let description = roadmap.description, !description.isEmpty {
            Text(verbatim: description).font(JV.font(14)).foregroundStyle(JV.textSecondary).fixedSize(horizontal: false, vertical: true)
        }
        let tally = Roadmaps.tally(value.items)
        if !tally.isEmpty { TallyChips(tally: tally) }
        let waiting = Roadmaps.waitingOn(value.items)
        if !waiting.isEmpty { waitingCard(waiting) }
        if roadmap.archived_at == nil { addForm(roadmap.phases) }
        if value.items.isEmpty {
            UnavailableState(title: "No items yet. Add the first one above.", kind: .empty, symbol: "map")
        } else {
            ForEach(groups, id: \.phase) { group in phase(group, now: group.phase != nil && group.phase == now, items: value.items) }
        }
    }

    private func waitingCard(_ waiting: [Roadmaps.Waiting]) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            Text("Waiting on").font(JV.font(18, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                .accessibilityAddTraits(.isHeader)
            ForEach(waiting, id: \.item.id) { entry in
                let blocked = entry.item.status == .blocked
                let reason = blocked ? Roadmaps.firstLine(entry.item.notes) : ""
                Button { openSheet(entry.item.id) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                            Text(verbatim: entry.item.title).font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary)
                            if blocked { Badge(text: "Blocked", style: .danger) }
                        }
                        if !reason.isEmpty { Text(verbatim: reason).font(JV.font(13)).foregroundStyle(JV.textSecondary) }
                        if !entry.unmet.isEmpty {
                            Text(verbatim: "waits on " + entry.unmet.map(\.title).joined(separator: ", "))
                                .font(JV.font(13)).foregroundStyle(JV.textSecondary)
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md, border: JV.dangerSolid)
    }

    private func addForm(_ phases: [String]) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.xs) {
            TextField("New item", text: $newTitle)
                .focused($addFocused)
                .submitLabel(.done)
                .onSubmit { Task { await addItem() } }
                .jvInput(focused: addFocused) { addFocused = true }
                .accessibilityLabel("New item title")
            HStack(spacing: JV.Space.xs) {
                Picker("Phase for the new item", selection: $newPhase) {
                    Text("No phase").tag(String?.none)
                    ForEach(phases, id: \.self) { phase in Text(verbatim: phase).tag(String?.some(phase)) }
                }
                Spacer(minLength: 0)
                Button("Add item") { Task { await addItem() } }
                    .buttonStyle(JVButtonStyle(kind: .primary))
                    .disabled(newTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || busy)
            }
            if let addError {
                Text(verbatim: addError).font(JV.font(13, relativeTo: .footnote)).foregroundStyle(JV.dangerText)
            }
        }
    }

    private func phase(_ group: Roadmaps.PhaseGroup, now: Bool, items: [RoadmapItem]) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.xs) {
            HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                Text(verbatim: group.phase ?? "No phase").font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                if now { Badge(text: "Now", style: .accent, dot: false) }
                Spacer(minLength: 0)
                Text(verbatim: itemCount(group.items.count)).font(JV.font(13)).foregroundStyle(JV.textSecondary)
            }
            if group.items.isEmpty {
                Text("Nothing here yet.").font(JV.font(13)).foregroundStyle(JV.textSecondary)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(group.items.enumerated()), id: \.element.id) { index, item in
                        if index > 0 { ListDivider(inset: 0) }
                        Button { openSheet(item.id) } label: { row(item, items: items) }
                            .buttonStyle(.plain)
                    }
                }
                .jvCard(padding: 0)
            }
        }
    }

    private func row(_ item: RoadmapItem, items: [RoadmapItem]) -> some View {
        let unmet = item.depends_on.filter { id in items.first { $0.id == id }.map { !$0.status.isDone } ?? false }.count
        let meta: [String] = [unmet > 0 ? "waits on \(unmet)" : ""]
            + item.links.map { link in Roadmaps.linkLabel(link) + (link.state.map { " (\($0))" } ?? "") }
            + [Roadmaps.firstLine(item.notes)]
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                Text(verbatim: item.title).font(JV.font(15, .medium)).foregroundStyle(JV.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 4)
                Badge(text: item.status.label, style: item.status.badge)
            }
            if let auto = item.auto_status, auto != item.status {
                Badge(text: "auto: \(auto.label)", dot: false).accessibilityLabel("GitHub last derived \(auto.label)")
            }
            let line = meta.filter { !$0.isEmpty }.joined(separator: " · ")
            if !line.isEmpty {
                Text(verbatim: line).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary).lineLimit(3)
            }
        }
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func itemSheet(_ state: SheetState) -> some View {
        if let found, let item = found.items.first(where: { $0.id == state.id }) {
            ItemSheet(item: item, items: found.items, phases: found.roadmap.phases, busy: busy, notice: state.notice,
                      save: { patch in Task { await save(patch) } }, remove: { Task { await remove() } })
                .id(state.key)
        }
    }

    // MARK: Writes

    private func openSheet(_ itemId: String) {
        if let found { sheet = SheetState(id: itemId, revision: found.roadmap.revision) }
    }

    /// Run one write on `revision` (default: the one on screen). After any failure the
    /// page reloads and explains.
    private func write(revision: Int? = nil, _ run: (Int) async throws -> Void) async -> Bool {
        guard let found, !busy else { return false }
        let before = revision ?? found.roadmap.revision
        busy = true
        notice = nil
        loads += 1 // a read already in flight must not overwrite the answer
        defer { busy = false }
        do {
            try await run(before)
            await load()
            return true
        } catch {
            await load()
            notice = Roadmaps.writeFailureText(error, revisionMoved: (self.found?.roadmap.revision ?? before) != before)
            return false
        }
    }

    private func addItem() async {
        let title = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Roadmaps.validText(title, max: 200) else { addError = "The title must be 1 to 200 characters on one line."; return }
        guard (found?.items.count ?? 0) < Roadmaps.maxItems else { addError = "A roadmap holds at most 300 items."; return }
        addError = nil
        let item = NewRoadmapItem(title: title, phase: newPhase)
        if await write({ try await model.createRoadmapItem(id, revision: $0, item: item) }) { newTitle = "" }
    }

    /// Close after a write; after a failure, reopen on Core's state with the notice inside
    /// the sheet (or on the page when the item is gone).
    private func afterSheetWrite(_ ok: Bool) {
        guard let current = sheet, !ok, let found, found.items.contains(where: { $0.id == current.id }) else {
            sheet = nil
            return
        }
        sheet = SheetState(id: current.id, revision: found.roadmap.revision, key: current.key + 1, notice: notice)
        notice = nil
    }

    private func save(_ patch: RoadmapItemPatch) async {
        guard let state = sheet else { return }
        let ok = await write(revision: state.revision) {
            try await model.updateRoadmapItem(id, revision: $0, item: state.id, patch: patch)
        }
        afterSheetWrite(ok)
    }

    private func remove() async {
        guard let state = sheet else { return }
        let ok = await write(revision: state.revision) { try await model.deleteRoadmapItem(id, revision: $0, item: state.id) }
        afterSheetWrite(ok)
    }

    private func load() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead(Roadmaps.path(id), as: RoadmapSnapshot.self).checked(Roadmaps.valid)
        guard !Task.isCancelled, mine == loads else { return }
        // A failed reload keeps the roadmap already on screen.
        if result.value != nil || found == nil { snapshot = result }
        guard let value = result.value else { return }
        if let phase = newPhase, !value.roadmap.phases.contains(phase) { newPhase = nil }
        // A reload without the open item (deleted elsewhere) closes its sheet and says so.
        // During a write, afterSheetWrite handles the sheet, own deletes too.
        if let state = sheet, !busy, !value.items.contains(where: { $0.id == state.id }) {
            sheet = nil
            notice = "This item was deleted elsewhere."
        }
    }
}

// MARK: - Item sheet

/// One item: title, status, phase, notes, dependencies and links, and Delete. The page
/// mounts it fresh per item and revision, writes the patch and remounts it after a
/// conflict, so the draft always starts from Core. Notes are plain text.
private struct ItemSheet: View {
    let item: RoadmapItem
    let items: [RoadmapItem]
    let phases: [String]
    let busy: Bool
    let notice: String?
    let save: (RoadmapItemPatch) -> Void
    let remove: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var draft: RoadmapItemDraft
    /// Against the item as opened: a live reload must not turn a change made elsewhere
    /// into an edit. Saving then meets Core's 409 and remounts.
    @State private var opened: RoadmapItem
    @State private var linkText = ""
    @State private var linkHint = RoadmapLink.Kind.pr
    @State private var linkError: String?
    @State private var confirmingDelete = false

    init(item: RoadmapItem, items: [RoadmapItem], phases: [String], busy: Bool, notice: String?,
         save: @escaping (RoadmapItemPatch) -> Void, remove: @escaping () -> Void) {
        self.item = item
        self.items = items
        self.phases = phases
        self.busy = busy
        self.notice = notice
        self.save = save
        self.remove = remove
        _draft = State(initialValue: RoadmapItemDraft(item))
        _opened = State(initialValue: item)
    }

    private var patch: RoadmapItemPatch? { Roadmaps.patch(opened, draft) }
    private var issue: String? { Roadmaps.draftIssue(draft) }
    // A phase Core no longer has still shows, so the picker never hides a value.
    private var phaseChoices: [String] { item.phase.map { phases.contains($0) ? phases : phases + [$0] } ?? phases }

    var body: some View {
        NavigationStack {
            Form {
                if let notice { Section { Text(verbatim: notice) }.instrumentRows() }
                Section { TextField("Title", text: $draft.title) } header: { listHeader("Title") }
                    .instrumentRows()
                Section {
                    Picker("Status", selection: $draft.status) {
                        ForEach(RoadmapStatus.allCases, id: \.self) { status in Text(status.label).tag(status) }
                    }
                    Picker("Phase", selection: $draft.phase) {
                        Text("No phase").tag(String?.none)
                        ForEach(phaseChoices, id: \.self) { phase in Text(verbatim: phase).tag(String?.some(phase)) }
                    }
                    if let auto = item.auto_status {
                        Text(verbatim: "GitHub last derived \(auto.label)" + (auto != draft.status ? " · your status wins" : ""))
                            .foregroundStyle(JV.textSecondary)
                    }
                }
                .instrumentRows()
                Section {
                    TextEditor(text: $draft.notes).frame(minHeight: 100).accessibilityLabel("Notes")
                } header: {
                    listHeader(draft.status == .blocked ? "Notes: what is it waiting on?" : "Notes")
                } footer: {
                    listFooter(draft.status == .blocked ? "Plain text. The first line shows as the reason under Waiting on." : "Plain text.")
                }
                .instrumentRows()
                Section { dependencies } header: { listHeader("Depends on") }
                    .instrumentRows()
                Section { links } header: { listHeader("Links") }
                    .instrumentRows()
                if let issue { Section { Text(verbatim: issue).foregroundStyle(JV.dangerText) }.instrumentRows() }
                Section {
                    Button("Delete item", role: .destructive) { confirmingDelete = true }.disabled(busy)
                }
                .instrumentRows()
            }
            .font(JV.font(15))
            .instrumentList()
            .navigationTitle(item.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { if let patch, issue == nil, !busy { save(patch) } }
                        .disabled(patch == nil || issue != nil || busy)
                }
            }
            .confirmationDialog("Delete this item?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive, action: remove)
                Button("Keep", role: .cancel) {}
            } message: {
                Text("Items that depend on it no longer will.")
            }
        }
        .interactiveDismissDisabled(busy)
        .privacyCover()
        .onAppear {
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    @ViewBuilder private var dependencies: some View {
        let options = Roadmaps.dependencyOptions(items, item.id)
        if options.isEmpty && draft.depends_on.isEmpty {
            Text("No other items to depend on.").foregroundStyle(JV.textSecondary)
        }
        ForEach(options) { option in
            Toggle(isOn: dependency(option.id)) {
                HStack(spacing: JV.Space.xs) {
                    Text(verbatim: option.title)
                    Badge(text: option.status.label, style: option.status.badge)
                }
            }
            .disabled(!draft.depends_on.contains(option.id) && draft.depends_on.count >= Roadmaps.maxDependsOn)
        }
        // A dependency Core still has but the picker would no longer offer.
        ForEach(draft.depends_on.filter { id in !options.contains { $0.id == id } }, id: \.self) { id in
            Toggle(isOn: dependency(id)) {
                Text(verbatim: items.first { $0.id == id }?.title ?? "An item that no longer exists")
            }
        }
    }

    private func dependency(_ id: String) -> Binding<Bool> {
        Binding(get: { draft.depends_on.contains(id) }, set: { on in
            let rest = draft.depends_on.filter { $0 != id }
            draft.depends_on = on ? rest + [id] : rest
        })
    }

    @ViewBuilder private var links: some View {
        ForEach(Array(draft.links.enumerated()), id: \.offset) { index, link in
            let label = Roadmaps.linkLabel(link)
            HStack(spacing: JV.Space.xs) {
                if let href = Roadmaps.linkHref(link), let url = URL(string: href) {
                    Link(destination: url) { Text(verbatim: label).font(JV.mono(14)) }
                } else {
                    Text(verbatim: label).font(JV.mono(14))
                }
                if let state = link.state { Badge(text: "GitHub: \(state)", dot: false) }
                Spacer(minLength: 0)
                Button("Remove") { draft.links.remove(at: index) }.accessibilityLabel(Text(verbatim: "Remove \(label)"))
            }
            // Separate tap targets inside one form row.
            .buttonStyle(.borderless)
        }
        Picker("Kind for owner/name#12", selection: $linkHint) {
            Text("PR").tag(RoadmapLink.Kind.pr)
            Text("Issue").tag(RoadmapLink.Kind.issue)
        }
        TextField("GitHub link, owner/name#12, owner/name@v1.2.0 or https URL", text: $linkText)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.URL)
            .submitLabel(.done)
            .onSubmit(addTypedLink)
            .accessibilityLabel("Link to add")
        Button("Add link", action: addTypedLink)
            .disabled(linkText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || draft.links.count >= Roadmaps.maxLinks)
        if let linkError { Text(verbatim: linkError).foregroundStyle(JV.dangerText) }
    }

    private func addTypedLink() {
        switch Roadmaps.parseLink(linkText, hint: linkHint) {
        case let .invalid(message):
            linkError = message
        case let .link(link):
            guard let next = Roadmaps.addLink(draft.links, link) else {
                linkError = draft.links.count >= Roadmaps.maxLinks ? "At most 10 links." : "That link is already there."
                return
            }
            draft.links = next
            linkText = ""
            linkError = nil
        }
    }
}
