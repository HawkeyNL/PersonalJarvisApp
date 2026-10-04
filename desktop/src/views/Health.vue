<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from "vue";
import { useRoute } from "vue-router";
import { getJson, getJsonAuth, postJsonAuth } from "../api";
import { currentAuthStatus } from "../auth";
import { homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import SystemUsageChart from "../components/SystemUsageChart.vue";
import ModelControls from "../components/ModelControls.vue";
import ModelRouting from "../components/ModelRouting.vue";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvPanel from "../components/jv/JvPanel.vue";
import JvSegmented from "../components/jv/JvSegmented.vue";
import JvTile from "../components/jv/JvTile.vue";
import JvActivityList, { type ActivityItem } from "../components/jv/JvActivityList.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";
import { loadOptional } from "../coreStatus";
import { formatUptime, relativeTime, type Availability, type Tone } from "../hubModel";
import { diskState, formatMs, measuredCount, NOT_MEASURED, serviceState, servicesSummary, type Disk } from "../nodeModels";

// System Health: the former System view (connection, live host, usage,
// model access, self-improvement) on the shared node layout.
type Health = { status: string; environment?: string };
type Check = "checking" | "ok" | "fout";

// --- Connection (/livez, /readyz) -------------------------------------------
const livez = ref<Check>("checking");
const readyz = ref<Check>("checking");
const environment = ref<string | null>(null);
const error = ref<string | null>(null);
const checkedAt = ref<Date | null>(null);

async function check() {
  livez.value = "checking";
  readyz.value = "checking";
  environment.value = null;
  error.value = null;
  try {
    await getJson<Health>("/livez");
    livez.value = "ok";
  } catch (e) {
    livez.value = "fout";
    error.value = String(e);
  }
  try {
    const r = await getJson<Health>("/readyz");
    readyz.value = "ok";
    environment.value = r.environment ?? null;
  } catch (e) {
    readyz.value = "fout";
    error.value = String(e);
  }
  checkedAt.value = new Date();
}
const checkLabel = (c: Check) => (c === "ok" ? "OK" : c === "fout" ? "Failed" : "Checking…");

// --- Host inventory, live host and usage ------------------------------------
// Model authorization is shown separately from the owner-controlled policy; a
// configured credential is not an enabled model.
interface SoftwareItem { name: string; present: boolean; version: string | null; detail: string | null }
interface HostInfo { os: string; arch: string; cpu: string; cpu_cores: number; mem_total_gb: number; gpu: string }
interface Registry {
  live_host?: { sampled_at: number; cpu_percent: number | null; memory_total_bytes: number; memory_used_bytes: number; uptime_seconds: number };
  host: HostInfo;
  software: SoftwareItem[];
}
interface Usage {
  budget_eur: number;
  spent_eur: number;
  remaining_eur: number;
  over_budget: boolean;
  requests?: number;
  input_tokens?: number;
  output_tokens?: number;
  cache_read_tokens?: number;
  total_tokens?: number;
  // Newer Cores add failure, fallback and latency aggregates; `null` means
  // Core does not measure that yet (never a measured zero).
  failures?: number | null;
  fallbacks?: number | null;
  latency_p50_ms?: number | null;
  latency_p95_ms?: number | null;
  failures_by_category?: { category: string; requests: number }[] | null;
  by_backend: { backend: string; spent_eur: number; total_tokens?: number; failures?: number | null; latency_p95_ms?: number | null }[];
  daily?: { day: string; spent_eur: number; total_tokens: number; input_tokens?: number; output_tokens?: number; cache_read_tokens?: number; cache_write_tokens?: number }[];
}

const reg = ref<Registry | null>(null);
const usage = ref<Usage | null>(null);
const regError = ref<string | null>(null);
const regBusy = ref(false);
const sampledAt = computed(() => reg.value?.live_host ? new Date(reg.value.live_host.sampled_at * 1000).toLocaleTimeString() : null);
function gib(bytes: number) { return (bytes / 1024 ** 3).toFixed(1) + " GiB"; }
function eur(n: number): string { return "€" + n.toFixed(2); }
function tokens(n: number): string {
  return new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}
const budgetPct = computed(() => {
  const u = usage.value;
  if (!u || u.budget_eur <= 0) return 0;
  return Math.min(100, Math.round((u.spent_eur / u.budget_eur) * 100));
});

async function loadRegistry(refresh = false) {
  if (regBusy.value) return;
  regBusy.value = true;
  regError.value = null;
  try {
    const status = await currentAuthStatus();
    if (!status.authenticated) {
      regError.value = "Not signed in";
      return;
    }
    reg.value = refresh
      ? await postJsonAuth<Registry>("/v1/system/registry/refresh", {})
      : await getJsonAuth<Registry>("/v1/system/registry");
    usage.value = await getJsonAuth<Usage>("/v1/system/usage");
  } catch (e) {
    regError.value = String(e);
  } finally {
    regBusy.value = false;
  }
}

// --- Security events (/v1/system/audit) -------------------------------------
type AuditEntry = { event: string; outcome: string; ts: string };
const audit = ref<Availability<{ entries: AuditEntry[] }>>({ state: "loading" });
const auditItems = computed<ActivityItem[]>(() => {
  if (audit.value.state !== "ok") return [];
  const now = Date.now();
  return audit.value.value.entries.slice(0, 20).map((entry, i) => {
    const good = /^(ok|success|approved|allowed)$/i.test(entry.outcome);
    return {
      id: `${entry.ts}-${i}`,
      icon: entry.event.startsWith("auth") ? "key" : "shield",
      title: entry.event,
      detail: entry.outcome,
      time: relativeTime(entry.ts, now),
      tone: good ? "ok" : "warn",
      toneLabel: entry.outcome,
    };
  });
});

// --- Services & disk (/v1/system/services) ------------------------------------
type Service = { label: string; unit: string; state: string };
const services = ref<Availability<{ services: Service[]; disks: Disk[] }>>({ state: "loading" });
const servicesBusy = ref(false);
async function loadServices() {
  servicesBusy.value = true;
  services.value = await loadOptional<{ services: Service[]; disks: Disk[] }>("/v1/system/services");
  servicesBusy.value = false;
}
const serviceItems = computed<ActivityItem[]>(() => (services.value.state === "ok" ? services.value.value.services : []).map((svc) => {
  const [tone, label] = serviceState(svc.state);
  return { id: svc.unit, icon: "chip", title: svc.label, detail: svc.unit, tone, toneLabel: label, time: label };
}));
const failureItems = computed<ActivityItem[]>(() => (usage.value?.failures_by_category ?? []).map((f) => ({
  id: f.category, icon: "alert", title: f.category, time: `${f.requests}×`, tone: "warn", toneLabel: "Failed requests",
})));

// --- Self-improvement (ADR-029 4d): Jarvis proposes, the owner approves -------
interface Proposal { title: string; category: string; rationale: string; cost: string; requires_approval: boolean; steps: string[] }
interface SelfDev { summary: string; proposals: Proposal[]; note: string }
const advice = ref<SelfDev | null>(null);
const adviceBusy = ref(false);
const adviceError = ref<string | null>(null);
const adviceCancelled = ref(false);
// Held while a request is in flight so the UI can stop waiting immediately.
const adviceCtrl = ref<AbortController | null>(null);
// Live elapsed seconds so the status shows real progress, not just a spinner.
const adviceElapsed = ref(0);
let adviceTimer: number | undefined;
const elapsedLabel = computed(() => {
  const m = Math.floor(adviceElapsed.value / 60);
  const s = adviceElapsed.value % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
});

async function askSelfImprove() {
  adviceBusy.value = true;
  adviceError.value = null;
  adviceCancelled.value = false;
  adviceElapsed.value = 0;
  clearInterval(adviceTimer);
  adviceTimer = window.setInterval(() => (adviceElapsed.value += 1), 1000);
  const ctrl = new AbortController();
  adviceCtrl.value = ctrl;
  try {
    const status = await currentAuthStatus();
    if (!status.authenticated) {
      adviceError.value = "Not signed in";
      return;
    }
    advice.value = await postJsonAuth<SelfDev>("/v1/system/self-improve", {}, ctrl.signal);
  } catch (e) {
    // An abort is a deliberate cancel, not an error.
    if (ctrl.signal.aborted) adviceCancelled.value = true;
    else adviceError.value = String(e);
  } finally {
    adviceBusy.value = false;
    adviceCtrl.value = null;
    clearInterval(adviceTimer);
  }
}
function cancelSelfImprove() {
  adviceCtrl.value?.abort();
}

// --- Node items ---------------------------------------------------------------
// `?node=models` opens a tab directly (e.g. from Integrations → Models).
const NODES = ["core", "node", "usage", "models", "improve", "services"];
const requested = useRoute().query.node;
const selected = ref(typeof requested === "string" && NODES.includes(requested) ? requested : "core");
const sub = ref("overview");
watch(selected, () => { sub.value = "overview"; });

const items = computed<NodeItem[]>(() => {
  const core: [Tone, string] = readyz.value === "ok" ? ["ok", "Running"] : readyz.value === "fout" ? ["error", "Unreachable"] : ["idle", "Checking…"];
  const live = reg.value?.live_host;
  const node: [Tone, string] = live ? ["ok", `Up ${formatUptime(live.uptime_seconds)}`]
    : reg.value ? ["idle", "No live vitals"] : regError.value ? ["warn", "Unavailable"] : ["idle", "Loading…"];
  const u = usage.value;
  const spend: [Tone, string] = u ? (u.over_budget ? ["error", "Budget reached"] : ["ok", `${eur(u.spent_eur)} of ${eur(u.budget_eur)}`])
    : regError.value ? ["warn", "Unavailable"] : ["idle", "Loading…"];
  const improve: [Tone, string] = adviceBusy.value ? ["ok", "Thinking…"]
    : advice.value ? ["ok", `${advice.value.proposals.length} proposals`] : ["idle", "On request"];
  let svc: [Tone, string] = services.value.state === "ok" ? servicesSummary(services.value.value.services)
    : services.value.state === "loading" ? ["idle", "Loading…"]
    : services.value.state === "unsupported" ? ["idle", "Requires newer Core"] : ["warn", "Unavailable"];
  // A nearly full disk outranks "all running".
  const fullDisk = services.value.state === "ok" && services.value.value.disks.some((d) => ["warn", "error"].includes(diskState(d)[0]));
  if (fullDisk && svc[0] === "ok") svc = ["warn", `${svc[1]} · disk nearly full`];
  return [
    { id: "core", label: "Core", title: "Jarvis Core", icon: "chip", tone: core[0], status: core[1], description: "The heart of Jarvis" },
    { id: "node", label: "Home Node", title: "Home Node", icon: "home", tone: node[0], status: node[1], description: "Hardware, live load and software" },
    { id: "usage", label: "Usage", title: "Usage", icon: "trend", tone: spend[0], status: spend[1], description: "Spend, budget and tokens this month" },
    { id: "models", label: "Models", title: "Models", icon: "layers", tone: "idle", status: "Owner policy", description: "Which models Jarvis may use" },
    { id: "improve", label: "Improve", title: "Self-improvement", icon: "bulb", tone: improve[0], status: improve[1], description: "Jarvis proposes, you approve" },
    { id: "services", label: "Services", title: "Services & disk", icon: "disk", tone: svc[0], status: svc[1], description: "Service status and disk space" },
  ];
});
const current = computed(() => items.value.find((item) => item.id === selected.value)!);
const subTabs = computed(() => ({
  core: [{ id: "overview", label: "Overview", icon: "doc" as const }, { id: "history", label: "History", icon: "clock" as const }],
  node: [{ id: "overview", label: "Overview", icon: "doc" as const }, { id: "software", label: "Software", icon: "layers" as const }],
  usage: [{ id: "overview", label: "Overview", icon: "doc" as const }, { id: "charts", label: "Charts", icon: "trend" as const }],
  models: [{ id: "overview", label: "Access", icon: "shield" as const }, { id: "routing", label: "Routing", icon: "branch" as const }],
}[selected.value] ?? []));

const DETAILS: Record<string, { description: string; quote?: string }> = {
  core: { description: "The heart of Jarvis on your Home Node — everything else talks through it." },
  node: { description: "The machine Jarvis runs on: hardware inventory, live load and installed tools." },
  usage: { description: "Monthly spend against the hard budget, with token use per backend." },
  models: { description: "Owner-controlled model access and routing order. Allowing a model never selects it for a running request." },
  improve: { description: "Jarvis reviews its own ecosystem and proposes improvements. It never runs anything itself." },
  services: { description: "The Jarvis services on your Home Node and the free space on its disks." },
};
const softwareItems = computed<ActivityItem[]>(() => (reg.value?.software ?? []).map((s) => ({
  id: s.name,
  icon: "sandbox",
  title: s.name + (s.version ? ` ${s.version}` : ""),
  detail: s.detail ?? undefined,
  tone: s.present ? "ok" : "idle",
  toneLabel: s.present ? "Present" : "Missing",
})));
const backendItems = computed<ActivityItem[]>(() => (usage.value?.by_backend ?? []).map((b) => ({
  id: b.backend,
  icon: "spark",
  title: b.backend,
  detail: [`${tokens(b.total_tokens ?? 0)} tokens`, b.failures ? `${b.failures} failed` : "",
    b.latency_p95_ms != null ? `p95 ${formatMs(b.latency_p95_ms)}` : ""].filter(Boolean).join(" · "),
  time: eur(b.spent_eur),
  tone: "ok",
  toneLabel: "Used this month",
})));

// --- Lifecycle ------------------------------------------------------------------
let hardwareTimer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
async function pollHardware() {
  if (disposed) return;
  if (selected.value === "node" && document.visibilityState === "visible" && !regBusy.value) {
    await loadRegistry();
  }
  if (!disposed) hardwareTimer = setTimeout(pollHardware, 5000);
}

onMounted(async () => {
  await loadHomeNodeConfig();
  if (disposed) return;
  check();
  loadRegistry();
  hardwareTimer = setTimeout(pollHardware, 5000);
  void loadServices();
  audit.value = await loadOptional<{ entries: AuditEntry[] }>("/v1/system/audit");
});
onUnmounted(() => {
  disposed = true;
  clearTimeout(hardwareTimer);
  clearInterval(adviceTimer);
  adviceCtrl.value?.abort();
});
</script>

<template>
  <NodePage v-model="selected" title="SYSTEM HEALTH" subtitle="THE STATE OF YOUR HOME NODE" :items="items"
    :mood="adviceBusy ? 'thinking' : 'idle'">
    <JvPanel :icon="current.icon" :title="current.title" :tone="current.tone" :status="current.status"
      :description="DETAILS[current.id].description"
      :quote="current.id === 'core' ? (homeNodeConfig.origin ?? 'No Home Node configured') : undefined">
      <template #action>
        <button v-if="current.id === 'core'" type="button" class="ghost-btn" @click="check">Check again</button>
        <button v-else-if="current.id === 'node' || current.id === 'usage'" type="button" class="ghost-btn"
          :disabled="regBusy" @click="loadRegistry(true)">{{ regBusy ? "Refreshing…" : "Refresh" }}</button>
        <button v-else-if="current.id === 'services'" type="button" class="ghost-btn" :disabled="servicesBusy"
          @click="loadServices">{{ servicesBusy ? "Refreshing…" : "Refresh" }}</button>
      </template>

      <JvSegmented v-if="subTabs.length" v-model="sub" :items="subTabs" :label="`${current.title} sections`" />

      <!-- Core -->
      <template v-if="current.id === 'core'">
        <div v-if="sub === 'overview'" class="split">
          <section class="tiles" aria-label="Connection">
            <h3 class="eyebrow">CONNECTION</h3>
            <div class="grid">
              <JvTile icon="health" label="Reachable · /livez" :value="checkLabel(livez)" />
              <JvTile icon="chip" label="Ready · /readyz" :value="checkLabel(readyz)" />
              <JvTile icon="api" label="Server" :value="homeNodeConfig.origin ?? 'Not configured'" />
              <JvTile icon="layers" label="Environment" :value="environment ?? 'Not reported'" />
              <JvTile icon="clock" label="Last check" :value="checkedAt ? checkedAt.toLocaleTimeString() : 'Checking…'" />
              <JvTile icon="alert" label="Last error" :value="error ?? 'None'" />
            </div>
          </section>
          <JvActivityList v-if="audit.state === 'ok'" title="Recent events" :items="auditItems.slice(0, 5)" empty="No security events recorded." />
          <JvUnavailable v-else-if="audit.state !== 'loading'" title="Recent events"
            :kind="audit.state === 'unsupported' ? 'core-update' : 'error'" icon="shield" />
          <p v-else class="muted small" role="status">Loading events…</p>
        </div>
        <template v-else>
          <JvActivityList v-if="audit.state === 'ok'" title="Security events" :items="auditItems" empty="No security events recorded." />
          <p v-else-if="audit.state === 'loading'" class="muted small" role="status">Loading events…</p>
          <JvUnavailable v-else title="Security events" :kind="audit.state === 'unsupported' ? 'core-update' : 'error'" icon="shield" />
        </template>
      </template>

      <!-- Home Node -->
      <template v-else-if="current.id === 'node'">
        <p v-if="regError" class="err" role="status">{{ regError }}</p>
        <p v-else-if="regBusy && !reg" class="muted" role="status">Loading resources…</p>
        <template v-else-if="reg">
          <div v-if="sub === 'overview'" class="split">
            <section class="tiles" aria-label="Live Home Node">
              <h3 class="eyebrow">VITALS</h3>
              <div class="grid">
                <template v-if="reg.live_host">
                  <JvTile icon="chip" label="CPU load" :value="reg.live_host.cpu_percent == null ? 'First sample…' : reg.live_host.cpu_percent.toFixed(1) + '%'" />
                  <JvTile icon="layers" label="Memory in use" :value="`${gib(reg.live_host.memory_used_bytes)} / ${gib(reg.live_host.memory_total_bytes)}`" />
                  <JvTile icon="health" label="Uptime" :value="formatUptime(reg.live_host.uptime_seconds)" />
                  <JvTile icon="clock" label="Last sample" :value="sampledAt ?? '—'" />
                </template>
                <JvTile icon="chip" label="Processor" :value="`${reg.host.cpu} · ${reg.host.cpu_cores} cores`" />
                <JvTile icon="monitor" label="System" :value="`${reg.host.os} · ${reg.host.arch} · ${reg.host.mem_total_gb} GB · ${reg.host.gpu}`" />
              </div>
              <p v-if="reg.live_host" class="muted small">Refreshes every 5 seconds while this tab is visible.</p>
              <JvUnavailable v-else class="gap" title="Live load" kind="core-update" icon="health"
                detail="This Core reports a hardware inventory only, not the current load." />
            </section>
            <JvActivityList title="Software" :items="softwareItems.slice(0, 5)" empty="No software reported.">
              <template #action>
                <button v-if="softwareItems.length > 5" type="button" class="link-btn" @click="sub = 'software'">View all</button>
              </template>
            </JvActivityList>
          </div>
          <JvActivityList v-else title="Software" :items="softwareItems" empty="No software reported." />
        </template>
      </template>

      <!-- Usage -->
      <template v-else-if="current.id === 'usage'">
        <p v-if="regError" class="err" role="status">{{ regError }}</p>
        <p v-else-if="!usage" class="muted" role="status">Loading usage…</p>
        <template v-else-if="sub === 'overview'">
          <div class="split">
            <section class="tiles" aria-label="Spend this month">
              <h3 class="eyebrow">THIS MONTH</h3>
              <div class="budget">
                <div class="brow">
                  <span>Monthly budget</span>
                  <span :class="{ over: usage.over_budget }">
                    {{ eur(usage.spent_eur) }} / {{ eur(usage.budget_eur) }}<span v-if="usage.over_budget"> · cap reached</span>
                  </span>
                </div>
                <div class="bar" role="img" :aria-label="`${budgetPct}% of the budget used`">
                  <div class="fill" :class="{ over: usage.over_budget }" :style="{ width: budgetPct + '%' }"></div>
                </div>
              </div>
              <div class="grid">
                <JvTile icon="trend" label="Remaining" :value="eur(usage.remaining_eur)" />
                <JvTile icon="layers" label="Tokens" :value="tokens(usage.total_tokens ?? 0)" />
                <JvTile icon="arrow-right" label="Input / output" :value="`${tokens(usage.input_tokens ?? 0)} / ${tokens(usage.output_tokens ?? 0)}`" />
                <JvTile icon="memory" label="Cached" :value="tokens(usage.cache_read_tokens ?? 0)" />
                <JvTile icon="api" label="Calls" :value="String(usage.requests ?? 0)" />
                <JvTile icon="calendar" label="Days with usage" :value="String(usage.daily?.length ?? 0)" />
                <JvTile v-if="usage.failures !== undefined" icon="alert" label="Failures / fallbacks"
                  :value="usage.failures === null && usage.fallbacks === null ? NOT_MEASURED
                    : `${measuredCount(usage.failures)} / ${measuredCount(usage.fallbacks)}`" />
                <JvTile v-if="usage.latency_p50_ms !== undefined" icon="clock" label="Latency p50 / p95"
                  :value="`${formatMs(usage.latency_p50_ms)} / ${formatMs(usage.latency_p95_ms)}`" />
              </div>
            </section>
            <div class="lists">
              <JvActivityList title="By backend" :items="backendItems" empty="No usage this month." />
              <JvActivityList v-if="failureItems.length" title="Failures by category" :items="failureItems" empty="" />
            </div>
          </div>
        </template>
        <template v-else>
          <div v-if="usage.daily?.length" class="charts">
            <div><h3 class="eyebrow">COST PER DAY</h3><SystemUsageChart :rows="usage.daily" mode="cost" /></div>
            <div><h3 class="eyebrow">TOKENS PER DAY</h3><SystemUsageChart :rows="usage.daily" mode="tokens" /></div>
          </div>
          <p v-else class="muted">No daily statistics yet.</p>
        </template>
      </template>

      <!-- Models -->
      <div v-else-if="current.id === 'models'" class="models">
        <ModelControls v-if="sub === 'overview'" />
        <ModelRouting v-else />
      </div>

      <!-- Self-improvement -->
      <div v-else-if="current.id === 'improve'" class="improve">
        <p class="muted small">
          Jarvis looks at its own ecosystem and makes proposals. It never carries anything out itself;
          the Core and <code>Jarvis.md</code> stay manual, changed only by you.
        </p>
        <div class="sd-actions">
          <button v-if="!adviceBusy" type="button" class="primary-btn" @click="askSelfImprove">Ask for proposals</button>
          <template v-else>
            <span class="thinking">Jarvis is thinking<span class="dots"><span></span><span></span><span></span></span></span>
            <span class="sd-elapsed">{{ elapsedLabel }}</span>
            <button type="button" class="ghost-btn" @click="cancelSelfImprove">Cancel</button>
          </template>
        </div>
        <p v-if="adviceBusy" class="muted small">Consulting the configured router · reading the ecosystem and drafting proposals</p>
        <p v-if="adviceCancelled" class="muted small">Cancelled.</p>
        <p v-if="adviceError" class="err">{{ adviceError }}</p>
        <template v-if="advice">
          <p class="summary">{{ advice.summary }}</p>
          <ul class="props">
            <li v-for="(p, i) in advice.proposals" :key="i">
              <div class="prow">
                <span class="chip">{{ p.category }}</span>
                <span class="ptitle">{{ p.title }}</span>
                <span class="chip" :class="p.requires_approval ? 'warn' : 'ok'">{{ p.requires_approval ? "needs approval" : "free" }} · {{ p.cost }}</span>
              </div>
              <p v-if="p.rationale" class="muted small">{{ p.rationale }}</p>
              <ol v-if="p.steps.length" class="steps">
                <li v-for="(st, j) in p.steps" :key="j">{{ st }}</li>
              </ol>
            </li>
          </ul>
          <p class="muted small">{{ advice.note }}</p>
        </template>
      </div>

      <!-- Services & disk -->
      <template v-else>
        <JvUnavailable v-if="services.state === 'unsupported' || services.state === 'error'" title="Services & disk"
          :kind="services.state === 'unsupported' ? 'core-update' : 'error'" icon="disk" />
        <p v-else-if="services.state === 'loading'" class="muted" role="status">Loading services…</p>
        <div v-else class="split">
          <section class="tiles" aria-label="Disks">
            <h3 class="eyebrow">DISKS</h3>
            <div class="grid">
              <JvTile v-for="disk in services.value.disks" :key="disk.label" icon="disk"
                :label="disk.label.charAt(0).toUpperCase() + disk.label.slice(1)" :value="diskState(disk)[1]" />
            </div>
            <p v-if="!services.value.disks.length" class="muted small">No disks reported.</p>
          </section>
          <JvActivityList title="Services" :items="serviceItems" empty="No services reported." />
        </div>
      </template>
    </JvPanel>
  </NodePage>
</template>

<style scoped>
.split { display: grid; grid-template-columns: minmax(0, 544px) minmax(0, 1fr); gap: 20px; align-items: start; }
.tiles { padding-right: 17px; border-right: 1px solid var(--line-a30); min-width: 0; }
.grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 14px; }
.eyebrow { margin: 0 0 12px; font-size: 11px; font-weight: 400; letter-spacing: 0.3em; color: var(--text-5); }
.muted { color: var(--text-4); }
.small { font-size: var(--fs-12); line-height: 1.5; }
.err { color: var(--danger); overflow-wrap: anywhere; }
.gap { margin-top: 12px; }
.budget { margin-bottom: 14px; font-size: var(--fs-12); color: var(--text-2); }
.brow { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.brow .over { color: var(--danger); }
.bar { height: 6px; border-radius: 999px; background: rgba(255, 255, 255, 0.1); overflow: hidden; }
.bar .fill { height: 100%; background: var(--accent); transition: width 0.3s ease; }
.bar .fill.over { background: var(--danger); }
.charts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.charts > div { min-width: 0; }
.lists { display: flex; flex-direction: column; gap: 16px; min-width: 0; }

.ghost-btn, .primary-btn, .link-btn { font: inherit; font-size: var(--fs-13); font-weight: 400; cursor: pointer; }
.ghost-btn {
  height: 40px; padding: 0 20px; border-radius: var(--r-10); border: 1px solid rgba(var(--accent-rgb), 0.5);
  background: rgba(4, 30, 24, 0.7); color: var(--text-1);
}
.ghost-btn:hover { filter: none; border-color: var(--accent); }
.ghost-btn:disabled { opacity: 0.5; cursor: default; }
.primary-btn { height: 40px; padding: 0 20px; border-radius: var(--r-10); background: var(--accent); color: #02150e; font-weight: 600; }
.link-btn { background: transparent; border: none; padding: 0; color: var(--text-2); font-size: 12px; }

.models { min-width: 0; }
.models :deep(input), .models :deep(select) { background: var(--field-bg); border: 1px solid var(--line-a30); color: var(--text-1); border-radius: var(--r-8); }
.models :deep(button) { background: rgba(4, 30, 24, 0.7); color: var(--text-1); border: 1px solid rgba(var(--accent-rgb), 0.5); font-weight: 400; }
.models :deep(li) { border-bottom-color: var(--line-a18); }

.improve { display: flex; flex-direction: column; gap: 10px; max-width: 900px; }
.improve p { margin: 0; }
.sd-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.sd-elapsed { font-size: 12px; color: var(--text-4); font-variant-numeric: tabular-nums; }
.thinking { display: inline-flex; align-items: center; font-size: var(--fs-13); color: var(--accent); }
.dots { display: inline-flex; gap: 4px; margin-left: 8px; }
.dots span { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); opacity: 0.4; animation: sdpulse 1.1s infinite ease-in-out; }
.dots span:nth-child(2) { animation-delay: 0.18s; }
.dots span:nth-child(3) { animation-delay: 0.36s; }
@keyframes sdpulse { 0%, 60%, 100% { opacity: 0.3; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-2px); } }
.summary { font-size: var(--fs-14); color: var(--text-1); }
.props { list-style: none; padding: 0; margin: 4px 0; display: flex; flex-direction: column; gap: 12px; }
.props > li { border-left: 2px solid var(--line-a30); padding-left: 12px; }
.prow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ptitle { font-size: var(--fs-13); font-weight: 600; color: var(--text-0); }
.chip { font-size: 11px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--line-a45); color: var(--accent); }
.chip.warn { color: var(--warn); border-color: rgba(245, 184, 74, 0.5); }
.chip.ok { color: var(--text-3); border-color: var(--line-a30); }
.steps { margin: 6px 0 0; padding-left: 18px; font-size: 12px; color: var(--text-4); }
.steps li { margin: 2px 0; }
@media (prefers-reduced-motion: reduce) { .dots span { animation: none; } }
@media (max-width: 1099px), (max-height: 759px) {
  .split, .charts { grid-template-columns: minmax(0, 1fr); }
  .tiles { padding-right: 0; border-right: none; }
}
</style>
