<script setup lang="ts">
import { computed, onUnmounted, ref } from "vue";
import PageShell from "../components/PageShell.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import "../components/trading/trading.css";
import { asOfText, formatDecimal, signTone } from "../trading";
import { agentStatus, failureText, readOverview, shortMint, usd } from "../memecoin";
import {
  FAMILY_LABEL, NO_EDGE_TEXT, bpsPct, decisionView, readDecisions, readStrategies, strategyState, versionOwner,
} from "../memeStrategies";
import { coalesce } from "../coalesce";
import { memeApi, onMemeHint, useMemeRead } from "../memecoinApi";
import { MEME_DATA_SETS } from "../tradingApi";

// The paper strategies side by side and Claude's decision log. Read-only:
// disabling and enabling happen on a strategy's own page.
const { state: strategies, reload: reloadStrategies } = useMemeRead({
  live: memeApi.strategies, fixture: (f, set) => f.strategiesFixture(set), read: readStrategies,
});
const { state: decisions, reload: reloadDecisions } = useMemeRead({
  live: memeApi.decisions, fixture: (f, set) => f.decisionsFixture(set), read: readDecisions,
});
const { state: overview, reload: reloadOverview } = useMemeRead({
  live: memeApi.overview, fixture: (f, set) => f.overviewFixture(set), read: readOverview,
});

const now = ref(Date.now());
const timer = window.setInterval(() => (now.value = Date.now()), 30_000);
onUnmounted(() => window.clearInterval(timer));

const agent = computed(() => (overview.value.kind === "ok" ? agentStatus(overview.value.data, now.value) : null));
const listed = computed(() => (strategies.value.kind === "ok" ? strategies.value.data : []));

// A decision also spends Claude's call budget, shown from the overview. A
// burst of hints costs at most one more reload of each.
const strategiesSoon = coalesce(reloadStrategies);
const decisionsSoon = coalesce(() => Promise.all([reloadDecisions(), reloadOverview()]).then(() => {}));
onMemeHint((hint) => {
  if (hint.kind !== "decision") void strategiesSoon();
  if (hint.kind !== "strategy") void decisionsSoon();
});

function refresh(): void {
  void reloadStrategies();
  void reloadDecisions();
  void reloadOverview();
}

const changedBy = (by: string) => (by === "seed" ? "Seed" : by === "claude" ? "Claude" : "You");
</script>

