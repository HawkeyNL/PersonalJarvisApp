<script setup lang="ts">
import { computed, ref } from "vue";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import JvSegmented from "../components/jv/JvSegmented.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import TradingChart from "../components/trading/TradingChart.vue";
import TradingStatus from "../components/trading/TradingStatus.vue";
import "../components/trading/trading.css";
import {
  HISTORY_RANGES, asOfText, changeRatio, decimalSign, readHistory, readPortfolio, formatMoney, formatPercent, onTarget, signTone, sourceLabel,
  type HistoryRange, type Portfolio, type PortfolioHistory,
} from "../trading";
import { tradingApi, useTradingRead } from "../tradingApi";

// Trading dashboard: every pot on one desk. Read-only; only the IBKR stocks
// pot has data, the others say plainly that they are not built yet.
const { state: portfolio, reload: reloadPortfolio } = useTradingRead<Portfolio>({
  live: tradingApi.portfolio,
  fixture: (f, set) => f.portfolioFixture(set),
  read: readPortfolio,
  isEmpty: (p) => p.positions.length === 0 && decimalSign(p.net_liquidation) === 0,
});

const range = ref<HistoryRange>("3M");
const RANGES = HISTORY_RANGES.map((id) => ({ id, label: id === "ALL" ? "All" : id }));
const { state: history, reload: reloadHistory } = useTradingRead<PortfolioHistory>({
  live: () => tradingApi.history(range.value),
  fixture: (f, set) => f.historyFixture(set, range.value),
  read: readHistory,
  isEmpty: (h) => h.points.length < 2,
  key: () => range.value,
});

const historyData = computed(() => (history.value.kind === "data" || history.value.kind === "stale" ? history.value.data : null));
const series = computed(() => (historyData.value?.points ?? []).map((p) => ({ t: p.t, o: p.value, h: p.value, l: p.value, c: p.value })));
const rangeRatio = computed(() => {
  const points = historyData.value?.points ?? [];
  return points.length > 1 ? changeRatio(points[0].value, points[points.length - 1].value) : null;
});
const rangeChange = computed(() => (rangeRatio.value === null ? null : formatPercent(rangeRatio.value)));
const chartLabel = computed(() => `Total value over ${range.value === "ALL" ? "all time" : range.value}: ${rangeChange.value ?? "no change data"}`);
const changeTone = computed(() => ({ pos: "pos", neg: "neg", flat: "" })[signTone(rangeRatio.value)]);
</script>

