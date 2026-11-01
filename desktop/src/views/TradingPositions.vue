<script setup lang="ts">
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import TradingStatus from "../components/trading/TradingStatus.vue";
import "../components/trading/trading.css";
import { asOfText, readPortfolio, formatDecimal, formatMoney, formatPrice, signTone, sourceLabel, type Portfolio } from "../trading";
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
  <div class="td-page">
    <JvBackdrop glow-y="20%" horizon="80px" />
    <JvTopBar variant="node" title="POSITIONS" subtitle="WHAT IS OPEN AT IBKR" />
    <section class="td-wrap">
      <header class="td-head">
        <div>
          <p class="td-crumb"><RouterLink to="/trading">Trading</RouterLink></p>
          <h1>Positions</h1>
          <p v-if="state.kind === 'data' || state.kind === 'stale'" class="td-sub">
            {{ sourceLabel(state.data) }} · read-only · as of {{ asOfText(state.data.as_of) }}
          </p>
        </div>
      </header>

      <TradingStatus :state="state" what="your positions" @retry="reload">
        <template #default="{ data, empty }">
          <section class="td-card tight" aria-labelledby="pos-open">
            <div class="td-card-head">
              <h2 id="pos-open">Open positions</h2>
              <span class="td-small">{{ data.positions.length ? `${data.positions.length} open` : "Nothing open" }}</span>
            </div>
            <p v-if="empty" class="td-empty">No open positions in your IBKR account.</p>
            <div v-else class="td-scroll">
              <table class="td-table">
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
                    <td><span class="td-badge">{{ p.asset_class }}</span><span v-if="p.exchange" class="td-small exch">{{ p.exchange }}</span></td>
                    <td class="r">{{ formatDecimal(p.quantity, { maxPlaces: 8 }) }}</td>
                    <td class="r">{{ formatPrice(p.avg_cost, p.currency) }}</td>
                    <td class="r">{{ formatPrice(p.last_price, p.currency) }}</td>
                    <td class="r">{{ formatMoney(p.market_value, data.base_currency) }}</td>
                    <td class="r" :class="`td-${signTone(p.unrealized_pnl)}`">{{ formatMoney(p.unrealized_pnl, data.base_currency, { sign: true }) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </template>
      </TradingStatus>

      <section class="td-card" aria-labelledby="pos-orders">
        <div class="td-card-head"><h2 id="pos-orders">Open orders</h2><span class="td-badge">Not available</span></div>
        <p class="td-note">The IBKR connection is read-only, and Core does not report orders yet. Orders come with trading, behind risk checks and your confirmation.</p>
      </section>
    </section>
    <DevDataToggle />
  </div>
</template>

<style scoped>
.exch { display: block; margin-top: 4px; }
</style>
