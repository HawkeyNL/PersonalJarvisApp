package com.hawkeynl.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.BAR_RANGES
import com.hawkeynl.jarvis.network.Bar
import com.hawkeynl.jarvis.network.Freshness
import com.hawkeynl.jarvis.network.HISTORY_RANGES
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.Holdings
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.network.LinkTone
import com.hawkeynl.jarvis.network.Portfolio
import com.hawkeynl.jarvis.network.Position
import com.hawkeynl.jarvis.network.TradingState
import com.hawkeynl.jarvis.network.ageText
import com.hawkeynl.jarvis.network.asOfText
import com.hawkeynl.jarvis.network.changeRatio
import com.hawkeynl.jarvis.network.chartNumber
import com.hawkeynl.jarvis.network.chartTime
import com.hawkeynl.jarvis.network.dayText
import com.hawkeynl.jarvis.network.decimalSign
import com.hawkeynl.jarvis.network.errorText
import com.hawkeynl.jarvis.network.formatDecimal
import com.hawkeynl.jarvis.network.formatMoney
import com.hawkeynl.jarvis.network.formatPercent
import com.hawkeynl.jarvis.network.formatPrice
import com.hawkeynl.jarvis.network.HOLDING_NUMBER_RULE
import com.hawkeynl.jarvis.network.holdingNumber
import com.hawkeynl.jarvis.network.holdingsErrorText
import com.hawkeynl.jarvis.network.holdingsWriteText
import com.hawkeynl.jarvis.network.ibkrLinkState
import com.hawkeynl.jarvis.network.isStock
import com.hawkeynl.jarvis.network.movingAverage
import com.hawkeynl.jarvis.network.notConnectedText
import com.hawkeynl.jarvis.network.offTarget
import com.hawkeynl.jarvis.network.onTarget
import com.hawkeynl.jarvis.network.skippedText
import com.hawkeynl.jarvis.network.sourceLabel
import com.hawkeynl.jarvis.network.tradingState
import kotlinx.coroutines.launch

// Trading (desktop TradingDesk.vue, TradingStocks.vue, TradingStock.vue, TradingPositions.vue
// and the manual holdings of Trading.vue). Read-only towards IBKR: this app places no orders.
// The only writes are the owner's own manual holdings.

/** One trading read as a state, read again when [key] changes or on [TradingRead.reload]. Each
 *  read starts from loading and a newer one cancels it, so the newest wins; a null answer
 *  (locked or signed out) leaves it loading, as the page is about to go. */
private class TradingRead<T>(val state: TradingState<T>, val reload: () -> Unit)

@Composable
private fun <T> rememberTradingRead(
    actions: JarvisViewModel, key: Any?, fresh: (T) -> Freshness, isEmpty: (T) -> Boolean, instrument: Boolean = false,
    call: suspend JarvisApi.(HomeNodeEndpoint, String) -> ApiResult<T>,
): TradingRead<T> {
    var state by remember(key) { mutableStateOf<TradingState<T>>(TradingState.Loading) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, tick) {
        state = TradingState.Loading
        val result = actions.jobs(call) ?: return@LaunchedEffect
        state = tradingState(result, fresh, isEmpty, System.currentTimeMillis(), instrument)
    }
    return TradingRead(state) { tick++ }
}

@Composable
private fun rememberPortfolio(actions: JarvisViewModel, isEmpty: (Portfolio) -> Boolean): TradingRead<Portfolio> =
    rememberTradingRead<Portfolio>(actions, Unit, { it.fresh }, isEmpty) { e, t -> portfolio(e, t) }

private val noValue: (Portfolio) -> Boolean = { it.positions.isEmpty() && decimalSign(it.netLiquidation) == 0 }

/** Colour of a signed amount (desktop td-pos / td-neg). */
@Composable
internal fun signColor(value: String?): Color = when (decimalSign(value)) {
    1 -> JvTheme.colors.positiveText
    -1 -> JvTheme.colors.negativeText
    else -> JvTheme.colors.textPrimary
}

private fun signTone(value: String?): Tone = when (decimalSign(value)) {
    1 -> Tone.OK
    -1 -> Tone.WARN
    else -> Tone.IDLE
}

/** Every trading read renders through this: loading, not connected (with what to do), an
 *  error, and a stale banner above data. [content] gets the data and whether it is empty. */
@Composable
private fun <T> TradingStatus(read: TradingRead<T>, what: String, content: @Composable (T, Boolean) -> Unit) {
    when (val state = read.state) {
        TradingState.Loading -> Skeleton("Loading $what…")
        is TradingState.Disconnected -> {
            val (title, detail) = notConnectedText(state.reason)
            StateCard(title, detail, error = false, retry = read.reload)
        }
        is TradingState.Failed -> StateCard("Could not load $what", errorText(state.reason), error = true, retry = read.reload)
        is TradingState.Ready -> {
            state.staleMs?.let { age ->
                Banner("Prices are ${ageText(age)} old. Core got no newer data from IBKR; values may be off.", warn = true, retry = read.reload)
            }
            content(state.data, state.empty)
        }
    }
}

