import SwiftUI

// Activiteit tab and the approval sheets (canvas I-Activity, I-Approve, I-Unlock).

/// Activiteit: everything waiting on the owner first (unlock requests, agent
/// approvals, pairing requests), then agent history and the Tasks and Agents
/// pages. Agent approvals and unlock requests open as sheets.
struct ActivityView: View {
    @ObservedObject var model: JarvisAppModel
    let unlocks: [UnlockRequestItem]
    let openUnlock: (UnlockRequestItem) -> Void
    @State private var pending: Availability<[PendingAction]> = .loading
    @State private var sessions: Availability<[CodingSession]> = .loading
    @State private var audit: Availability<[AgentAuditEntry]> = .loading
    @State private var pairingCount: Int?
    @State private var refresh = 0
    @State private var now = Date()
    @Environment(\.openAgentApproval) private var openAgentApproval

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ListSection(title: "Waiting on you") {
                    ForEach(unlocks) { item in
                        UnlockRow(item: item, now: now) { openUnlock(item) }
                    }
                    if let state = UnavailableState.forSource(pending, title: "Agent approvals", symbol: "exclamationmark.triangle",
                                                              forbidden: "Agent actions are turned off in Core.") { state }
                    ForEach(pending.value ?? []) { action in
                        PendingActionRow(item: action, time: relativeTime(action.created_at, now: now)) {
                            openAgentApproval(action) { await load() }
                        }
                    }
                    PairingRequestsSection(model: model, refresh: refresh, showsEmpty: false, count: $pairingCount)
                    if unlocks.isEmpty && pending.value?.isEmpty == true && pairingCount == 0 {
                        ActivityList(rows: [], emptyText: "Nothing is waiting for you.")
                    }
                }
                ListSection(title: "History") {
                    if let state = UnavailableState.forSource(audit, title: "Agent history", symbol: "shield",
                                                              forbidden: "Agent actions are turned off in Core.") { state }
                    if let entries = audit.value {
                        ActivityList(rows: Array(auditRows(entries, now: now).prefix(10)),
                                     emptyText: "Core recorded no agent actions yet.")
                    }
                }
                ListSection(title: "More") {
                    RouteList(routes: AppTab.activity.links) { route in
                        route == .tasks ? tasksCard(sessions: sessions, pending: pending).line1 : nil
                    }
                }
            }
            .padding(16)
        }
        .background(JV.bgPage)
        .navigationTitle(AppTab.activity.title)
        .toolbarBackground(JV.bgPage, for: .navigationBar)
        .refreshable {
            refresh += 1
            await load()
        }
        .task {
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }

    private func load() async {
        async let pending = model.coreRead("/v1/agent/pending", as: PendingActionList.self)
        async let sessions = model.coreRead("/v1/coding/sessions", as: CodingSessionList.self)
        async let audit = model.coreRead("/v1/agent/audit", as: AgentAuditResponse.self)
        let results = (await pending, await sessions, await audit)
        guard !Task.isCancelled else { return }
        self.pending = results.0.map(\.pending)
        self.sessions = results.1.map(\.sessions)
        self.audit = results.2.map(\.entries)
        now = Date()
    }
}

/// One line for a pending agent action in lists.
func pendingTitle(_ item: PendingAction) -> String {
    switch AgentReview.of(item) {
    case let .approvable(.writeFile(path, _), _): "An agent wants to write " + AgentReview.asciiPath(path)
    case .refused: "Agent action: " + PairingFingerprint.displayName(item.action, max: 40)
    }
}

