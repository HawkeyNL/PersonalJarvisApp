package com.hawkeynl.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.ENABLE_ELSEWHERE
import com.hawkeynl.jarvis.network.EXIT_REASONS
import com.hawkeynl.jarvis.network.FAMILY_LABEL
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.network.MemeFailure
import com.hawkeynl.jarvis.network.MemeReport
import com.hawkeynl.jarvis.network.MemeStatusKind
import com.hawkeynl.jarvis.network.MemeTone
import com.hawkeynl.jarvis.network.NO_EDGE_TEXT
import com.hawkeynl.jarvis.network.REJECT_REASONS
import com.hawkeynl.jarvis.network.RESUME_TEXT
import com.hawkeynl.jarvis.network.StrategyDetail
import com.hawkeynl.jarvis.network.StrategyVersion
import com.hawkeynl.jarvis.network.WriteOutcome
import com.hawkeynl.jarvis.network.ageText
import com.hawkeynl.jarvis.network.agentStatus
import com.hawkeynl.jarvis.network.asOfText
import com.hawkeynl.jarvis.network.bpsPct
import com.hawkeynl.jarvis.network.clock
import com.hawkeynl.jarvis.network.decisionView
import com.hawkeynl.jarvis.network.disableOutcome
import com.hawkeynl.jarvis.network.formatDecimal
import com.hawkeynl.jarvis.network.formatPrice
import com.hawkeynl.jarvis.network.goalPct
import com.hawkeynl.jarvis.network.memeFailure
import com.hawkeynl.jarvis.network.memeFailureText
import com.hawkeynl.jarvis.network.memeStatus
import com.hawkeynl.jarvis.network.pauseOutcome
import com.hawkeynl.jarvis.network.progressPct
import com.hawkeynl.jarvis.network.reasonLabel
import com.hawkeynl.jarvis.network.reportAge
import com.hawkeynl.jarvis.network.shortMint
import com.hawkeynl.jarvis.network.strategyState
import com.hawkeynl.jarvis.network.stuckCount
import com.hawkeynl.jarvis.network.unconfirmedOutcome
import com.hawkeynl.jarvis.network.usd
import com.hawkeynl.jarvis.network.versionOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Memecoin (desktop TradingMemecoin.vue, TradingMemeStrategies.vue, TradingMemeStrategy.vue):
// Core's PAPER trader, read-only apart from the pause, its strategies (read-only apart from
// disable) and Claude's decisions. It is a simulation with no wallet; nothing here may suggest
// real money or live trading. Pause and disable each sit behind a confirmation and count only
// once Core confirms them. There is no resume and no enable here.

@Composable
fun MemecoinScreen(actions: JarvisViewModel, onNavigate: (Screen) -> Unit) {
    var strategies by rememberSaveable { mutableStateOf(false) }
    var strategyId by rememberSaveable { mutableStateOf<String?>(null) }
    // A pause or disable still running when its page was left: said on the next page.
    var unconfirmed by rememberSaveable { mutableStateOf<String?>(null) }
    val left = Unconfirmed(unconfirmed) { unconfirmed = it }
    val id = strategyId
    when {
        id != null -> {
            BackHandler { strategyId = null }
            key(id) { StrategyPage(id, actions, left) { strategyId = null } }
        }
        strategies -> {
            BackHandler { strategies = false }
            StrategiesPage(actions, left, { strategies = false }) { strategyId = it }
        }
        else -> MemecoinPage(actions, left, { onNavigate(backOf(Screen.MEMECOIN)) }) { strategies = true }
    }
}

/** The note of a write whose page was left before Core answered, and how to set or clear it. */
private class Unconfirmed(val note: String?, val set: (String?) -> Unit)

/** Run a pause or disable; when it is cancelled before its outcome (the page was left), keep
 *  the unconfirmed note for the next page. */
private suspend fun guardedWrite(left: Unconfirmed, sending: () -> Boolean, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (sending()) left.set(unconfirmedOutcome(clock(System.currentTimeMillis())).message)
        throw cancelled
    }
}

