<script setup lang="ts" generic="T">
import NavIcon from "../NavIcon.vue";
import { ageText, errorText, notConnectedText, type TradingState } from "../../trading";

// Every trading read renders through this: loading, not connected (with what
// to do), error, and a stale banner above data. The slot gets the data and
// whether it is empty; each screen words its own empty state.
defineProps<{ state: TradingState<T>; what: string; compact?: boolean }>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
  <div v-if="state.kind === 'loading'" class="td-skel" :class="compact ? 'compact' : 'card'" role="status">
    <span class="sr-only">Loading {{ what }}…</span>
    <span class="skel line short" aria-hidden="true"></span>
    <span class="skel big" aria-hidden="true"></span>
    <span v-if="!compact" class="skel line" aria-hidden="true"></span>
  </div>
  <div v-else-if="state.kind === 'not-connected'" class="td-state" :class="{ compact }" role="status">
    <span class="icon" aria-hidden="true"><NavIcon name="trend" /></span>
    <div class="text">
      <p class="title">{{ notConnectedText(state.reason).title }}</p>
      <p v-if="!compact" class="detail">{{ notConnectedText(state.reason).detail }}</p>
      <p class="actions">
        <RouterLink v-if="state.reason !== 'unreachable'" to="/trading/ibkr" class="ghost-btn sm">IBKR status</RouterLink>
        <button type="button" class="ghost-btn sm" @click="emit('retry')">Try again</button>
      </p>
    </div>
  </div>
  <div v-else-if="state.kind === 'error'" class="td-state" :class="{ compact }" role="alert">
    <span class="text">Could not load {{ what }}. {{ errorText(state.reason) }}</span>
    <button type="button" class="ghost-btn sm" @click="emit('retry')">Try again</button>
  </div>
  <template v-else>
    <div v-if="state.kind === 'stale'" class="td-stale" role="status">
      <span><strong>Prices are {{ ageText(state.ageMs) }} old.</strong> Core got no newer data from IBKR; values may be off.</span>
      <button type="button" class="ghost-btn sm" @click="emit('retry')">Try again</button>
    </div>
    <slot :data="state.data" :empty="state.kind === 'empty' || (state.kind === 'stale' && state.empty)" />
  </template>
</template>

<style scoped>
.td-state {
  display: flex; align-items: flex-start; gap: 14px; flex-wrap: wrap; padding: 18px 20px;
  border: 1px dashed var(--border); border-radius: var(--r-12); background: var(--bg-surface);
  color: var(--text-secondary); font-size: var(--fs-13);
}
.td-state.compact { padding: 12px 14px; }
.td-state[role="alert"] { align-items: center; justify-content: space-between; }
.td-skel { display: flex; flex-direction: column; gap: 10px; padding: 18px 20px; }
.td-skel.compact { padding: 12px 14px; }
.skel.line { width: 92%; height: 14px; }
.skel.line.short { width: 40%; }
.skel.big { width: 70%; height: 28px; }
.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
.icon {
  flex: none; width: 40px; height: 40px; display: grid; place-items: center; border-radius: 50%;
  border: 1.5px solid var(--border); color: var(--text-secondary);
}
.icon :deep(svg) { width: 20px; height: 20px; }
.text { flex: 1 1 240px; min-width: 0; }
p { margin: 0; }
.title { font-weight: 500; color: var(--text-primary); }
.detail { margin-top: 6px; line-height: 1.55; max-width: 64ch; }
.actions { margin-top: 12px; display: flex; gap: 8px; flex-wrap: wrap; }
.td-stale {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  margin-bottom: 16px; padding: 12px 16px; border-radius: var(--r-12);
  background: var(--negative-subtle); color: var(--negative-text); font-size: var(--fs-13);
}
.td-stale strong { font-weight: 600; }
</style>
