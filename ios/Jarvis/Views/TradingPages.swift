import Charts
import SwiftUI
import UIKit

// Trading (desktop TradingDesk.vue, TradingStocks.vue, TradingStock.vue, TradingPositions.vue
// TradingCrypto.vue and the holdings view Trading.vue): Core's read-only IBKR portfolio, one
// instrument's prices, every open position, the crypto wallets and the manual holdings.
// Towards IBKR this is read-only:
// nothing here can place an order. Amounts are Core's decimal strings, formatted as text.

private enum TradingRoute: Hashable { case stocks, positions, holdings, ibkr, memecoin, crypto, stock(Int) }

/// Colour of a signed amount; the sign is always in the text too.
func signColor(_ value: String?) -> Color {
    switch Trading.sign(value) {
    case .some(1): JV.positiveText
    case .some(-1): JV.negativeText
    default: JV.textSecondary
    }
}

func plural(_ count: Int, _ one: String, _ many: String) -> String { count == 1 ? "1 \(one)" : "\(count) \(many)" }

// MARK: - Shared parts

/// Every trading read renders through this: loading, not connected (with what to do),
/// error, and a stale banner above the data. `content` gets the data and whether it is
/// empty; each screen words its own empty state.
private struct TradingStatus<Value, Content: View>: View {
    let state: TradingState<Value>
    let what: String
    var rows = 3
    let retry: () async -> Void
    @ViewBuilder let content: (Value, Bool) -> Content

    var body: some View {
        switch state {
        case .loading:
            LoadingState(label: "Loading \(what)", rows: rows)
        case let .notConnected(reason):
            UnavailableState(title: Trading.notConnectedText(reason).title, kind: .error, detail: Trading.notConnectedText(reason).detail,
                             symbol: "chart.line.uptrend.xyaxis", action: UnavailableState.Action(label: "Try again", run: retry),
                             errorLabel: "No trading data")
        case let .error(reason):
            UnavailableState(title: "Could not load \(what)", kind: .error, detail: Trading.errorText(reason),
                             symbol: "chart.line.uptrend.xyaxis", action: UnavailableState.Action(label: "Try again", run: retry))
        case let .ok(value, empty, stale):
            if let stale {
                VStack(alignment: .leading, spacing: JV.Space.xs) {
                    JobNotice(text: "Prices are \(Trading.ageText(stale)) old. Core got no newer data from IBKR; values may be off.",
                              style: .negative)
                    Button("Try again") { Task { await retry() } }.buttonStyle(JVButtonStyle(kind: .secondary, size: .small))
                }
            }
            content(value, empty)
        }
    }
}

struct Kpi: Identifiable {
    let label: String
    let value: String
    var color = JV.textPrimary
    let detail: String
    var id: String { label }
}

/// Key figures as small cards, two per row; one per row at accessibility text sizes.
struct KpiGrid: View {
    let kpis: [Kpi]
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .top), count: typeSize.isAccessibilitySize ? 1 : 2)
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(kpis) { kpi in
                VStack(alignment: .leading, spacing: JV.Space.xxs) {
                    Text(verbatim: kpi.label).font(JV.font(13)).foregroundStyle(JV.textSecondary)
                    Text(verbatim: kpi.value).font(JV.number(20, .semibold, relativeTo: .title3)).foregroundStyle(kpi.color)
                        .lineLimit(2).minimumScaleFactor(0.7)
                    Text(verbatim: kpi.detail).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .jvCard(padding: JV.Space.md)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

private func valueKpis(_ data: Portfolio) -> [Kpi] {
    [
        Kpi(label: "Today", value: Trading.formatMoney(data.day_pnl?.value, data.base_currency, sign: true),
            color: signColor(data.day_pnl?.value), detail: Trading.formatPercent(data.day_change_pct?.value)),
        Kpi(label: "Total return", value: Trading.formatMoney(data.total_pnl?.value, data.base_currency, sign: true),
            color: signColor(data.total_pnl?.value),
            detail: data.total_return_pct?.value.map { "\(Trading.formatPercent($0)) since start" } ?? "Since start"),
    ]
}

/// A filled bar of 0..100 with an optional target mark.
struct ProgressMeter: View {
    let value: Double
    var target: Double?
    var over = false

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(JV.bgSunken)
                Capsule().fill(over ? JV.negativeSolid : JV.accentSolid).frame(width: width * min(100, max(0, value)) / 100)
                if let target {
                    Rectangle().fill(JV.textPrimary).frame(width: 2, height: 14).offset(x: width * min(100, max(0, target)) / 100 - 1)
                }
            }
        }
        .frame(height: 8)
        .accessibilityHidden(true)
    }
}

private struct ChartPoint: Identifiable { let id: Int; let bar: ChartBar }

private struct AveragePoint: Identifiable { let id: Int; let value: Double }

/// One moving average: `id` is its length in bars.
private struct AverageSeries: Identifiable { let id: Int; let points: [AveragePoint] }

/// A read's chart, worked out once when it arrives: the series to draw, the change over
/// the period and the figures VoiceOver reads.
private struct ChartData {
    let series: ChartSeries
    let ratio: String?
    let last: String?
    let low: String?
    let high: String?

    init(bars: [InstrumentBars.Bar]) {
        series = ChartSeries(bars.compactMap { bar -> ChartBar? in
            guard let open = Trading.chartNumber(bar.o), let high = Trading.chartNumber(bar.h), let low = Trading.chartNumber(bar.l),
                  let close = Trading.chartNumber(bar.c) else { return nil }
            return ChartBar(open: open, high: high, low: low, close: close, volume: bar.volume)
        }, averages: [50, 200])
        ratio = bars.last.flatMap { Trading.changeRatio(bars[0].o, $0.c) }
        last = bars.last?.c
        low = bars.min { (Trading.chartNumber($0.l) ?? 0) < (Trading.chartNumber($1.l) ?? 0) }?.l
        high = bars.max { (Trading.chartNumber($0.h) ?? 0) < (Trading.chartNumber($1.h) ?? 0) }?.h
    }

    init(points: [PortfolioHistory.Point]) {
        series = ChartSeries(points.compactMap { point in
            Trading.chartNumber(point.value).map { ChartBar(open: $0, high: $0, low: $0, close: $0) }
        })
        ratio = points.count > 1 ? Trading.changeRatio(points[0].value, points[points.count - 1].value) : nil
        last = points.last?.value
        low = points.min { (Trading.chartNumber($0.value) ?? 0) < (Trading.chartNumber($1.value) ?? 0) }?.value
        high = points.max { (Trading.chartNumber($0.value) ?? 0) < (Trading.chartNumber($1.value) ?? 0) }?.value
    }

    /// "AAPL, 3M: +4.80%. Last $176.40, low $160.00, high $180.00, 63 bars."
    func label(_ title: String, money: (String?) -> String) -> String {
        "\(title): \(ratio.map { Trading.formatPercent($0) } ?? "no change data"). Last \(money(last)), low \(money(low)), "
            + "high \(money(high)), \(plural(series.count, "bar", "bars"))."
    }
}