@Composable
private fun StateCard(title: String, detail: String, error: Boolean, retry: () -> Unit) {
    ListSection(null) {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                IconTile(if (error) JvIcon.ALERT else JvIcon.TREND, if (error) Tone.ERROR else null)
                Text(title, Modifier.padding(start = JvSpace.md), style = MaterialTheme.typography.titleSmall)
            }
            MutedText(detail)
            GhostButton("Try again", retry)
        }
    }
}

/** A note band (desktop td-banner); [warn] tints it. Announced when it appears. */
@Composable
internal fun Banner(text: String, warn: Boolean = false, retry: (() -> Unit)? = null) {
    val c = JvTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(JvShapes.lg).background(if (warn) c.negativeSubtle else c.accentSubtle)
            .padding(JvSpace.lg).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(JvSpace.sm),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        retry?.let { GhostButton("Try again", it) }
    }
}

/** One key figure (desktop td-kpi). */
@Composable
internal fun RowScope.Kpi(label: String, value: String, sub: String, color: Color = JvTheme.colors.textPrimary) {
    Column(
        Modifier.weight(1f).clip(JvShapes.lg).background(JvTheme.colors.bgSurface).padding(JvSpace.md).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(JvSpace.xxs),
    ) {
        Text(label, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.labelMedium)
        Text(value, color = color, style = MaterialTheme.typography.titleMedium.tabular())
        Text(sub, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** A progress bar (desktop td-meter), clamped; [mark] draws the target. Decorative: the
 *  numbers next to it carry the meaning. */
@Composable
internal fun Meter(percent: Double, mark: Double? = null, over: Boolean = false) {
    val c = JvTheme.colors
    Box(Modifier.fillMaxWidth().height(8.dp).clip(JvShapes.pill).background(c.bgSunken)) {
        Box(Modifier.fillMaxWidth((percent.coerceIn(0.0, 100.0) / 100).toFloat()).fillMaxHeight().background(if (over) c.negativeSolid else c.accentSolid))
        mark?.let { m ->
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * (m.coerceIn(0.0, 100.0) / 100).toFloat()
                drawLine(c.textPrimary, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

private fun pct(value: String?): Double = chartNumber(value) ?: 0.0

// ---- the Trading tab ---------------------------------------------------------

/** The Trading tab (desktop TradingDesk.vue): every pot on one desk. Read-only; only the IBKR
 *  stocks pot has data, the others say plainly that they are not built yet. */
@Composable
fun TradingScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    val portfolio = rememberPortfolio(actions, noValue)
    var range by rememberSaveable { mutableStateOf("3M") }
    val history = rememberTradingRead<com.hawkeynl.jarvis.network.PortfolioHistory>(actions, range, { it.fresh }, { it.points.size < 2 }) { e, t -> valueHistory(e, t, range) }
    val now = System.currentTimeMillis()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JvSpace.lg),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabTitle("Trading", "All pots in one place. Read-only: this app places no orders.", Modifier.weight(1f))
            GhostButton("Refresh", { portfolio.reload(); history.reload() })
        }
        (portfolio.state as? TradingState.Ready)?.let { MutedText("${sourceLabel(it.data.fresh)} · as of ${asOfText(it.data.fresh.asOf, now)}") }
        TradingStatus(portfolio, "your portfolio") { data, empty ->
            if (empty) {
                ListSection(null) {
                    EmptyState("Nothing in your IBKR account yet", body = "Core is connected, but the account holds no positions and no cash.", icon = JvIcon.TREND)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                        Kpi("Total value", formatMoney(data.netLiquidation, data.baseCurrency), "incl. ${formatMoney(data.cash, data.baseCurrency)} cash")
                        Kpi("Today", formatMoney(data.dayPnl, data.baseCurrency, sign = true), formatPercent(data.dayChangePct), signColor(data.dayPnl))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                        Kpi(
                            "Total return", formatMoney(data.totalPnl, data.baseCurrency, sign = true),
                            data.totalReturnPct?.let { "${formatPercent(it)} since start" } ?: "Since start", signColor(data.totalPnl),
                        )
                        Kpi("Positions", "${data.positions.size}", "${if (data.positions.size == 1) "1 instrument" else "${data.positions.size} instruments"} at IBKR")
                    }
                }
            }
        }

        ListSection("Total value") {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                val points = (history.state as? TradingState.Ready)?.data?.points.orEmpty()
                val ratio = if (points.size > 1) changeRatio(points.first().value, points.last().value) else null
                Segmented(HISTORY_RANGES.map { it to if (it == "ALL") "All" else it }, range, { range = it })
                ratio?.let { StatusBadge(formatPercent(it), tone = signTone(it)) }
                TradingStatus(history, "the value history") { data, empty ->
                    if (empty) {
                        MutedText("Not enough history for this period yet.")
                    } else {
                        val series = remember(data) { data.points.map { Bar(it.t, it.value, it.value, it.value, it.value) } }
                        val label = "Total value over ${if (range == "ALL") "all time" else range}: ${ratio?.let { formatPercent(it) } ?: "no change data"}"
                        PriceChart(series, data.baseCurrency, label, height = 220)
                    }
                }
            }
        }

        SectionLabel("Pots", Modifier.padding(horizontal = JvSpace.xs))
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Text("Stocks", style = MaterialTheme.typography.titleSmall)
                MutedText("IBKR · read-only")
                when (val state = portfolio.state) {
                    is TradingState.Ready -> if (state.empty) {
                        MutedText("Connected; nothing held yet.")
                    } else {
                        val p = state.data
                        Text(formatMoney(p.netLiquidation, p.baseCurrency), style = MaterialTheme.typography.titleMedium.tabular())
                        Text("${formatMoney(p.dayPnl, p.baseCurrency, sign = true)} today", color = signColor(p.dayPnl), style = MaterialTheme.typography.bodyMedium)
                        val allocation = p.allocation
                        if (!allocation.isNullOrEmpty()) {
                            Row { Text("Allocation on target", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); MutedText("${onTarget(allocation)} of ${allocation.size}") }
                            Meter(onTarget(allocation) * 100.0 / allocation.size)
                        } else {
                            MutedText("No allocation targets set.")
                        }
                    }
                    TradingState.Loading -> MutedText("Loading…")
                    else -> MutedText("No IBKR data yet; see above.")
                }
            }
        }
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Memecoin", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    StatusBadge("Paper only")
                }
                MutedText("Solana · paper")
                MutedText("A paper simulation on Solana market data, off by default. No wallet, no real money.")
                GhostButton("Open memecoin", { onNavigate(Screen.MEMECOIN) })
            }
        }
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Futures", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    StatusBadge("Later")
                }
                MutedText("Futures through IBKR come later: paper and read-only first, then a signed mandate.")
            }
        }
        ListSection(null) {
            linksOf(Tab.TRADING).forEachIndexed { i, screen ->
                if (i > 0) ListDivider()
                when (screen) {
                    Screen.STOCKS -> NavRow(JvIcon.TREND, "Stocks", "Holdings, allocation, deposit and dividends", { onNavigate(screen) })
                    Screen.MEMECOIN -> NavRow(JvIcon.SPARK, "Memecoin", "The paper trader, its strategies and Claude's decisions", { onNavigate(screen) })
                    Screen.POSITIONS -> NavRow(JvIcon.LINES, "Positions", "Every open IBKR position", { onNavigate(screen) })
                    Screen.HOLDINGS -> NavRow(JvIcon.BRIEFCASE, "Holdings", "Manual holdings and the IBKR status", { onNavigate(screen) })
                    else -> MissingLinkRow(Tab.TRADING, screen, onNavigate)
                }
            }
        }
    }
}

