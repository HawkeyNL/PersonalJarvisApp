<script setup lang="ts">
import PageShell from "../components/PageShell.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import TradingStatus from "../components/trading/TradingStatus.vue";
import "../components/trading/trading.css";
import { asOfText, readPortfolio, formatDecimal, formatMoney, formatPrice, signTone, skippedText, sourceLabel, type Portfolio } from "../trading";
import { tradingApi, useTradingRead } from "../tradingApi";

// Every open position in the IBKR account, all asset classes. Orders are not
// part of the read-only connection, so that section says so.
const { state, reload } = useTradingRead<Portfolio>({
  live: tradingApi.portfolio,
  fixture: (f, set) => f.portfolioFixture(set),
  read: readPortfolio,
  isEmpty: (p) => p.positions.length === 0,
});
</script>

<template>
  <PageShell class="td-page" title="Positions">
    <template #crumb><p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink></p></template>
    <template #subtitle>
      <p v-if="state.kind === 'data' || state.kind === 'stale' || state.kind === 'empty'" class="td-sub">
        {{ sourceLabel(state.data) }} · read-only · as of {{ asOfText(state.data.as_of) }}
      </p>
    </template>

    <TradingStatus :state="state" what="your positions" @retry="reload">
      <template #default="{ data, empty }">
        <section class="card td-tight" aria-labelledby="pos-open">
          <div class="td-card-head">
            <h2 id="pos-open">Open positions</h2>
            <span class="t-sm t-2 num">{{ data.positions.length ? `${data.positions.length} open` : "Nothing open" }}</span>
          </div>
          <p v-if="skippedText(data)" class="td-note" role="note">{{ skippedText(data) }}</p>
          <div v-if="empty" class="empty">
            <h3>No open positions</h3>
            <p class="t-sm">Your IBKR account has no open positions.</p>
          </div>
          <div v-else class="td-scroll" tabindex="0" role="region" aria-label="Open positions table">
            <table class="tbl" style="min-width: 860px">
              <thead>
                <tr><th scope="col">Instrument</th><th scope="col">Class</th><th scope="col" class="r">Quantity</th><th scope="col" class="r">Avg. cost</th><th scope="col" class="r">Last</th><th scope="col" class="r">Value</th><th scope="col" class="r">Result</th></tr>
              </thead>
              <tbody>
                <tr v-for="p in data.positions" :key="p.conid">
                  <td>
                    <RouterLink v-if="p.asset_class === 'STK' || p.asset_class === 'ETF'" :to="`/trading/stocks/${p.conid}`" class="td-inst">
                      <span class="sym">{{ p.symbol }}</span><span v-if="p.name" class="name">{{ p.name }}</span>
                    </RouterLink>
                    <span v-else class="td-inst"><span class="sym">{{ p.symbol }}</span><span v-if="p.name" class="name">{{ p.name }}</span></span>
                  </td>
                  <td><span class="badge">{{ p.asset_class }}</span><span v-if="p.exchange" class="t-xs t-2 exch">{{ p.exchange }}</span></td>
                  <td class="r mono t-sm">{{ formatDecimal(p.quantity, { maxPlaces: 8 }) }}</td>
                  <td class="r mono t-sm">{{ formatPrice(p.avg_cost, p.currency) }}</td>
                  <td class="r mono t-sm">{{ formatPrice(p.last_price, p.currency) }}</td>
                  <td class="r">{{ formatMoney(p.market_value, data.base_currency) }}</td>
                  <td class="r result" :class="`td-${signTone(p.unrealized_pnl)}`">{{ formatMoney(p.unrealized_pnl, data.base_currency, { sign: true }) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </TradingStatus>

    <section class="card td-col" aria-labelledby="pos-orders">
      <div class="td-card-head"><h2 id="pos-orders">Open orders</h2><span class="badge">Not available</span></div>
      <p class="t-sm t-2">The IBKR connection is read-only, and Core does not report orders yet. Orders come with trading, behind risk checks and your confirmation.</p>
    </section>
    <DevDataToggle />
  </PageShell>
</template>

<style scoped>
.exch { display: block; margin-top: 4px; }
.result { font-weight: 600; }
</style>
