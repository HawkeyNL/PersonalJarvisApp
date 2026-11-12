<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import ActivityState from "../activity/ActivityState.vue";
import { loadOptional } from "../../coreStatus";
import { formatUptime, type Availability } from "../../hubModel";
import { resourceMeters, type Disk, type LiveHost } from "../../nodeModels";

// Live CPU, memory and disk use of the Home Node. Vitals refresh every
// 5 seconds while the page is visible; disks come with the services read.
type Registry = { live_host?: LiveHost };
const registry = ref<Availability<Registry>>({ state: "loading" });
const services = ref<Availability<{ disks: Disk[] }>>({ state: "loading" });
const meters = computed(() => registry.value.state === "ok"
  ? resourceMeters(registry.value.value.live_host, services.value.state === "ok" ? services.value.value.disks : [])
  : []);

async function loadVitals() {
  const next = await loadOptional<Registry>("/v1/system/registry");
  // A failed poll keeps the last reading on screen.
  if (next.state === "ok" || registry.value.state !== "ok") registry.value = next;
}
async function load() {
  registry.value = { state: "loading" };
  const [, disks] = await Promise.all([loadVitals(), loadOptional<{ disks: Disk[] }>("/v1/system/services")]);
  services.value = disks;
}

let timer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
async function poll() {
  if (disposed) return;
  if (document.visibilityState === "visible") await loadVitals();
  if (!disposed) timer = setTimeout(poll, 5000);
}
onMounted(async () => {
  await load();
  if (!disposed) timer = setTimeout(poll, 5000);
});
onUnmounted(() => {
  disposed = true;
  clearTimeout(timer);
});
</script>

<template>
  <section class="card col" aria-labelledby="resources-heading">
    <div class="head">
      <h2 id="resources-heading">Resources</h2>
      <p v-if="registry.state === 'ok' && registry.value.live_host" class="t-sm t-2">
        Up {{ formatUptime(registry.value.live_host.uptime_seconds) }} · refreshes every 5 seconds
      </p>
    </div>
    <ActivityState nested :state="registry" what="the Home Node resources" @retry="load">
      <p v-if="!meters.length" class="t-sm t-2">This Core reports no live load. Update Core to see CPU, memory and disks.</p>
      <ul v-else class="meters">
        <li v-for="m in meters" :key="m.id">
          <div class="mrow t-sm">
            <span>{{ m.label }}</span>
            <span class="num t-2">{{ m.detail }}</span>
          </div>
          <div class="meter" role="img" :aria-label="m.percent == null ? `${m.label}: ${m.detail}` : `${m.label}: ${m.percent}% used`">
            <div class="fill" :class="m.tone" :style="{ width: (m.percent ?? 0) + '%' }"></div>
          </div>
        </li>
      </ul>
      <p v-if="services.state !== 'ok' && services.state !== 'loading'" class="t-xs t-2">Disk space is unavailable right now.</p>
    </ActivityState>
  </section>
</template>

<style scoped>
.col { display: flex; flex-direction: column; gap: 14px; }
.head { display: flex; flex-direction: column; gap: 4px; }
.card p { margin: 0; }
.meters { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 14px; }
.mrow { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 6px; }
.meter { height: 6px; border-radius: var(--r-pill); background: var(--bg-sunken); overflow: hidden; }
.meter .fill { height: 100%; background: var(--accent-solid); }
.meter .fill.warn { background: var(--negative-solid); }
.meter .fill.error { background: var(--danger-solid); }
@media (prefers-reduced-motion: no-preference) {
  .meter .fill { transition: width var(--d-enter) var(--ease-out); }
}
</style>