// ---- Stocks ------------------------------------------------------------------

/** Stocks (desktop TradingStocks.vue) and one instrument (TradingStock.vue), opened inside. */
@Composable
fun StocksScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    var conid by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = conid
    if (open != null) {
        BackHandler { conid = null }
        key(open) { StockPage(open, actions) { conid = null } }
        return
    }
    val read = rememberPortfolio(actions, noValue)
    val now = System.currentTimeMillis()
    PushedPage(
        screenTitle(Screen.STOCKS), (read.state as? TradingState.Ready)?.let { "${sourceLabel(it.data.fresh)} · read-only · as of ${asOfText(it.data.fresh.asOf, now)}" },
        { onNavigate(backOf(Screen.STOCKS)) }, trailing = { GhostButton("Refresh", read.reload) },
    ) {
        TradingStatus(read, "your holdings") { data, empty ->
            if (empty) {
                ListSection(null) {
                    EmptyState(
                        "No holdings yet", icon = JvIcon.TREND,
                        body = "Your IBKR account is connected but holds nothing. Holdings appear here after your first purchase.",
                    )
                }
            } else {
                StocksContent(data) { conid = it }
            }
        }
    }
}

@Composable
private fun StocksContent(data: Portfolio, open: (Long) -> Unit) {
    val base = data.baseCurrency
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Kpi("Value", formatMoney(data.netLiquidation, base), "incl. ${formatMoney(data.cash, base)} cash")
            Kpi("Today", formatMoney(data.dayPnl, base, sign = true), formatPercent(data.dayChangePct), signColor(data.dayPnl))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Kpi("Total return", formatMoney(data.totalPnl, base, sign = true), data.totalReturnPct?.let { "${formatPercent(it)} since start" } ?: "Since start", signColor(data.totalPnl))
            Kpi("Dividends, 12 months", formatMoney(data.dividends12m, base), if (data.dividends12m != null) "Received" else "Not reported by Core")
        }
    }

    ListSection("Allocation and your target") {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            StatusBadge("Calculation")
            val allocation = data.allocation
            if (allocation.isNullOrEmpty()) {
                MutedText("No targets set yet. Core reports targets once you set them for the stocks pot.")
            } else {
                allocation.forEach { a ->
                    val off = offTarget(a)
                    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                        Text(a.label, style = MaterialTheme.typography.bodyMedium)
                        MutedText("${formatDecimal(a.currentPct, places = 0)}% · target ${formatDecimal(a.targetPct, places = 0)}%")
                        off?.let { (text, over) -> StatusBadge(text, tone = if (over) Tone.WARN else Tone.IDLE) }
                        Meter(pct(a.currentPct), mark = pct(a.targetPct), over = off?.second == true)
                    }
                }
                MutedText("The mark is your target. Computed by code from your IBKR positions; Jarvis never sells just to rebalance.")
            }
        }
    }

    ListSection("Monthly deposit") {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
            val deposit = data.monthlyDeposit
            if (deposit == null) {
                MutedText("No monthly deposit set.")
            } else {
                Text("${formatMoney(deposit.amount, base)} every month on day ${deposit.dayOfMonth}", style = MaterialTheme.typography.bodyMedium)
                MutedText("Next deposit: ${dayText(deposit.nextDate)}.")
            }
        }
    }

    val stocks = data.positions.filter(::isStock)
    ListSection("Holdings · ${stocks.size} ${if (stocks.size == 1) "holding" else "holdings"}") {
        if (stocks.isEmpty()) {
            EmptyState("No stocks or ETFs", body = "This account holds no stocks or ETFs. Other positions are under Positions.", compact = true)
        }
        stocks.forEachIndexed { i, p ->
            if (i > 0) ListDivider()
            PositionRow(p, base, stock = true) { open(p.conid) }
        }
    }

    ListSection("Coming dividends") {
        val dividends = data.upcomingDividends
        if (dividends.isNullOrEmpty()) EmptyState("No dividends announced for your holdings.", compact = true)
        dividends.orEmpty().forEachIndexed { i, d ->
            if (i > 0) ListDivider()
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
                horizontalArrangement = Arrangement.spacedBy(JvSpace.md), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dayText(d.exDate), Modifier.width(56.dp), color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${d.symbol} ex-dividend" + (d.payDate?.let { ", paid ${dayText(it)}" } ?: "") + (d.amount?.let { ", about ${formatMoney(it, d.currency)}" } ?: ""),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                StatusBadge(if (d.amount != null) "Calculation" else "Fact")
            }
        }
    }
}

