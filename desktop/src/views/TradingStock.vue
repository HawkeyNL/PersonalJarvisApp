<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import PageShell from "../components/PageShell.vue";
import JvSegmented from "../components/jv/JvSegmented.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import TradingChart from "../components/trading/TradingChart.vue";
import TradingStatus from "../components/trading/TradingStatus.vue";
import "../components/trading/trading.css";
import {
  BAR_RANGES, NOTE_LABEL, asOfText, changeRatio, formatDecimal, formatMoney, formatPercent, formatPrice, parseConid, readBars, readPortfolio,
  signTone, sourceLabel,
  type BarRange, type InstrumentBars, type Portfolio,
} from "../trading";
import { tradingApi, useTradingRead } from "../tradingApi";

// One instrument: price chart (line or candles, 1D–5Y, moving averages,
// volume), Jarvis's notes labelled by kind, and your position if you hold it.
const route = useRoute();
const conid = computed(() => parseConid(route.params.conid));

const range = ref<BarRange>("3M");
const mode = ref<"line" | "candle">("line");
const show = ref({ ma50: false, ma200: false, volume: true });
const RANGES = BAR_RANGES.map((id) => ({ id, label: id }));
const MODES = [{ id: "line", label: "Line" }, { id: "candle", label: "Candles" }];

const { state: bars, reload } = useTradingRead<InstrumentBars>({
  live: () => (conid.value === null ? Promise.reject(new Error("invalid conid")) : tradingApi.bars(conid.value, range.value)),
  fixture: (f, set) => f.barsFixture(set, conid.value ?? 0, range.value),
  read: readBars,
  isEmpty: (b) => b.bars.length === 0,
  key: () => [conid.value, range.value],
  instrument: true,
});
const { state: portfolio } = useTradingRead<Portfolio>({
  live: tradingApi.portfolio,
  fixture: (f, set) => f.portfolioFixture(set),
  read: readPortfolio,
  isEmpty: () => false,
});

const data = computed(() => (bars.value.kind === "data" || bars.value.kind === "stale" ? bars.value.data : null));
const title = computed(() => data.value?.name ?? data.value?.symbol ?? (conid.value ? `Instrument ${conid.value}` : "Unknown instrument"));
const last = computed(() => data.value?.bars[data.value.bars.length - 1]);
const changeRaw = computed(() => (data.value && last.value ? changeRatio(data.value.bars[0].o, last.value.c) : null));
const barCount = computed(() => data.value?.bars.length ?? 0);
const ma = computed(() => [show.value.ma50 && 50, show.value.ma200 && 200].filter((n): n is number => !!n && n <= barCount.value));
const hasVolume = computed(() => data.value?.bars.some((b) => b.v !== undefined) ?? false);
const chartLabel = computed(() => `${data.value?.symbol ?? "Price"}, ${range.value}: ${changeRaw.value === null ? "no change data" : formatPercent(changeRaw.value)}`);
const position = computed(() =>
  portfolio.value.kind === "data" || portfolio.value.kind === "stale" ? portfolio.value.data.positions.find((p) => p.conid === conid.value) : undefined,
);
const base = computed(() => (portfolio.value.kind === "data" || portfolio.value.kind === "stale" ? portfolio.value.data.base_currency : ""));
const noteTime = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleDateString("en", { day: "numeric", month: "short" });
};
</script>

