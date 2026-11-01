<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import {
  listHoldings,
  addHolding,
  deleteHolding,
  type Holding,
} from "../portfolio";
import { asOfText, chartNumber, formatDecimal, formatMoney, formatPrice, ibkrLinkState, readPortfolio, skippedText, type Portfolio } from "../trading";
import { tradingApi, useTradingRead } from "../tradingApi";
import TradingStatus from "../components/trading/TradingStatus.vue";
import PageShell from "../components/PageShell.vue";
import "../components/trading/trading.css";

// Sub-tab is driven by the route (/trading/holdings vs /trading/ibkr).
const route = useRoute();
const seg = computed<"manual" | "ibkr">(() =>
  route.path.endsWith("/ibkr") ? "ibkr" : "manual",
);

// Manual holdings
const holdings = ref<Holding[]>([]);
const total = ref("0");
const symbol = ref("");
const quantity = ref("");
const avgCost = ref("");
const hError = ref<string | null>(null);
const loading = ref(true);

// IBKR: the link state is read from Core's portfolio (GET /v1/trading/portfolio).
const { state: ibkr, reload: refreshIbkr } = useTradingRead<Portfolio>({
  live: tradingApi.portfolio,
  fixture: (f, set) => f.portfolioFixture(set),
  read: readPortfolio,
  isEmpty: (p) => p.positions.length === 0,
});
const ibLink = computed(() => ibkrLinkState(ibkr.value));
const ibTone = computed(() => ({ ok: "pos", idle: "", warn: "neg", error: "dng" })[ibLink.value[0]]);
/** Weight bar width, clamped to the track. */
const pct = (v: string) => `${Math.min(100, Math.max(0, chartNumber(v) ?? 0))}%`;

async function refreshHoldings() {
  loading.value = true;
  hError.value = null;
  try {
    const data = await listHoldings();
    holdings.value = data.holdings;
    total.value = data.total_cost;
  } catch (e) {
    hError.value = String(e);
  } finally {
    loading.value = false;
  }
}

async function add() {
  hError.value = null;
  try {
    await addHolding({
      symbol: symbol.value,
      quantity: quantity.value,
      avg_cost: avgCost.value,
    });
    symbol.value = "";
    quantity.value = "";
    avgCost.value = "";
    await refreshHoldings();
  } catch (e) {
    hError.value = String(e);
  }
}

async function remove(id: string) {
  try {
    await deleteHolding(id);
    await refreshHoldings();
  } catch (e) {
    hError.value = String(e);
  }
}

onMounted(refreshHoldings);
</script>