/** One position: symbol and name, then its figures; [open] makes it a button. [stock] rows
 *  show the day change and share (Stocks), others the class, prices and result (Positions). */
@Composable
private fun PositionRow(p: Position, base: String, stock: Boolean, open: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open instrument", onClick = open) else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xxs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.symbol, style = MaterialTheme.typography.titleSmall)
                p.name?.let { Text(it, color = JvTheme.colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            StatusBadge(if (stock) mapOf("STK" to "Stock", "ETF" to "ETF")[p.assetClass] ?: p.assetClass else p.assetClass)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Text(formatMoney(p.marketValue, base), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.tabular())
            if (stock) {
                Text(formatPercent(p.dayChangePct), color = signColor(p.dayChangePct), style = MaterialTheme.typography.bodyMedium.tabular())
            }
            Text(formatMoney(p.unrealizedPnl, base, sign = true), color = signColor(p.unrealizedPnl), style = MaterialTheme.typography.bodyMedium.tabular())
        }
        MutedText(
            if (stock) {
                "Quantity ${formatDecimal(p.quantity, maxPlaces = 8)} · share ${p.weightPct?.let { "${formatDecimal(it, places = 1)}%" } ?: "—"}"
            } else {
                listOfNotNull(
                    p.exchange, "Quantity ${formatDecimal(p.quantity, maxPlaces = 8)}",
                    "avg. ${formatPrice(p.avgCost, p.currency)}", "last ${formatPrice(p.lastPrice, p.currency)}",
                ).joinToString(" · ")
            },
        )
    }
}

// ---- one instrument ----------------------------------------------------------

/** One instrument: price chart (line or candles, 1D–5Y, moving averages, volume), Jarvis's
 *  notes labelled by kind, and the owner's position if held. */
