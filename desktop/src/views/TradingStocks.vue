<script setup lang="ts">
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import TradingStatus from "../components/trading/TradingStatus.vue";
import "../components/trading/trading.css";
import {
  asOfText, chartNumber, readPortfolio, decimalSign, formatDecimal, formatMoney, formatPercent, signTone, sourceLabel,
  type AllocationTarget, type Portfolio, type Position,
} from "../trading";
import { tradingApi, useTradingRead } from "../tradingApi";

// Stocks pot: holdings, allocation against the owner's targets, the monthly
// deposit and dividends. Everything comes from GET /v1/trading/portfolio.
const { state, reload } = useTradingRead<Portfolio>({
  live: tradingApi.portfolio,
  fixture: (f, set) => f.portfolioFixture(set),
  read: readPortfolio,
  isEmpty: (p) => p.positions.length === 0 && decimalSign(p.net_liquidation) === 0,
});

const STOCK_CLASSES = new Set(["STK", "ETF"]);
const KIND: Record<string, string> = { STK: "Stock", ETF: "ETF" };
const stocks = (p: Portfolio): Position[] => p.positions.filter((x) => STOCK_CLASSES.has(x.asset_class));

/** Bar width and goal mark, clamped to the track. */
const pct = (v: string) => `${Math.min(100, Math.max(0, chartNumber(v) ?? 0))}%`;
function offTarget(a: AllocationTarget): { text: string; over: boolean } | null {
  const now = chartNumber(a.current_pct);
  const goal = chartNumber(a.target_pct);
  if (now === null || goal === null || Math.abs(now - goal) < 2) return null;
  const pts = Math.round(Math.abs(now - goal));
  return { text: `${pts} pt ${now > goal ? "above" : "below"} target`, over: now > goal };
}
const day = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleDateString("en", { day: "numeric", month: "short" });
};
</script>