<template>
  <PageShell class="td-page" title="Strategies" subtitle="Memecoin paper strategies and Claude's decisions. A simulation: no wallet, no orders.">
    <template #crumb><p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink> / <RouterLink to="/trading/memecoin">Memecoin</RouterLink></p></template>
    <template #badges><span class="badge td-outline">Paper</span></template>
    <template #actions><button type="button" class="btn btn-secondary" @click="refresh">Refresh</button></template>

    <p class="td-banner t-sm" role="note">{{ NO_EDGE_TEXT }}</p>

    <section class="card td-tight" aria-labelledby="ms-list">
      <div class="td-card-head">
        <h2 id="ms-list">Strategies</h2>
        <span class="t-sm t-2">results over all versions, net of every cost</span>
      </div>
      <div v-if="strategies.kind === 'loading'" class="td-col td-inline" role="status">
        <span class="sr-only">Loading strategies…</span>
        <span class="skel" style="width: 100%; height: 14px" aria-hidden="true"></span>
        <span class="skel" style="width: 85%; height: 14px" aria-hidden="true"></span>
      </div>
      <div v-else-if="strategies.kind === 'failed'" class="td-inline ms-failed" role="alert">
        <span class="t-sm">Could not load strategies. {{ failureText(strategies.failure) }}</span>
        <button type="button" class="btn btn-secondary btn-sm" @click="reloadStrategies">Try again</button>
      </div>
      <p v-else-if="!strategies.data.length" class="empty t-sm">No strategies yet. Core records the seed strategies when the paper trader starts.</p>
      <div v-else class="td-scroll" tabindex="0" role="region" aria-label="Strategies table">
        <table class="tbl" style="min-width: 860px">
          <thead>
            <tr>
              <th scope="col">Strategy</th><th scope="col">Family</th><th scope="col">State</th><th scope="col" class="r">Trades</th>
              <th scope="col" class="r">Net</th><th scope="col" class="r">Per trade</th><th scope="col" class="r">Win rate</th>
              <th scope="col" class="r">Max drawdown</th><th scope="col">Last change</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in strategies.data" :key="s.id">
              <td>
                <RouterLink class="mono ms-id" :to="`/trading/memecoin/strategies/${s.id}`">{{ s.id }}</RouterLink>
                <span class="t-xs t-2 ms-sub">version {{ s.active_version }}</span>
              </td>
              <td class="t-sm">{{ s.family ? FAMILY_LABEL[s.family] : "Unknown" }}</td>
              <td><span class="badge" :class="strategyState(s).tone"><span class="dot" aria-hidden="true"></span>{{ strategyState(s).label }}</span></td>
              <td class="r">{{ formatDecimal(String(s.performance.trades)) }}</td>
              <td class="r" :class="`td-${signTone(s.performance.net_usd)}`">{{ usd(s.performance.net_usd, { sign: true }) }}</td>
              <td class="r" :class="`td-${signTone(s.performance.expectancy_usd)}`">{{ s.performance.trades ? usd(s.performance.expectancy_usd, { sign: true }) : "—" }}</td>
              <td class="r">{{ s.performance.trades ? bpsPct(s.performance.win_rate_bps) : "—" }}</td>
              <td class="r">{{ usd(s.performance.max_drawdown_usd) }}</td>
              <td class="t-sm">{{ changedBy(s.changed_by) }} · <time :datetime="s.changed_at">{{ asOfText(s.changed_at, now) }}</time></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="td-note">Open a strategy for its versions, Claude's reasons, the backtest and its paper gate.</p>
    </section>

    <section class="card td-tight" aria-labelledby="ms-decisions">
      <div class="td-card-head">
        <h2 id="ms-decisions">Claude's decisions</h2>
        <span v-if="agent" class="badge" :class="agent.tone"><span class="dot" aria-hidden="true"></span>{{ agent.label }}<template v-if="agent.until"> until {{ asOfText(agent.until, now) }}</template></span>
      </div>
      <p v-if="agent" class="td-note">{{ agent.detail }}</p>
      <div v-if="decisions.kind === 'loading'" class="td-col td-inline" role="status">
        <span class="sr-only">Loading decisions…</span>
        <span class="skel" style="width: 100%; height: 14px" aria-hidden="true"></span>
      </div>
      <div v-else-if="decisions.kind === 'failed'" class="td-inline ms-failed" role="alert">
        <span class="t-sm">Could not load decisions. {{ failureText(decisions.failure) }}</span>
        <button type="button" class="btn btn-secondary btn-sm" @click="reloadDecisions">Try again</button>
      </div>
      <p v-else-if="!decisions.data.length" class="empty t-sm">No decisions yet. Claude decides only when the paper profile turns it on.</p>
      <div v-else class="td-scroll" tabindex="0" role="region" aria-label="Claude's decisions table">
        <table class="tbl" style="min-width: 920px">
          <thead>
            <tr>
              <th scope="col">Time</th><th scope="col">Decision</th><th scope="col">Strategy</th><th scope="col">Token</th>
              <th scope="col" class="r">Size</th><th scope="col">Reason</th><th scope="col">Claude's note <span class="t-2">(AI-written)</span></th><th scope="col">Model</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in decisions.data" :key="d.key" :class="{ 'ms-muted': decisionView(d).muted }">
              <td class="t-sm"><time :datetime="d.at">{{ asOfText(d.at, now) }}</time></td>
              <td class="t-sm">{{ decisionView(d).kind }}</td>
              <td class="t-sm">
                <template v-if="d.strategy_version !== null">
                  <span v-if="versionOwner(listed, d.strategy_version)" class="mono">{{ versionOwner(listed, d.strategy_version) }}</span>
                  <span class="t-xs t-2 ms-sub">version {{ d.strategy_version }}</span>
                </template>
                <template v-else>—</template>
              </td>
              <td><span v-if="d.mint" class="mono" :title="d.mint">{{ shortMint(d.mint) }}</span><template v-else>—</template></td>
              <td class="r">{{ d.size_bps === null ? "—" : bpsPct(d.size_bps) }}</td>
              <td class="t-sm ms-reason">{{ decisionView(d).reason || "—" }}</td>
              <td class="t-sm ms-text">{{ d.rationale ?? "—" }}</td>
              <td class="t-xs t-2 mono">{{ d.model ?? "—" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="td-note">"Claude called" marks that a call started; it counts toward the call budget. Size is the share of what the strategy would enter. Notes are Claude's own words, shown as written and never checked by Core; Core checks every action again before it happens.</p>
    </section>
    <DevDataToggle :sets="MEME_DATA_SETS" />
  </PageShell>
</template>

<style scoped>
.ms-failed { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.ms-id { overflow-wrap: anywhere; }
.ms-sub { display: block; margin-top: 2px; }
.ms-reason { min-width: 140px; overflow-wrap: anywhere; }
.ms-text { min-width: 240px; max-width: 420px; overflow-wrap: anywhere; }
.ms-muted td { color: var(--text-secondary); font-size: 12px; }
</style>
