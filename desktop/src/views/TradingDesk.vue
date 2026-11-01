<script setup lang="ts">
import { computed, ref } from "vue";
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";
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
const changeTone = computed(() => (signTone(rangeRatio.value) === "neg" ? "neg" : "acc"));
</script>

<template>
  <div class="td-page">
    <JvBackdrop glow-y="20%" horizon="80px" />
    <JvTopBar variant="node" title="TRADING" subtitle="ALL POTS IN ONE PLACE" />
    <section class="td-wrap">
      <header class="td-head">
        <div>
          <h1>Trading</h1>
          <p class="td-sub">All pots in one place. Read-only: this app places no orders.</p>
        </div>
      </header>

      <TradingStatus :state="portfolio" what="your portfolio" @retry="reloadPortfolio">
        <template #default="{ data, empty }">
          <p class="td-source">{{ sourceLabel(data) }} · as of {{ asOfText(data.as_of) }}</p>
          <div v-if="empty" class="td-card td-empty">
            <h3>Nothing in your IBKR account yet</h3>
            Core is connected, but the account holds no positions and no cash.
          </div>
          <div v-else class="td-kpis">
            <div class="td-card td-kpi">
              <span class="td-label">Total value</span>
              <span class="td-big">{{ formatMoney(data.net_liquidation, data.base_currency) }}</span>
              <span class="td-small">incl. {{ formatMoney(data.cash, data.base_currency) }} cash</span>
            </div>
            <div class="td-card td-kpi">
              <span class="td-label">Today</span>
              <span class="td-big" :class="`td-${signTone(data.day_pnl)}`">{{ formatMoney(data.day_pnl, data.base_currency, { sign: true }) }}</span>
              <span class="td-small">{{ formatPercent(data.day_change_pct) }}</span>
            </div>
            <div class="td-card td-kpi">
              <span class="td-label">Total return</span>
              <span class="td-big" :class="`td-${signTone(data.total_pnl)}`">{{ formatMoney(data.total_pnl, data.base_currency, { sign: true }) }}</span>
              <span class="td-small">{{ data.total_return_pct ? `${formatPercent(data.total_return_pct)} since start` : "Since start" }}</span>
            </div>
            <div class="td-card td-kpi">
              <span class="td-label">Positions</span>
              <span class="td-big">{{ data.positions.length }}</span>
              <span class="td-small">{{ data.positions.length === 1 ? "1 instrument" : `${data.positions.length} instruments` }} at IBKR</span>
            </div>
          </div>
        </template>
      </TradingStatus>

      <section class="td-card" aria-labelledby="desk-value">
        <div class="td-card-head">
          <div class="td-card-head">
            <h2 id="desk-value">Total value</h2>
            <span v-if="rangeChange" class="td-badge" :class="changeTone">{{ rangeChange }}</span>
          </div>
          <JvSegmented v-model="range" :items="RANGES" label="Period" controls="desk-chart" class="td-seg" />
        </div>
        <div id="desk-chart">
          <TradingStatus :state="history" what="the value history" compact @retry="reloadHistory">
            <template #default="{ data, empty }">
              <p v-if="empty" class="td-empty">Not enough history for this period yet.</p>
              <TradingChart v-else :bars="series" :currency="data.base_currency" :label="chartLabel" :height="220" />
            </template>
          </TradingStatus>
        </div>
      </section>

      <section class="td-grid" aria-label="Pots">
        <article class="td-card">
          <div class="td-card-head">
            <div><h3>Stocks</h3><span class="td-small">IBKR · read-only</span></div>
          </div>
          <template v-if="portfolio.kind === 'data' || portfolio.kind === 'stale'">
            <div class="td-card-head">
              <span class="td-big">{{ formatMoney(portfolio.data.net_liquidation, portfolio.data.base_currency) }}</span>
              <span class="td-num" :class="`td-${signTone(portfolio.data.day_pnl)}`">{{ formatMoney(portfolio.data.day_pnl, portfolio.data.base_currency, { sign: true }) }} today</span>
            </div>
            <div v-if="portfolio.data.allocation?.length">
              <div class="td-card-head td-small"><span>Allocation on target</span><span class="td-num">{{ onTarget(portfolio.data.allocation) }} of {{ portfolio.data.allocation.length }}</span></div>
              <div class="td-meter" style="margin-top: 6px"><i :style="{ width: `${(onTarget(portfolio.data.allocation) / portfolio.data.allocation.length) * 100}%` }"></i></div>
            </div>
            <p v-else class="td-note">No allocation targets set.</p>
          </template>
          <p v-else-if="portfolio.kind === 'empty'" class="td-note">Connected; nothing held yet.</p>
          <p v-else-if="portfolio.kind === 'loading'" class="td-note">Loading…</p>
          <p v-else class="td-note">No IBKR data yet; see above.</p>
          <RouterLink to="/trading/stocks" class="ghost-btn sm" style="align-self: flex-start">Open stocks</RouterLink>
        </article>
        <article class="td-card">
          <div class="td-card-head"><h3>Memecoin</h3><span class="td-badge">Paper only</span></div>
          <p class="td-note">A paper simulation on Solana market data, off by default. No wallet, no real money.</p>
          <RouterLink to="/trading/memecoin" class="ghost-btn sm" style="align-self: flex-start">Open memecoin</RouterLink>
        </article>
        <article class="td-card">
          <div class="td-card-head"><h3>Futures</h3><span class="td-badge">Later</span></div>
          <p class="td-note">Futures through IBKR come later: paper and read-only first, then a signed mandate.</p>
        </article>
      </section>

      <p class="td-note">
        Also here: <RouterLink to="/trading/holdings">manual holdings</RouterLink> and the
        <RouterLink to="/trading/ibkr">IBKR status</RouterLink>.
      </p>
    </section>
    <DevDataToggle />
  </div>
</template>