private sealed interface MemeRead<out T> {
    data object Loading : MemeRead<Nothing>
    data class Failed(val failure: MemeFailure) : MemeRead<Nothing>
    data class Ok<T>(val data: T) : MemeRead<T>
}

/** One memecoin read. A reload keeps what is on screen; only the first load shows loading.
 *  Only the newest request may set the state; a null answer (locked or signed out) leaves it. */
private class MemeLoader<T>(private val actions: JarvisViewModel, private val call: suspend JarvisApi.(HomeNodeEndpoint, String) -> ApiResult<T>) {
    var state by mutableStateOf<MemeRead<T>>(MemeRead.Loading)
        private set
    private var seq = 0

    suspend fun load() {
        val mine = ++seq
        if (state !is MemeRead.Ok) state = MemeRead.Loading
        val result = actions.jobs(call) ?: return
        if (mine == seq) state = if (result is ApiResult.Success) MemeRead.Ok(result.value) else MemeRead.Failed(memeFailure(result))
    }
}

@Composable
private fun <T> rememberMeme(actions: JarvisViewModel, call: suspend JarvisApi.(HomeNodeEndpoint, String) -> ApiResult<T>): MemeLoader<T> {
    val loader = remember { MemeLoader(actions, call) }
    LaunchedEffect(loader) { loader.load() }
    return loader
}

/** The clock, again every 30 seconds, so staleness and "until" times stay current. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private enum class Step { IDLE, CONFIRM, SENDING }

private fun tone(t: MemeTone): Tone = when (t) {
    MemeTone.NEUTRAL -> Tone.IDLE
    MemeTone.ACCENT -> Tone.OK
    MemeTone.NEGATIVE -> Tone.WARN
}

private fun plural(n: Long, one: String, many: String) = "${formatDecimal(n.toString())} ${if (n == 1L) one else many}"

@Composable
private fun AmountRow(label: String, value: String, color: Color = JvTheme.colors.textPrimary) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = color, style = MaterialTheme.typography.bodyMedium.tabular())
    }
}

@Composable
private fun Mono(text: String, description: String? = null) {
    Text(
        text, if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

/** A confirmation card (desktop's confirm group): what happens, then keep or go. */
@Composable
private fun ConfirmCard(title: String, points: List<String>, keep: String, go: String, sending: String, step: Step, onKeep: () -> Unit, onGo: () -> Unit) {
    ListSection(null) {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            points.forEach { MutedText("• $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(keep, onKeep, enabled = step != Step.SENDING)
                ActionButton(if (step == Step.SENDING) sending else go, onGo, Modifier.weight(1f), enabled = step == Step.CONFIRM)
            }
        }
    }
}

@Composable
private fun FailedLine(what: String, failure: MemeFailure, retry: () -> Unit) {
    Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        ErrorText("Could not load $what. ${memeFailureText(failure)}", live = false)
        GhostButton("Try again", retry)
    }
}

/** The paper gate's meters: what a strategy has to show on paper, after every cost. */
@Composable
private fun GateMeters(rows: List<Triple<String, Long, Long>>, met: List<Boolean>?, expectancy: String) {
    rows.forEachIndexed { i, (label, have, need) ->
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
            AmountRow(label, "${formatDecimal(have.toString())} of ${formatDecimal(need.toString())}${if (met?.get(i) == true) " · met" else ""}")
            Meter(progressPct(have, need))
        }
    }
    AmountRow("Net per closed trade", usd(expectancy, sign = true), signColor(expectancy))
}

// ---- the paper trader --------------------------------------------------------

