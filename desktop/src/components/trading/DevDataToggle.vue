<script setup lang="ts">
import { DATA_SETS, devDataSet, setDevDataSet, type DataSet } from "../../tradingApi";

// Dev builds only: swap the trading screens' data at the API boundary.
// Plain chrome on purpose, so it never reads as part of the design.
withDefaults(defineProps<{ sets?: readonly DataSet[] }>(), { sets: () => DATA_SETS });
const dev = import.meta.env.DEV;
const LABEL: Record<DataSet, string> = {
  live: "Live", demo: "Demo data", worst: "Worst case", empty: "Empty", stale: "Stale", offline: "Not connected", loading: "Loading",
  disabled: "Off", paused: "Paused",
};
</script>

<template>
  <div v-if="dev" class="dev-toggle" role="group" aria-label="Dev data (not in production builds)">
    <button v-for="set in sets" :key="set" type="button" :aria-pressed="devDataSet === set" @click="setDevDataSet(set)">
      {{ LABEL[set] }}
    </button>
  </div>
</template>

<style scoped>
.dev-toggle {
  position: fixed; left: 50%; bottom: 16px; transform: translateX(-50%); z-index: 50;
  display: flex; flex-wrap: wrap; justify-content: center; gap: 2px; max-width: calc(100vw - 32px);
  padding: 3px; border-radius: 10px; background: #d4d4d8; font: 12px system-ui, sans-serif;
}
button {
  border: 0; border-radius: 7px; padding: 5px 10px; background: transparent; color: #27272a;
  font: inherit; cursor: pointer;
}
button[aria-pressed="true"] { background: #fff; box-shadow: 0 1px 2px rgb(0 0 0 / 0.2); }
</style>
