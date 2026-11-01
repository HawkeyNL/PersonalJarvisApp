<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from "vue";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
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

// Set only once Core confirmed a pause, until the following overview read.
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
  await nextTick();
  noteEl.value?.focus();
  if (outcome.ok) {
    // Bridge until Core's own overview arrives; from then on it is the truth.
    pausedHere.value = true;
    await reloadOverview();
    pausedHere.value = false;
  } else if (outcome.status === 503) {
    // Core paused in memory but did not save it: show what it reports now.
    await reloadOverview();
  }
}

watch(devDataSet, () => {
  pausedHere.value = false;
  pauseNote.value = null;
  pauseStep.value = "idle";
});

const plural = (n: number, one: string, many: string) => `${formatDecimal(String(n))} ${n === 1 ? one : many}`;
</script>

<template>
  <PageShell class="td-page" title="Memecoin" subtitle="Solana · a simulation on read-only market data. It has no wallet and cannot buy or sell anything.">
    <template #crumb><p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink></p></template>
    <template #badges>
      <span v-if="status" class="badge" :class="status.tone"><span class="dot" aria-hidden="true"></span>{{ status.label }}</span>
      <span class="badge td-outline">Paper</span>
    </template>
    <template #actions>
      <button type="button" class="btn btn-secondary" @click="refresh">Refresh</button>
      <button
        v-if="canPause" ref="pauseBtn" type="button" class="btn btn-secondary"
        :aria-expanded="pauseStep !== 'idle'" aria-controls="mc-pause" :disabled="pauseStep === 'sending'" @click="askPause"
      >Pause paper entries</button>
    </template>

    <div class="td-banner mc-paper" role="note">
      <div class="mc-paper-text">
        <strong class="mc-paper-title">Paper — no real money.</strong>
        <span class="t-sm">Every amount here is simulated: Core models fills, fees and failed attempts on public market data. No order is placed and no wallet exists.</span>
      </div>
      <section v-if="report" class="mc-gate" aria-labelledby="mc-gate">
        <div class="td-card-head">
          <h2 id="mc-gate" class="t-sm mc-gate-title">Paper gate</h2>
          <span class="badge" :class="report.gate.passed ? 'pos' : ''"><span class="dot" aria-hidden="true"></span>{{ report.gate.passed ? "Met" : "Not met" }}</span>
        </div>
        <div class="mc-meters">
          <div v-for="row in [
            { label: 'Closed trades', have: report.gate.closed_trades, need: report.gate.min_closed_trades, ok: report.gate.enough_trades },
            { label: 'Days', have: report.gate.duration_days, need: report.gate.min_duration_days, ok: report.gate.enough_time },
            { label: 'Trading days', have: report.gate.trading_days, need: report.gate.min_trading_days, ok: report.gate.enough_days },
          ]" :key="row.label" class="mc-meter">
            <div class="td-card-head t-sm">
              <span>{{ row.label }}</span>
              <span class="mono">{{ formatDecimal(String(row.have)) }} of {{ formatDecimal(String(row.need)) }}{{ row.ok ? " · met" : "" }}</span>
            </div>
            <div class="td-meter" aria-hidden="true"><i :style="{ width: `${progressPct(row.have, row.need)}%` }"></i></div>
          </div>
          <div class="mc-meter">
            <div class="td-card-head t-sm">
              <span>Net per closed trade</span>
              <span class="mono" :class="`td-${signTone(report.gate.net_expectancy_usd)}`">{{ usd(report.gate.net_expectancy_usd, { sign: true }) }}</span>
            </div>
            <span class="t-xs t-2">{{ report.gate.positive_expectancy ? "Positive" : "Not positive" }} after every cost</span>
          </div>
        </div>
        <p class="td-note">What the strategy has to show on paper, after every cost. This is a report only: meeting it changes nothing, and this app cannot trade.</p>
      </section>
    </div>

    <section
      v-if="pauseStep !== 'idle'" id="mc-pause" class="card td-col mc-confirm" role="group" aria-labelledby="mc-pause-title"
      @keydown.esc="keepRunning"
    >
      <h2 id="mc-pause-title">Pause paper entries?</h2>
      <ul class="mc-list t-sm t-2">
        <li>The trader stops opening new paper positions at once.</li>
        <li>Exits keep running: open paper positions are still valued and can still close.</li>
        <li>Core saves the pause and records it in its security audit log.</li>
        <li>There is no resume here. Resuming is a configuration change on the Home Node.</li>
      </ul>
      <div class="mc-actions">
        <button ref="keepBtn" type="button" class="btn btn-secondary" :disabled="pauseStep === 'sending'" @click="keepRunning">Keep running</button>
        <button type="button" class="btn btn-primary" :aria-busy="pauseStep === 'sending'" :disabled="pauseStep === 'sending'" @click="confirmPause">
          <span v-if="pauseStep === 'sending'" class="spinner" aria-hidden="true"></span>
          {{ pauseStep === "sending" ? "Pausing…" : "Pause entries now" }}
        </button>
      </div>
    </section>

    <p v-if="pauseNote" ref="noteEl" tabindex="-1" class="td-banner t-sm" :class="{ warn: !pauseNote.ok }" :role="pauseNote.ok ? 'status' : 'alert'">
      <template v-if="pauseNote.ok"><span><strong>Paused at {{ pauseNote.at }}.</strong> New paper entries stopped; exits keep running.</span></template>
      <template v-else><span>{{ pauseNote.message }}</span></template>
    </p>

    <div v-if="overview.kind === 'loading'" class="card td-col" role="status" aria-busy="true">
      <span class="sr-only">Loading the paper trader…</span>
      <span class="skel" style="width: 40%; height: 14px" aria-hidden="true"></span>
      <span class="skel" style="width: 70%; height: 28px" aria-hidden="true"></span>
      <span class="skel" style="width: 92%; height: 14px" aria-hidden="true"></span>
    </div>
    <div v-else-if="overview.kind === 'failed'" class="card td-col" role="alert">
      <div class="td-title">
        <span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span>
        <h3>Could not load the paper trader</h3>
      </div>
      <p class="t-sm t-2">{{ failureText(overview.failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="reloadOverview">Try again</button></div>
    </div>
    <template v-else-if="data && status">
      <section class="card td-col" aria-labelledby="mc-status-title">
        <div class="td-title">
          <span class="tile" :class="status.tone" aria-hidden="true"><NavIcon :name="status.tone === 'neg' ? 'alert' : 'coin'" /></span>
          <h2 id="mc-status-title">{{ status.label }}</h2>
        </div>
        <p class="t-sm t-2">{{ status.detail }}</p>
        <p v-if="status.kind === 'disabled'" class="t-sm t-2">To try it, the owner sets JARVIS_MEMECOIN_PAPER_ENABLED=true in Core's configuration and restarts Core (see MEMECOIN_PAPER.md in the Core repository). It stays a paper simulation.</p>
        <template v-if="data.paused">
          <p class="t-sm t-2">
            Paused since
            <time v-if="data.paused_at" :datetime="data.paused_at">{{ asOfText(data.paused_at, now) }} <span class="mono">({{ data.paused_at }})</span></time>
            <template v-else>an unknown time</template>.
          </p>
          <p class="t-sm t-2">{{ RESUME_TEXT }}</p>
        </template>
      </section>

      <div v-if="staleMs !== null" class="td-banner warn" role="status">
        <span class="t-sm"><strong>The last report is {{ ageText(staleMs) }} old.</strong> Core has not reported since; these figures may be out of date.</span>
        <button type="button" class="btn btn-secondary btn-sm" @click="refresh">Try again</button>
      </div>
      <div v-if="stuck" class="td-banner warn" role="status">
        <span class="t-sm">
          <strong>{{ plural(stuck, "paper position is", "paper positions are") }} stuck.</strong>
          {{ stuck === 1 ? "Its exit failed 10 times and is no longer retried; it keeps its slot." : "Their exits failed 10 times each and are no longer retried; they keep their slots." }}
          The Core log has the details.
        </span>
      </div>

      <template v-if="report">
        <p class="td-note">Strategy version {{ report.strategy_version }} · as of {{ asOfText(report.as_of, now) }} · all amounts in simulated US dollars</p>
        <div class="td-kpis">
          <div class="card td-kpi">
            <span class="t-sm t-2">Paper equity</span>
            <span class="big">{{ usd(report.equity_usd) }}</span>
            <span class="t-xs t-2">simulated stake {{ usd(report.stake_usd) }}</span>
          </div>
          <div class="card td-kpi">
            <span class="t-sm t-2">Net result</span>
            <span class="big" :class="`td-${signTone(report.pnl.net_usd)}`">{{ usd(report.pnl.net_usd, { sign: true }) }}</span>
            <span class="t-xs t-2">after fees, failed attempts and AI cost</span>
          </div>
          <div class="card td-kpi">
            <span class="t-sm t-2">Open paper positions</span>
            <span class="big">{{ report.open_positions.length }}</span>
            <span class="t-xs" :class="stuck ? 'td-neg' : 't-2'">{{ stuck ? `${stuck} stuck` : "none stuck" }}</span>
          </div>
        </div>

        <div class="td-grid">
          <section class="card td-col td-wide mc-goal" aria-labelledby="mc-goal">
            <div class="td-card-head">
              <h2 id="mc-goal">Goal</h2>
              <span class="badge">{{ formatDecimal(report.goal.multiple_target, { maxPlaces: 2 }) }}× the stake</span>
            </div>
            <template v-if="report.goal.multiple !== null">
              <div class="mc-goal-row">
                <div class="mc-now"><span class="t-sm t-2">Now</span><span class="big mc-multiple">{{ formatDecimal(report.goal.multiple, { places: 2 }) }}×</span></div>
                <div class="mc-goal-bar">
                  <div class="td-card-head t-sm">
                    <span>On the way to {{ formatDecimal(report.goal.multiple_target, { maxPlaces: 2 }) }}×</span>
                    <span class="mono t-2">{{ report.goal.reached ? "Reached on paper" : `${usd(report.goal.remaining_usd)} to go` }}</span>
                  </div>
                  <div class="td-meter mc-goal-meter" aria-hidden="true"><i :style="{ width: `${goalPct(report.goal.fraction_to_goal) ?? 0}%` }"></i></div>
                </div>
              </div>
              <p class="td-note">Progress only. Reaching it on paper triggers nothing.</p>
            </template>
            <p v-else class="t-sm t-2">No goal progress to show yet.</p>
          </section>

          <section class="card td-col" aria-labelledby="mc-pnl">
            <h2 id="mc-pnl">Result, net of every cost</h2>
            <dl class="mc-dl t-sm">
              <div><dt>Realised</dt><dd :class="`td-${signTone(report.pnl.realised_usd)}`">{{ usd(report.pnl.realised_usd, { sign: true }) }}</dd></div>
              <div><dt>Unrealised</dt><dd :class="`td-${signTone(report.pnl.unrealised_usd)}`">{{ usd(report.pnl.unrealised_usd, { sign: true }) }}</dd></div>
              <div><dt>Failed-attempt fees</dt><dd>{{ usd(report.pnl.failed_tx_fees_usd) }}</dd></div>
              <div><dt>AI cost</dt><dd>{{ usd(report.pnl.ai_cost_usd) }}</dd></div>
              <div class="total"><dt>Net</dt><dd :class="`td-${signTone(report.pnl.net_usd)}`">{{ usd(report.pnl.net_usd, { sign: true }) }}</dd></div>
            </dl>
            <p class="td-note">Fees inside each trade are already in realised and unrealised.</p>
          </section>
        </div>

        <section class="card td-tight" aria-labelledby="mc-open">
          <div class="td-card-head">
            <h2 id="mc-open">Open paper positions</h2>
            <span class="t-sm t-2">{{ report.open_positions.length ? `${report.open_positions.length} open` : "Nothing open" }}</span>
          </div>
          <p v-if="!report.open_positions.length" class="empty t-sm">No open paper positions.</p>
          <div v-else class="td-scroll" tabindex="0" role="region" aria-labelledby="mc-open">
            <table class="tbl">
              <thead>
                <tr><th scope="col">Token</th><th scope="col">Opened</th><th scope="col" class="r">Tokens</th><th scope="col" class="r">Cost</th><th scope="col" class="r">Value now</th><th scope="col">Exit</th></tr>
              </thead>
              <tbody>
                <tr v-for="(p, i) in report.open_positions" :key="`${p.mint}-${i}`">
                  <td><span class="mono" :title="p.mint">{{ shortMint(p.mint) }}</span><span v-if="p.strategy_version !== report.strategy_version" class="t-xs t-2 mc-sub">strategy v{{ p.strategy_version }}</span></td>
                  <td class="t-sm"><time :datetime="p.opened_at">{{ asOfText(p.opened_at, now) }}</time></td>
                  <td class="r">{{ formatDecimal(p.tokens, { maxPlaces: 6 }) }}</td>
                  <td class="r">{{ usd(p.cost_usd) }}</td>
                  <td class="r">{{ p.value_usd === null ? "No fresh price" : usd(p.value_usd) }}</td>
                  <td>
                    <span v-if="p.stuck" class="badge neg"><span class="dot" aria-hidden="true"></span>Stuck</span>
                    <span v-else-if="p.exit_failures" class="t-sm t-2">{{ plural(p.exit_failures, "failed exit", "failed exits") }}</span>
                    <span v-else class="t-sm t-2">Running</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </template>

    <section class="card td-tight" aria-labelledby="mc-cand">
      <div class="td-card-head">
        <h2 id="mc-cand">Candidate decisions</h2>
        <span class="t-sm t-2">newest first</span>
      </div>
      <div v-if="candidates.kind === 'loading'" class="td-col td-inline" role="status" aria-busy="true">
        <span class="sr-only">Loading decisions…</span>
        <span class="skel" style="width: 100%; height: 14px" aria-hidden="true"></span>
        <span class="skel" style="width: 85%; height: 14px" aria-hidden="true"></span>
      </div>
      <div v-else-if="candidates.kind === 'failed'" class="td-inline mc-failed" role="alert">
        <span class="t-sm">Could not load decisions. {{ failureText(candidates.failure) }}</span>
        <button type="button" class="btn btn-secondary btn-sm" @click="reloadCandidates">Try again</button>
      </div>
      <p v-else-if="!candidates.data.length" class="empty t-sm">No decisions recorded yet. A decision is recorded when a token's verdict changes.</p>
      <div v-else class="td-scroll" tabindex="0" role="region" aria-labelledby="mc-cand">
        <table class="tbl">
          <thead>
            <tr><th scope="col">Seen</th><th scope="col">Token</th><th scope="col">Decision</th><th scope="col">Reason</th><th scope="col" class="r">Price</th><th scope="col" class="r">Liquidity</th></tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in candidates.data" :key="`${c.mint}-${c.observed_at}-${i}`">
              <td class="t-sm"><time :datetime="c.observed_at">{{ asOfText(c.observed_at, now) }}</time></td>
              <td><span class="mono" :title="c.mint">{{ shortMint(c.mint) }}</span></td>
              <td><span class="badge" :class="{ acc: c.verdict === 'accept' }">{{ c.verdict === "accept" ? "Accepted" : "Rejected" }}</span></td>
              <td class="t-sm mc-reason">{{ c.verdict === "accept" ? "Passed every filter" : reasonLabel(REJECT_REASONS, c.reason) }}</td>
              <td class="r">{{ formatPrice(c.price_usd, "USD") }}</td>
              <td class="r">{{ usd(c.liquidity_usd) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card td-tight" aria-labelledby="mc-trades">
      <div class="td-card-head">
        <h2 id="mc-trades">Recent paper trades</h2>
        <span class="t-sm t-2">closed, newest first</span>
      </div>
      <div v-if="trades.kind === 'loading'" class="td-col td-inline" role="status" aria-busy="true">
        <span class="sr-only">Loading paper trades…</span>
        <span class="skel" style="width: 100%; height: 14px" aria-hidden="true"></span>
        <span class="skel" style="width: 85%; height: 14px" aria-hidden="true"></span>
      </div>
      <div v-else-if="trades.kind === 'failed'" class="td-inline mc-failed" role="alert">
        <span class="t-sm">Could not load paper trades. {{ failureText(trades.failure) }}</span>
        <button type="button" class="btn btn-secondary btn-sm" @click="reloadTrades">Try again</button>
      </div>
      <p v-else-if="!trades.data.length" class="empty t-sm">No closed paper trades yet.</p>
      <div v-else class="td-scroll" tabindex="0" role="region" aria-labelledby="mc-trades">
        <table class="tbl">
          <thead>
            <tr><th scope="col">Closed</th><th scope="col">Token</th><th scope="col" class="r">Cost</th><th scope="col" class="r">Out</th><th scope="col" class="r">Exit fees</th><th scope="col" class="r">Net</th><th scope="col">Exit</th></tr>
          </thead>
          <tbody>
            <tr v-for="(t, i) in trades.data" :key="`${t.mint}-${t.closed_at}-${i}`">
              <td class="t-sm"><time :datetime="t.closed_at">{{ asOfText(t.closed_at, now) }}</time></td>
              <td><span class="mono" :title="t.mint">{{ shortMint(t.mint) }}</span></td>
              <td class="r">{{ usd(t.cost_usd) }}</td>
              <td class="r">{{ usd(t.usd_out) }}</td>
              <td class="r">{{ usd(t.exit_fees_usd) }}</td>
              <td class="r" :class="`td-${signTone(t.net_pnl_usd)}`">{{ usd(t.net_pnl_usd, { sign: true }) }}</td>
              <td class="t-sm mc-reason">{{ reasonLabel(EXIT_REASONS, t.exit_reason) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <DevDataToggle :sets="MEME_DATA_SETS" />
  </PageShell>
</template>

<style scoped>
.mc-paper { align-items: flex-start; }
.mc-paper-text { display: flex; flex-direction: column; gap: 2px; flex: 1 1 420px; min-width: 0; }
.mc-paper-title { color: var(--accent-text); }
.mc-gate { flex: 1 1 100%; display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.mc-gate-title { font-weight: 600; }
.mc-meters { display: flex; flex-wrap: wrap; gap: 12px 20px; }
.mc-meter { flex: 1 1 200px; display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.mc-confirm { box-shadow: var(--shadow-border), 0 0 0 2px var(--focus-ring); }
.mc-list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 4px; line-height: 1.55; }
.mc-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.mc-goal { gap: 16px; }
.mc-goal-row { display: flex; flex-wrap: wrap; gap: 24px; align-items: flex-end; }
.mc-now { display: flex; flex-direction: column; }
.mc-multiple { font-size: 40px; overflow-wrap: anywhere; }
.mc-goal-bar { flex: 1 1 220px; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.mc-goal-meter { height: 10px; border-radius: 5px; }
.mc-dl { margin: 0; display: flex; flex-direction: column; gap: 6px; }
.mc-dl > div { display: flex; justify-content: space-between; gap: 12px; }
.mc-dl dt { color: var(--text-secondary); }
.mc-dl dd { margin: 0; font-variant-numeric: tabular-nums; text-align: right; overflow-wrap: anywhere; }
.mc-dl .total { padding-top: 6px; border-top: 1px solid var(--separator); font-weight: 600; }
.mc-dl .total dt { color: var(--text-primary); }
.mc-failed { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.mc-sub { display: block; margin-top: 2px; }
.mc-reason { min-width: 160px; overflow-wrap: anywhere; }
</style>