@Composable
private fun MemecoinPage(actions: JarvisViewModel, left: Unconfirmed, back: () -> Unit, openStrategies: () -> Unit) {
    val overview = rememberMeme(actions) { e, t -> memeOverview(e, t) }
    val candidates = rememberMeme(actions) { e, t -> memeCandidates(e, t) }
    val trades = rememberMeme(actions) { e, t -> memeTrades(e, t) }
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    // Set only once Core confirmed a pause, until the following overview read.
    var pausedHere by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(Step.IDLE) }
    var note by remember { mutableStateOf<WriteOutcome?>(null) }

    val data = (overview.state as? MemeRead.Ok)?.data?.let { if (pausedHere && !it.paused) it.copy(paused = true) else it }
    val status = data?.let(::memeStatus)
    val stuck = data?.let(::stuckCount) ?: 0
    val staleMs = data?.let { reportAge(it, now) }
    val canPause = data != null && data.enabled && !data.paused

    fun refresh() {
        pausedHere = false
        left.set(null)
        scope.launch { overview.load() }
        scope.launch { candidates.load() }
        scope.launch { trades.load() }
    }

    fun confirmPause() {
        if (step != Step.CONFIRM) return
        step = Step.SENDING
        scope.launch {
            guardedWrite(left, { step == Step.SENDING }) {
                val result = actions.jobs { e, t -> memePause(e, t) }
                if (result == null) {
                    note = unconfirmedOutcome(clock(System.currentTimeMillis()))
                    step = Step.IDLE
                    return@guardedWrite
                }
                val outcome = pauseOutcome(result, clock(System.currentTimeMillis()))
                note = outcome
                step = Step.IDLE
                if (outcome.ok) {
                    // Bridge until Core's own overview arrives; from then on it is the truth.
                    pausedHere = true
                    overview.load()
                    pausedHere = false
                } else if (outcome.status == 503) {
                    // Core paused in memory but did not save it: show what it reports now.
                    overview.load()
                }
            }
        }
    }

    PushedPage(
        screenTitle(Screen.MEMECOIN), "Solana · a simulation on read-only market data. It has no wallet and cannot buy or sell anything.", back,
        trailing = { GhostButton("Refresh", ::refresh) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            status?.let { StatusBadge(it.label, tone = tone(it.tone)) }
            StatusBadge("Paper")
        }
        left.note?.let { Banner(it, warn = true) }
        if (canPause) GhostButton("Pause paper entries", { note = null; step = Step.CONFIRM }, enabled = step != Step.SENDING)

        // Above every report figure, the paper gate included.
        staleMs?.let { Banner("The last report is ${ageText(it)} old. Core has not reported since; these figures may be out of date.", warn = true, retry = ::refresh) }
        if (stuck > 0) {
            Banner(
                "${plural(stuck.toLong(), "paper position is", "paper positions are")} stuck. " +
                    (if (stuck == 1) "Its exit failed 10 times and is no longer retried; it keeps its slot." else "Their exits failed 10 times each and are no longer retried; they keep their slots.") +
                    " The Core log has the details.",
                warn = true,
            )
        }
        ListSection(null) {
            Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
                Text("Paper — no real money.", style = MaterialTheme.typography.titleSmall)
                MutedText("Every amount here is simulated: Core models fills, fees and failed attempts on public market data. No order is placed and no wallet exists.")
                data?.report?.let { report ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Paper gate", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        StatusBadge(if (report.gate.passed) "Met" else "Not met", tone = if (report.gate.passed) Tone.OK else Tone.IDLE)
                    }
                    val g = report.gate
                    GateMeters(
                        listOf(Triple("Closed trades", g.closedTrades, g.minClosedTrades), Triple("Days", g.durationDays, g.minDurationDays), Triple("Trading days", g.tradingDays, g.minTradingDays)),
                        listOf(g.enoughTrades, g.enoughTime, g.enoughDays), g.netExpectancyUsd,
                    )
                    MutedText("${if (g.positiveExpectancy) "Positive" else "Not positive"} after every cost")
                    MutedText("What the strategy has to show on paper, after every cost. This is a report only: meeting it changes nothing, and this app cannot trade.")
                }
            }
        }

        if (step != Step.IDLE) {
            ConfirmCard(
                "Pause paper entries?",
                listOf(
                    "The trader stops opening new paper positions at once.",
                    "Exits keep running: open paper positions are still valued and can still close.",
                    "Core saves the pause and records it in its security audit log.",
                    "There is no resume here. Resuming is a configuration change on the Home Node.",
                ),
                "Keep running", "Pause entries now", "Pausing…", step, { if (step != Step.SENDING) step = Step.IDLE }, ::confirmPause,
            )
        }
        note?.let { Banner(if (it.ok) "Paused at ${it.at}. New paper entries stopped; exits keep running." else it.message.orEmpty(), warn = !it.ok) }

        when (val state = overview.state) {
            MemeRead.Loading -> Skeleton("Loading the paper trader…")
            is MemeRead.Failed -> Unavailable(
                "The paper trader", JvIcon.ALERT, kind = UnavailableKind.ERROR, detail = memeFailureText(state.failure),
                onRetry = { scope.launch { overview.load() } },
            )
            is MemeRead.Ok -> if (data != null && status != null) {
                ListSection(null) {
                    Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            IconTile(if (status.tone == MemeTone.NEGATIVE) JvIcon.ALERT else JvIcon.SPARK, tone(status.tone))
                            Text(status.label, Modifier.padding(start = JvSpace.md), style = MaterialTheme.typography.titleSmall)
                        }
                        MutedText(status.detail)
                        if (status.kind == MemeStatusKind.DISABLED) {
                            MutedText("To try it, the owner sets JARVIS_MEMECOIN_PAPER_ENABLED=true in Core's configuration and restarts Core (see MEMECOIN_PAPER.md in the Core repository). It stays a paper simulation.")
                        }
                        if (data.paused) {
                            MutedText("Paused since ${data.pausedAt?.let { "${asOfText(it, now)} ($it)" } ?: "an unknown time"}.")
                            MutedText(RESUME_TEXT)
                        }
                    }
                }
                val agent = agentStatus(data, now)
                ListSection(null) {
                    Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                        Text("Claude as strategy agent", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        StatusBadge(agent.label + (agent.until?.let { " until ${asOfText(it, now)}" } ?: ""), tone = tone(agent.tone))
                        MutedText(agent.detail)
                        GhostButton("Strategies and Claude's decisions", openStrategies)
                    }
                }
                data.report?.let { ReportSections(it, stuck, now) }
            }
        }

        ListSection("Candidate decisions · newest first") {
            when (val state = candidates.state) {
                MemeRead.Loading -> Skeleton("Loading decisions…")
                is MemeRead.Failed -> FailedLine("decisions", state.failure) { scope.launch { candidates.load() } }
                is MemeRead.Ok -> {
                    if (state.data.isEmpty()) EmptyState("No decisions recorded yet. A decision is recorded when a token's verdict changes.", compact = true)
                    state.data.forEachIndexed { i, c ->
                        if (i > 0) ListDivider()
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = JvSpace.lg, vertical = JvSpace.md), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                                Mono(shortMint(c.mint), "Token ${c.mint}")
                                StatusBadge(if (c.accepted) "Accepted" else "Rejected", tone = if (c.accepted) Tone.OK else Tone.IDLE)
                                MutedText(asOfText(c.observedAt, now))
                            }
                            Text(if (c.accepted) "Passed every filter" else reasonLabel(REJECT_REASONS, c.reason), style = MaterialTheme.typography.bodyMedium)
                            MutedText("Price ${formatPrice(c.priceUsd, "USD")} · liquidity ${usd(c.liquidityUsd)}")
                        }
                    }
                }
            }
        }

        ListSection("Recent paper trades · closed, newest first") {
            when (val state = trades.state) {
                MemeRead.Loading -> Skeleton("Loading paper trades…")
                is MemeRead.Failed -> FailedLine("paper trades", state.failure) { scope.launch { trades.load() } }
                is MemeRead.Ok -> {
                    if (state.data.isEmpty()) EmptyState("No closed paper trades yet.", compact = true)
                    state.data.forEachIndexed { i, t ->
                        if (i > 0) ListDivider()
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = JvSpace.lg, vertical = JvSpace.md), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                                Mono(shortMint(t.mint), "Token ${t.mint}")
                                Text(usd(t.netPnlUsd, sign = true), Modifier.weight(1f), color = signColor(t.netPnlUsd), style = MaterialTheme.typography.bodyMedium.tabular())
                                MutedText(asOfText(t.closedAt, now))
                            }
                            MutedText("Cost ${usd(t.costUsd)} · out ${usd(t.usdOut)} · exit fees ${usd(t.exitFeesUsd)}")
                            Text(reasonLabel(EXIT_REASONS, t.exitReason), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSections(report: MemeReport, stuck: Int, now: Long) {
    MutedText("Strategy version ${report.strategyVersion} · as of ${asOfText(report.asOf, now)} · all amounts in simulated US dollars")
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Kpi("Paper equity", usd(report.equityUsd), "simulated stake ${usd(report.stakeUsd)}")
            Kpi("Net result", usd(report.pnl.netUsd, sign = true), "after fees, failed attempts and AI cost", signColor(report.pnl.netUsd))
        }
        Row {
            Kpi(
                "Open paper positions", "${report.openPositions.size}", if (stuck > 0) "$stuck stuck" else "none stuck",
                if (stuck > 0) JvTheme.colors.negativeText else JvTheme.colors.textPrimary,
            )
        }
    }

    ListSection("Goal") {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            val goal = report.goal
            StatusBadge("${formatDecimal(goal.multipleTarget, maxPlaces = 2)}× the stake")
            if (goal.multiple != null) {
                AmountRow("Now", "${formatDecimal(goal.multiple, places = 2)}×")
                AmountRow(
                    "On the way to ${formatDecimal(goal.multipleTarget, maxPlaces = 2)}×",
                    if (goal.reached == true) "Reached on paper" else "${usd(goal.remainingUsd)} to go",
                )
                Meter(goalPct(goal.fractionToGoal) ?: 0.0)
                MutedText("Progress only. Reaching it on paper triggers nothing.")
            } else {
                MutedText("No goal progress to show yet.")
            }
        }
    }

    ListSection("Result, net of every cost") {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            val p = report.pnl
            AmountRow("Realised", usd(p.realisedUsd, sign = true), signColor(p.realisedUsd))
            AmountRow("Unrealised", usd(p.unrealisedUsd, sign = true), signColor(p.unrealisedUsd))
            AmountRow("Failed-attempt fees", usd(p.failedTxFeesUsd))
            AmountRow("AI cost", usd(p.aiCostUsd))
            ListDivider()
            AmountRow("Net", usd(p.netUsd, sign = true), signColor(p.netUsd))
            MutedText("Fees inside each trade are already in realised and unrealised.")
        }
    }

    ListSection("Open paper positions · ${if (report.openPositions.isEmpty()) "Nothing open" else "${report.openPositions.size} open"}") {
        if (report.openPositions.isEmpty()) EmptyState("No open paper positions.", compact = true)
        report.openPositions.forEachIndexed { i, p ->
            if (i > 0) ListDivider()
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = JvSpace.lg, vertical = JvSpace.md), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    Mono(shortMint(p.mint), "Token ${p.mint}")
                    if (p.strategyVersion != report.strategyVersion) MutedText("strategy v${p.strategyVersion}")
                    when {
                        p.stuck -> StatusBadge("Stuck", tone = Tone.WARN)
                        p.exitFailures > 0 -> MutedText(plural(p.exitFailures, "failed exit", "failed exits"))
                        else -> MutedText("Running")
                    }
                }
                MutedText("Opened ${asOfText(p.openedAt, now)} · ${formatDecimal(p.tokens, maxPlaces = 6)} tokens")
                MutedText("Cost ${usd(p.costUsd)} · value now ${p.valueUsd?.let { usd(it) } ?: "No fresh price"}")
            }
        }
    }
}