@Composable
private fun StockPage(conid: Long, actions: JarvisViewModel, back: () -> Unit) {
    var range by rememberSaveable { mutableStateOf("3M") }
    var candle by rememberSaveable { mutableStateOf(false) }
    var ma50 by rememberSaveable { mutableStateOf(false) }
    var ma200 by rememberSaveable { mutableStateOf(false) }
    var volume by rememberSaveable { mutableStateOf(true) }
    val bars = rememberTradingRead<com.hawkeynl.jarvis.network.InstrumentBars>(actions, range, { it.fresh }, { it.bars.isEmpty() }, instrument = true) { e, t -> instrumentBars(e, t, conid, range) }
    val portfolio = rememberPortfolio(actions) { false }
    val data = (bars.state as? TradingState.Ready)?.data
    val last = data?.bars?.lastOrNull()
    val change = if (data != null && last != null) changeRatio(data.bars.first().o, last.c) else null
    val count = data?.bars?.size ?: 0
    val hasVolume = data?.bars?.any { it.v != null } == true
    val now = System.currentTimeMillis()

    PushedPage(
        data?.name ?: data?.symbol ?: "Instrument $conid",
        data?.let { listOfNotNull(it.symbol, it.exchange).joinToString(" · ") }, back,
        trailing = { GhostButton("Refresh", { bars.reload(); portfolio.reload() }) },
    ) {
        if (data != null && last != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.md), verticalAlignment = Alignment.Bottom) {
                Text(formatPrice(last.c, data.currency), style = MaterialTheme.typography.headlineSmall.tabular())
                change?.let { Text("${formatPercent(it)} over $range", color = signColor(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                Segmented(BAR_RANGES.map { it to it }, range, { range = it })
                Segmented(listOf("line" to "Line", "candle" to "Candles"), if (candle) "candle" else "line", { candle = it == "candle" })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                    FilterChip(selected = ma50 && count >= 50, onClick = { ma50 = !ma50 }, label = { Text("Average 50") }, enabled = count >= 50)
                    FilterChip(selected = ma200 && count >= 200, onClick = { ma200 = !ma200 }, label = { Text("Average 200") }, enabled = count >= 200)
                    FilterChip(selected = volume && hasVolume, onClick = { volume = !volume }, label = { Text("Volume") }, enabled = hasVolume)
                }
                TradingStatus(bars, "the price history") { d, empty ->
                    if (empty) {
                        MutedText("No prices for this period. The market may not have traded yet.")
                    } else {
                        val label = "${d.symbol}, $range: ${change?.let { formatPercent(it) } ?: "no change data"}"
                        PriceChart(
                            d.bars, d.currency, label, candle = candle, volume = volume && hasVolume,
                            ma = listOfNotNull(50.takeIf { ma50 }, 200.takeIf { ma200 }).filter { it <= d.bars.size },
                        )
                    }
                    MutedText("Prices: ${sourceLabel(d.fresh)} · as of ${asOfText(d.fresh.asOf, now)}. Averages are over the bars of this period.")
                }
            }
        }

        ListSection("Jarvis notes") {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                val notes = data?.notes.orEmpty()
                if (notes.isEmpty()) {
                    MutedText("No notes from Jarvis for this period.")
                } else {
                    notes.forEach { n ->
                        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                                StatusBadge(n.kind.label)
                                MutedText(dayText(n.t).takeIf { it != "—" }.orEmpty())
                            }
                            Text(n.text, style = MaterialTheme.typography.bodyMedium)
                            if (n.sources.isNotEmpty()) MutedText("Sources: ${n.sources.joinToString(", ")}")
                        }
                    }
                    MutedText("Fact: from a source. Calculation: computed by code. AI inference: Jarvis's estimate, which can be wrong.")
                }
            }
        }

        val held = (portfolio.state as? TradingState.Ready)?.data
        val position = held?.positions?.firstOrNull { it.conid == conid }
        if (position != null && held != null) {
            Facts(
                "Your position",
                listOf(
                    "Quantity" to formatDecimal(position.quantity, maxPlaces = 8),
                    "Avg. cost" to formatPrice(position.avgCost, position.currency),
                    "Value" to formatMoney(position.marketValue, held.baseCurrency),
                    "Result" to formatMoney(position.unrealizedPnl, held.baseCurrency, sign = true),
                ),
            )
        } else {
            ListSection("Your position") {
                EmptyState(
                    when {
                        held != null -> "You do not hold this instrument."
                        portfolio.state == TradingState.Loading -> "Loading your position…"
                        else -> "Your position shows once Core reports your IBKR portfolio."
                    },
                    compact = true,
                )
            }
        }
    }
}

/** The chart's numbers, computed once per input: decimal strings become doubles only here
 *  (NaN where a value is missing). The guards keep only bars with readable prices. */
