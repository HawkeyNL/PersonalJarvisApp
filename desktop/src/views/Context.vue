<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvPanel from "../components/jv/JvPanel.vue";
import JvTile from "../components/jv/JvTile.vue";
import JvActivityList, { type ActivityItem } from "../components/jv/JvActivityList.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";
import { loadOptional } from "../coreStatus";
import { currentAuthStatus } from "../auth";
import { relativeTime, summarize, type Availability } from "../hubModel";

// Context: only the linked devices are real today. Modes, sources and the
// schedule need Core support that does not exist yet.
type Device = { id: string; name: string; platform: string; status: string; created_at?: number };
const devices = ref<Availability<{ devices: Device[] }>>({ state: "loading" });
const thisDevice = ref<string | null>(null);
onMounted(async () => {
  devices.value = await loadOptional<{ devices: Device[] }>("/v1/devices");
  try { thisDevice.value = (await currentAuthStatus()).device_id; } catch { /* browser preview */ }
});

const deviceSummary = computed(() => summarize(devices.value, ({ devices: list }) => ({
  lines: [`${list.length} linked`, ""],
  tone: list.length ? "ok" : "idle",
})));

const planned = (id: string, label: string, title: string, icon: NodeItem["icon"], description: string): NodeItem =>
  ({ id, label, title, icon, description, tone: "idle", status: "Not yet available" });
const items = computed<NodeItem[]>(() => [
  planned("work", "Work", "Work mode", "briefcase", "Focus on projects and code"),
  planned("focus", "Focus", "Focus mode", "moon", "Only what really matters"),
  planned("home", "Home", "Home mode", "home", "Personal life, no work pings"),
  planned("sources", "Sources", "Sources", "link-2", "Where context comes from"),
  planned("schedule", "Schedule", "Schedule", "calendar", "When each mode switches on"),
  { id: "devices", label: "Devices", title: "Devices", icon: "monitor", description: "Desktop and mobile app",
    tone: deviceSummary.value.tone, status: deviceSummary.value.lines[0] },
]);
const selected = ref("devices");
const current = computed(() => items.value.find((item) => item.id === selected.value)!);

const list = computed(() => (devices.value.state === "ok" ? devices.value.value.devices : []));
const platforms = computed(() => [...new Set(list.value.map((d) => d.platform))].join(", ") || "—");
const mine = computed(() => list.value.find((d) => d.id === thisDevice.value)?.name ?? "Unknown");
const activity = computed<ActivityItem[]>(() => list.value.slice(0, 5).map((d) => ({
  id: d.id,
  icon: /ios|android/i.test(d.platform) ? "phone" : "monitor",
  title: d.id === thisDevice.value ? `${d.name} (this device)` : d.name,
  detail: d.platform,
  time: d.created_at ? `linked ${relativeTime(new Date(d.created_at * 1000).toISOString(), Date.now())}` : undefined,
  tone: d.status === "active" ? "ok" : "idle",
  toneLabel: d.status,
})));
</script>

<template>
  <NodePage v-model="selected" title="CONTEXT" subtitle="WHAT JARVIS PAYS ATTENTION TO" :items="items">
    <JvPanel :icon="current.icon" :title="current.title" :tone="current.tone" :status="current.status" :description="current.description">
      <div v-if="current.id === 'devices'" class="split">
        <JvUnavailable v-if="devices.state === 'unsupported' || devices.state === 'error'" title="Devices"
          :kind="devices.state === 'error' ? 'error' : 'core-update'" icon="monitor"
          detail="Sign in and check the connection to your Home Node." />
        <template v-else>
          <div class="tiles">
            <JvTile icon="monitor" label="Linked devices" :value="devices.state === 'ok' ? String(list.length) : 'Loading…'" />
            <JvTile icon="user" label="This device" :value="mine" />
            <JvTile icon="layers" label="Platforms" :value="platforms" />
            <JvTile icon="shield" label="Sign-in" value="Device-bound keys" />
          </div>
          <JvActivityList title="Devices" :items="activity" empty="No devices linked yet." />
        </template>
      </div>
      <JvUnavailable v-else :title="current.title" :icon="current.icon"
        detail="Context modes, sources and schedules need Core support that does not exist yet. Nothing is shown until it does." />
    </JvPanel>
  </NodePage>
</template>

<style scoped>
.split { display: grid; grid-template-columns: minmax(0, 544px) minmax(0, 1fr); gap: 20px; }
.tiles { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 14px; align-content: start; padding-right: 17px; border-right: 1px solid var(--line-a30); }
@media (max-width: 1099px), (max-height: 759px) {
  .split { grid-template-columns: minmax(0, 1fr); }
  .tiles { padding-right: 0; border-right: none; }
}
</style>
