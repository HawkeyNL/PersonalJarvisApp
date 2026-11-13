import SwiftUI

// Memecoin pot (desktop TradingMemecoin.vue, TradingMemeStrategies.vue,
// TradingMemeStrategy.vue): Core's PAPER trader. Read-only apart from two tightening
// actions, each behind a confirmation: pause new paper entries, and disable one strategy.
// It is a simulation with no wallet; nothing here may suggest real money. Claude's text is
// shown as plain text, never as Markdown.

/// One list row: a title, a trailing figure and a detail line.
private struct MemeRow: View {
    let title: String
    var badge: Badge?
    let value: String
    var valueColor = JV.textPrimary
    let detail: String
    var muted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                Text(verbatim: title).font(JV.mono(14, .medium)).foregroundStyle(JV.textPrimary)
                if let badge { badge }
                Spacer(minLength: 4)
                Text(verbatim: value).font(JV.number(14, .medium)).foregroundStyle(valueColor)
            }
            Text(verbatim: detail).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .opacity(muted ? 0.65 : 1)
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Rows in one list card.
private struct MemeList<Row: View>: View {
    let count: Int
    @ViewBuilder let row: (Int) -> Row

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<count, id: \.self) { index in
                if index > 0 { ListDivider(inset: 0) }
                row(index)
            }
        }
        .jvCard(padding: 0)
    }
}

/// A list read: skeleton, failure with Try again, empty text or the rows.
private struct MemeSection<Value, Content: View>: View {
    let title: String
    let source: Availability<Value>
    let what: String
    let retry: () async -> Void
    @ViewBuilder let content: (Value) -> Content

    var body: some View {
        ListSection(title: title) {
            switch source {
            case .loading: LoadingState(label: "Loading \(what)", rows: 2)
            case let .ok(value): content(value)
            case .unsupported, .error:
                UnavailableState(title: "Could not load \(what)", kind: .error, detail: Memecoin.failureText(failure(source)),
                                 symbol: "bitcoinsign.circle", action: UnavailableState.Action(label: "Try again", run: retry))
            }
        }
    }
}

/// Nil for "no such route on this Core"; else the read failure.
private func failure<Value>(_ source: Availability<Value>) -> LoadFailure? {
    if case let .error(reason) = source { return reason }
    return nil
}

/// The figures of a paper gate as meters.
private struct GateMeters: View {
    struct Row { let label: String; let have: Int; let need: Int; var met = false }

    let rows: [Row]
    let expectancy: String
    var positive: Bool?

    var body: some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            ForEach(rows, id: \.label) { row in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(verbatim: row.label).font(JV.font(13)).foregroundStyle(JV.textPrimary)
                        Spacer()
                        Text(verbatim: "\(Trading.formatDecimal(String(row.have))) of \(Trading.formatDecimal(String(row.need)))\(row.met ? " · met" : "")")
                            .font(JV.mono(13)).foregroundStyle(JV.textPrimary)
                    }
                    ProgressMeter(value: Memecoin.progressPct(row.have, row.need))
                }
                .accessibilityElement(children: .combine)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text("Net per closed trade").font(JV.font(13)).foregroundStyle(JV.textPrimary)
                    Spacer()
                    Text(verbatim: Memecoin.usd(expectancy, sign: true)).font(JV.mono(13)).foregroundStyle(signColor(expectancy))
                }
                if let positive { tradingNote("\(positive ? "Positive" : "Not positive") after every cost") }
            }
            .accessibilityElement(children: .combine)
        }
    }
}

private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: JV.Space.sm) { content() }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
}

// MARK: - Memecoin