// ---- strategies and Claude's decisions ---------------------------------------

private fun changedBy(by: String) = when (by) {
    "seed" -> "Seed"
    "claude" -> "Claude"
    else -> "You"
}

/** The paper strategies side by side and Claude's decision log. Read-only: disabling happens
 *  on a strategy's own page. */
@Composable
private fun StrategiesPage(actions: JarvisViewModel, left: Unconfirmed, back: () -> Unit, open: (String) -> Unit) {
    val strategies = rememberMeme(actions) { e, t -> memeStrategies(e, t) }
    val decisions = rememberMeme(actions) { e, t -> memeDecisions(e, t) }
    val overview = rememberMeme(actions) { e, t -> memeOverview(e, t) }
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    val listed = (strategies.state as? MemeRead.Ok)?.data.orEmpty()

    fun refresh() {
        left.set(null)
        scope.launch { strategies.load() }
        scope.launch { decisions.load() }
        scope.launch { overview.load() }
    }

    PushedPage(
        "Strategies", "Memecoin paper strategies and Claude's decisions. A simulation: no wallet, no orders.", back,
        trailing = { GhostButton("Refresh", ::refresh) },
    ) {
        StatusBadge("Paper")
        left.note?.let { Banner(it, warn = true) }
        Banner(NO_EDGE_TEXT)
        ListSection("Strategies · results over all versions, net of every cost") {
            when (val state = strategies.state) {
                MemeRead.Loading -> Skeleton("Loading strategies…")
                is MemeRead.Failed -> FailedLine("strategies", state.failure) { scope.launch { strategies.load() } }
                is MemeRead.Ok -> {
                    if (state.data.isEmpty()) EmptyState("No strategies yet. Core records the seed strategies when the paper trader starts.", compact = true)
                    state.data.forEachIndexed { i, s ->
                        if (i > 0) ListDivider()
                        val (label, stateTone) = strategyState(s)
                        val p = s.performance
                        Column(
                            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open strategy") { open(s.id) }
                                .padding(horizontal = JvSpace.lg, vertical = JvSpace.md),
                            verticalArrangement = Arrangement.spacedBy(JvSpace.xxs),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Mono(s.id)
                                    MutedText("version ${s.activeVersion} · ${s.family?.let { FAMILY_LABEL[it] } ?: "Unknown"}")
                                }
                                StatusBadge(label, tone = tone(stateTone))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
                                Text("Net ${usd(p.netUsd, sign = true)}", color = signColor(p.netUsd), style = MaterialTheme.typography.bodyMedium.tabular())
                                Text(
                                    "per trade ${if (p.trades > 0) usd(p.expectancyUsd, sign = true) else "—"}",
                                    color = signColor(p.expectancyUsd), style = MaterialTheme.typography.bodyMedium.tabular(),
                                )
                            }
                            MutedText(
                                "${formatDecimal(p.trades.toString())} trades · win rate ${if (p.trades > 0) bpsPct(p.winRateBps) else "—"} · " +
                                    "max drawdown ${usd(p.maxDrawdownUsd)} · ${changedBy(s.changedBy)} · ${asOfText(s.changedAt, now)}",
                            )
                        }
                    }
                }
            }
        }
        MutedText("Open a strategy for its versions, Claude's reasons, the backtest and its paper gate.")

        ListSection("Claude's decisions") {
            (overview.state as? MemeRead.Ok)?.data?.let { o ->
                val agent = agentStatus(o, now)
                Column(Modifier.padding(start = JvSpace.lg, end = JvSpace.lg, top = JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.xs)) {
                    StatusBadge(agent.label + (agent.until?.let { " until ${asOfText(it, now)}" } ?: ""), tone = tone(agent.tone))
                    MutedText(agent.detail)
                }
            }
            when (val state = decisions.state) {
                MemeRead.Loading -> Skeleton("Loading decisions…")
                is MemeRead.Failed -> FailedLine("decisions", state.failure) { scope.launch { decisions.load() } }
                is MemeRead.Ok -> {
                    if (state.data.isEmpty()) EmptyState("No decisions yet. Claude decides only when the paper profile turns it on.", compact = true)
                    state.data.forEach { d ->
                        ListDivider()
                        val view = decisionView(d)
                        val muted = if (view.muted) JvTheme.colors.textSecondary else JvTheme.colors.textPrimary
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = JvSpace.lg, vertical = JvSpace.md), verticalArrangement = Arrangement.spacedBy(JvSpace.xxs)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                                Text(view.kind, Modifier.weight(1f), color = muted, style = MaterialTheme.typography.titleSmall)
                                MutedText(asOfText(d.at, now))
                            }
                            val strategy = d.strategyVersion?.let { v -> listOfNotNull(versionOwner(listed, v), "version $v").joinToString(" ") }
                            MutedText(
                                listOfNotNull(
                                    strategy, d.mint?.let { "token ${shortMint(it)}" }, d.sizeBps?.let { "size ${bpsPct(it)}" },
                                    view.reason.ifEmpty { null }, d.model,
                                ).joinToString(" · ").ifEmpty { "—" },
                            )
                            d.rationale?.let { Text("Claude's note (AI-written): $it", color = muted, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
        MutedText(
            "\"Claude called\" marks that a call started; it counts toward the call budget. Size is the share of what the strategy would enter. " +
                "Notes are Claude's own words, shown as written and never checked by Core; Core checks every action again before it happens.",
        )
    }
}

/** One paper strategy: results, gate, version timeline with Core's diff, Claude's rationale
 *  (AI-written, plain text) and the backtest. Disable is immediate and confirmed first; enable
 *  needs desktop's signer, so it is not offered here. */
@Composable
private fun StrategyPage(id: String, actions: JarvisViewModel, left: Unconfirmed, back: () -> Unit) {
    val loader = rememberMeme(actions) { e, t -> memeStrategy(e, t, id) }
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    var step by remember { mutableStateOf(Step.IDLE) }
    var note by remember { mutableStateOf<WriteOutcome?>(null) }
    val detail = (loader.state as? MemeRead.Ok)?.data
    val s = detail?.strategy

    fun confirmDisable() {
        if (step != Step.CONFIRM) return
        step = Step.SENDING
        scope.launch {
            guardedWrite(left, { step == Step.SENDING }) {
                val result = actions.jobs { e, t -> memeDisable(e, t, id) }
                note = if (result == null) unconfirmedOutcome(clock(System.currentTimeMillis())) else disableOutcome(id, result, clock(System.currentTimeMillis()))
                step = Step.IDLE
                loader.load()
            }
        }
    }

    PushedPage(
        id, s?.family?.let { "Family ${FAMILY_LABEL[it]} · paper simulation" } ?: "Paper simulation", back,
        trailing = { GhostButton("Refresh", { left.set(null); scope.launch { loader.load() } }) },
    ) {
        left.note?.let { Banner(it, warn = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            s?.let { val (label, t) = strategyState(it); StatusBadge(label, tone = tone(t)) }
            StatusBadge("Paper")
        }
        when {
            s?.enabled == true -> GhostButton("Disable", { note = null; step = Step.CONFIRM }, enabled = step != Step.SENDING)
            s != null -> MutedText(ENABLE_ELSEWHERE)
        }
        Banner(NO_EDGE_TEXT)
        if (step != Step.IDLE) {
            ConfirmCard(
                "Disable $id?",
                listOf(
                    "It stops opening new paper positions at once.",
                    "Its open paper positions keep exiting by their own rules.",
                    "It stays disabled until you enable it again with a signature from your Mac or phone. Claude cannot enable it.",
                ),
                "Keep enabled", "Disable now", "Disabling…", step, { if (step != Step.SENDING) step = Step.IDLE }, ::confirmDisable,
            )
        }
        note?.let { Banner(if (it.ok) "Disabled at ${it.at}. No new paper entries; open positions keep exiting." else it.message.orEmpty(), warn = !it.ok) }

        when (val state = loader.state) {
            MemeRead.Loading -> Skeleton("Loading the strategy…")
            is MemeRead.Failed -> Unavailable(
                "This strategy", JvIcon.ALERT, kind = UnavailableKind.ERROR,
                detail = if (state.failure == MemeFailure.NO_API) {
                    "Core has no strategy with this id, or this Core has no strategies yet. Update Core on your Home Node."
                } else {
                    memeFailureText(state.failure)
                },
                onRetry = { scope.launch { loader.load() } },
            )
            is MemeRead.Ok -> StrategyDetails(state.data, now)
        }
    }
}

@Composable
private fun StrategyDetails(detail: StrategyDetail, now: Long) {
    val s = detail.strategy
    val p = s.performance
    Column(verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
            Kpi("Net result", usd(p.netUsd, sign = true), "${formatDecimal(p.trades.toString())} closed trades · costs ${usd(p.costsUsd)}", signColor(p.netUsd))
            Kpi(
                "Per trade", if (p.trades > 0) usd(p.expectancyUsd, sign = true) else "—",
                "win rate ${if (p.trades > 0) bpsPct(p.winRateBps) else "—"} · profit factor ${p.profitFactor?.let { formatDecimal(it, places = 2) } ?: "—"}",
                signColor(p.expectancyUsd),
            )
        }
        Row { Kpi("Max drawdown", usd(p.maxDrawdownUsd), "of the cumulative net result") }
    }

    ListSection("Paper gate, version ${s.activeVersion}") {
        Column(Modifier.padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.md)) {
            val g = detail.gate
            if (g == null) {
                MutedText("No gate snapshot for this version yet. Core writes one every hour.")
            } else {
                MutedText("snapshot ${asOfText(g.at, now)}")
                GateMeters(
                    listOf(Triple("Closed trades", g.closedTrades, g.minClosedTrades), Triple("Days", g.durationDays, g.minDurationDays), Triple("Trading days", g.tradingDays, g.minTradingDays)),
                    null, g.netExpectancyUsd,
                )
                MutedText("A report only: meeting it changes nothing, and this app cannot trade.")
            }
        }
    }

    ListSection("Versions · newest first, up to 20") {
        if (detail.versions.isEmpty()) EmptyState("No versions listed.", compact = true)
        detail.versions.forEachIndexed { i, v ->
            if (i > 0) ListDivider()
            VersionBlock(v, v.version == s.activeVersion, now)
        }
    }
    MutedText("Changes are computed by Core. A backtest replays stored observations of recently migrated tokens; it is a sanity check, not a forecast.")
}

@Composable
private fun VersionBlock(v: StrategyVersion, active: Boolean, now: Long) {
    var params by rememberSaveable(v.version) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(JvSpace.lg), verticalArrangement = Arrangement.spacedBy(JvSpace.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(JvSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            Text("Version ${v.version}", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            StatusBadge(if (v.byClaude) "Claude" else "Seed")
            if (active) StatusBadge("Active", tone = Tone.OK)
        }
        MutedText(asOfText(v.createdAt, now) + (v.parentVersion?.let { " · from version $it" } ?: ""))
        val diff = v.diff
        when {
            diff == null -> MutedText(if (v.parentVersion == null) "First version: nothing to compare." else "Core could not compare this version with its parent.")
            diff.isEmpty() -> MutedText("No parameter changed.")
            else -> diff.forEach { d ->
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Mono(d.field)
                    MutedText("Was ${d.from}")
                    Text("Now ${d.to}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
        v.rationale?.let {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                MutedText("Claude's reasoning · AI-written text, shown as written; display only")
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val b = v.backtest
        Text(
            "Backtest: " + when {
                b == null -> "none recorded."
                b.status == "ok" -> "${formatDecimal(b.trades.toString())} trades on ${b.mints} tokens · net ${usd(b.netUsd, sign = true)} · " +
                    "per trade ${usd(b.expectancyUsd, sign = true)} · win rate ${bpsPct(b.winRateBps ?: 0)} · max drawdown ${usd(b.maxDrawdownUsd)}"
                b.status == "unbacktested" -> "unbacktested: too little stored data (${b.mints} tokens, ${formatDecimal(b.points.toString())} points)."
                else -> "failed to run (the Core log says why)."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        v.config?.let { config ->
            GhostButton(if (params) "Hide parameters" else "Parameters", { params = !params })
            if (params) config.forEach { (field, value) -> AmountRow(field, value) }
        }
    }
}
