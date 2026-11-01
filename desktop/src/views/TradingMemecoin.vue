<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from "vue";
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import "../components/trading/trading.css";
import { ageText, asOfText, formatDecimal, formatPrice, signTone } from "../trading";
import {
  EXIT_REASONS, REJECT_REASONS, RESUME_TEXT, failureText, goalPct, memeStatus, progressPct, readCandidates, readOverview,
  readTrades, reasonLabel, reportAge, sendPause, shortMint, stuckCount, usd, type PauseOutcome,
} from "../memecoin";
import { memeApi, useMemeRead } from "../memecoinApi";
import { MEME_DATA_SETS, devDataSet } from "../tradingApi";

// Memecoin pot: Core's PAPER trader, read-only apart from the pause. It is a
// simulation with no wallet; nothing here may suggest real money or live
// trading. Off by default, so "Off" is the state most owners see first.
const { state: overview, reload: reloadOverview } = useMemeRead({
  live: memeApi.overview, fixture: (f, set) => f.overviewFixture(set), read: readOverview,
});
const { state: candidates, reload: reloadCandidates } = useMemeRead({
  live: memeApi.candidates, fixture: (f, set) => f.candidatesFixture(set), read: readCandidates,
});
const { state: trades, reload: reloadTrades } = useMemeRead({
  live: memeApi.trades, fixture: (f, set) => f.tradesFixture(set), read: readTrades,
});

// Set only once Core confirmed a pause, until the next deliberate refresh.
const pausedHere = ref(false);
const data = computed(() => {
  if (overview.value.kind !== "ok") return null;
  const o = overview.value.data;
  return pausedHere.value && !o.paused ? { ...o, paused: true } : o;
});
const status = computed(() => (data.value ? memeStatus(data.value) : null));
const report = computed(() => data.value?.report ?? null);
const stuck = computed(() => (data.value ? stuckCount(data.value) : 0));

// Re-evaluate staleness while the page is open.
const now = ref(Date.now());
const timer = window.setInterval(() => (now.value = Date.now()), 30_000);
onUnmounted(() => window.clearInterval(timer));
const staleMs = computed(() => (data.value ? reportAge(data.value, now.value) : null));

function refresh(): void {
  pausedHere.value = false;
  void reloadOverview();
  void reloadCandidates();
  void reloadTrades();
}

// ---- pause: confirm, send, and only then show it as paused ----------------
const pauseStep = ref<"idle" | "confirm" | "sending">("idle");
const pauseNote = ref<PauseOutcome | null>(null);
const pauseBtn = ref<HTMLButtonElement | null>(null);
const keepBtn = ref<HTMLButtonElement | null>(null);
const noteEl = ref<HTMLElement | null>(null);
const canPause = computed(() => !!data.value && data.value.enabled && !data.value.paused);

async function askPause(): Promise<void> {
  pauseNote.value = null;
  pauseStep.value = "confirm";
  await nextTick();
  keepBtn.value?.focus();
}

async function keepRunning(): Promise<void> {
  if (pauseStep.value === "sending") return;
  pauseStep.value = "idle";
  await nextTick();
  pauseBtn.value?.focus();
}

async function confirmPause(): Promise<void> {
  if (pauseStep.value !== "confirm") return;
  pauseStep.value = "sending";
  const outcome = await sendPause(memeApi.pause);
  pauseNote.value = outcome;
  pauseStep.value = "idle";
  if (outcome.ok) {
    pausedHere.value = true;
    void reloadOverview();
  }
  await nextTick();
  noteEl.value?.focus();
}

watch(devDataSet, () => {
  pausedHere.value = false;
  pauseNote.value = null;
  pauseStep.value = "idle";
});

const plural = (n: number, one: string, many: string) => `${formatDecimal(String(n))} ${n === 1 ? one : many}`;
</script>