/// The paper trader: status, Claude as strategy agent, the report with its gate and goal,
/// open paper positions, candidate decisions and closed paper trades.
struct MemecoinPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var overview: Availability<MemeOverview> = .loading
    @State private var candidates: Availability<MemeCandidates> = .loading
    @State private var trades: Availability<MemeTrades> = .loading
    /// Set only once Core confirmed a pause, until the following overview read.
    @State private var pausedHere = false
    @State private var confirming = false
    @State private var sending = false
    @State private var outcome: Memecoin.Outcome?
    @State private var strategies = false
    @State private var now = Date()
    /// Reads already in flight must not overwrite newer answers.
    @State private var loads = 0
    @State private var overviewLoads = 0
    @State private var candidateLoads = 0

    private var data: MemeOverview? {
        guard var value = overview.value else { return nil }
        if pausedHere { value.paused = true }
        return value
    }

    private var canPause: Bool { data.map { $0.enabled && !$0.paused } ?? false }

    var body: some View {
        let status = data.map(Memecoin.status)
        PageScaffold(title: "Memecoin", subtitle: "Solana · paper simulation") {
            HStack(spacing: JV.Space.xs) {
                if let status { Badge(text: status.label, style: status.style) }
                Badge(text: "Paper", dot: false)
            }
            tradingNote("A simulation on read-only market data. It has no wallet and cannot buy or sell anything.")
            banners
            if let outcome { JobNotice(text: outcome.message, style: outcome.ok ? .accent : .negative) }
            switch overview {
            case .loading:
                LoadingState(label: "Loading the paper trader", rows: 2)
            case .unsupported, .error:
                UnavailableState(title: "Could not load the paper trader", kind: .error, detail: Memecoin.failureText(failure(overview)),
                                 symbol: "bitcoinsign.circle", action: UnavailableState.Action(label: "Try again", run: loadOverview))
            case .ok:
                if let data, let status { overviewContent(data, status) }
            }
            candidateList
            tradeList
        }
        .toolbar {
            if canPause {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Pause paper entries") { outcome = nil; confirming = true }.disabled(sending)
                }
            }
        }
        .confirmationDialog("Pause paper entries?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Pause entries now", role: .destructive) { Task { await pause() } }
            Button("Keep running", role: .cancel) {}
        } message: {
            Text("""
            The trader stops opening new paper positions at once. Exits keep running: open paper positions are still valued and can still close. \
            Core saves the pause and records it in its security audit log. There is no resume here. Resuming is a configuration change on the Home Node.
            """)
        }
        .navigationDestination(isPresented: $strategies) { MemeStrategiesPage(model: model) }
        .refreshable { await load() }
        .task { await load() }
        .task {
            // Re-evaluate staleness while the page is open.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                now = Date()
            }
        }
        .onChange(of: outcome) { _, outcome in
            if let outcome { AccessibilityNotification.Announcement(outcome.message).post() }
        }
    }

    @ViewBuilder private var banners: some View {
        if let data, let age = Memecoin.reportAge(data, now: now) {
            JobNotice(text: "The last report is \(Trading.ageText(age)) old. Core has not reported since; these figures may be out of date.",
                      style: .negative)
        }
        if let stuck = data?.stuck, stuck > 0 {
            JobNotice(text: "\(plural(stuck, "paper position is", "paper positions are")) stuck. "
                      + (stuck == 1 ? "Its exit failed 10 times and is no longer retried; it keeps its slot."
                         : "Their exits failed 10 times each and are no longer retried; they keep their slots.")
                      + " The Core log has the details.", style: .negative)
        }
        card {
            Text("Paper — no real money.").font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary)
            tradingNote("Every amount here is simulated: Core models fills, fees and failed attempts on public market data. No order is placed and no wallet exists.")
            if let report = data?.report {
                let gate = report.gate
                TitleBadgeRow(title: "Paper gate", badge: Badge(text: gate.passed ? "Met" : "Not met", style: gate.passed ? .positive : .neutral))
                GateMeters(rows: [
                    .init(label: "Closed trades", have: gate.closed_trades, need: gate.min_closed_trades, met: gate.enough_trades),
                    .init(label: "Days", have: gate.duration_days, need: gate.min_duration_days, met: gate.enough_time),
                    .init(label: "Trading days", have: gate.trading_days, need: gate.min_trading_days, met: gate.enough_days),
                ], expectancy: gate.net_expectancy_usd, positive: gate.positive_expectancy)
                tradingNote("What the strategy has to show on paper, after every cost. This is a report only: meeting it changes nothing, and this app cannot trade.")
            }
        }
    }

    @ViewBuilder private func overviewContent(_ data: MemeOverview, _ status: Memecoin.Status) -> some View {
        card {
            TitleBadgeRow(title: "Paper trader", badge: Badge(text: status.label, style: status.style))
            tradingNote(status.detail)
            if status.kind == .disabled {
                tradingNote("To try it, the owner sets JARVIS_MEMECOIN_PAPER_ENABLED=true in Core's configuration and restarts Core (see MEMECOIN_PAPER.md in the Core repository). It stays a paper simulation.")
            }
            if data.paused {
                tradingNote("Paused since \(data.paused_at.map { "\(Trading.asOfText($0, now: now)) (\($0))" } ?? "an unknown time").")
                tradingNote(Memecoin.resumeText)
            }
        }
        let agent = Memecoin.agentStatus(data, now: now)
        card {
            TitleBadgeRow(title: "Claude as strategy agent",
                          badge: Badge(text: agent.label + (agent.until.map { " until \(Trading.asOfText($0, now: now))" } ?? ""), style: agent.style))
            tradingNote(agent.detail)
            PanelButton(title: "Strategies and Claude's decisions") { strategies = true }
        }
        if let report = data.report { reportContent(report) }
    }

    @ViewBuilder private func reportContent(_ report: MemeOverview.Report) -> some View {
        tradingNote("Strategy version \(report.strategy_version) · as of \(Trading.asOfText(report.as_of, now: now)) · all amounts in simulated US dollars")
        let stuck = report.open_positions.filter(\.stuck).count
        KpiGrid(kpis: [
            Kpi(label: "Paper equity", value: Memecoin.usd(report.equity_usd), detail: "simulated stake \(Memecoin.usd(report.stake_usd))"),
            Kpi(label: "Net result", value: Memecoin.usd(report.pnl.net_usd, sign: true), color: signColor(report.pnl.net_usd),
                detail: "after fees, failed attempts and AI cost"),
            Kpi(label: "Open paper positions", value: String(report.open_positions.count), detail: stuck > 0 ? "\(stuck) stuck" : "none stuck"),
        ])
        let goal = report.goal
        card {
            TitleBadgeRow(title: "Goal", badge: Badge(text: "\(Trading.formatDecimal(goal.multiple_target, maxPlaces: 2))× the stake", dot: false))
            if let multiple = goal.multiple {
                HStack(alignment: .firstTextBaseline) {
                    Text(verbatim: "Now \(Trading.formatDecimal(multiple, places: 2))×").font(JV.number(20, .semibold, relativeTo: .title3))
                        .foregroundStyle(JV.textPrimary)
                    Spacer()
                    Text(verbatim: goal.reached == true ? "Reached on paper" : "\(Memecoin.usd(goal.remaining_usd)) to go")
                        .font(JV.mono(13)).foregroundStyle(JV.textSecondary)
                }
                .accessibilityElement(children: .combine)
                ProgressMeter(value: Memecoin.goalPct(goal.fraction_to_goal) ?? 0)
                tradingNote("On the way to \(Trading.formatDecimal(goal.multiple_target, maxPlaces: 2))×. Progress only. Reaching it on paper triggers nothing.")
            } else {
                tradingNote("No goal progress to show yet.")
            }
        }
        card {
            Text("Result, net of every cost").font(JV.font(18, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                .accessibilityAddTraits(.isHeader)
            let rows: [(label: String, value: String, signed: Bool)] = [
                ("Realised", report.pnl.realised_usd, true), ("Unrealised", report.pnl.unrealised_usd, true),
                ("Failed-attempt fees", report.pnl.failed_tx_fees_usd, false), ("AI cost", report.pnl.ai_cost_usd, false),
                ("Net", report.pnl.net_usd, true),
            ]
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack {
                    Text(verbatim: row.label).font(JV.font(14, row.label == "Net" ? .semibold : .regular)).foregroundStyle(JV.textPrimary)
                    Spacer()
                    Text(verbatim: Memecoin.usd(row.value, sign: row.signed)).font(JV.number(14, row.label == "Net" ? .semibold : .regular))
                        .foregroundStyle(row.signed ? signColor(row.value) : JV.textPrimary)
                }
                .accessibilityElement(children: .combine)
            }
            tradingNote("Fees inside each trade are already in realised and unrealised.")
        }
        ListSection(title: report.open_positions.isEmpty ? "Open paper positions · nothing open" : "Open paper positions · \(report.open_positions.count) open") {
            if report.open_positions.isEmpty {
                tradingNote("No open paper positions.")
            } else {
                MemeList(count: report.open_positions.count) { index in
                    let p = report.open_positions[index]
                    let exit = p.stuck ? "Stuck" : p.exit_failures > 0 ? plural(p.exit_failures, "failed exit", "failed exits") : "Running"
                    MemeRow(title: Memecoin.shortMint(p.mint), badge: p.stuck ? Badge(text: "Stuck", style: .negative) : nil,
                            value: p.value_usd.map { Memecoin.usd($0) } ?? "No fresh price",
                            detail: ["Opened \(Trading.asOfText(p.opened_at, now: now))", "\(Trading.formatDecimal(p.tokens, maxPlaces: 6)) tokens",
                                     "cost \(Memecoin.usd(p.cost_usd))", p.strategy_version != report.strategy_version ? "strategy v\(p.strategy_version)" : "",
                                     p.stuck ? "" : exit].filter { !$0.isEmpty }.joined(separator: " · "))
                }
            }
        }
    }

    private var candidateList: some View {
        MemeSection(title: "Candidate decisions · newest first", source: candidates, what: "decisions", retry: loadCandidates) { list in
            if list.candidates.isEmpty {
                tradingNote("No decisions recorded yet. A decision is recorded when a token's verdict changes.")
            } else {
                MemeList(count: list.candidates.count) { index in
                    let c = list.candidates[index]
                    MemeRow(title: Memecoin.shortMint(c.mint),
                            badge: Badge(text: c.verdict == .accept ? "Accepted" : "Rejected", style: c.verdict == .accept ? .accent : .neutral, dot: false),
                            value: Trading.formatPrice(c.price_usd, "USD"),
                            detail: [Trading.asOfText(c.observed_at, now: now),
                                     c.verdict == .accept ? "Passed every filter" : Memecoin.reasonLabel(Memecoin.rejectReasons, c.reason),
                                     "liquidity \(Memecoin.usd(c.liquidity_usd))"].joined(separator: " · "))
                }
            }
        }
    }

    private var tradeList: some View {
        MemeSection(title: "Recent paper trades · closed, newest first", source: trades, what: "paper trades", retry: loadTrades) { list in
            if list.trades.isEmpty {
                tradingNote("No closed paper trades yet.")
            } else {
                MemeList(count: list.trades.count) { index in
                    let t = list.trades[index]
                    MemeRow(title: Memecoin.shortMint(t.mint), value: Memecoin.usd(t.net_pnl_usd, sign: true), valueColor: signColor(t.net_pnl_usd),
                            detail: ["Closed \(Trading.asOfText(t.closed_at, now: now))", Memecoin.reasonLabel(Memecoin.exitReasons, t.exit_reason),
                                     "cost \(Memecoin.usd(t.cost_usd))", "out \(Memecoin.usd(t.usd_out))", "exit fees \(Memecoin.usd(t.exit_fees_usd))"]
                                .joined(separator: " · "))
                }
            }
        }
    }

    private func pause() async {
        guard !sending, canPause else { return }
        sending = true
        let result: Result<MemeAnswer, Error>
        do { result = .success(try await model.pauseMemecoin()) } catch { result = .failure(error) }
        let next = Memecoin.pauseOutcome(result)
        outcome = next
        sending = false
        if next.ok {
            // Bridge until Core's own overview arrives; from then on it is the truth.
            pausedHere = true
            await loadOverview()
            pausedHere = false
        } else if next.unsaved {
            // Core paused in memory but did not save it: show what it reports now.
            await loadOverview()
        }
    }

    private func load() async {
        pausedHere = false
        now = Date()
        async let first: Void = loadOverview()
        async let second: Void = loadCandidates()
        async let third: Void = loadTrades()
        _ = await (first, second, third)
    }

    private func loadOverview() async {
        overviewLoads += 1
        let mine = overviewLoads
        let result = await model.coreRead(Memecoin.overviewPath, as: MemeOverview.self).checked(Memecoin.valid)
        guard !Task.isCancelled, mine == overviewLoads else { return }
        overview = result
    }

    private func loadCandidates() async {
        candidateLoads += 1
        let mine = candidateLoads
        let result = await model.coreRead(Memecoin.candidatesPath, as: MemeCandidates.self).checked(Memecoin.valid)
        guard !Task.isCancelled, mine == candidateLoads else { return }
        candidates = result
    }

    private func loadTrades() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead(Memecoin.tradesPath, as: MemeTrades.self).checked(Memecoin.valid)
        guard !Task.isCancelled, mine == loads else { return }
        trades = result
    }
}

