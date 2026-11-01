<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import {
  listHoldings,
  addHolding,
  deleteHolding,
  type Holding,
} from "../portfolio";
import { asOfText, formatDecimal, formatMoney, formatPrice, ibkrLinkState, readPortfolio, skippedText, type Portfolio } from "../trading";
import { tradingApi, useTradingRead } from "../tradingApi";
import TradingStatus from "../components/trading/TradingStatus.vue";
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";

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
const ibDot = computed(() => ({ ok: "dot-ok", idle: "dot-todo", warn: "dot-err", error: "dot-err" })[ibLink.value[0]]);

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
  <div class="trading-page">
  <JvBackdrop glow-y="20%" horizon="80px" />
  <JvTopBar variant="node" title="TRADING" subtitle="YOUR POSITIONS ON ONE DESK" />
  <section class="view trading">
    <header class="desk-top">
      <div>
        <h1>Trading</h1>
        <p class="muted sub">
          Your positions on one desk: manual, and live through IBKR (read-only).
          Trading (orders) comes later, with risk checks and confirmation.
        </p>
      </div>
    </header>

    <!-- summary tiles -->
    <div class="tiles">
      <div class="kpi glass">
        <span class="t-k">POSITIONS</span>
        <span class="t-v">{{ holdings.length }}</span>
      </div>
      <div class="kpi glass">
        <span class="t-k">COST BASIS</span>
        <span class="t-v mono">{{ total }}</span>
      </div>
      <div class="kpi glass">
        <span class="t-k">IBKR</span>
        <span class="t-v small"><span class="dot" :class="ibDot"></span>{{ ibLink[1] }}</span>
      </div>
    </div>

    <nav class="seg" aria-label="Trading">
      <RouterLink to="/trading/holdings" :class="{ on: seg === 'manual' }">Portfolio</RouterLink>
      <RouterLink to="/trading/ibkr" :class="{ on: seg === 'ibkr' }">IBKR</RouterLink>
    </nav>

    <!-- MANUAL -->
    <div v-show="seg === 'manual'" class="panel glass">
      <form class="holding-form" @submit.prevent="add">
        <input v-model="symbol" placeholder="Symbol (AAPL)" aria-label="Symbol" />
        <input v-model="quantity" placeholder="Quantity" inputmode="decimal" aria-label="Quantity" />
        <input v-model="avgCost" placeholder="Avg. cost" inputmode="decimal" aria-label="Average cost" />
        <button type="submit" class="btn btn-primary">Add</button>
      </form>

      <p v-if="hError" class="err">{{ hError }}</p>
      <p v-if="loading" class="muted">Loading…</p>

      <div v-else-if="holdings.length" class="holdings">
        <div class="total">Total cost basis: <strong>{{ total }}</strong></div>
        <ul class="holding-list">
          <li v-for="h in holdings" :key="h.id" class="holding">
            <div class="holding-head">
              <span class="sym">{{ h.symbol }}</span>
              <span class="muted">{{ h.quantity }} × {{ h.avg_cost }} {{ h.currency }}</span>
              <span class="cost mono">{{ h.cost_basis }}</span>
              <button class="del" @click="remove(h.id)" aria-label="Remove">✕</button>
            </div>
            <div class="bar"><div class="bar-fill" :style="{ width: h.weight_pct + '%' }"></div></div>
            <div class="weight muted">{{ h.weight_pct }}%</div>
          </li>
        </ul>
      </div>
      <p v-else-if="!loading" class="muted">No positions yet. Add one above.</p>
    </div>

    <!-- IBKR -->
    <div v-show="seg === 'ibkr'" class="panel glass">
      <div class="ib-head">
        <div class="badge"><span class="dot" :class="ibDot"></span>{{ ibLink[1] }}</div>
        <button class="ghost" @click="refreshIbkr">Refresh</button>
      </div>

      <p class="muted sub">
        Read-only through Core's IBKR connection; Jarvis only reads, it never places orders.
      </p>

      <TradingStatus :state="ibkr" what="the IBKR portfolio" @retry="refreshIbkr" v-slot="{ data, empty }">
        <p class="muted">Last sync {{ asOfText(data.as_of) }}.</p>
        <p v-if="skippedText(data)" class="muted" role="note">{{ skippedText(data) }}</p>
        <p v-if="empty" class="muted">No open positions in your IBKR account.</p>
        <ul v-else class="holding-list" style="margin-top: 14px">
          <li v-for="p in data.positions" :key="p.conid" class="holding-head">
            <span class="sym">{{ p.symbol }}</span>
            <span class="muted">{{ formatDecimal(p.quantity, { maxPlaces: 8 }) }} @ {{ formatPrice(p.last_price, p.currency) }}</span>
            <span class="cost mono">{{ formatMoney(p.market_value, data.base_currency) }}</span>
          </li>
        </ul>
      </TradingStatus>
    </div>
  </section>
  </div>
</template>

<style scoped>
.trading-page { position: relative; min-height: 100%; padding-bottom: 96px; }
.trading { position: relative; z-index: 1; width: auto; max-width: 980px; margin: 0 auto; padding: 28px 24px 0; }
.desk-top { margin-bottom: 18px; }
.sub { margin: 6px 0 0; font-size: 13px; }

.glass {
  background: var(--panel-bg);
  border: 1px solid var(--line-a30);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

.tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 18px; }
.kpi {
  border-radius: 12px; padding: 12px 14px;
  display: flex; flex-direction: column; gap: 6px;
}
.t-k { font-family: var(--mono); font-size: 10px; letter-spacing: 0.16em; color: var(--muted); }
.t-v { font-size: 26px; font-weight: 700; color: var(--text-primary); font-variant-numeric: tabular-nums; }
.t-v.small { font-size: 15px; display: inline-flex; align-items: center; gap: 8px; }
.mono { font-family: var(--mono); }

.seg {
  display: inline-flex; gap: 4px; padding: 4px; margin-bottom: 16px;
  border-radius: 14px;
  background: var(--bg-surface);
  border: 1px solid var(--line-a30);
}
.seg a {
  text-decoration: none; color: var(--muted);
  padding: 8px 18px; border-radius: 10px; font-size: 13px; font-weight: 600; cursor: pointer;
}
.seg a:hover { color: var(--text); }
.seg a.on {
  color: var(--accent);
  background: rgba(var(--accent-rgb), 0.1);
  box-shadow: inset 0 0 0 1.5px var(--accent), 0 0 14px rgba(var(--accent-rgb), 0.35);
}

.panel { border-radius: 14px; padding: 16px 18px 18px; }
.ib-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.ib-head .badge { margin: 0; }
.ghost {
  background: transparent; border: 1px solid var(--border); color: var(--muted);
  font-family: var(--mono); font-size: 12px;
}
.ghost:hover { color: var(--accent-2); border-color: var(--accent-2); filter: none; }

@media (max-width: 560px) {
  .tiles { grid-template-columns: 1fr; }
}
</style>