<template>
  <PageShell class="td-page" :title="title">
    <template #crumb><p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink><span class="t-2"> / </span><RouterLink to="/trading/stocks">Stocks</RouterLink></p></template>
    <template #badges>
      <span v-if="data" class="mono t-2 ident">{{ data.symbol }}<template v-if="data.exchange"> · {{ data.exchange }}</template></span>
    </template>
    <template #subtitle>
      <p v-if="data && last" class="price">
        <span class="big">{{ formatPrice(last.c, data.currency) }}</span>
        <span v-if="changeRaw !== null" class="num change" :class="`td-${signTone(changeRaw)}`">{{ formatPercent(changeRaw) }} over {{ range }}</span>
      </p>
    </template>

    <div class="detail">
      <section class="card td-col chart-card" aria-label="Price chart">
        <div class="td-card-head">
          <JvSegmented v-model="range" :items="RANGES" label="Period" controls="stock-chart" class="td-seg" />
          <JvSegmented v-model="mode" :items="MODES" label="Chart type" controls="stock-chart" class="td-seg" />
        </div>
        <div class="toggles" role="group" aria-label="Indicators">
          <button type="button" class="btn btn-sm" :class="show.ma50 && barCount >= 50 ? 'btn-quiet' : 'btn-secondary'" :aria-pressed="show.ma50 && barCount >= 50" :disabled="barCount < 50" :title="barCount < 50 ? `Needs 50 bars; this period has ${barCount}` : undefined" @click="show.ma50 = !show.ma50">
            <span class="td-swatch ma50" aria-hidden="true"></span>Average 50
          </button>
          <button type="button" class="btn btn-sm" :class="show.ma200 && barCount >= 200 ? 'btn-quiet' : 'btn-secondary'" :aria-pressed="show.ma200 && barCount >= 200" :disabled="barCount < 200" :title="barCount < 200 ? `Needs 200 bars; this period has ${barCount}` : undefined" @click="show.ma200 = !show.ma200">
            <span class="td-swatch ma200" aria-hidden="true"></span>Average 200
          </button>
          <button type="button" class="btn btn-sm" :class="show.volume && hasVolume ? 'btn-quiet' : 'btn-secondary'" :aria-pressed="show.volume && hasVolume" :disabled="!hasVolume" @click="show.volume = !show.volume">
            <span class="td-swatch vol" aria-hidden="true"></span>Volume
          </button>
        </div>
        <div id="stock-chart" class="td-col">
          <TradingStatus :state="bars" what="the price history" compact @retry="reload">
            <template #default="{ data: d, empty }">
              <p v-if="empty" class="empty t-sm">No prices for this period. The market may not have traded yet.</p>
              <TradingChart v-else :bars="d.bars" :mode="mode" :currency="d.currency" :label="chartLabel" :ma="ma" :volume="show.volume && hasVolume" />
              <p class="td-note">Prices: {{ sourceLabel(d) }} · as of {{ asOfText(d.as_of) }}. Averages are over the bars of this period.</p>
            </template>
          </TradingStatus>
        </div>
      </section>

      <aside class="side">
        <section class="card td-col notes" aria-labelledby="stock-notes">
          <h2 id="stock-notes">Jarvis notes</h2>
          <template v-if="data?.notes?.length">
            <div v-for="(n, i) in data.notes" :key="i" class="note">
              <div class="td-card-head">
                <span class="badge" :class="{ acc: n.kind === 'calculation', 'td-outline': n.kind === 'inference' }">{{ NOTE_LABEL[n.kind] ?? n.kind }}</span>
                <span class="t-xs t-2">{{ noteTime(n.t) }}</span>
              </div>
              <p class="t-sm text">{{ n.text }}</p>
              <p v-if="n.sources?.length" class="td-note">Sources: {{ n.sources.join(", ") }}</p>
            </div>
            <p class="td-note legend">Fact: from a source. Calculation: computed by code. AI inference: Jarvis's estimate, which can be wrong.</p>
          </template>
          <p v-else class="t-sm t-2">No notes from Jarvis for this period.</p>
        </section>

        <section class="card td-col" aria-labelledby="stock-position">
          <h2 id="stock-position">Your position</h2>
          <div v-if="position" class="td-wells">
            <div class="td-well"><span class="t-xs t-2">Quantity</span><span class="mono">{{ formatDecimal(position.quantity, { maxPlaces: 8 }) }}</span></div>
            <div class="td-well"><span class="t-xs t-2">Avg. cost</span><span class="mono">{{ formatPrice(position.avg_cost, position.currency) }}</span></div>
            <div class="td-well"><span class="t-xs t-2">Value</span><span class="mono">{{ formatMoney(position.market_value, base) }}</span></div>
            <div class="td-well"><span class="t-xs t-2">Result</span><span class="mono" :class="`td-${signTone(position.unrealized_pnl)}`">{{ formatMoney(position.unrealized_pnl, base, { sign: true }) }}</span></div>
          </div>
          <p v-else-if="portfolio.kind === 'data' || portfolio.kind === 'stale'" class="t-sm t-2">You do not hold this instrument.</p>
          <div v-else-if="portfolio.kind === 'loading'" class="td-wells" role="status">
            <span class="sr-only">Loading your position…</span>
            <span v-for="i in 4" :key="i" class="skel" style="height: 52px" aria-hidden="true"></span>
          </div>
          <p v-else class="t-sm t-2">Your position shows once Core reports your IBKR portfolio.</p>
        </section>
      </aside>
    </div>
    <DevDataToggle />
  </PageShell>
</template>

<style scoped>
.ident { overflow-wrap: anywhere; }
.price { margin-top: 8px; display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.price .big { overflow-wrap: anywhere; }
.change { font-weight: 500; }
.detail { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }
.chart-card { flex: 999 1 620px; gap: 14px; }
.side { flex: 1 1 340px; display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.toggles { display: flex; gap: 6px; flex-wrap: wrap; }
.td-swatch.ma50 { color: var(--chart-ma); }
.td-swatch.ma200 { color: var(--text-secondary); border-top-style: dashed; }
.td-swatch.vol { color: var(--border-strong); }
.notes { gap: 14px; }
.note { display: flex; flex-direction: column; gap: 4px; }
.note .badge { align-self: flex-start; }
.note .text { line-height: 1.55; overflow-wrap: anywhere; }
.legend { padding-top: 12px; border-top: 1px solid var(--separator); }
</style>
