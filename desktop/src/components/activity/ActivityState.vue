<script setup lang="ts" generic="T">
import NavIcon from "../NavIcon.vue";
import { failureText, type Availability } from "../../hubModel";

// Every Activity read renders through this: a skeleton while loading, an older
// Core, or an error with "Try again"; the slot gets the data once it is there.
// `nested`: inside a card that has its own h2, so no card and an h3.
defineProps<{ state: Availability<T>; what: string; forbidden?: string; nested?: boolean }>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
  <div v-if="state.state === 'loading'" :class="nested ? 'act-state nested' : 'card act-state'" role="status">
    <span class="sr-only">Loading {{ what }}…</span>
    <span class="skel" style="width: 40%; height: 14px" aria-hidden="true"></span>
    <span class="skel" style="width: 70%; height: 24px" aria-hidden="true"></span>
    <span class="skel" style="width: 100%; height: 14px" aria-hidden="true"></span>
    <span class="skel" style="width: 85%; height: 14px" aria-hidden="true"></span>
  </div>
  <div v-else-if="state.state === 'unsupported'" :class="nested ? 'act-state nested' : 'card act-state'" role="status">
    <div class="head">
      <span class="tile" aria-hidden="true"><NavIcon name="restart" /></span>
      <component :is="nested ? 'h3' : 'h2'" class="act-title">Requires a newer Core</component>
    </div>
    <p class="t-sm t-2">Update Core on your Home Node to see {{ what }} here.</p>
  </div>
  <div v-else-if="state.state === 'error'" :class="nested ? 'act-state nested' : 'card act-state'" role="alert">
    <div class="head">
      <span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span>
      <component :is="nested ? 'h3' : 'h2'" class="act-title">Could not load {{ what }}</component>
    </div>
    <p class="t-sm t-2">{{ failureText(state.reason, forbidden) }}</p>
    <div><button type="button" class="btn btn-secondary btn-sm" @click="emit('retry')">Try again</button></div>
  </div>
  <slot v-else :data="state.value" />
</template>

<style scoped>
.act-state { display: flex; flex-direction: column; gap: 12px; }
.head { display: flex; align-items: center; gap: 10px; min-width: 0; }
.act-title { margin: 0; font-size: 15px; line-height: 1.4; overflow-wrap: anywhere; }
.nested { padding: 16px; border-radius: var(--r-md); background: var(--bg-sunken); }
p { margin: 0; max-width: 64ch; }
.tile :deep(svg) { width: 20px; height: 20px; }
.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
</style>