// MARK: - Strategies

/// The paper strategies side by side and Claude's decision log. Read-only: disabling
/// happens on a strategy's own page.
private struct MemeStrategiesPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var strategies: Availability<MemeStrategies> = .loading
    @State private var decisions: Availability<MemeDecisions> = .loading
    @State private var overview: Availability<MemeOverview> = .loading
    @State private var open: String?
    @State private var now = Date()
    @State private var loads = 0

    var body: some View {
        PageScaffold(title: "Strategies", subtitle: "Memecoin paper strategies · no wallet, no orders") {
            Badge(text: "Paper", dot: false)
            JobNotice(text: Memecoin.noEdgeText)
            MemeSection(title: "Strategies · results over all versions, net of every cost", source: strategies, what: "strategies",
                        retry: load) { list in
                if list.strategies.isEmpty {
                    tradingNote("No strategies yet. Core records the seed strategies when the paper trader starts.")
                } else {
                    MemeList(count: list.strategies.count) { index in
                        let s = list.strategies[index]
                        let state = Memecoin.state(s)
                        let p = s.performance
                        Button { open = s.id } label: {
                            MemeRow(title: s.id, badge: Badge(text: state.label, style: state.style),
                                    value: Memecoin.usd(p.net_usd, sign: true), valueColor: signColor(p.net_usd),
                                    detail: ["version \(s.active_version)", s.family?.label ?? "Unknown family", plural(p.trades, "trade", "trades"),
                                             "per trade \(p.trades > 0 ? Memecoin.usd(p.expectancy_usd, sign: true) : "—")",
                                             "win rate \(p.trades > 0 ? Memecoin.bpsPct(p.win_rate_bps) : "—")",
                                             "max drawdown \(Memecoin.usd(p.max_drawdown_usd))",
                                             "\(Memecoin.changedBy(s.changed_by)) · \(Trading.asOfText(s.changed_at, now: now))"].joined(separator: " · "))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            tradingNote("Open a strategy for its versions, Claude's reasons, the backtest and its paper gate.")
            decisionList
        }
        .navigationDestination(item: $open) { id in MemeStrategyPage(model: model, id: id) }
        .refreshable { await load() }
        .task { await load() }
    }

    private var decisionList: some View {
        VStack(alignment: .leading, spacing: JV.Space.xs) {
            let agent = overview.value.map { Memecoin.agentStatus($0, now: now) }
            if let agent {
                TitleBadgeRow(title: "Claude's decisions",
                              badge: Badge(text: agent.label + (agent.until.map { " until \(Trading.asOfText($0, now: now))" } ?? ""), style: agent.style))
                tradingNote(agent.detail)
            }
            MemeSection(title: agent == nil ? "Claude's decisions" : "Log", source: decisions, what: "decisions", retry: load) { list in
                if list.decisions.isEmpty {
                    tradingNote("No decisions yet. Claude decides only when the paper profile turns it on.")
                } else {
                    MemeList(count: list.decisions.count) { index in
                        let d = list.decisions[index]
                        let view = Memecoin.decisionView(d)
                        let owner = Memecoin.versionOwner(strategies.value?.strategies ?? [], d.strategy_version)
                        let strategy = d.strategy_version.map { version in owner.map { "\($0) version \(version)" } ?? "version \(version)" }
                        MemeRow(title: view.kind, value: d.size_bps.map(Memecoin.bpsPct) ?? "—",
                                detail: [Trading.asOfText(d.at, now: now), strategy ?? "", d.mint.map(Memecoin.shortMint) ?? "", view.reason,
                                         Memecoin.plain(d.rationale).map { "Claude's note (AI-written): \($0)" } ?? "", d.model ?? ""]
                                    .filter { !$0.isEmpty }.joined(separator: " · "),
                                muted: view.muted)
                    }
                }
            }
            tradingNote("\"Claude called\" marks that a call started; it counts toward the call budget. Size is the share of what the strategy would enter. Notes are Claude's own words, shown as written and never checked by Core; Core checks every action again before it happens.")
        }
    }

    private func load() async {
        loads += 1
        let mine = loads
        now = Date()
        async let list = model.coreRead(Memecoin.strategiesPath, as: MemeStrategies.self)
        async let log = model.coreRead(Memecoin.decisionsPath, as: MemeDecisions.self)
        async let status = model.coreRead(Memecoin.overviewPath, as: MemeOverview.self)
        let (a, b, c) = (await list.checked(Memecoin.valid), await log.checked(Memecoin.valid), await status.checked(Memecoin.valid))
        guard !Task.isCancelled, mine == loads else { return }
        strategies = a
        decisions = b
        overview = c
    }
}

// MARK: - Strategy

/// One paper strategy: results, gate, version timeline with Core's diff, Claude's
/// reasoning (AI-written, plain text) and the backtest. Disable is immediate; enabling is
/// signed with Touch ID on a Mac only.
private struct MemeStrategyPage: View {
    @ObservedObject var model: JarvisAppModel
    let id: String
    @State private var detail: Availability<MemeStrategyDetail> = .loading
    @State private var confirming = false
    @State private var sending = false
    @State private var outcome: Memecoin.Outcome?
    @State private var loads = 0

    var body: some View {
        let s = detail.value?.strategy
        let state = s.map(Memecoin.state)
        PageScaffold(title: id, subtitle: s?.family.map { "Family \($0.label) · paper simulation" } ?? "Paper simulation") {
            HStack(spacing: JV.Space.xs) {
                if let state { Badge(text: state.label, style: state.style) }
                Badge(text: "Paper", dot: false)
                Spacer()
                if let s, !s.enabled { tradingNote(Memecoin.enableElsewhere) }
            }
            JobNotice(text: Memecoin.noEdgeText)
            if let outcome { JobNotice(text: outcome.message, style: outcome.ok ? .accent : .negative) }
            switch detail {
            case .loading:
                LoadingState(label: "Loading the strategy", rows: 2)
            case .unsupported:
                UnavailableState(title: "Could not load this strategy", kind: .error,
                                 detail: "Core has no strategy with this id, or this Core has no strategies yet. Update Core on your Home Node.",
                                 symbol: "bitcoinsign.circle", action: UnavailableState.Action(label: "Try again", run: load))
            case let .error(reason):
                UnavailableState(title: "Could not load this strategy", kind: .error, detail: Memecoin.failureText(reason),
                                 symbol: "bitcoinsign.circle", action: UnavailableState.Action(label: "Try again", run: load))
            case let .ok(value):
                content(value)
            }
        }
        .toolbar {
            if s?.enabled == true {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Disable") { outcome = nil; confirming = true }.disabled(sending)
                }
            }
        }
        .confirmationDialog("Disable \(id)?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Disable now", role: .destructive) { Task { await disable() } }
            Button("Keep enabled", role: .cancel) {}
        } message: {
            Text("""
            It stops opening new paper positions at once. Its open paper positions keep exiting by their own rules. \
            It stays disabled until you enable it again with a signature from your Mac. Claude cannot enable it.
            """)
        }
        .refreshable { await load() }
        .task { await load() }
        .onChange(of: outcome) { _, outcome in
            if let outcome { AccessibilityNotification.Announcement(outcome.message).post() }
        }
    }

    @ViewBuilder private func content(_ d: MemeStrategyDetail) -> some View {
        let p = d.strategy.performance
        KpiGrid(kpis: [
            Kpi(label: "Net result", value: Memecoin.usd(p.net_usd, sign: true), color: signColor(p.net_usd),
                detail: "\(plural(p.trades, "closed trade", "closed trades")) · costs \(Memecoin.usd(p.costs_usd))"),
            Kpi(label: "Per trade", value: p.trades > 0 ? Memecoin.usd(p.expectancy_usd, sign: true) : "—", color: signColor(p.expectancy_usd),
                detail: "win rate \(p.trades > 0 ? Memecoin.bpsPct(p.win_rate_bps) : "—") · profit factor \(p.profit_factor.map { Trading.formatDecimal($0, places: 2) } ?? "—")"),
            Kpi(label: "Max drawdown", value: Memecoin.usd(p.max_drawdown_usd), detail: "of the cumulative net result"),
        ])
        card {
            TitleBadgeRow(title: "Paper gate, version \(d.strategy.active_version)",
                          badge: Badge(text: d.gate.map { "snapshot \(Trading.asOfText($0.at))" } ?? "No snapshot", dot: false))
            if let gate = d.gate {
                GateMeters(rows: [
                    .init(label: "Closed trades", have: gate.closed_trades, need: gate.min_closed_trades),
                    .init(label: "Days", have: gate.duration_days, need: gate.min_duration_days),
                    .init(label: "Trading days", have: gate.trading_days, need: gate.min_trading_days),
                ], expectancy: gate.net_expectancy_usd)
                tradingNote("A report only: meeting it changes nothing, and this app cannot trade.")
            } else {
                tradingNote("No gate snapshot for this version yet. Core writes one every hour.")
            }
        }
        ListSection(title: "Versions · newest first, up to 20") {
            if d.versions.isEmpty { tradingNote("No versions listed.") }
            ForEach(d.versions, id: \.version) { version in versionCard(version, active: version.version == d.strategy.active_version) }
            tradingNote("Changes are computed by Core. A backtest replays stored observations of recently migrated tokens; it is a sanity check, not a forecast.")
        }
    }

    private func versionCard(_ v: MemeStrategyDetail.Version, active: Bool) -> some View {
        card {
            HStack(spacing: JV.Space.xs) {
                Text(verbatim: "Version \(v.version)").font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary).accessibilityAddTraits(.isHeader)
                Spacer()
                Badge(text: v.author == .claude ? "Claude" : "Seed", style: v.author == .claude ? .accent : .neutral, dot: false)
                if active { Badge(text: "Active", style: .positive) }
            }
            tradingNote(Trading.asOfText(v.created_at) + (v.parent_version.map { " · from version \($0)" } ?? ""))
            if let diff = v.diff {
                if diff.isEmpty { tradingNote("No parameter changed.") }
                ForEach(Array(diff.enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: row.field).font(JV.mono(13, .medium)).foregroundStyle(JV.textPrimary)
                        Text(verbatim: "Was \(row.from?.text ?? "—") · now \(row.to?.text ?? "—")").font(JV.mono(12)).foregroundStyle(JV.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
            } else {
                tradingNote(v.parent_version == nil ? "First version: nothing to compare." : "Core could not compare this version with its parent.")
            }
            if let rationale = Memecoin.plain(v.rationale) {
                VStack(alignment: .leading, spacing: 4) {
                    tradingNote("Claude's reasoning · AI-written text, shown as written; display only")
                    Text(verbatim: rationale).font(JV.font(14)).foregroundStyle(JV.textPrimary).fixedSize(horizontal: false, vertical: true)
                        .padding(.leading, JV.Space.sm)
                        .overlay(alignment: .leading) { Rectangle().fill(JV.border).frame(width: 2) }
                }
            }
            Text(verbatim: "Backtest: " + backtestText(v.backtest)).font(JV.font(13)).foregroundStyle(JV.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            if !v.parameters.isEmpty {
                DisclosureGroup("Parameters") {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(v.parameters.enumerated()), id: \.offset) { _, parameter in
                            Text(verbatim: "\(parameter.field): \(parameter.value)").font(JV.mono(12)).foregroundStyle(JV.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(JV.font(13))
            }
        }
    }

    private func backtestText(_ b: MemeStrategyDetail.Backtest?) -> String {
        guard let b else { return "none recorded." }
        switch b.status {
        case .ok:
            return "\(Trading.formatDecimal(String(b.trades ?? 0))) trades on \(b.mints) tokens · net \(Memecoin.usd(b.net_usd, sign: true)) · per trade "
                + "\(Memecoin.usd(b.expectancy_usd, sign: true)) · win rate \(Memecoin.bpsPct(b.win_rate_bps ?? 0)) · max drawdown \(Memecoin.usd(b.max_drawdown_usd))"
        case .unbacktested:
            return "unbacktested: too little stored data (\(b.mints) tokens, \(Trading.formatDecimal(String(b.points))) points)."
        case .failed:
            return "failed to run (the Core log says why)."
        }
    }

    private func disable() async {
        guard !sending, detail.value?.strategy.enabled == true else { return }
        sending = true
        let target = id
        let result: Result<MemeAnswer, Error>
        do { result = .success(try await model.disableMemeStrategy(target)) } catch { result = .failure(error) }
        outcome = Memecoin.disableOutcome(target, result)
        sending = false
        await load()
    }

    private func load() async {
        guard let path = Memecoin.strategyPath(id) else { detail = .unsupported; return }
        loads += 1
        let mine = loads
        let result = await model.coreRead(path, as: MemeStrategyDetail.self).checked { Memecoin.valid($0, id: id) }
        guard !Task.isCancelled, mine == loads else { return }
        detail = result
    }
}