<template>
  <PageShell class="td-page" title="Stocks">
    <template #crumb><p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink></p></template>
    <template #subtitle>
      <p v-if="state.kind === 'data' || state.kind === 'stale' || state.kind === 'empty'" class="td-sub">
        {{ sourceLabel(state.data) }} · read-only · as of {{ asOfText(state.data.as_of) }}
      </p>
    </template>

    <TradingStatus :state="state" what="your holdings" @retry="reload">
      <template #default="{ data, empty }">
        <div v-if="empty" class="card empty">
          <span class="tile acc" aria-hidden="true"><NavIcon name="stocks" /></span>
          <h3>No holdings yet</h3>
          <p class="t-sm">Your IBKR account is connected but holds nothing. Holdings appear here after your first purchase.</p>
        </div>
        <div v-else class="td-col stack">
          <div class="td-kpis">
            <div class="card td-kpi">
              <span class="t-sm t-2">Value</span>
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
              <span class="t-sm t-2">Dividends, 12 months</span>
              <span class="big">{{ formatMoney(data.dividends_12m, data.base_currency) }}</span>
              <span class="t-xs t-2">{{ data.dividends_12m ? "Received" : "Not reported by Core" }}</span>
            </div>
          </div>

          <div class="td-grid">
            <section class="card td-col td-wide alloc" aria-labelledby="stocks-alloc">
              <div class="td-card-head"><h2 id="stocks-alloc">Allocation and your target</h2><span class="badge acc">Calculation</span></div>
              <template v-if="data.allocation?.length">
                <div v-for="a in data.allocation" :key="a.label" class="alloc-row">
                  <div class="td-card-head t-sm">
                    <span class="alloc-label">{{ a.label }}</span>
                    <span class="alloc-nums">
                      <span class="mono num">{{ formatDecimal(a.current_pct, { places: 0 }) }}%</span>
                      <span class="t-2">target {{ formatDecimal(a.target_pct, { places: 0 }) }}%</span>
                      <span v-if="offTarget(a)" class="badge" :class="offTarget(a)!.over ? 'neg' : 'acc'">{{ offTarget(a)!.text }}</span>
                    </span>
                  </div>
                  <div class="td-meter lg" aria-hidden="true">
                    <i :class="{ over: offTarget(a)?.over }" :style="{ width: pct(a.current_pct) }"></i>
                    <b :style="{ left: pct(a.target_pct) }"></b>
                  </div>
                </div>
                <p class="td-note">The mark is your target. Computed by code from your IBKR positions; Jarvis never sells just to rebalance.</p>
              </template>
              <p v-else class="t-sm t-2">No targets set yet. Core reports targets once you set them for the stocks pot.</p>
            </section>

            <section class="card td-col" aria-labelledby="stocks-deposit">
              <div class="td-card-head"><h2 id="stocks-deposit">Monthly deposit</h2></div>
              <template v-if="data.monthly_deposit">
                <div class="deposit">
                  <span class="big">{{ formatMoney(data.monthly_deposit.amount, data.base_currency) }}</span>
                  <span class="t-sm t-2">every month on day {{ data.monthly_deposit.day_of_month }}</span>
                </div>
                <p class="td-note">Next deposit: {{ day(data.monthly_deposit.next_date) }}.</p>
              </template>
              <p v-else class="t-sm t-2">No monthly deposit set.</p>
            </section>
          </div>

          <section class="card td-tight" aria-labelledby="stocks-holdings">
            <div class="td-card-head"><h2 id="stocks-holdings">Holdings</h2><span class="t-sm t-2">{{ stocks(data).length }} {{ stocks(data).length === 1 ? "holding" : "holdings" }}</span></div>
            <div v-if="stocks(data).length" class="td-scroll" tabindex="0" role="region" aria-label="Holdings table">
              <table class="tbl" style="min-width: 760px">
                <thead>
                  <tr><th scope="col">Instrument</th><th scope="col">Type</th><th scope="col" class="r">Quantity</th><th scope="col" class="r">Value</th><th scope="col" class="r">Today</th><th scope="col" class="r">Total</th><th scope="col" class="r">Share</th></tr>
                </thead>
                <tbody>
                  <tr v-for="p in stocks(data)" :key="p.conid">
                    <td>
                      <RouterLink :to="`/trading/stocks/${p.conid}`" class="td-inst">
                        <span class="sym">{{ p.symbol }}</span><span v-if="p.name" class="name">{{ p.name }}</span>
                      </RouterLink>
                    </td>
                    <td><span class="badge">{{ KIND[p.asset_class] ?? p.asset_class }}</span></td>
                    <td class="r">{{ formatDecimal(p.quantity, { maxPlaces: 8 }) }}</td>
                    <td class="r">{{ formatMoney(p.market_value, data.base_currency) }}</td>
                    <td class="r" :class="`td-${signTone(p.day_change_pct)}`">{{ formatPercent(p.day_change_pct) }}</td>
                    <td class="r" :class="`td-${signTone(p.unrealized_pnl)}`">{{ formatMoney(p.unrealized_pnl, data.base_currency, { sign: true }) }}</td>
                    <td class="r">{{ p.weight_pct ? `${formatDecimal(p.weight_pct, { places: 1 })}%` : "—" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="empty">
              <h3>No stocks or ETFs</h3>
              <p class="t-sm">This account holds no stocks or ETFs. Other positions are under <RouterLink to="/trading/positions">Positions</RouterLink>.</p>
            </div>
          </section>

          <section class="card td-col" aria-labelledby="stocks-dividends">
            <h2 id="stocks-dividends">Coming dividends</h2>
            <ul v-if="data.upcoming_dividends?.length" class="dividends">
              <li v-for="d in data.upcoming_dividends" :key="`${d.conid}-${d.ex_date}`">
                <span class="date mono t-sm t-2">{{ day(d.ex_date) }}</span>
                <span class="text t-sm"><strong class="mono">{{ d.symbol }}</strong> ex-dividend<template v-if="d.pay_date">, paid {{ day(d.pay_date) }}</template><template v-if="d.amount">, about {{ formatMoney(d.amount, d.currency) }}</template></span>
                <span class="badge" :class="{ acc: d.amount }">{{ d.amount ? "Calculation" : "Fact" }}</span>
              </li>
            </ul>
            <p v-else class="t-sm t-2">No dividends announced for your holdings.</p>
          </section>
        </div>
      </template>
    </TradingStatus>
    <DevDataToggle />
  </PageShell>
</template>

<style scoped>
/* The page gap between sections inside the status slot. */
.stack { gap: 20px; }
.alloc { gap: 16px; }
.alloc-row { display: flex; flex-direction: column; gap: 6px; }
.alloc-label { font-weight: 500; min-width: 0; overflow-wrap: anywhere; }
.alloc-nums { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.deposit { display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; }
.deposit .big { overflow-wrap: anywhere; }
.dividends { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.dividends li { display: flex; align-items: center; gap: 14px; min-height: 40px; flex-wrap: wrap; }
.dividends .date { flex: none; width: 64px; }
.dividends .text { flex: 1 1 200px; min-width: 0; overflow-wrap: anywhere; }
</style>