/// A price or value chart: a line or candles, moving averages and optional volume, drawn
/// from a thinned series. Bars are drawn by index, so market gaps take no room. VoiceOver
/// reads `label`: the change, last price, the period's low and high and the bar count.
private struct TradingChart: View {
    enum Mode: String { case line, candle }

    let series: ChartSeries
    var mode = Mode.line
    var averages: [Int] = []
    var volume = false
    let label: String
    var height: CGFloat = 220

    var body: some View {
        let points = series.bars.enumerated().map { ChartPoint(id: $0.offset, bar: $0.element) }
        let lines = averages.map { n in
            AverageSeries(id: n, points: (series.averages[n] ?? []).enumerated().compactMap { index, value in
                value.map { AveragePoint(id: index, value: $0) }
            })
        }
        VStack(spacing: JV.Space.xxs) {
            Chart {
                ForEach(points) { point in
                    if mode == .line {
                        LineMark(x: .value("Bar", point.id), y: .value("Price", point.bar.close), series: .value("Series", "Price"))
                            .foregroundStyle(JV.chartAccent)
                    } else {
                        let color = point.bar.close >= point.bar.open ? JV.positiveSolid : JV.negativeSolid
                        RuleMark(x: .value("Bar", point.id), yStart: .value("Low", point.bar.low), yEnd: .value("High", point.bar.high))
                            .foregroundStyle(color)
                        RectangleMark(x: .value("Bar", point.id), yStart: .value("Open", point.bar.open), yEnd: .value("Close", point.bar.close),
                                      width: .ratio(0.6))
                            .foregroundStyle(color)
                    }
                }
                ForEach(lines) { average in
                    ForEach(average.points) { point in
                        LineMark(x: .value("Bar", point.id), y: .value("Average", point.value), series: .value("Series", "Average \(average.id)"))
                            .foregroundStyle(average.id == 50 ? JV.chartMa : JV.textSecondary)
                            .lineStyle(StrokeStyle(lineWidth: 1.5, dash: average.id == 50 ? [] : [4, 3]))
                    }
                }
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXAxis(.hidden)
            .frame(height: height)
            if volume {
                Chart(points) { point in
                    BarMark(x: .value("Bar", point.id), y: .value("Volume", point.bar.volume ?? 0)).foregroundStyle(JV.border)
                }
                .chartXAxis(.hidden)
                .chartYAxis(.hidden)
                .frame(height: 48)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: label))
    }
}

/// One position as a row: symbol and name, class badge, value and result.
private struct PositionRow: View {
    let position: TradingPosition
    let base: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                Text(verbatim: position.symbol).font(JV.mono(15, .medium)).foregroundStyle(JV.textPrimary)
                Badge(text: position.asset_class == "STK" ? "Stock" : position.asset_class, dot: false)
                Spacer(minLength: 4)
                Text(verbatim: Trading.formatMoney(position.market_value, base)).font(JV.number(15, .medium)).foregroundStyle(JV.textPrimary)
            }
            HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                if let name = position.name?.value {
                    Text(verbatim: name).font(JV.font(13)).foregroundStyle(JV.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                Text(verbatim: Trading.formatMoney(position.unrealized_pnl?.value, base, sign: true))
                    .font(JV.number(13)).foregroundStyle(signColor(position.unrealized_pnl?.value))
            }
            Text(verbatim: detail).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Rows in one list card; a row opens a stock when `open` is set for it.
private struct PositionList: View {
    let positions: [TradingPosition]
    let base: String
    let detail: (TradingPosition) -> String
    let open: ((TradingPosition) -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(positions.enumerated()), id: \.element.id) { index, position in
                if index > 0 { ListDivider(inset: 0) }
                if let open, position.isStock {
                    Button { open(position) } label: { PositionRow(position: position, base: base, detail: detail(position)) }
                        .buttonStyle(.plain)
                } else {
                    PositionRow(position: position, base: base, detail: detail(position))
                }
            }
        }
        .jvCard(padding: 0)
    }
}

/// Plain text lines for notes and sources.
func tradingNote(_ text: String) -> some View {
    Text(verbatim: text).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary).fixedSize(horizontal: false, vertical: true)
}

private struct TradingDestination: View {
    @ObservedObject var model: JarvisAppModel
    let route: TradingRoute

    var body: some View {
        switch route {
        case .stocks: StocksPage(model: model)
        case .positions: PositionsPage(model: model)
        case .holdings: HoldingsPage(model: model, ibkr: false)
        case .ibkr: HoldingsPage(model: model, ibkr: true)
        case let .stock(conid): StockPage(model: model, conid: conid)
        case .memecoin: MemecoinPage(model: model)
        case .crypto: CryptoPage(model: model)
        }
    }
}

// MARK: - Desk