private class ChartData(bars: List<Bar>, candle: Boolean, ma: List<Int>, volume: Boolean) {
    val size = bars.size
    val open = DoubleArray(size) { chartNumber(bars[it].o) ?: Double.NaN }
    val high = DoubleArray(size) { chartNumber(bars[it].h) ?: Double.NaN }
    val low = DoubleArray(size) { chartNumber(bars[it].l) ?: Double.NaN }
    val close = DoubleArray(size) { chartNumber(bars[it].c) ?: Double.NaN }
    val volumes = if (volume) DoubleArray(size) { chartNumber(bars[it].v) ?: Double.NaN } else null
    val maxVolume = volumes?.filter { !it.isNaN() }?.maxOrNull()?.takeIf { it > 0 }
    val averages = ma.map { n -> n to movingAverage(close.toList(), n).map { it ?: Double.NaN }.toDoubleArray() }
    private val drawn = (if (candle) high.asList() + low.asList() else close.asList()) + averages.flatMap { it.second.asList() }
    val top = drawn.filter { !it.isNaN() }.maxOrNull()
    val bottom = drawn.filter { !it.isNaN() }.minOrNull()
    /** Indexes of the highest high and the lowest low, for the spoken summary. */
    val highest = high.indices.maxByOrNull { if (high[it].isNaN()) Double.NEGATIVE_INFINITY else high[it] }
    val lowest = low.indices.minByOrNull { if (low[it].isNaN()) Double.POSITIVE_INFINITY else low[it] }
}

/** A price or value chart drawn on a Canvas: a line with its area, or candles (wick low–high,
 *  body open–close), moving averages and volume along the bottom. The values table and the
 *  spoken summary show the original strings. */
@Composable
private fun PriceChart(
    bars: List<Bar>, currency: String, label: String, height: Int = 300, candle: Boolean = false, ma: List<Int> = emptyList(), volume: Boolean = false,
) {
    val c = JvTheme.colors
    var table by rememberSaveable { mutableStateOf(false) }
    val d = remember(bars, candle, ma, volume) { ChartData(bars, candle, ma, volume) }
    val summary = remember(bars, label, currency) {
        listOfNotNull(
            label, bars.lastOrNull()?.let { "last ${formatPrice(it.c, currency)}" },
            d.highest?.let { "high ${formatPrice(bars[it].h, currency)}" }, d.lowest?.let { "low ${formatPrice(bars[it].l, currency)}" },
        ).joinToString(", ")
    }
    Canvas(Modifier.fillMaxWidth().height(height.dp).semantics { contentDescription = summary }) {
        val high = d.top ?: return@Canvas
        val low = d.bottom ?: return@Canvas
        val volumeBand = if (d.volumes != null) size.height * 0.25f else 0f
        val plot = size.height - volumeBand
        val pad = if (high == low) maxOf(1.0, high * 0.01) else (high - low) * 0.05
        val top = high + pad
        val span = (top - (low - pad)).toFloat()
        val slot = size.width / d.size
        fun x(i: Int) = slot * (i + 0.5f)
        fun y(v: Double) = ((top - v) / span * plot).toFloat()

        for (g in 1..3) drawLine(c.separator, Offset(0f, plot * g / 4), Offset(size.width, plot * g / 4), strokeWidth = 1f)

        val maxVol = d.maxVolume
        if (d.volumes != null && maxVol != null) {
            for (i in 0 until d.size) {
                val v = d.volumes[i]
                if (v.isNaN()) continue
                val h = (v / maxVol * volumeBand).toFloat()
                drawRect(if (d.close[i] >= d.open[i]) c.borderStrong else c.border, Offset(x(i) - slot * 0.35f, size.height - h), Size(slot * 0.7f, h))
            }
        }

        if (candle) {
            for (i in 0 until d.size) {
                val o = d.open[i]
                val cl = d.close[i]
                val color = if (o.isNaN() || cl.isNaN()) c.borderStrong else if (cl >= o) c.positiveSolid else c.negativeSolid
                if (!d.high[i].isNaN() && !d.low[i].isNaN()) drawLine(color, Offset(x(i), y(d.high[i])), Offset(x(i), y(d.low[i])), strokeWidth = 1.dp.toPx())
                if (!o.isNaN() && !cl.isNaN()) {
                    val bodyTop = y(maxOf(o, cl))
                    drawRect(color, Offset(x(i) - slot * 0.35f, bodyTop), Size(slot * 0.7f, maxOf(1f, y(minOf(o, cl)) - bodyTop)))
                }
            }
        } else {
            val line = Path()
            val area = Path()
            var start = -1
            var end = -1
            for (i in 0 until d.size) {
                val v = d.close[i]
                if (v.isNaN()) continue
                if (start < 0) { start = i; line.moveTo(x(i), y(v)); area.moveTo(x(i), plot); area.lineTo(x(i), y(v)) } else { line.lineTo(x(i), y(v)); area.lineTo(x(i), y(v)) }
                end = i
            }
            if (start >= 0) {
                area.lineTo(x(end), plot)
                area.close()
                drawPath(area, c.chartAccent.copy(alpha = 0.1f))
                drawPath(line, c.chartAccent, style = Stroke(width = 2.dp.toPx()))
            }
        }

        d.averages.forEach { (n, series) ->
            val path = Path()
            var started = false
            for (i in series.indices) {
                val v = series[i]
                if (v.isNaN()) continue
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            val long = n >= 200
            drawPath(
                path, if (long) c.textSecondary else c.chartMa,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = if (long) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null),
            )
        }
    }
    Row {
        MutedText(chartTime(bars.first().t, bars), Modifier.weight(1f))
        MutedText(chartTime(bars.last().t, bars))
    }
    GhostButton(if (table) "Hide the values table" else "Show the values as a table", { table = !table })
    if (table) ValuesTable(bars, currency, candle, volume)
}

