<script setup lang="ts" generic="T">
import NavIcon from "../NavIcon.vue";
import { ageText, errorText, notConnectedText, type TradingState } from "../../trading";

// Every trading read renders through this: loading, not connected (with what
// to do), error, and a stale banner above data. Self-contained styles: it is
// also used outside the trading pages (Integrations). The slot gets the data and
// whether it is empty; each screen words its own empty state.
defineProps<{ state: TradingState<T>; what: string; compact?: boolean }>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
  <div v-if="state.kind === 'loading'" class="td-col td-state" :class="{ card: !compact, compact }" role="status">
    <span class="sr-only">Loading {{ what }}…</span>
    <span class="skel" style="width: 40%; height: 14px" aria-hidden="true"></span>
    <span class="skel" style="width: 70%; height: 28px" aria-hidden="true"></span>
    <template v-if="!compact">
      <span class="sep" aria-hidden="true"></span>
      <span class="skel" style="width: 100%; height: 14px" aria-hidden="true"></span>
      <span class="skel" style="width: 92%; height: 14px" aria-hidden="true"></span>
      <span class="skel" style="width: 85%; height: 14px" aria-hidden="true"></span>
    </template>
  </div>
  <div v-else-if="state.kind === 'not-connected'" class="td-col td-state" :class="{ card: !compact, compact }" role="status">
    <div class="td-title">
      <span class="tile" aria-hidden="true"><NavIcon name="trend" /></span>
      <component :is="compact ? 'h3' : 'h2'" class="title">{{ notConnectedText(state.reason).title }}</component>
    </div>
    <p v-if="!compact" class="t-sm t-2 detail">{{ notConnectedText(state.reason).detail }}</p>
    <div class="actions">
      <button type="button" class="btn btn-secondary btn-sm" @click="emit('retry')">Try again</button>
      <RouterLink v-if="state.reason !== 'unreachable'" to="/trading/ibkr" class="t-sm link">IBKR status</RouterLink>
    </div>
  </div>
  <div v-else-if="state.kind === 'error'" class="td-col td-state" :class="{ card: !compact, compact }" role="alert">
    <div class="td-title">
      <span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span>
      <component :is="compact ? 'h3' : 'h2'" class="title">Could not load {{ what }}</component>
    </div>
    <p class="t-sm t-2 detail">{{ errorText(state.reason) }}</p>
    <div class="actions">
      <button type="button" class="btn btn-secondary btn-sm" @click="emit('retry')">Try again</button>
    </div>
  </div>
  <template v-else>
    <div v-if="state.kind === 'stale'" class="td-banner warn stale" role="status">
      <span class="t-sm"><strong>Prices are {{ ageText(state.ageMs) }} old.</strong> Core got no newer data from IBKR; values may be off.</span>
      <button type="button" class="btn btn-secondary btn-sm" @click="emit('retry')">Try again</button>
    </div>
    <slot :data="state.data" :empty="state.kind === 'empty' || (state.kind === 'stale' && state.empty)" />
  </template>
</template>

<style scoped>
/* Compact: inside a card that already has its own padding. */
h2, h3, p { margin: 0; }
.title { font-size: 15px; line-height: 1.4; font-weight: 600; letter-spacing: 0; color: var(--text-primary); }
.link { color: var(--accent-text); text-underline-offset: 3px; }
.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
/* Trading pages space the banner with their flex gap; elsewhere it needs its own. */
.stale { margin-bottom: 16px; }
.td-page .stale { margin-bottom: 0; }
.td-state.compact { padding: 4px 0; }
.sep { height: 1px; background: var(--separator); }
.tile :deep(svg) { width: 20px; height: 20px; }
.detail { max-width: 64ch; line-height: 1.55; }
.actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
</style>