<template>
  <div class="td-page">
    <JvBackdrop glow-y="20%" horizon="80px" />
    <JvTopBar variant="node" title="MEMECOIN" subtitle="PAPER · NO REAL MONEY" />
    <section class="td-wrap">
      <header class="td-head">
        <div>
          <p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink></p>
          <div class="mc-title">
            <h1>Memecoin</h1>
            <span class="td-badge outline">Paper</span>
            <span v-if="status" class="td-badge" :class="status.tone">{{ status.label }}</span>
          </div>
          <p class="td-sub">Solana · a simulation on read-only market data. It has no wallet and cannot buy or sell anything.</p>
        </div>
        <div class="mc-actions">
          <button type="button" class="ghost-btn sm" @click="refresh">Refresh</button>
          <button
            v-if="canPause" ref="pauseBtn" type="button" class="ghost-btn sm"
            :aria-expanded="pauseStep !== 'idle'" aria-controls="mc-pause" :disabled="pauseStep === 'sending'" @click="askPause"
          >Pause paper entries</button>
        </div>
      </header>

      <p class="mc-paper" role="note">
        <strong>Paper — no real money.</strong>
        Every amount here is simulated: Core models fills, fees and failed attempts on public market data. No order is placed and no wallet exists.
      </p>

      <section
        v-if="pauseStep !== 'idle'" id="mc-pause" class="td-card mc-confirm" role="group" aria-labelledby="mc-pause-title"
        @keydown.esc="keepRunning"
      >
        <h2 id="mc-pause-title">Pause paper entries?</h2>
        <ul class="mc-list">
          <li>The trader stops opening new paper positions at once.</li>
          <li>Exits keep running: open paper positions are still valued and can still close.</li>
          <li>Core saves the pause and records it in its security audit log.</li>
          <li>There is no resume here. Resuming is a configuration change on the Home Node.</li>
        </ul>
        <div class="mc-actions">
          <button ref="keepBtn" type="button" class="ghost-btn sm" :disabled="pauseStep === 'sending'" @click="keepRunning">Keep running</button>
          <button type="button" class="ghost-btn sm mc-go" :disabled="pauseStep === 'sending'" @click="confirmPause">
            {{ pauseStep === "sending" ? "Pausing…" : "Pause entries now" }}
          </button>
        </div>
      </section>

      <p v-if="pauseNote" ref="noteEl" tabindex="-1" class="mc-note" :class="{ neg: !pauseNote.ok }" :role="pauseNote.ok ? 'status' : 'alert'">
        <template v-if="pauseNote.ok">Paused at {{ pauseNote.at }}. New paper entries stopped; exits keep running.</template>
        <template v-else>{{ pauseNote.message }}</template>
      </p>

      <div v-if="overview.kind === 'loading'" class="mc-state" role="status"><span class="mc-dot" aria-hidden="true"></span>Loading the paper trader…</div>
      <div v-else-if="overview.kind === 'failed'" class="mc-state" role="alert">
        <span>Could not load the paper trader. {{ failureText(overview.failure) }}</span>
        <button type="button" class="ghost-btn sm" @click="reloadOverview">Try again</button>
      </div>
      <template v-else-if="data && status">
        <section class="td-card mc-status" :class="status.tone" aria-labelledby="mc-status-title">
          <h2 id="mc-status-title">{{ status.label }}</h2>
          <p class="td-note">{{ status.detail }}</p>
          <template v-if="status.kind === 'disabled'">
            <p class="td-note">To try it, the owner sets JARVIS_MEMECOIN_PAPER_ENABLED=true in Core's configuration and restarts Core (see MEMECOIN_PAPER.md in the Core repository). It stays a paper simulation.</p>
          </template>
          <template v-if="data.paused">
            <p class="td-note">
              Paused since
              <time v-if="data.paused_at" :datetime="data.paused_at">{{ asOfText(data.paused_at, now) }} <span class="td-mono">({{ data.paused_at }})</span></time>
              <template v-else>an unknown time</template>.
            </p>
            <p class="td-note">{{ RESUME_TEXT }}</p>
          </template>
        </section>

        <div v-if="staleMs !== null" class="mc-banner" role="status">
          <strong>The last report is {{ ageText(staleMs) }} old.</strong> Core has not reported since; these figures may be out of date.
        </div>
        <div v-if="stuck" class="mc-banner" role="status">
          <strong>{{ plural(stuck, "paper position is", "paper positions are") }} stuck.</strong>
          {{ stuck === 1 ? "Its exit failed 10 times and is no longer retried; it keeps its slot." : "Their exits failed 10 times each and are no longer retried; they keep their slots." }}
          The Core log has the details.
        </div>

        <template v-if="report">
          <p class="td-source">Strategy version {{ report.strategy_version }} · as of {{ asOfText(report.as_of, now) }} · all amounts in simulated US dollars</p>
          <div class="td-kpis">
            <div class="td-card td-kpi">
              <span class="td-label">Paper equity</span>
              <span class="td-big">{{ usd(report.equity_usd) }}</span>
              <span class="td-small">simulated stake {{ usd(report.stake_usd) }}</span>
            </div>
            <div class="td-card td-kpi">
              <span class="td-label">Net result</span>
              <span class="td-big" :class="`td-${signTone(report.pnl.net_usd)}`">{{ usd(report.pnl.net_usd, { sign: true }) }}</span>
              <span class="td-small">after fees, failed attempts and AI cost</span>
            </div>
            <div class="td-card td-kpi">
              <span class="td-label">Open paper positions</span>
              <span class="td-big">{{ report.open_positions.length }}</span>
              <span class="td-small" :class="{ 'td-neg': stuck }">{{ stuck ? `${stuck} stuck` : "none stuck" }}</span>
            </div>
          </div>

          <div class="td-grid">
            <section class="td-card" aria-labelledby="mc-pnl">
              <h2 id="mc-pnl">Result, net of every cost</h2>
              <dl class="mc-dl">
                <div><dt>Realised</dt><dd :class="`td-${signTone(report.pnl.realised_usd)}`">{{ usd(report.pnl.realised_usd, { sign: true }) }}</dd></div>
                <div><dt>Unrealised</dt><dd :class="`td-${signTone(report.pnl.unrealised_usd)}`">{{ usd(report.pnl.unrealised_usd, { sign: true }) }}</dd></div>
                <div><dt>Failed-attempt fees</dt><dd>{{ usd(report.pnl.failed_tx_fees_usd) }}</dd></div>
                <div><dt>AI cost</dt><dd>{{ usd(report.pnl.ai_cost_usd) }}</dd></div>
                <div class="total"><dt>Net</dt><dd :class="`td-${signTone(report.pnl.net_usd)}`">{{ usd(report.pnl.net_usd, { sign: true }) }}</dd></div>
              </dl>
              <p class="td-note">Fees inside each trade are already in realised and unrealised.</p>
            </section>

            <section class="td-card" aria-labelledby="mc-gate">
              <div class="td-card-head">
                <h2 id="mc-gate">Paper gate</h2>
                <span class="td-badge" :class="{ acc: report.gate.passed }">{{ report.gate.passed ? "Met" : "Not met" }}</span>
              </div>
              <p class="td-note">What the strategy has to show on paper, after every cost. This is a report only: meeting it changes nothing, and this app cannot trade.</p>
              <div v-for="row in [
                { label: 'Closed trades', have: report.gate.closed_trades, need: report.gate.min_closed_trades, ok: report.gate.enough_trades },
                { label: 'Days', have: report.gate.duration_days, need: report.gate.min_duration_days, ok: report.gate.enough_time },
                { label: 'Trading days', have: report.gate.trading_days, need: report.gate.min_trading_days, ok: report.gate.enough_days },
              ]" :key="row.label" class="mc-meter">
                <div class="td-card-head td-small">
                  <span>{{ row.label }}</span>
                  <span class="td-num">{{ formatDecimal(String(row.have)) }} of {{ formatDecimal(String(row.need)) }}{{ row.ok ? " · met" : "" }}</span>
                </div>
                <div class="td-meter" aria-hidden="true"><i :style="{ width: `${progressPct(row.have, row.need)}%` }"></i></div>
              </div>
              <div class="td-card-head td-small">
                <span>Net per closed trade</span>
                <span class="td-num" :class="`td-${signTone(report.gate.net_expectancy_usd)}`">
                  {{ usd(report.gate.net_expectancy_usd, { sign: true }) }} · {{ report.gate.positive_expectancy ? "positive" : "not positive" }}
                </span>
              </div>
            </section>

            <section class="td-card" aria-labelledby="mc-goal">
              <div class="td-card-head">
                <h2 id="mc-goal">Goal</h2>
                <span class="td-badge">{{ formatDecimal(report.goal.multiple_target, { maxPlaces: 2 }) }}× the stake</span>
              </div>
              <template v-if="report.goal.multiple !== null">
                <div class="td-card-head">
                  <span class="td-big">{{ formatDecimal(report.goal.multiple, { places: 2 }) }}×</span>
                  <span class="td-small">{{ report.goal.reached ? "Reached on paper" : `${usd(report.goal.remaining_usd)} to go` }}</span>
                </div>
                <div class="td-meter" aria-hidden="true"><i :style="{ width: `${goalPct(report.goal.fraction_to_goal) ?? 0}%` }"></i></div>
                <p class="td-note">Progress only. Reaching it on paper triggers nothing.</p>
              </template>
              <p v-else class="td-note">No goal progress to show yet.</p>
            </section>
          </div>

          <section class="td-card tight" aria-labelledby="mc-open">
            <div class="td-card-head">
              <h2 id="mc-open">Open paper positions</h2>
              <span class="td-small">{{ report.open_positions.length ? `${report.open_positions.length} open` : "Nothing open" }}</span>
            </div>
            <p v-if="!report.open_positions.length" class="td-empty">No open paper positions.</p>
            <div v-else class="td-scroll">
              <table class="td-table">
                <thead>
                  <tr><th scope="col">Token</th><th scope="col">Opened</th><th scope="col" class="r">Tokens</th><th scope="col" class="r">Cost</th><th scope="col" class="r">Value now</th><th scope="col">Exit</th></tr>
                </thead>
                <tbody>
                  <tr v-for="(p, i) in report.open_positions" :key="`${p.mint}-${i}`">
                    <td><span class="td-mono" :title="p.mint">{{ shortMint(p.mint) }}</span><span v-if="p.strategy_version !== report.strategy_version" class="td-small mc-sub">strategy v{{ p.strategy_version }}</span></td>
                    <td><time :datetime="p.opened_at">{{ asOfText(p.opened_at, now) }}</time></td>
                    <td class="r">{{ formatDecimal(p.tokens, { maxPlaces: 6 }) }}</td>
                    <td class="r">{{ usd(p.cost_usd) }}</td>
                    <td class="r">{{ p.value_usd === null ? "No fresh price" : usd(p.value_usd) }}</td>
                    <td>
                      <span v-if="p.stuck" class="td-badge neg">Stuck</span>
                      <span v-else-if="p.exit_failures" class="td-small">{{ plural(p.exit_failures, "failed exit", "failed exits") }}</span>
                      <span v-else class="td-small">Running</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </template>
      </template>

      <section class="td-card tight" aria-labelledby="mc-cand">
        <div class="td-card-head">
          <h2 id="mc-cand">Candidate decisions</h2>
          <span class="td-small">newest first</span>
        </div>
        <div v-if="candidates.kind === 'loading'" class="mc-inline" role="status">Loading decisions…</div>
        <div v-else-if="candidates.kind === 'failed'" class="mc-inline" role="alert">
          <span>Could not load decisions. {{ failureText(candidates.failure) }}</span>
          <button type="button" class="ghost-btn sm" @click="reloadCandidates">Try again</button>
        </div>
        <p v-else-if="!candidates.data.length" class="td-empty">No decisions recorded yet. A decision is recorded when a token's verdict changes.</p>
        <div v-else class="td-scroll">
          <table class="td-table">
            <thead>
              <tr><th scope="col">Seen</th><th scope="col">Token</th><th scope="col">Decision</th><th scope="col">Reason</th><th scope="col" class="r">Price</th><th scope="col" class="r">Liquidity</th></tr>
            </thead>
            <tbody>
              <tr v-for="(c, i) in candidates.data" :key="`${c.mint}-${c.observed_at}-${i}`">
                <td><time :datetime="c.observed_at">{{ asOfText(c.observed_at, now) }}</time></td>
                <td><span class="td-mono" :title="c.mint">{{ shortMint(c.mint) }}</span></td>
                <td><span class="td-badge" :class="{ acc: c.verdict === 'accept' }">{{ c.verdict === "accept" ? "Accepted" : "Rejected" }}</span></td>
                <td class="mc-reason">{{ c.verdict === "accept" ? "Passed every filter" : reasonLabel(REJECT_REASONS, c.reason) }}</td>
                <td class="r">{{ formatPrice(c.price_usd, "USD") }}</td>
                <td class="r">{{ usd(c.liquidity_usd) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="td-card tight" aria-labelledby="mc-trades">
        <div class="td-card-head">
          <h2 id="mc-trades">Recent paper trades</h2>
          <span class="td-small">closed, newest first</span>
        </div>
        <div v-if="trades.kind === 'loading'" class="mc-inline" role="status">Loading paper trades…</div>
        <div v-else-if="trades.kind === 'failed'" class="mc-inline" role="alert">
          <span>Could not load paper trades. {{ failureText(trades.failure) }}</span>
          <button type="button" class="ghost-btn sm" @click="reloadTrades">Try again</button>
        </div>
        <p v-else-if="!trades.data.length" class="td-empty">No closed paper trades yet.</p>
        <div v-else class="td-scroll">
          <table class="td-table">
            <thead>
              <tr><th scope="col">Closed</th><th scope="col">Token</th><th scope="col" class="r">Cost</th><th scope="col" class="r">Out</th><th scope="col" class="r">Exit fees</th><th scope="col" class="r">Net</th><th scope="col">Exit</th></tr>
            </thead>
            <tbody>
              <tr v-for="(t, i) in trades.data" :key="`${t.mint}-${t.closed_at}-${i}`">
                <td><time :datetime="t.closed_at">{{ asOfText(t.closed_at, now) }}</time></td>
                <td><span class="td-mono" :title="t.mint">{{ shortMint(t.mint) }}</span></td>
                <td class="r">{{ usd(t.cost_usd) }}</td>
                <td class="r">{{ usd(t.usd_out) }}</td>
                <td class="r">{{ usd(t.exit_fees_usd) }}</td>
                <td class="r" :class="`td-${signTone(t.net_pnl_usd)}`">{{ usd(t.net_pnl_usd, { sign: true }) }}</td>
                <td class="mc-reason">{{ reasonLabel(EXIT_REASONS, t.exit_reason) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </section>
    <DevDataToggle :sets="MEME_DATA_SETS" />
  </div>
</template>

<style scoped>
.mc-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.mc-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.mc-paper {
  margin: 0; padding: 12px 16px; border-radius: var(--r-12); background: var(--accent-subtle); color: var(--text-primary);
  font-size: var(--fs-13); line-height: 1.55;
}
.mc-paper strong { color: var(--accent-text); font-weight: 600; }
.mc-confirm { box-shadow: 0 0 0 2px var(--focus-ring); }
.mc-list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 4px; font-size: var(--fs-13); line-height: 1.55; color: var(--text-secondary); }
.mc-go { background: var(--accent-solid); border-color: var(--accent-solid); color: var(--text-on-accent); font-weight: 600; }
.mc-note {
  margin: 0; padding: 12px 16px; border-radius: var(--r-12); background: var(--positive-subtle); color: var(--positive-text);
  font-size: var(--fs-13); line-height: 1.55;
}
.mc-note.neg { background: var(--negative-subtle); color: var(--negative-text); }
.mc-state, .mc-inline {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  color: var(--text-secondary); font-size: var(--fs-13);
}
.mc-state { padding: 18px 20px; border: 1px dashed var(--border); border-radius: var(--r-12); background: var(--bg-surface); justify-content: flex-start; }
.mc-state[role="alert"] { justify-content: space-between; }
.mc-inline { padding: 12px 20px 18px; }
.mc-dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); }
@media (prefers-reduced-motion: no-preference) {
  .mc-dot { animation: mc-pulse 1.2s var(--ease-in-out) infinite alternate; }
}
@keyframes mc-pulse { from { opacity: 1; } to { opacity: 0.3; } }
.mc-status { gap: 8px; }
.mc-status.neg { box-shadow: inset 3px 0 0 var(--negative-solid), var(--shadow-border); }
.mc-status.acc { box-shadow: inset 3px 0 0 var(--accent-solid), var(--shadow-border); }
.mc-banner {
  padding: 12px 16px; border-radius: var(--r-12); background: var(--negative-subtle); color: var(--negative-text);
  font-size: var(--fs-13); line-height: 1.55;
}
.mc-banner strong { font-weight: 600; }
.mc-dl { margin: 0; display: flex; flex-direction: column; gap: 6px; font-size: var(--fs-13); }
.mc-dl > div { display: flex; justify-content: space-between; gap: 12px; }
.mc-dl dt { color: var(--text-secondary); }
.mc-dl dd { margin: 0; font-variant-numeric: tabular-nums; text-align: right; overflow-wrap: anywhere; }
.mc-dl .total { padding-top: 6px; border-top: 1px solid var(--separator); font-weight: 600; }
.mc-dl .total dt { color: var(--text-primary); }
.mc-meter { display: flex; flex-direction: column; gap: 6px; }
.mc-sub { display: block; margin-top: 2px; }
.mc-reason { min-width: 160px; overflow-wrap: anywhere; }
</style>