/** The chart's values as rows, newest last (desktop's table under the chart). */
@Composable
private fun ValuesTable(bars: List<Bar>, currency: String, candle: Boolean, volume: Boolean) {
    LazyColumn(
        Modifier.fillMaxWidth().height(280.dp).clip(JvShapes.md).background(JvTheme.colors.bgSunken),
        verticalArrangement = Arrangement.spacedBy(JvSpace.xxs),
    ) {
        items(bars) { b ->
            val prices = if (candle) {
                "O ${formatPrice(b.o, currency)}  H ${formatPrice(b.h, currency)}  L ${formatPrice(b.l, currency)}  C ${formatPrice(b.c, currency)}"
            } else {
                formatPrice(b.c, currency)
            }
            Text(
                listOfNotNull(chartTime(b.t, bars, full = true), prices, if (volume) "Volume ${formatDecimal(b.v, places = 0)}" else null).joinToString(" · "),
                Modifier.padding(horizontal = JvSpace.md, vertical = JvSpace.xxs), style = MaterialTheme.typography.bodySmall.tabular(),
            )
        }
    }
}

// ---- Positions -----------------------------------------------------------------

/** Every open position in the IBKR account, all asset classes (desktop TradingPositions.vue).
 *  Stocks and ETFs open their instrument page. */
@Composable
fun PositionsScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    var conid by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = conid
    if (open != null) {
        BackHandler { conid = null }
        key(open) { StockPage(open, actions) { conid = null } }
        return
    }
    val read = rememberPortfolio(actions) { it.positions.isEmpty() }
    val now = System.currentTimeMillis()
    PushedPage(
        screenTitle(Screen.POSITIONS), (read.state as? TradingState.Ready)?.let { "${sourceLabel(it.data.fresh)} · read-only · as of ${asOfText(it.data.fresh.asOf, now)}" },
        { onNavigate(backOf(Screen.POSITIONS)) }, trailing = { GhostButton("Refresh", read.reload) },
    ) {
        TradingStatus(read, "your positions") { data, empty ->
            skippedText(data)?.let { MutedText(it) }
            ListSection("Open positions · ${if (data.positions.isEmpty()) "Nothing open" else "${data.positions.size} open"}") {
                if (empty) EmptyState("No open positions", body = "Your IBKR account has no open positions.", compact = true)
                data.positions.forEachIndexed { i, p ->
                    if (i > 0) ListDivider()
                    PositionRow(p, data.baseCurrency, stock = false, open = if (isStock(p)) ({ conid = p.conid }) else null)
                }
            }
        }
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open orders", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    StatusBadge("Not available")
                }
                MutedText("The IBKR connection is read-only, and Core does not report orders yet. Orders come with trading, behind risk checks and your confirmation.")
            }
        }
    }
}

// ---- Holdings --------------------------------------------------------------------

/** Manual holdings and the IBKR status (desktop Trading.vue, /trading/holdings and
 *  /trading/ibkr). The only writes on the Trading tab: adding and removing a holding the
 *  owner entered by hand. */