/// A pending agent action in a list; tapping opens the review sheet.
struct PendingActionRow: View {
    let item: PendingAction
    let time: String
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(kind).font(JV.font(12, .medium)).foregroundStyle(JV.negativeText)
                    Spacer()
                    Text(verbatim: time).font(JV.number(12)).foregroundStyle(JV.textSecondary)
                }
                Text(verbatim: pendingTitle(item))
                    .font(JV.font(15, .semibold))
                    .foregroundStyle(JV.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Read everything before you approve.")
                    .font(JV.font(13))
                    .foregroundStyle(JV.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jvCard(padding: 16, border: JV.negativeSolid)
            .contentShape(RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens the details to approve or deny.")
    }

    private var kind: String {
        if case .approvable = AgentReview.of(item) { return "File" }
        return "Can only be denied here"
    }
}

/// An unlock request in a list; tapping opens the unlock sheet.
struct UnlockRow: View {
    let item: UnlockRequestItem
    let now: Date
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Unlock request").font(JV.font(12, .medium)).foregroundStyle(JV.negativeText)
                Text(verbatim: "\"\(PairingFingerprint.displayName(item.device_name, max: 40))\" asks to unlock Jarvis")
                    .font(JV.font(15, .semibold))
                    .foregroundStyle(JV.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(verbatim: unlockSubtitle(item, now: now))
                    .font(JV.font(13))
                    .foregroundStyle(JV.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jvCard(padding: 16, border: JV.negativeSolid)
            .contentShape(RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens the request to approve or deny.")
    }
}

private func unlockSubtitle(_ item: UnlockRequestItem, now: Date) -> String {
    let asked = relativeTime(Date(timeIntervalSince1970: TimeInterval(item.created_at)), now: now)
    return PairingFingerprint.displayName(item.platform, max: 16) + (asked.isEmpty ? "" : " · asked \(asked)")
}

// MARK: - Agent approval sheet (I-Approve)

private struct ReviewEndKey: PreferenceKey {
    static let defaultValue = CGFloat.infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = min(value, nextValue()) }
}

private struct ReviewViewportKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// One pending agent action, shown in full from the JSON whose hash the approval
/// signs. Anything this app cannot parse exactly is shown raw (escaped) and can
/// only be denied. Approve stays off until the end of the details has been on
/// screen (`ReviewGate`); Approve and Deny then each need Face ID / Touch ID and
/// re-read the action by id. Core has no undo for an approval, so none is offered.
struct AgentApprovalSheet: View {
    @ObservedObject var model: JarvisAppModel
    let item: PendingAction
    let time: String
    let decided: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var decisionError: String?
    @State private var gate = ReviewGate()
    @State private var viewport: CGFloat = 0
    @State private var endY = CGFloat.infinity

    private var review: AgentReview { AgentReview.of(item) }
    private var approvable: Bool {
        if case .approvable = review { return true }
        return false
    }
    private static let space = "agent-approval-details"

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    details
                    if approvable {
                        Text("You can only approve what you have seen in full. Approving asks for Face ID or Touch ID.")
                            .font(JV.font(12, relativeTo: .caption))
                            .foregroundStyle(JV.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    // The gate opens once this marker has been inside the visible area.
                    Text("End of details")
                        .font(JV.font(12, relativeTo: .caption))
                        .foregroundStyle(JV.textSecondary)
                        .frame(maxWidth: .infinity)
                        .background(GeometryReader { proxy in
                            Color.clear.preference(key: ReviewEndKey.self, value: proxy.frame(in: .named(Self.space)).minY)
                        })
                }
                .padding(.horizontal, 20)
                .padding(.top, 28)
                .padding(.bottom, 16)
            }
            .coordinateSpace(.named(Self.space))
            .background(GeometryReader { proxy in
                Color.clear.preference(key: ReviewViewportKey.self, value: proxy.size.height)
            })
            .onPreferenceChange(ReviewViewportKey.self) { value in
                viewport = value
                gate.observe(endY: endY, viewport: viewport)
            }
            .onPreferenceChange(ReviewEndKey.self) { value in
                endY = value
                gate.observe(endY: endY, viewport: viewport)
            }
            actions
        }
        .onChange(of: gate.reachedEnd) { _, reached in
            if reached { AccessibilityNotification.Announcement("Approve is available").post() }
        }
        .background(JV.bgSurface)
        .presentationBackground(JV.bgSurface)
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(busy)
        .privacyCover()
    }

    private var header: some View {
        HStack(spacing: 12) {
            IconBadge(symbol: approvable ? "doc.text" : "exclamationmark.triangle", size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: approvable ? "Write a file?" : PairingFingerprint.displayName(item.action, max: 40))
                    .font(JV.font(20, .semibold, relativeTo: .title3))
                    .foregroundStyle(JV.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text(verbatim: time).font(JV.number(13)).foregroundStyle(JV.textSecondary)
            }
        }
    }

    @ViewBuilder private var details: some View {
        switch review {
        case let .approvable(.writeFile(path, content), _):
            field("Path", AgentReview.asciiPath(path))
            // Whether the file existed is Home Node state, not part of what is signed.
            if let state = item.preview?.split(separator: "\n", omittingEmptySubsequences: false).first, !state.isEmpty {
                Text(verbatim: "When proposed (not signed): " + AgentReview.visible(String(state), keepNewlines: false))
                    .font(JV.font(13)).foregroundStyle(JV.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            field("Content \u{00B7} \(content.utf8.count) bytes", AgentReview.visible(content))
        case let .refused(reason, raw):
            Text(verbatim: reason).font(JV.font(13)).foregroundStyle(JV.negativeText)
                .fixedSize(horizontal: false, vertical: true)
            if let raw { field("Raw action", AgentReview.visible(raw)) }
        }
    }

    private func field(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Eyebrow(text: label)
            // Strictly left-to-right: each line isolated at display time only.
            Text(verbatim: AgentReview.ltrLines(value))
                .environment(\.layoutDirection, .leftToRight)
                .multilineTextAlignment(.leading)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(JV.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
        }
    }

    private var actions: some View {
        VStack(spacing: 10) {
            if let decisionError {
                Text(verbatim: decisionError)
                    .font(JV.font(13)).foregroundStyle(JV.dangerText)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if approvable {
                Button { Task { await decide(approve: true) } } label: {
                    Text(gate.reachedEnd ? (busy ? "Verifying\u{2026}" : "Approve") : "Scroll to the end to approve")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(JVButtonStyle(size: .large))
                .disabled(!gate.reachedEnd || busy)
                .accessibilityHint(Text(gate.reachedEnd ? "Asks for Face ID or Touch ID." : "Turns on after you have seen all details."))
            }
            Button(role: .destructive) { Task { await decide(approve: false) } } label: {
                Text("Deny").frame(maxWidth: .infinity)
            }
            .buttonStyle(JVButtonStyle(kind: .secondary, size: .large))
            .disabled(busy)
        }
        // The bar must leave room to read the details at the largest text sizes.
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(JV.bgSurface)
        .overlay(alignment: .top) { Rectangle().fill(JV.separator).frame(height: 1) }
    }

    private func decide(approve: Bool) async {
        guard !busy, !approve || gate.reachedEnd else { return }
        busy = true
        decisionError = nil
        do {
            if approve {
                guard case let .approvable(_, sha256) = review else { busy = false; return }
                try await model.approveAgentAction(item.pending_id, shownSha256: sha256)
            } else {
                try await model.denyAgentAction(item.pending_id)
            }
            let announcement: String = approve ? "Approved" : "Denied"
            AccessibilityNotification.Announcement(announcement).post()
            busy = false
            await decided()
            dismiss()
        } catch {
            // Server text is shown sanitised and bounded.
            decisionError = PairingFingerprint.displayName((error as? LocalizedError)?.errorDescription ?? "The decision could not be completed.", max: 160)
            busy = false
        }
    }
}

// MARK: - Unlock sheet (I-Unlock)

/// Another device of this account asks to be unlocked. Approve first asks the
/// owner to pick the number the other device shows (a wrong pick denies);
/// approving and "Dit ben ik niet" (deny) then need Face ID / Touch ID. Each
/// step re-reads the request by id. Core sends no expiry for unlock requests,
/// so there is no countdown.
struct UnlockSheet: View {
    @ObservedObject var model: JarvisAppModel
    let item: UnlockRequestItem
    /// The request is gone (approved, denied or denied by a wrong pick).
    let resolved: (UUID) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var busy = false
    @State private var choices: [Int]?
    @State private var decisionError: String?
    @State private var outcome: String?

    private var name: String { PairingFingerprint.displayName(item.device_name, max: 40) }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                IconBadge(symbol: "lock", size: 64)
                if let outcome {
                    Text(verbatim: outcome)
                        .font(JV.font(20, .semibold, relativeTo: .title3))
                        .foregroundStyle(JV.textPrimary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Done") { dismiss() }
                        .buttonStyle(JVButtonStyle(kind: .secondary, size: .large))
                } else {
                    request
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 24)
        }
        .background(JV.bgSurface)
        .presentationBackground(JV.bgSurface)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(busy)
        .privacyCover()
    }

    @ViewBuilder private var request: some View {
        VStack(spacing: 6) {
            Text(verbatim: "Unlock \"\(name)\"?")
                .font(JV.font(24, .semibold, relativeTo: .title2))
                .foregroundStyle(JV.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Text(verbatim: unlockSubtitle(item, now: Date()))
                .font(JV.font(13))
                .foregroundStyle(JV.textSecondary)
        }
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        Text("Approve only if you just asked on that device.")
            .font(JV.font(15))
            .foregroundStyle(JV.textPrimary)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        if let choices {
            Text("Pick the number shown on that device. A wrong number denies the request.")
                .font(JV.font(13))
                .foregroundStyle(JV.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            let layout = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
            layout {
                ForEach(choices, id: \.self) { choice in
                    Button { Task { await pick(choice) } } label: {
                        Text(verbatim: UnlockCode.display(choice))
                            .font(JV.mono(30, .medium, relativeTo: .title))
                            .frame(maxWidth: .infinity, minHeight: 64)
                    }
                    .buttonStyle(JVButtonStyle(kind: .secondary, size: .large))
                }
            }
            .disabled(busy)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Pick the number")
        } else {
            Button { Task { await approve() } } label: {
                Text(busy ? "Verifying\u{2026}" : "Approve").frame(maxWidth: .infinity)
            }
            .buttonStyle(JVButtonStyle(size: .large))
            .disabled(busy)
        }
        if let decisionError {
            Text(verbatim: decisionError)
                .font(JV.font(13)).foregroundStyle(JV.dangerText)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        Button(role: .destructive) { Task { await deny() } } label: {
            Text("Dit ben ik niet").frame(maxWidth: .infinity)
        }
        .buttonStyle(JVButtonStyle(kind: .quiet, size: .large))
        .disabled(busy)
        .accessibilityHint("Denies the request.")
    }

    private func approve() async {
        guard !busy else { return }
        busy = true
        decisionError = nil
        choices = nil
        do { choices = try await model.unlockChoices(item.id) } catch { show(error) }
        busy = false
    }

    private func deny() async {
        guard !busy else { return }
        busy = true
        decisionError = nil
        choices = nil
        do {
            try await model.denyUnlock(item.id)
            finish("Denied. \"\(name)\" was not unlocked.")
        } catch { show(error) }
        busy = false
    }

    private func pick(_ choice: Int) async {
        guard !busy, choices != nil else { return }
        busy = true
        choices = nil
        decisionError = nil
        do {
            try await model.approveUnlock(item.id, choice: choice)
            finish("\"\(name)\" is unlocked.")
        } catch {
            if error is UnlockWrongNumber {
                finish(PairingFingerprint.displayName(UnlockWrongNumber().errorDescription, max: 160))
            } else { show(error) }
        }
        busy = false
    }

    private func finish(_ text: String) {
        outcome = text
        resolved(item.id)
        AccessibilityNotification.Announcement(text).post()
    }

    private func show(_ error: Error) {
        // Server text is shown sanitised and bounded.
        decisionError = PairingFingerprint.displayName((error as? LocalizedError)?.errorDescription ?? "The decision could not be completed.", max: 160)
    }
}