/// Trading tab: every pot on one desk. Read-only; only the IBKR stocks pot has data, the
/// others say plainly what they are.
struct TradingPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var portfolio: TradingState<Portfolio> = .loading
    /// IBKR plus the crypto wallets; an older Core has no total.
    @State private var total: Availability<PortfolioTotal> = .loading
    @State private var history: TradingState<PortfolioHistory> = .loading
    /// Worked out once per history read.
    @State private var historyChart: ChartData?
    @State private var range = "3M"
    @State private var route: TradingRoute?
    /// Reads already in flight must not overwrite newer answers.
    @State private var loads = 0
    @State private var totalLoads = 0
    @State private var historyLoads = 0

    var body: some View {
        PageScaffold(title: AppTab.trading.title, subtitle: "Read-only: this app places no orders.") {
            Text("All pots in one place.").font(JV.font(14)).foregroundStyle(JV.textSecondary)
            if let data = portfolio.value {
                tradingNote("\(Trading.sourceLabel(data)) · as of \(Trading.asOfText(data.as_of))")
            }
            if let sum = total.value, sum.shown { totalCard(sum) }
            TradingStatus(state: portfolio, what: "your portfolio", retry: { await loadPortfolio(); await loadTotal() }) { data, empty in
                if empty {
                    UnavailableState(title: "Nothing in your IBKR account yet", kind: .empty,
                                     detail: "Core is connected, but the account holds no positions and no cash.", symbol: "chart.line.uptrend.xyaxis")
                } else {
                    KpiGrid(kpis: [totalKpi(data)] + valueKpis(data) + [
                        Kpi(label: "Positions", value: String(data.positions.count),
                            detail: "\(plural(data.positions.count, "instrument", "instruments")) at IBKR"),
                    ])
                    if case .error = total { tradingNote(Crypto.totalFailedText) }
                }
            }
            valueCard
            pots
            ListSection(title: "Also here") {
                VStack(spacing: 0) {
                    link("Stocks", symbol: "chart.bar", to: .stocks)
                    ListDivider()
                    link("Positions", symbol: "list.bullet.rectangle", to: .positions)
                    ListDivider()
                    link("Manual holdings", symbol: "square.and.pencil", to: .holdings)
                    ListDivider()
                    link("Crypto", symbol: "wallet.pass", to: .crypto)
                    ListDivider()
                    link("IBKR status", symbol: "link", to: .ibkr)
                }
                .jvCard(padding: 0)
            }
        }
        .navigationDestination(item: $route) { route in TradingDestination(model: model, route: route) }
        .refreshable { await loadPortfolio(); await loadTotal(); await loadHistory() }
        .task { await loadPortfolio() }
        .task { await loadTotal() }
        .task(id: range) { await loadHistory() }
    }

    /// IBKR's own value: "Total value" while Core has no total (an older Core, 404/405).
    private func totalKpi(_ data: Portfolio) -> Kpi {
        Kpi(label: total.value == nil ? "Total value" : "IBKR value", value: Trading.formatMoney(data.net_liquidation, data.base_currency),
            detail: "incl. \(Trading.formatMoney(data.cash, data.base_currency)) cash")
    }

    /// IBKR plus the crypto wallets, each part with its own time.
    private func totalCard(_ sum: PortfolioTotal) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.xxs) {
            Text("Total value").font(JV.font(13)).foregroundStyle(JV.textSecondary)
            Text(verbatim: Trading.formatMoney(sum.total, sum.base_currency)).font(JV.number(20, .semibold, relativeTo: .title3))
                .foregroundStyle(JV.textPrimary)
            ForEach(Array(sum.parts.enumerated()), id: \.offset) { _, part in
                tradingNote("\(Crypto.partLabel(part.source)) \(Trading.formatMoney(part.value, sum.base_currency)) · as of \(Trading.asOfText(part.as_of))")
            }
            if sum.partial { tradingNote(Crypto.partialText) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
        .accessibilityElement(children: .combine)
    }

    private func link(_ title: String, symbol: String, to route: TradingRoute) -> some View {
        Button { self.route = route } label: { ListRow(title: title, symbol: symbol) }.buttonStyle(.plain)
    }

    private var valueCard: some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            HStack(spacing: JV.Space.xs) {
                Text("IBKR value").font(JV.font(18, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                if let ratio = historyChart?.ratio {
                    Badge(text: Trading.formatPercent(ratio), style: Trading.sign(ratio) == 1 ? .positive : Trading.sign(ratio) == -1 ? .negative : .neutral)
                }
            }
            SegmentedControl(label: "Period", pairs: Trading.historyRanges.map { ($0, $0 == "ALL" ? "All" : $0) }, selection: $range)
            TradingStatus(state: history, what: "the value history", rows: 1, retry: loadHistory) { data, empty in
                if empty {
                    Text("Not enough history for this period yet.").font(JV.font(13)).foregroundStyle(JV.textSecondary)
                } else if let chart = historyChart {
                    TradingChart(series: chart.series, label: chart.label("IBKR value over \(range == "ALL" ? "all time" : range)") {
                        Trading.formatMoney($0, data.base_currency)
                    })
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
    }

    private var pots: some View {
        ListSection(title: "Pots") {
            VStack(alignment: .leading, spacing: JV.Space.sm) {
                VStack(alignment: .leading, spacing: JV.Space.xs) {
                    TitleBadgeRow(title: "Stocks", badge: Badge(text: "IBKR · read-only", dot: false))
                    switch portfolio {
                    case let .ok(data, empty, _) where !empty:
                        HStack(alignment: .firstTextBaseline) {
                            Text(verbatim: Trading.formatMoney(data.net_liquidation, data.base_currency))
                                .font(JV.number(20, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                            Spacer(minLength: 4)
                            Text(verbatim: "\(Trading.formatMoney(data.day_pnl?.value, data.base_currency, sign: true)) today")
                                .font(JV.number(13, .medium)).foregroundStyle(signColor(data.day_pnl?.value))
                        }
                        if let allocation = data.allocation, !allocation.isEmpty {
                            HStack {
                                Text("Allocation on target").font(JV.font(13)).foregroundStyle(JV.textPrimary)
                                Spacer()
                                Text(verbatim: "\(Trading.onTarget(allocation)) of \(allocation.count)").font(JV.mono(13)).foregroundStyle(JV.textSecondary)
                            }
                            .accessibilityElement(children: .combine)
                            ProgressMeter(value: Double(Trading.onTarget(allocation)) / Double(allocation.count) * 100)
                        } else {
                            tradingNote("No allocation targets set.")
                        }
                    case .ok:
                        tradingNote("Connected; nothing held yet.")
                    case .loading:
                        LoadingState(label: "Loading the stocks pot", rows: 1)
                    case .notConnected, .error:
                        tradingNote("No IBKR data yet; see above.")
                    }
                    PanelButton(title: "Open stocks") { route = .stocks }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .jvCard(padding: JV.Space.md)
                NodePanel(item: NodeItem(id: "memecoin", label: "Memecoin", title: "Memecoin", icon: "bitcoinsign.circle",
                                         detail: "A paper simulation on Solana market data, off by default. No wallet, no real money.",
                                         tone: .idle, status: "Paper only")) {
                    PanelButton(title: "Open memecoin") { route = .memecoin }
                }
                NodePanel(item: .planned("futures", "Futures", "Futures", icon: "chart.xyaxis.line",
                                         detail: "Futures through IBKR come later: paper and read-only first, then a signed mandate."))
            }
        }
    }

    private func loadPortfolio() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead("/v1/trading/portfolio", as: Portfolio.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == loads else { return }
        portfolio = Trading.state(result) { $0.positions.isEmpty && Trading.sign($0.net_liquidation) == 0 }
    }

    private func loadTotal() async {
        totalLoads += 1
        let mine = totalLoads
        let result = await model.coreRead(Crypto.totalPath, as: PortfolioTotal.self).checked(Crypto.valid)
        guard !Task.isCancelled, mine == totalLoads else { return }
        total = result
    }

    private func loadHistory() async {
        guard let path = Trading.historyPath(range) else { return }
        historyLoads += 1
        let mine = historyLoads
        history = .loading
        historyChart = nil
        let result = await model.coreRead(path, as: PortfolioHistory.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == historyLoads else { return }
        history = Trading.state(result) { $0.readable.count < 2 }
        historyChart = result.value.map { ChartData(points: $0.readable) }
    }
}

// MARK: - Stocks

/// Stocks pot: allocation against the owner's targets, the monthly deposit, the stocks and
/// ETFs held, and coming dividends. Everything comes from GET /v1/trading/portfolio.
private struct StocksPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var state: TradingState<Portfolio> = .loading
    @State private var open: Int?
    @State private var loads = 0

    var body: some View {
        PageScaffold(title: "Stocks", subtitle: state.value.map { "\(Trading.sourceLabel($0)) · read-only" } ?? "Read-only") {
            if let data = state.value { tradingNote("As of \(Trading.asOfText(data.as_of)).") }
            TradingStatus(state: state, what: "your holdings", retry: load) { data, empty in
                if empty {
                    UnavailableState(title: "No holdings yet", kind: .empty,
                                     detail: "Your IBKR account is connected but holds nothing. Holdings appear here after your first purchase.",
                                     symbol: "chart.bar")
                } else {
                    content(data)
                }
            }
        }
        .navigationDestination(item: $open) { conid in StockPage(model: model, conid: conid) }
        .refreshable { await load() }
        .task { await load() }
    }

    @ViewBuilder private func content(_ data: Portfolio) -> some View {
        KpiGrid(kpis: [
            Kpi(label: "Value", value: Trading.formatMoney(data.net_liquidation, data.base_currency),
                detail: "incl. \(Trading.formatMoney(data.cash, data.base_currency)) cash"),
        ] + valueKpis(data) + [
            Kpi(label: "Dividends, 12 months", value: Trading.formatMoney(data.dividends_12m?.value, data.base_currency),
                detail: data.dividends_12m?.value == nil ? "Not reported by Core" : "Received"),
        ])
        allocation(data)
        ListSection(title: "Monthly deposit") {
            VStack(alignment: .leading, spacing: JV.Space.xxs) {
                if let deposit = data.monthly_deposit {
                    Text(verbatim: Trading.formatMoney(deposit.amount, data.base_currency))
                        .font(JV.number(20, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                    tradingNote("Every month on day \(deposit.day_of_month). Next deposit: \(Trading.dayText(deposit.next_date)).")
                } else {
                    tradingNote("No monthly deposit set.")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jvCard(padding: JV.Space.md)
        }
        let stocks = data.stocks
        ListSection(title: "Holdings · \(plural(stocks.count, "holding", "holdings"))") {
            if stocks.isEmpty {
                UnavailableState(title: "No stocks or ETFs", kind: .empty,
                                 detail: "This account holds no stocks or ETFs. Other positions are under Positions.", symbol: "chart.bar")
            } else {
                PositionList(positions: stocks, base: data.base_currency, detail: { p in
                    ["Quantity \(Trading.formatDecimal(p.quantity, maxPlaces: 8))", "Today \(Trading.formatPercent(p.day_change_pct?.value))",
                     "Share \(p.weight_pct?.value.map { "\(Trading.formatDecimal($0, places: 1))%" } ?? "—")"].joined(separator: " · ")
                }, open: { open = $0.conid })
            }
        }
        ListSection(title: "Coming dividends") {
            VStack(alignment: .leading, spacing: JV.Space.xs) {
                let dividends = data.upcoming_dividends ?? []
                if dividends.isEmpty { tradingNote("No dividends announced for your holdings.") }
                ForEach(Array(dividends.enumerated()), id: \.offset) { _, dividend in
                    HStack(alignment: .firstTextBaseline, spacing: JV.Space.sm) {
                        Text(verbatim: Trading.dayText(dividend.ex_date)).font(JV.mono(13)).foregroundStyle(JV.textSecondary)
                            .frame(minWidth: 56, alignment: .leading)
                        Text(verbatim: dividendText(dividend))
                            .font(JV.font(13)).foregroundStyle(JV.textPrimary).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                        Badge(text: dividend.amount?.value == nil ? "Fact" : "Calculation", style: dividend.amount?.value == nil ? .neutral : .accent, dot: false)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jvCard(padding: JV.Space.md)
        }
    }

    private func dividendText(_ dividend: Portfolio.Dividend) -> String {
        var text = "\(dividend.symbol) ex-dividend"
        if let paid = dividend.pay_date?.value { text += ", paid \(Trading.dayText(paid))" }
        if let amount = dividend.amount?.value { text += ", about \(Trading.formatMoney(amount, dividend.currency))" }
        return text
    }

    private func allocation(_ data: Portfolio) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            TitleBadgeRow(title: "Allocation and your target", badge: Badge(text: "Calculation", style: .accent, dot: false))
            if let allocation = data.allocation, !allocation.isEmpty {
                ForEach(Array(allocation.enumerated()), id: \.offset) { _, bucket in
                    let off = Trading.offTarget(bucket)
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                            Text(verbatim: bucket.label).font(JV.font(14, .medium)).foregroundStyle(JV.textPrimary)
                            Spacer(minLength: 4)
                            Text(verbatim: "\(Trading.formatDecimal(bucket.current_pct, places: 0))%").font(JV.mono(13)).foregroundStyle(JV.textPrimary)
                            Text(verbatim: "target \(Trading.formatDecimal(bucket.target_pct, places: 0))%").font(JV.font(13)).foregroundStyle(JV.textSecondary)
                        }
                        if let off { Badge(text: off.text, style: off.over ? .negative : .accent) }
                        ProgressMeter(value: Trading.chartNumber(bucket.current_pct) ?? 0, target: Trading.chartNumber(bucket.target_pct), over: off?.over == true)
                    }
                    .accessibilityElement(children: .combine)
                }
                tradingNote("The mark is your target. Computed by code from your IBKR positions; Jarvis never sells just to rebalance.")
            } else {
                tradingNote("No targets set yet. Core reports targets once you set them for the stocks pot.")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
    }

    private func load() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead("/v1/trading/portfolio", as: Portfolio.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == loads else { return }
        state = Trading.state(result) { $0.positions.isEmpty && Trading.sign($0.net_liquidation) == 0 }
    }
}

// MARK: - Stock

/// One instrument: price chart (line or candles, 1D–5Y, moving averages, volume), Jarvis's
/// notes labelled by kind, and your position if you hold it.
private struct StockPage: View {
    @ObservedObject var model: JarvisAppModel
    let conid: Int
    @State private var range = "3M"
    @State private var mode = TradingChart.Mode.line
    @State private var ma50 = false
    @State private var ma200 = false
    @State private var volume = true
    @State private var bars: TradingState<InstrumentBars> = .loading
    /// Worked out once per bars read.
    @State private var chart: ChartData?
    @State private var portfolio: TradingState<Portfolio> = .loading
    @State private var loads = 0
    @State private var portfolioLoads = 0

    private var data: InstrumentBars? { bars.value }

    var body: some View {
        let ratio = chart?.ratio
        PageScaffold(title: data.map { $0.name?.value ?? $0.symbol } ?? "Instrument \(conid)",
                     subtitle: data.map { [$0.symbol, $0.exchange?.value].compactMap { $0 }.joined(separator: " · ") } ?? "") {
            if let data, let last = chart?.last {
                HStack(alignment: .firstTextBaseline, spacing: JV.Space.sm) {
                    Text(verbatim: Trading.formatPrice(last, data.currency)).font(JV.number(28, .semibold, relativeTo: .title))
                        .foregroundStyle(JV.textPrimary)
                    if let ratio {
                        Text(verbatim: "\(Trading.formatPercent(ratio)) over \(range)").font(JV.number(15, .medium)).foregroundStyle(signColor(ratio))
                    }
                }
                .accessibilityElement(children: .combine)
            }
            chartCard
            notes
            position
        }
        .refreshable { await loadBars(); await loadPortfolio() }
        .task(id: range) { await loadBars() }
        .task { await loadPortfolio() }
    }

    private var chartCard: some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            SegmentedControl(label: "Period", pairs: Trading.barRanges.map { ($0, $0) }, selection: $range)
            SegmentedControl(label: "Chart type", options: [.init(id: TradingChart.Mode.line, label: "Line"), .init(id: .candle, label: "Candles")],
                             selection: $mode)
            let count = chart?.series.count ?? 0
            let hasVolume = chart?.series.hasVolume ?? false
            HStack(spacing: JV.Space.xs) {
                indicator("Average 50", isOn: $ma50, needs: 50, count: count)
                indicator("Average 200", isOn: $ma200, needs: 200, count: count)
                Toggle("Volume", isOn: $volume).toggleStyle(.button).disabled(!hasVolume)
            }
            .font(JV.font(13))
            TradingStatus(state: bars, what: "the price history", rows: 1, retry: loadBars) { data, empty in
                if empty {
                    tradingNote("No prices for this period. The market may not have traded yet.")
                } else if let chart {
                    TradingChart(series: chart.series, mode: mode,
                                 averages: [ma50 && count >= 50 ? 50 : nil, ma200 && count >= 200 ? 200 : nil].compactMap { $0 },
                                 volume: volume && hasVolume, label: chart.label("\(data.symbol), \(range)") { Trading.formatPrice($0, data.currency) },
                                 height: 260)
                }
                tradingNote("Prices: \(Trading.sourceLabel(data)) · as of \(Trading.asOfText(data.as_of)). Averages are over the bars of this period.")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
    }

    private func indicator(_ title: String, isOn: Binding<Bool>, needs: Int, count: Int) -> some View {
        Toggle(title, isOn: Binding(get: { isOn.wrappedValue && count >= needs }, set: { isOn.wrappedValue = $0 }))
            .toggleStyle(.button)
            .disabled(count < needs)
            .accessibilityHint(count < needs ? "Needs \(needs) bars; this period has \(count)" : "")
    }

    private var notes: some View {
        ListSection(title: "Jarvis notes") {
            VStack(alignment: .leading, spacing: JV.Space.sm) {
                if let notes = data?.notes, !notes.isEmpty {
                    ForEach(Array(notes.enumerated()), id: \.offset) { _, item in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Badge(text: item.kind.label, style: item.kind == .calculation ? .accent : .neutral, dot: false)
                                Spacer()
                                Text(verbatim: Trading.dayText(item.t)).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                            }
                            Text(verbatim: item.text).font(JV.font(14)).foregroundStyle(JV.textPrimary).fixedSize(horizontal: false, vertical: true)
                            if let sources = item.sources, !sources.isEmpty { tradingNote("Sources: \(sources.joined(separator: ", "))") }
                        }
                        .accessibilityElement(children: .combine)
                    }
                    tradingNote("Fact: from a source. Calculation: computed by code. AI inference: Jarvis's estimate, which can be wrong.")
                } else {
                    tradingNote("No notes from Jarvis for this period.")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jvCard(padding: JV.Space.md)
        }
    }

    @ViewBuilder private var position: some View {
        ListSection(title: "Your position") {
            switch portfolio {
            case let .ok(data, _, _):
                if let held = data.positions.first(where: { $0.conid == conid }) {
                    TileGrid(tiles: [
                        TileData(label: "Quantity", value: Trading.formatDecimal(held.quantity, maxPlaces: 8)),
                        TileData(label: "Avg. cost", value: Trading.formatPrice(held.avg_cost, held.currency)),
                        TileData(label: "Value", value: Trading.formatMoney(held.market_value, data.base_currency)),
                        TileData(label: "Result", value: Trading.formatMoney(held.unrealized_pnl?.value, data.base_currency, sign: true)),
                    ])
                } else {
                    tradingNote("You do not hold this instrument.")
                }
            case .loading:
                LoadingState(label: "Loading your position", rows: 1)
            case .notConnected, .error:
                tradingNote("Your position shows once Core reports your IBKR portfolio.")
            }
        }
    }

    private func loadBars() async {
        guard let path = Trading.barsPath(conid, range) else { bars = .error(.failed); return }
        loads += 1
        let mine = loads
        bars = .loading
        chart = nil
        let result = await model.coreRead(path, as: InstrumentBars.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == loads else { return }
        bars = Trading.state(result, instrument: true) { $0.readable.isEmpty }
        chart = result.value.map { ChartData(bars: $0.readable) }
    }

    private func loadPortfolio() async {
        portfolioLoads += 1
        let mine = portfolioLoads
        let result = await model.coreRead("/v1/trading/portfolio", as: Portfolio.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == portfolioLoads else { return }
        portfolio = Trading.state(result) { _ in false }
    }
}

// MARK: - Positions

/// Every open position in the IBKR account, all asset classes. Orders are not part of the
/// read-only connection, so that section says so.
private struct PositionsPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var state: TradingState<Portfolio> = .loading
    @State private var open: Int?
    @State private var loads = 0

    var body: some View {
        PageScaffold(title: "Positions", subtitle: state.value.map { "\(Trading.sourceLabel($0)) · read-only" } ?? "Read-only") {
            if let data = state.value { tradingNote("As of \(Trading.asOfText(data.as_of)).") }
            TradingStatus(state: state, what: "your positions", retry: load) { data, empty in
                ListSection(title: data.positions.isEmpty ? "Open positions · nothing open" : "Open positions · \(data.positions.count) open") {
                    if let skipped = Trading.skippedText(data) { tradingNote(skipped) }
                    if empty {
                        UnavailableState(title: "No open positions", kind: .empty, detail: "Your IBKR account has no open positions.",
                                         symbol: "list.bullet.rectangle")
                    } else {
                        PositionList(positions: data.positions, base: data.base_currency, detail: { p in
                            ["Quantity \(Trading.formatDecimal(p.quantity, maxPlaces: 8))", "Avg. cost \(Trading.formatPrice(p.avg_cost, p.currency))",
                             "Last \(Trading.formatPrice(p.last_price, p.currency))", p.exchange?.value ?? ""]
                                .filter { !$0.isEmpty }.joined(separator: " · ")
                        }, open: { open = $0.conid })
                    }
                }
            }
            VStack(alignment: .leading, spacing: JV.Space.xs) {
                TitleBadgeRow(title: "Open orders", badge: Badge(text: "Not available", dot: false))
                tradingNote("The IBKR connection is read-only, and Core does not report orders yet. Orders come with trading, behind risk checks and your confirmation.")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jvCard(padding: JV.Space.md)
        }
        .navigationDestination(item: $open) { conid in StockPage(model: model, conid: conid) }
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead("/v1/trading/portfolio", as: Portfolio.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == loads else { return }
        state = Trading.state(result) { $0.positions.isEmpty }
    }
}

// MARK: - Holdings

/// Positions entered by hand (/v1/holdings), and the IBKR link with its positions.
private struct HoldingsPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var tab: String
    @State private var holdings: Availability<HoldingList> = .loading
    @State private var ibkr: TradingState<Portfolio> = .loading
    @State private var symbol = ""
    @State private var quantity = ""
    @State private var avgCost = ""
    @State private var error: String?
    @State private var busy = false
    @State private var loads = 0
    @State private var ibkrLoads = 0

    init(model: JarvisAppModel, ibkr: Bool) {
        self.model = model
        _tab = State(initialValue: ibkr ? "ibkr" : "manual")
    }

    var body: some View {
        let link = Trading.linkState(ibkr)
        PageScaffold(title: "Holdings", subtitle: "Manual, and IBKR read-only") {
            Text("Your positions on one desk: manual, and live through IBKR (read-only). Trading (orders) comes later, with risk checks and confirmation.")
                .font(JV.font(13)).foregroundStyle(JV.textSecondary).fixedSize(horizontal: false, vertical: true)
            KpiGrid(kpis: [
                Kpi(label: "Positions", value: holdings.value.map { String($0.holdings.count) } ?? "—", detail: "entered by hand"),
                Kpi(label: "Cost basis", value: holdings.value?.total_cost ?? "—", detail: "of the manual positions"),
                Kpi(label: "IBKR", value: link.1, color: link.0.badge.foreground, detail: "read-only connection"),
            ])
            SegmentedControl(label: "Holdings", pairs: [("manual", "Portfolio"), ("ibkr", "IBKR")], selection: $tab)
            if tab == "manual" { manual } else { ibkrSection(link) }
        }
        .refreshable { await loadHoldings(); await loadIbkr() }
        .task { await loadHoldings() }
        .task { await loadIbkr() }
        .onChange(of: error) { _, error in
            if let error { AccessibilityNotification.Announcement(error).post() }
        }
    }

    private var manual: some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            Text("Manual positions").font(JV.font(18, .semibold, relativeTo: .title3)).foregroundStyle(JV.textPrimary)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: JV.Space.xs) {
                field("Symbol (AAPL)", text: $symbol, label: "Symbol", decimal: false)
                field("Quantity", text: $quantity, label: "Quantity", decimal: true)
                field("Avg. cost", text: $avgCost, label: "Average cost", decimal: true)
                HStack {
                    Spacer()
                    Button("Add") { Task { await add() } }.buttonStyle(JVButtonStyle(kind: .primary)).disabled(busy)
                }
            }
            if let error { Text(verbatim: error).font(JV.font(13, relativeTo: .footnote)).foregroundStyle(JV.dangerText) }
            Loaded(source: holdings, title: "Manual positions", symbol: "square.and.pencil", rows: 2, retry: loadHoldings) { list in
                if list.holdings.isEmpty {
                    UnavailableState(title: "No positions yet", kind: .empty, detail: "Add one above.", symbol: "square.and.pencil")
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(list.holdings.enumerated()), id: \.element.id) { index, holding in
                            if index > 0 { ListDivider(inset: 0) }
                            holdingRow(holding)
                        }
                        ListDivider(inset: 0)
                        HStack {
                            Text("Total cost basis").font(JV.font(14, .semibold))
                            Spacer()
                            Text(verbatim: list.total_cost).font(JV.number(14, .semibold))
                        }
                        .foregroundStyle(JV.textPrimary)
                        .padding(.horizontal, JV.Space.md)
                        .padding(.vertical, 10)
                        .accessibilityElement(children: .combine)
                    }
                    .jvCard(padding: 0)
                }
            }
        }
    }

    private func field(_ placeholder: String, text: Binding<String>, label: String, decimal: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous)
        return TextField(placeholder, text: text)
            .font(JV.font(15))
            .foregroundStyle(JV.textPrimary)
            .keyboardType(decimal ? .decimalPad : .default)
            .textInputAutocapitalization(decimal ? .never : .characters)
            .autocorrectionDisabled()
            .padding(.horizontal, JV.Space.sm)
            .frame(minHeight: 44)
            .background(JV.bgSurface, in: shape)
            .overlay(shape.strokeBorder(JV.borderStrong, lineWidth: 1))
            .accessibilityLabel(label)
    }

    private func holdingRow(_ holding: Holding) -> some View {
        HStack(alignment: .center, spacing: JV.Space.sm) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(verbatim: holding.symbol).font(JV.mono(15, .semibold)).foregroundStyle(JV.textPrimary)
                    Spacer(minLength: 4)
                    Text(verbatim: holding.cost_basis).font(JV.number(15, .medium)).foregroundStyle(JV.textPrimary)
                }
                tradingNote("\(holding.quantity) at \(holding.avg_cost) \(holding.currency) · weight \(holding.weight_pct)%")
                ProgressMeter(value: Trading.chartNumber(holding.weight_pct) ?? 0)
            }
            .accessibilityElement(children: .combine)
            Button("Remove") { Task { await remove(holding) } }
                .buttonStyle(JVButtonStyle(kind: .quiet, size: .small))
                .disabled(busy)
                .accessibilityLabel(Text(verbatim: "Remove \(holding.symbol)"))
        }
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, 10)
    }

    private func ibkrSection(_ link: (Tone, String)) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.sm) {
            TitleBadgeRow(title: "IBKR", badge: Badge(text: link.1, style: link.0.badge))
            tradingNote("Read-only through Core's IBKR connection; Jarvis only reads, it never places orders.")
            TradingStatus(state: ibkr, what: "the IBKR portfolio", rows: 2, retry: loadIbkr) { data, empty in
                tradingNote("Last sync \(Trading.asOfText(data.as_of)).")
                if let skipped = Trading.skippedText(data) { tradingNote(skipped) }
                if empty {
                    tradingNote("No open positions in your IBKR account.")
                } else {
                    PositionList(positions: data.positions, base: data.base_currency, detail: { p in
                        "Quantity \(Trading.formatDecimal(p.quantity, maxPlaces: 8)) · Last \(Trading.formatPrice(p.last_price, p.currency))"
                    }, open: nil)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func add() async {
        guard !busy else { return }
        guard let holding = Trading.newHolding(symbol: symbol, quantity: quantity, avgCost: avgCost) else {
            error = Trading.holdingIssueText(quantity: quantity, avgCost: avgCost)
            return
        }
        busy = true
        error = nil
        do {
            try await model.addHolding(holding)
            symbol = ""
            quantity = ""
            avgCost = ""
        } catch {
            self.error = Trading.holdingFailureText(error, adding: true)
        }
        await loadHoldings()
        busy = false
    }

    private func remove(_ holding: Holding) async {
        guard !busy else { return }
        busy = true
        error = nil
        do {
            try await model.deleteHolding(holding.id)
        } catch {
            self.error = Trading.holdingFailureText(error, adding: false)
        }
        await loadHoldings()
        busy = false
    }

    private func loadHoldings() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead("/v1/holdings", as: HoldingList.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == loads else { return }
        holdings = result
    }

    private func loadIbkr() async {
        ibkrLoads += 1
        let mine = ibkrLoads
        let result = await model.coreRead("/v1/trading/portfolio", as: Portfolio.self).checked(Trading.valid)
        guard !Task.isCancelled, mine == ibkrLoads else { return }
        ibkr = Trading.state(result) { $0.positions.isEmpty }
    }
}

// MARK: - Crypto

/// Read-only self-custody wallets by public address (desktop TradingCrypto.vue): their EUR
/// value per wallet and per asset, add and remove, and a refresh within Core's one-minute
/// cooldown. Nothing here signs or sends; key material is refused before any request.
private struct CryptoPage: View {
    @ObservedObject var model: JarvisAppModel
    @State private var overview: Availability<CryptoOverview> = .loading
    @State private var adding = false
    /// The chains Core had on when Add was tapped.
    @State private var addChains: [CryptoChain] = []
    @State private var removing: CryptoWallet?
    @State private var notice: String?
    @State private var busy = false
    @State private var cooldownUntil: Date?
    @State private var copied: String?
    /// A read already in flight must not overwrite a newer answer.
    @State private var loads = 0

    var body: some View {
        PageScaffold(title: Crypto.title, subtitle: Crypto.subtitle) {
            switch overview {
            case .loading:
                LoadingState(label: "Loading your wallets…", rows: 3)
            case let .ok(data):
                content(data)
            default:
                if let failure = Crypto.failure(overview) {
                    UnavailableState(title: failure == .off ? Crypto.offTitle : "Could not load your wallets", kind: .error,
                                     detail: failure == .off ? Crypto.offDetail : Crypto.failureText(failure), symbol: "wallet.pass",
                                     action: UnavailableState.Action(label: "Try again", run: load))
                }
            }
        }
        .sheet(isPresented: $adding) {
            AddCryptoWalletSheet(model: model, available: addChains) { added in
                if added { notice = Crypto.added }
                await load()
            }
        }
        .confirmationDialog(Crypto.removeConfirm, isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { wallet in
            Button("Remove", role: .destructive) { Task { await remove(wallet) } }
            Button("Cancel", role: .cancel) {}
        }
        .refreshable { await load() }
        .task { await load() }
        .onChange(of: notice) { _, notice in
            if let notice { AccessibilityNotification.Announcement(notice).post() }
        }
    }

    @ViewBuilder private func content(_ data: CryptoOverview) -> some View {
        if let notice { JobNotice(text: notice) }
        if let until = cooldownUntil {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let seconds = Int(until.timeIntervalSince(context.date).rounded(.up))
                if seconds > 0 { tradingNote(Crypto.cooldownText(seconds)) }
            }
        }
        KpiGrid(kpis: [
            Kpi(label: "Crypto value", value: Trading.formatMoney(data.total_eur, "EUR"),
                detail: data.wallets.isEmpty ? "No wallets yet" : "as of \(Trading.asOfText(data.as_of))"),
            Kpi(label: "Wallets", value: String(data.wallets.count), detail: "read-only, public addresses"),
        ])
        if data.hidden > 0 { JobNotice(text: Crypto.hiddenText(data.hidden), style: .negative) }
        tradingNote(Crypto.hint)
        HStack(spacing: JV.Space.xs) {
            Button("Add wallet") { notice = nil; addChains = data.chains_available; adding = true }
                .buttonStyle(JVButtonStyle(kind: .primary))
                .disabled(busy || data.kinds.isEmpty)
            Button("Refresh") { Task { await refresh() } }
                .buttonStyle(JVButtonStyle(kind: .secondary))
                .disabled(busy || cooldownUntil != nil)
        }
        if data.kinds.isEmpty { tradingNote("No chain is on in Core, so no wallet can be added.") }
        ListSection(title: "Wallets") {
            if data.wallets.isEmpty {
                UnavailableState(title: "No wallets yet", kind: .empty, detail: "Add a public address above to see its balances here.",
                                 symbol: "wallet.pass")
            } else {
                VStack(spacing: JV.Space.sm) {
                    ForEach(data.wallets) { wallet in walletCard(wallet) }
                }
            }
        }
        if !data.assets.isEmpty {
            ListSection(title: "By asset") {
                VStack(spacing: 0) {
                    ForEach(Array(data.assets.enumerated()), id: \.offset) { index, asset in
                        if index > 0 { ListDivider(inset: 0) }
                        assetRow(asset)
                    }
                }
                .jvCard(padding: 0)
            }
        }
        tradingNote(Crypto.attribution)
    }

    private func walletCard(_ wallet: CryptoWallet) -> some View {
        VStack(alignment: .leading, spacing: JV.Space.xs) {
            HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                Text(verbatim: wallet.name).font(JV.font(15, .medium)).foregroundStyle(JV.textPrimary).lineLimit(2)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 4)
                Text(verbatim: Trading.formatMoney(wallet.value_eur, "EUR")).font(JV.number(15, .medium)).foregroundStyle(JV.textPrimary)
            }
            HStack(spacing: JV.Space.xs) {
                Text(verbatim: Crypto.shortAddress(wallet.address)).font(JV.mono(13)).foregroundStyle(JV.textSecondary)
                Button(copied == wallet.id ? "Copied" : "Copy") { copy(wallet) }
                    .buttonStyle(JVButtonStyle(kind: .quiet, size: .small))
                    .accessibilityLabel(Text(verbatim: "Copy the address of \(wallet.name)"))
                Spacer(minLength: 0)
                Badge(text: wallet.status.label, style: wallet.status.tone.badge)
            }
            if !wallet.chains.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: JV.Space.xxs) {
                        ForEach(wallet.chains, id: \.self) { chain in Badge(text: chain.label, dot: false) }
                    }
                }
            }
            tradingNote(Crypto.refreshedText(wallet))
            if let code = wallet.error { JobNotice(text: Crypto.walletErrorText(code), style: .danger) }
            if wallet.other_tokens > 0 { tradingNote(Crypto.otherTokensText(wallet.other_tokens)) }
            ForEach(Array(wallet.balances.enumerated()), id: \.offset) { _, balance in
                HStack(alignment: .firstTextBaseline, spacing: JV.Space.xs) {
                    Text(verbatim: balance.symbol).font(JV.mono(13, .medium)).foregroundStyle(JV.textPrimary)
                    Text(verbatim: balance.chain?.label ?? "Other").font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                    Spacer(minLength: 4)
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(verbatim: Trading.formatDecimal(balance.amount)).font(JV.number(13)).foregroundStyle(JV.textPrimary)
                        Text(verbatim: balance.value_eur.map { Trading.formatMoney($0, "EUR") } ?? Crypto.noPrice)
                            .font(JV.number(12)).foregroundStyle(JV.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            HStack {
                Spacer()
                Button("Remove wallet") { notice = nil; removing = wallet }
                    .buttonStyle(JVButtonStyle(kind: .quiet, size: .small))
                    .disabled(busy)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: JV.Space.md)
    }

    /// Asset and value, then amount, EUR price and weight.
    private func assetRow(_ asset: CryptoAsset) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: asset.symbol).font(JV.mono(15, .semibold)).foregroundStyle(JV.textPrimary)
                Spacer(minLength: 4)
                Text(verbatim: asset.value_eur.map { Trading.formatMoney($0, "EUR") } ?? Crypto.noPrice)
                    .font(JV.number(15, .medium)).foregroundStyle(JV.textPrimary)
            }
            tradingNote([
                "Amount \(Trading.formatDecimal(asset.amount))",
                "EUR price \(asset.price_eur.map { Trading.formatPrice($0, "EUR") } ?? Trading.dash)",
                "Weight \(asset.weight_pct.map { Trading.formatPercent($0, sign: false) } ?? Trading.dash)",
            ].joined(separator: " · "))
        }
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// A public address, copied to this device only (no Universal Clipboard).
    private func copy(_ wallet: CryptoWallet) {
        UIPasteboard.general.setItems([["public.utf8-plain-text": wallet.address]], options: [.localOnly: true])
        copied = wallet.id
        AccessibilityNotification.Announcement("Copied").post()
        Task {
            try? await Task.sleep(for: .seconds(2))
            if copied == wallet.id { copied = nil }
        }
    }

    private func refresh() async {
        guard !busy, cooldownUntil == nil else { return }
        busy = true
        defer { busy = false }
        notice = nil
        do {
            try await model.refreshCrypto()
            notice = Crypto.refreshAsked
        } catch {
            guard let seconds = Crypto.retryAfterSecs(error) else {
                notice = "Not refreshed. \(Crypto.failureText(Crypto.failure(error)))"
                return
            }
            // Refresh stays off until Core's cooldown ends.
            let until = Date().addingTimeInterval(TimeInterval(seconds))
            cooldownUntil = until
            Task {
                try? await Task.sleep(for: .seconds(seconds))
                if cooldownUntil == until { cooldownUntil = nil }
            }
        }
    }

    private func remove(_ wallet: CryptoWallet) async {
        guard !busy, let id = wallet.uuid else { return }
        busy = true
        notice = nil
        do {
            try await model.deleteCryptoWallet(id)
            notice = Crypto.removed
        } catch {
            notice = Crypto.removeFailureText(error) ?? Crypto.removed
        }
        await load()
        busy = false
    }

    private func load() async {
        loads += 1
        let mine = loads
        let result = await model.coreRead(Crypto.path, as: CryptoOverview.self)
        guard !Task.isCancelled, mine == loads else { return }
        overview = result
    }
}

/// A new wallet (in a sheet): its chain (only kinds Core has a chain on), for EVM which
/// chains (all available ones ticked), the public address and an optional label. Key
/// material is refused here before anything is sent, and the field that held it is emptied.
private struct AddCryptoWalletSheet: View {
    @ObservedObject var model: JarvisAppModel
    let available: [CryptoChain]
    /// Called after every attempt; true when Core added the wallet.
    let done: (Bool) async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var kind: CryptoWalletKind
    @State private var chains: Set<CryptoChain>
    @State private var address = ""
    @State private var label = ""
    @State private var error: String?
    @State private var busy = false

    init(model: JarvisAppModel, available: [CryptoChain], done: @escaping (Bool) async -> Void) {
        self.model = model
        self.available = available
        self.done = done
        let kinds = CryptoWalletKind.allCases.filter { kind in kind.chains.contains { available.contains($0) } }
        _kind = State(initialValue: kinds.contains(.evm) ? .evm : kinds.first ?? .bitcoin)
        _chains = State(initialValue: Set(CryptoChain.evm.filter { available.contains($0) }))
    }

    private var kinds: [CryptoWalletKind] { CryptoWalletKind.allCases.filter { kind in kind.chains.contains { available.contains($0) } } }
    private var evmChains: [CryptoChain] { CryptoChain.evm.filter { available.contains($0) } }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: JV.Space.md) {
                    tradingNote(Crypto.hint)
                    LabeledContent("Chain") {
                        Picker("Chain", selection: $kind) {
                            ForEach(kinds, id: \.self) { kind in Text(verbatim: kind.label).tag(kind) }
                        }
                        .labelsHidden()
                        .disabled(busy)
                    }
                    if kind == .evm {
                        Text("Chains").font(JV.font(13, .medium)).foregroundStyle(JV.textPrimary)
                        ForEach(evmChains, id: \.self) { chain in
                            Toggle(chain.label, isOn: Binding(get: { chains.contains(chain) },
                                                              set: { on in if on { chains.insert(chain) } else { chains.remove(chain) } }))
                        }
                    }
                    TextField("Address", text: $address)
                        .font(JV.mono(15))
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.asciiCapable)
                        .privacySensitive()
                    TextField("Label (optional)", text: $label)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .privacySensitive()
                    if let error { JobNotice(text: error, style: .danger) }
                    HStack(spacing: JV.Space.xs) {
                        Spacer(minLength: 0)
                        Button("Cancel") { dismiss() }.buttonStyle(JVButtonStyle(kind: .secondary)).disabled(busy)
                        Button("Add wallet") { Task { await add() } }.buttonStyle(JVButtonStyle(kind: .primary)).disabled(busy)
                    }
                }
                .font(JV.font(15))
                .foregroundStyle(JV.textPrimary)
                .padding(JV.Space.md)
            }
            .navigationTitle("Add a wallet")
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled(busy)
        .privacyCover()
        .onChange(of: kind) { _, _ in chains = Set(evmChains) }
        .onChange(of: error) { _, error in
            if let error { AccessibilityNotification.Announcement(error).post() }
        }
    }

    private func add() async {
        guard !busy else { return }
        error = nil
        let body: NewCryptoWallet
        switch Crypto.newWallet(kind: kind, address: address, label: label, chains: Array(chains)) {
        case let .failure(issue):
            // Key material is never kept: both fields are emptied, the message is fixed.
            if issue.key { address = ""; label = "" }
            error = issue.message
            return
        case let .success(wallet):
            body = wallet
        }
        busy = true
        do {
            try await model.addCryptoWallet(body)
            address = ""
            label = ""
            await done(true)
            busy = false
            dismiss()
        } catch {
            self.error = Crypto.addFailureText(error)
            if Crypto.isKeyMaterialRefusal(error) { address = ""; label = "" }
            await done(false)
            busy = false
        }
    }
}