<template>
  <PageShell class="td-page" title="Trading"
    subtitle="Your positions on one desk: manual, and live through IBKR (read-only). Trading (orders) comes later, with risk checks and confirmation.">
    <div class="td-kpis">
      <div class="card td-kpi">
        <span class="t-sm t-2">Positions</span>
        <span class="big">{{ holdings.length }}</span>
        <span class="t-xs t-2">entered by hand</span>
      </div>
      <div class="card td-kpi">
        <span class="t-sm t-2">Cost basis</span>
        <span class="big">{{ total }}</span>
        <span class="t-xs t-2">of the manual positions</span>
      </div>
      <div class="card td-kpi">
        <span class="t-sm t-2">IBKR</span>
        <span><span class="badge" :class="ibTone"><span class="dot" aria-hidden="true"></span>{{ ibLink[1] }}</span></span>
        <span class="t-xs t-2">read-only connection</span>
      </div>
    </div>

    <nav class="seg td-seg-nav" aria-label="Trading">
      <RouterLink to="/trading/holdings">Portfolio</RouterLink>
      <RouterLink to="/trading/ibkr">IBKR</RouterLink>
    </nav>

    <!-- MANUAL -->
    <section v-show="seg === 'manual'" class="card td-col" aria-labelledby="manual-title">
      <h2 id="manual-title">Manual positions</h2>
      <form class="add-form" @submit.prevent="add">
        <input v-model="symbol" class="input" placeholder="Symbol (AAPL)" aria-label="Symbol" />
        <input v-model="quantity" class="input" placeholder="Quantity" inputmode="decimal" aria-label="Quantity" />
        <input v-model="avgCost" class="input" placeholder="Avg. cost" inputmode="decimal" aria-label="Average cost" />
        <button type="submit" class="btn btn-primary">Add</button>
      </form>

      <p v-if="hError" class="err" role="alert">{{ hError }}</p>
      <div v-if="loading" class="td-col" role="status" aria-busy="true">
        <span class="sr-only">Loading your positions…</span>
        <span class="skel" style="width: 100%; height: 14px" aria-hidden="true"></span>
        <span class="skel" style="width: 92%; height: 14px" aria-hidden="true"></span>
      </div>

      <div v-else-if="holdings.length" class="td-scroll" tabindex="0" role="region" aria-labelledby="manual-title">
        <table class="tbl">
          <thead>
            <tr><th scope="col">Symbol</th><th scope="col" class="r">Quantity</th><th scope="col" class="r">Avg. cost</th><th scope="col" class="r">Cost basis</th><th scope="col">Weight</th><th scope="col"><span class="sr-only">Actions</span></th></tr>
          </thead>
          <tbody>
            <tr v-for="h in holdings" :key="h.id">
              <td class="mono sym">{{ h.symbol }}</td>
              <td class="r">{{ h.quantity }}</td>
              <td class="r">{{ h.avg_cost }} {{ h.currency }}</td>
              <td class="r">{{ h.cost_basis }}</td>
              <td class="weight">
                <span class="num">{{ h.weight_pct }}%</span>
                <span class="td-meter" aria-hidden="true"><i :style="{ width: pct(h.weight_pct) }"></i></span>
              </td>
              <td class="r"><button type="button" class="btn btn-ghost btn-sm" :aria-label="`Remove ${h.symbol}`" @click="remove(h.id)">Remove</button></td>
            </tr>
          </tbody>
          <tfoot>
            <tr><th scope="row" colspan="3">Total cost basis</th><td class="r total">{{ total }}</td><td colspan="2"></td></tr>
          </tfoot>
        </table>
      </div>
      <div v-else-if="!hError" class="empty">
        <h3>No positions yet</h3>
        <p class="t-sm">Add one above.</p>
      </div>
    </section>

    <!-- IBKR -->
    <section v-show="seg === 'ibkr'" class="card td-col" aria-labelledby="ibkr-title">
      <div class="td-card-head">
        <div class="td-title">
          <h2 id="ibkr-title">IBKR</h2>
          <span class="badge" :class="ibTone"><span class="dot" aria-hidden="true"></span>{{ ibLink[1] }}</span>
        </div>
        <button type="button" class="btn btn-secondary btn-sm" @click="refreshIbkr">Refresh</button>
      </div>
      <p class="t-sm t-2">Read-only through Core's IBKR connection; Jarvis only reads, it never places orders.</p>

      <TradingStatus :state="ibkr" what="the IBKR portfolio" compact @retry="refreshIbkr" v-slot="{ data, empty }">
        <p class="td-note">Last sync {{ asOfText(data.as_of) }}.</p>
        <p v-if="skippedText(data)" class="td-note" role="note">{{ skippedText(data) }}</p>
        <p v-if="empty" class="empty t-sm">No open positions in your IBKR account.</p>
        <div v-else class="td-scroll" tabindex="0" role="region" aria-labelledby="ibkr-title">
          <table class="tbl">
            <thead>
              <tr><th scope="col">Symbol</th><th scope="col" class="r">Quantity</th><th scope="col" class="r">Last</th><th scope="col" class="r">Value</th></tr>
            </thead>
            <tbody>
              <tr v-for="p in data.positions" :key="p.conid">
                <td class="mono sym">{{ p.symbol }}</td>
                <td class="r">{{ formatDecimal(p.quantity, { maxPlaces: 8 }) }}</td>
                <td class="r">{{ formatPrice(p.last_price, p.currency) }}</td>
                <td class="r">{{ formatMoney(p.market_value, data.base_currency) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </TradingStatus>
    </section>
  </PageShell>
</template>

<style scoped>
.add-form { display: flex; flex-wrap: wrap; gap: 8px; }
.add-form .input { flex: 1 1 140px; width: auto; }
.sym { font-weight: 600; overflow-wrap: anywhere; }
.weight { min-width: 140px; }
.weight .td-meter { display: block; margin-top: 6px; }
.total { font-weight: 600; }
tfoot th, tfoot td { padding: 12px; border-top: 1px solid var(--separator); }
tfoot th { text-align: left; font-weight: 600; }
</style>