<template>
  <PageShell class="td-page" title="Trading">
    <template #subtitle>
      <p class="td-sub">All pots in one place. Read-only: this app places no orders.</p>
      <p v-if="portfolio.kind === 'data' || portfolio.kind === 'stale' || portfolio.kind === 'empty'" class="td-note">
        {{ sourceLabel(portfolio.data) }} · as of {{ asOfText(portfolio.data.as_of) }}
      </p>
    </template>

    <TradingStatus :state="portfolio" what="your portfolio" @retry="reloadPortfolio">
      <template #default="{ data, empty }">
        <div v-if="empty" class="card empty">
          <span class="tile acc" aria-hidden="true"><NavIcon name="stocks" /></span>
          <h3>Nothing in your IBKR account yet</h3>
          <p class="t-sm">Core is connected, but the account holds no positions and no cash.</p>
        </div>
        <div v-else class="td-kpis">
          <div class="card td-kpi">
            <span class="t-sm t-2">Total value</span>
            <span class="big">{{ formatMoney(data.net_liquidation, data.base_currency) }}</span>
            <span class="t-xs t-2">incl. {{ formatMoney(data.cash, data.base_currency) }} cash</span>
          </div>
          <div class="card td-kpi">
            <span class="t-sm t-2">Today</span>
            <span class="big" :class="`td-${signTone(data.day_pnl)}`">{{ formatMoney(data.day_pnl, data.base_currency, { sign: true }) }}</span>
            <span class="t-xs t-2">{{ formatPercent(data.day_change_pct) }}</span>
          </div>
          <div class="card td-kpi">
            <span class="t-sm t-2">Total return</span>
            <span class="big" :class="`td-${signTone(data.total_pnl)}`">{{ formatMoney(data.total_pnl, data.base_currency, { sign: true }) }}</span>
            <span class="t-xs t-2">{{ data.total_return_pct ? `${formatPercent(data.total_return_pct)} since start` : "Since start" }}</span>
          </div>
          <div class="card td-kpi">
            <span class="t-sm t-2">Positions</span>
            <span class="big">{{ data.positions.length }}</span>
            <span class="t-xs t-2">{{ data.positions.length === 1 ? "1 instrument" : `${data.positions.length} instruments` }} at IBKR</span>
          </div>
        </div>
      </template>
    </TradingStatus>

    <section class="card td-col" aria-labelledby="desk-value">
      <div class="td-card-head">
        <div class="td-title">
          <h2 id="desk-value">Total value</h2>
          <span v-if="rangeChange" class="badge" :class="changeTone"><span class="dot" aria-hidden="true"></span>{{ rangeChange }}</span>
        </div>
        <JvSegmented v-model="range" :items="RANGES" label="Period" controls="desk-chart" class="td-seg" />
      </div>
      <div id="desk-chart" class="td-col">
        <TradingStatus :state="history" what="the value history" compact @retry="reloadHistory">
          <template #default="{ data, empty }">
            <p v-if="empty" class="empty t-sm">Not enough history for this period yet.</p>
            <TradingChart v-else :bars="series" :currency="data.base_currency" :label="chartLabel" :height="220" />
          </template>
        </TradingStatus>
      </div>
    </section>

    <section class="td-col" aria-labelledby="desk-pots">
      <h2 id="desk-pots">Pots</h2>
      <div class="td-grid">
        <article class="card td-col">
          <div class="td-card-head">
            <div class="td-title">
              <span class="tile acc" aria-hidden="true"><NavIcon name="stocks" /></span>
              <div class="pot-name"><h3>Stocks</h3><span class="t-xs t-2">IBKR · read-only</span></div>
            </div>
          </div>
          <template v-if="portfolio.kind === 'data' || portfolio.kind === 'stale'">
            <div class="td-card-head pot-value">
              <span class="big">{{ formatMoney(portfolio.data.net_liquidation, portfolio.data.base_currency) }}</span>
              <span class="num today" :class="`td-${signTone(portfolio.data.day_pnl)}`">{{ formatMoney(portfolio.data.day_pnl, portfolio.data.base_currency, { sign: true }) }} today</span>
            </div>
            <div v-if="portfolio.data.allocation?.length" class="pot-meter">
              <div class="td-card-head t-sm"><span>Allocation on target</span><span class="mono t-2">{{ onTarget(portfolio.data.allocation) }} of {{ portfolio.data.allocation.length }}</span></div>
              <div class="td-meter" aria-hidden="true"><i :style="{ width: `${(onTarget(portfolio.data.allocation) / portfolio.data.allocation.length) * 100}%` }"></i></div>
            </div>
            <p v-else class="t-sm t-2">No allocation targets set.</p>
          </template>
          <p v-else-if="portfolio.kind === 'empty'" class="t-sm t-2">Connected; nothing held yet.</p>
          <span v-else-if="portfolio.kind === 'loading'" class="skel" style="width: 60%; height: 28px" aria-hidden="true"></span>
          <p v-else class="t-sm t-2">No IBKR data yet; see above.</p>
          <RouterLink to="/trading/stocks" class="btn btn-quiet pot-open">Open stocks</RouterLink>
        </article>
        <article class="card td-col">
          <div class="td-card-head">
            <div class="td-title">
              <span class="tile acc" aria-hidden="true"><NavIcon name="coin" /></span>
              <div class="pot-name"><h3>Memecoin</h3><span class="t-xs t-2">Solana · paper</span></div>
            </div>
            <span class="badge td-outline">Paper only</span>
          </div>
          <p class="t-sm t-2">A paper simulation on Solana market data, off by default. No wallet, no real money.</p>
          <RouterLink to="/trading/memecoin" class="btn btn-quiet pot-open">Open memecoin</RouterLink>
        </article>
        <article class="card td-col">
          <div class="td-card-head"><h3>Futures</h3><span class="badge">Later</span></div>
          <p class="t-sm t-2">Futures through IBKR come later: paper and read-only first, then a signed mandate.</p>
        </article>
      </div>
    </section>

    <p class="td-note">
      Also here: <RouterLink to="/trading/holdings">manual holdings</RouterLink> and the
      <RouterLink to="/trading/ibkr">IBKR status</RouterLink>.
    </p>
    <DevDataToggle />
  </PageShell>
</template>

<style scoped>
.pot-name { display: flex; flex-direction: column; min-width: 0; }
.pot-value { align-items: flex-end; }
.pot-value .big { overflow-wrap: anywhere; min-width: 0; }
.today { font-weight: 500; }
.pot-meter { display: flex; flex-direction: column; gap: 6px; }
.pot-open { margin-top: auto; }
</style>