@Composable
fun HoldingsScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    var manual by remember { mutableStateOf<Holdings?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var symbol by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var avgCost by rememberSaveable { mutableStateOf("") }
    var segment by rememberSaveable { mutableStateOf("manual") }
    val ibkr = rememberPortfolio(actions) { it.positions.isEmpty() }
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()

    val reads = remember { intArrayOf(0) }

    // Only the newest read may set the list; loading always ends, also when the answer is
    // null (locked or signed out: this page is about to go).
    suspend fun refresh() {
        val mine = ++reads[0]
        loading = true
        error = null
        val result = actions.jobs { e, t -> holdings(e, t) }
        if (mine != reads[0]) return
        loading = false
        if (result is ApiResult.Success) manual = result.value else if (result != null) error = holdingsErrorText(result)
    }
    LaunchedEffect(Unit) { refresh() }

    fun write(call: suspend JarvisApi.(HomeNodeEndpoint, String) -> ApiResult<Unit>, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val result = actions.jobs(call) ?: return@launch
                if (result is ApiResult.Success) {
                    done()
                    refresh()
                } else {
                    // An unreadable 2xx or no answer may have landed: reload to check.
                    if (result is ApiResult.InvalidResponse || result is ApiResult.Unreachable) refresh()
                    error = holdingsWriteText(result)
                }
            } finally {
                busy = false
            }
        }
    }

    val (linkTone, linkLabel) = ibkrLinkState(ibkr.state)
    val tone = when (linkTone) { LinkTone.OK -> Tone.OK; LinkTone.WARN -> Tone.WARN; LinkTone.IDLE -> Tone.IDLE }
    PushedPage(
        screenTitle(Screen.HOLDINGS),
        "Your positions on one desk: manual, and live through IBKR (read-only). Trading (orders) comes later, with risk checks and confirmation.",
        { onNavigate(backOf(Screen.HOLDINGS)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                Kpi("Positions", "${manual?.holdings?.size ?: 0}", "entered by hand")
                Kpi("Cost basis", manual?.totalCost ?: "0", "of the manual positions")
            }
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Text("IBKR", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                StatusBadge(linkLabel, tone = tone)
            }
            MutedText("read-only connection")
        }
        Segmented(listOf("manual" to "Portfolio", "ibkr" to "IBKR"), segment, { segment = it })
        if (segment == "manual") {
            ListSection("Manual positions") {
                Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                    HoldingField("Symbol (AAPL)", "Symbol", symbol, KeyboardType.Text) { symbol = it }
                    val invalid = error == HOLDING_NUMBER_RULE
                    HoldingField("Quantity", "Quantity", quantity, KeyboardType.Decimal, invalid && holdingNumber(quantity) == null) { quantity = it }
                    HoldingField("Avg. cost", "Average cost", avgCost, KeyboardType.Decimal, invalid && holdingNumber(avgCost) == null) { avgCost = it }
                    ActionButton("Add", {
                        val s = symbol.trim()
                        val q = holdingNumber(quantity)
                        val a = holdingNumber(avgCost)
                        if (q == null || a == null) {
                            error = HOLDING_NUMBER_RULE
                        } else {
                            write({ e, t -> addHolding(e, t, s, q, a) }) { symbol = ""; quantity = ""; avgCost = "" }
                        }
                    }, enabled = !busy)
                    error?.let { ErrorText(it) }
                }
                val list = manual?.holdings.orEmpty()
                when {
                    loading && manual == null -> Skeleton("Loading your positions…", Modifier.padding(JvSpace.lg))
                    list.isNotEmpty() -> {
                        list.forEach { h ->
                            ListDivider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = JvSpace.lg, vertical = JvSpace.md), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                                        Text(h.symbol, style = MaterialTheme.typography.titleSmall)
                                        MutedText("${h.quantity} at ${h.avgCost} ${h.currency}".trimEnd() + " · cost basis ${h.costBasis} · ${h.weightPct}%")
                                    }
                                    GhostButton(
                                        "Remove", { write({ e, t -> deleteHolding(e, t, h.id) }) },
                                        Modifier.semantics { contentDescription = "Remove ${h.symbol}" }, enabled = !busy,
                                    )
                                }
                                Meter(pct(h.weightPct))
                            }
                        }
                        ListDivider()
                        FactRow("Total cost basis", manual?.totalCost ?: "0")
                    }
                    error == null -> EmptyState("No positions yet", body = "Add one above.", compact = true)
                }
            }
        } else {
            ListSection(null) {
                Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                        Text("IBKR", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        StatusBadge(linkLabel, Modifier.weight(1f, fill = false), tone = tone)
                        Box(Modifier.weight(1f))
                        GhostButton("Refresh", ibkr.reload)
                    }
                    MutedText("Read-only through Core's IBKR connection; Jarvis only reads, it never places orders.")
                }
            }
            TradingStatus(ibkr, "the IBKR portfolio") { data, empty ->
                MutedText("Last sync ${asOfText(data.fresh.asOf, now)}.")
                skippedText(data)?.let { MutedText(it) }
                ListSection(null) {
                    if (empty) EmptyState("No open positions in your IBKR account.", compact = true)
                    data.positions.forEachIndexed { i, p ->
                        if (i > 0) ListDivider()
                        Row(
                            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
                            horizontalArrangement = Arrangement.spacedBy(JvSpace.md), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.symbol, style = MaterialTheme.typography.titleSmall)
                                MutedText("${formatDecimal(p.quantity, maxPlaces = 8)} · last ${formatPrice(p.lastPrice, p.currency)}")
                            }
                            Text(formatMoney(p.marketValue, data.baseCurrency), style = MaterialTheme.typography.bodyMedium.tabular())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldingField(placeholder: String, label: String, value: String, keyboard: KeyboardType, invalid: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().semantics { if (invalid) error(HOLDING_NUMBER_RULE) },
        isError = invalid,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = true,
        shape = JvShapes.md,
    )
}

