<script setup lang="ts">
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import TradingStatus from "../components/trading/TradingStatus.vue";
import "../components/trading/trading.css";
import {
  asOfText, chartNumber, decimalSign, formatDecimal, formatMoney, formatPercent, signTone, sourceLabel,
  type AllocationTarget, type Portfolio, type Position,
} from "../trading";
import { isPortfolio, tradingApi, useTradingRead } from "../tradingApi";

// Stocks pot: holdings, allocation against the owner's targets, the monthly
// deposit and dividends. Everything comes from GET /v1/trading/portfolio.
const { state, reload } = useTradingRead<Portfolio>({
  live: tradingApi.portfolio,
  fixture: (f, set) => f.portfolioFixture(set),
  valid: isPortfolio,
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
  <div class="td-page">
    <JvBackdrop glow-y="20%" horizon="80px" />
    <JvTopBar variant="node" title="STOCKS" subtitle="YOUR IBKR HOLDINGS" />
    <section class="td-wrap">
      <header class="td-head">
        <div>
          <p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink></p>
          <h1>Stocks</h1>
          <p v-if="state.kind === 'data' || state.kind === 'stale'" class="td-sub">
            {{ sourceLabel(state.data) }} · read-only · as of {{ asOfText(state.data.as_of) }}
          </p>
        </div>
      </header>

      <TradingStatus :state="state" what="your holdings" @retry="reload">
        <template #default="{ data, empty }">
          <div v-if="empty" class="td-card td-empty">
            <h3>No holdings yet</h3>
            Your IBKR account is connected but holds nothing. Holdings appear here after your first purchase.
          </div>
          <template v-else>
            <div class="td-kpis">
              <div class="td-card td-kpi">
                <span class="td-label">Value</span>
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
                <span class="td-label">Dividends, 12 months</span>
                <span class="td-big">{{ formatMoney(data.dividends_12m, data.base_currency) }}</span>
                <span class="td-small">{{ data.dividends_12m ? "Received" : "Not reported by Core" }}</span>
              </div>
            </div>

            <div class="td-grid">
              <section class="td-card" aria-labelledby="stocks-alloc">
                <div class="td-card-head"><h2 id="stocks-alloc">Allocation and your target</h2><span class="td-badge acc">Calculation</span></div>
                <template v-if="data.allocation?.length">
                  <div v-for="a in data.allocation" :key="a.label">
                    <div class="td-card-head td-small" style="margin-bottom: 6px">
                      <span class="alloc-label">{{ a.label }}</span>
                      <span class="td-num">{{ formatDecimal(a.current_pct, { places: 0 }) }}% · target {{ formatDecimal(a.target_pct, { places: 0 }) }}%
                        <span v-if="offTarget(a)" class="td-badge" :class="offTarget(a)!.over ? 'neg' : 'acc'">{{ offTarget(a)!.text }}</span>
                      </span>
                    </div>
                    <div class="td-meter">
                      <i :class="{ over: offTarget(a)?.over }" :style="{ width: pct(a.current_pct) }"></i>
                      <b :style="{ left: pct(a.target_pct) }" aria-hidden="true"></b>
                    </div>
                  </div>
                  <p class="td-note">The mark is your target. Computed by code from your IBKR positions; Jarvis never sells just to rebalance.</p>
                </template>
                <p v-else class="td-note">No targets set yet. Core reports targets once you set them for the stocks pot.</p>
              </section>

              <section class="td-card" aria-labelledby="stocks-deposit">
                <div class="td-card-head"><h2 id="stocks-deposit">Monthly deposit</h2></div>
                <template v-if="data.monthly_deposit">
                  <span class="td-big">{{ formatMoney(data.monthly_deposit.amount, data.base_currency) }}</span>
                  <span class="td-small">Every month on day {{ data.monthly_deposit.day_of_month }} · next {{ day(data.monthly_deposit.next_date) }}</span>
                </template>
                <p v-else class="td-note">No monthly deposit set.</p>
              </section>
            </div>

            <section class="td-card tight" aria-labelledby="stocks-holdings">
              <div class="td-card-head"><h2 id="stocks-holdings">Holdings</h2><span class="td-small">{{ stocks(data).length }} {{ stocks(data).length === 1 ? "holding" : "holdings" }}</span></div>
              <div v-if="stocks(data).length" class="td-scroll">
                <table class="td-table">
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
                      <td><span class="td-badge">{{ KIND[p.asset_class] ?? p.asset_class }}</span></td>
                      <td class="r">{{ formatDecimal(p.quantity, { maxPlaces: 8 }) }}</td>
                      <td class="r">{{ formatMoney(p.market_value, data.base_currency) }}</td>
                      <td class="r" :class="`td-${signTone(p.day_change_pct)}`">{{ formatPercent(p.day_change_pct) }}</td>
                      <td class="r" :class="`td-${signTone(p.unrealized_pnl)}`">{{ formatMoney(p.unrealized_pnl, data.base_currency, { sign: true }) }}</td>
                      <td class="r">{{ p.weight_pct ? `${formatDecimal(p.weight_pct, { places: 1 })}%` : "—" }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-else class="td-empty">No stocks or ETFs in this account. Other positions are under Positions.</p>
            </section>

            <section class="td-card" aria-labelledby="stocks-dividends">
              <h2 id="stocks-dividends">Coming dividends</h2>
              <ul v-if="data.upcoming_dividends?.length" class="dividends">
                <li v-for="d in data.upcoming_dividends" :key="`${d.conid}-${d.ex_date}`">
                  <span class="date td-mono td-small">{{ day(d.ex_date) }}</span>
                  <span class="text"><strong class="td-mono">{{ d.symbol }}</strong> ex-dividend<template v-if="d.pay_date">, paid {{ day(d.pay_date) }}</template></span>
                  <span v-if="d.amount" class="td-num">≈ {{ formatMoney(d.amount, d.currency) }}</span>
                  <span class="td-badge" :class="{ acc: d.amount }">{{ d.amount ? "Calculation" : "Fact" }}</span>
                </li>
              </ul>
              <p v-else class="td-note">No dividends announced for your holdings.</p>
            </section>
          </template>
        </template>
      </TradingStatus>
    </section>
    <DevDataToggle />
  </div>
</template>

<style scoped>
.alloc-label { font-weight: 500; color: var(--text-primary); min-width: 0; overflow-wrap: anywhere; }
.dividends { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.dividends li { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; font-size: var(--fs-13); }
.dividends .date { flex: none; width: 56px; }
.dividends .text { flex: 1 1 200px; min-width: 0; overflow-wrap: anywhere; }
</style>
