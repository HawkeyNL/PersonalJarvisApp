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
import JvSegmented from "../components/jv/JvSegmented.vue";
import RowList, { type Row } from "../components/RowList.vue";
import ActivityState from "../components/activity/ActivityState.vue";
import { loadOptional } from "../coreStatus";
import { formatUptime, relativeTime, type Availability, type Tone } from "../hubModel";
import { diskState, formatMs, measuredCount, NOT_MEASURED, serviceState, servicesSummary, type Disk } from "../nodeModels";

// System Health: connection, live host, usage, model access,
// self-improvement and services, on the shared node layout.
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
  reserved_eur?: number;
  above_soft_budget?: boolean;
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
async function loadAudit() {
  audit.value = { state: "loading" };
  audit.value = await loadOptional<{ entries: AuditEntry[] }>("/v1/system/audit");
}
const auditItems = computed<Row[]>(() => {
  if (audit.value.state !== "ok") return [];
  const now = Date.now();
  return audit.value.value.entries.slice(0, 20).map((entry, i) => {
    const good = /^(ok|success|approved|allowed)$/i.test(entry.outcome);
    return { id: `${entry.ts}-${i}`, title: entry.event, time: relativeTime(entry.ts, now), tone: good ? "ok" : "warn", status: entry.outcome };
  });
});

// --- Services & disk (/v1/system/services) ------------------------------------
type Service = { label: string; unit: string; state: string };
const services = ref<Availability<{ services: Service[]; disks: Disk[] }>>({ state: "loading" });
const servicesBusy = ref(false);
async function loadServices() {
  servicesBusy.value = true;
  services.value = { state: "loading" };
  services.value = await loadOptional<{ services: Service[]; disks: Disk[] }>("/v1/system/services");
  servicesBusy.value = false;
}
const serviceItems = computed<Row[]>(() => (services.value.state === "ok" ? services.value.value.services : []).map((svc) => {
  const [tone, label] = serviceState(svc.state);
  return { id: svc.unit, title: svc.label, detail: svc.unit, tone, status: label };
}));
const failureItems = computed<Row[]>(() => (usage.value?.failures_by_category ?? []).map((f) => ({
  id: f.category, title: f.category, time: `${f.requests}×`, tone: "warn", status: "Failed requests",
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
  const spend: [Tone, string] = u ? (u.over_budget ? ["error", "Budget reached"] : u.above_soft_budget ? ["warn", "Above soft budget"] : ["ok", `${eur(u.spent_eur)} of ${eur(u.budget_eur)}`])
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
    {
      id: "core", title: "Jarvis Core", icon: "chip", tone: core[0], status: core[1],
      description: "The heart of Jarvis on your Home Node. Everything else talks through it.",
      meta: homeNodeConfig.value.origin ?? "No Home Node configured",
    },
    { id: "node", title: "Home Node", icon: "home", tone: node[0], status: node[1], description: "The machine Jarvis runs on: hardware inventory, live load and installed tools." },
    { id: "usage", title: "Usage", icon: "trend", tone: spend[0], status: spend[1], description: "Monthly spend against the hard budget, with token use per backend." },
    { id: "models", title: "Models", icon: "layers", tone: "idle", status: "Owner policy", description: "Owner-controlled model access and routing order. Allowing a model never selects it for a running request." },
    { id: "improve", title: "Self-improvement", icon: "bulb", tone: improve[0], status: improve[1], description: "Jarvis reviews its own ecosystem and proposes improvements. It never runs anything itself." },
    { id: "services", title: "Services & disk", icon: "disk", tone: svc[0], status: svc[1], description: "The Jarvis services on your Home Node and the free space on its disks." },
  ];
});
const subTabs = computed(() => ({
  core: [{ id: "overview", label: "Overview" }, { id: "history", label: "History" }],
  node: [{ id: "overview", label: "Overview" }, { id: "software", label: "Software" }],
  usage: [{ id: "overview", label: "Overview" }, { id: "charts", label: "Charts" }],
  models: [{ id: "overview", label: "Access" }, { id: "routing", label: "Routing" }],
}[selected.value] ?? []));
const sectionTitle = computed(() => items.value.find((item) => item.id === selected.value)?.title ?? "");

const softwareItems = computed<Row[]>(() => (reg.value?.software ?? []).map((s) => ({
  id: s.name,
  title: s.name + (s.version ? ` ${s.version}` : ""),
  detail: s.detail ?? undefined,
  tone: s.present ? "ok" : "idle",
  status: s.present ? "Present" : "Missing",
})));
const backendItems = computed<Row[]>(() => (usage.value?.by_backend ?? []).map((b) => ({
  id: b.backend,
  title: b.backend,
  detail: [`${tokens(b.total_tokens ?? 0)} tokens`, b.failures ? `${b.failures} failed` : "",
    b.latency_p95_ms != null ? `p95 ${formatMs(b.latency_p95_ms)}` : ""].filter(Boolean).join(" · "),
  time: eur(b.spent_eur),
  tone: "ok",
  status: "Used this month",
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
  await loadAudit();
});
onUnmounted(() => {
  disposed = true;
  clearTimeout(hardwareTimer);
  clearInterval(adviceTimer);
  adviceCtrl.value?.abort();
});
</script>

<template>
  <NodePage v-model="selected" title="System health" subtitle="The state of your Home Node" :items="items">
    <template v-if="['core', 'node', 'usage', 'services'].includes(selected)" #actions>
      <button v-if="selected === 'core'" type="button" class="btn btn-secondary btn-sm" @click="check">Check again</button>
      <button v-else-if="selected === 'services'" type="button" class="btn btn-secondary btn-sm" :disabled="servicesBusy"
        @click="loadServices">{{ servicesBusy ? "Refreshing…" : "Refresh" }}</button>
      <button v-else type="button" class="btn btn-secondary btn-sm" :disabled="regBusy"
        @click="loadRegistry(true)">{{ regBusy ? "Refreshing…" : "Refresh" }}</button>
    </template>

    <JvSegmented v-if="subTabs.length" v-model="sub" class="np-seg" :items="subTabs" :label="`${sectionTitle} sections`" />

    <!-- Core -->
    <template v-if="selected === 'core'">
      <div v-if="sub === 'overview'" class="np-cols">
        <dl class="np-facts" aria-label="Connection">
          <div><dt>Reachable · /livez</dt><dd>{{ checkLabel(livez) }}</dd></div>
          <div><dt>Ready · /readyz</dt><dd>{{ checkLabel(readyz) }}</dd></div>
          <div><dt>Server</dt><dd>{{ homeNodeConfig.origin ?? "Not configured" }}</dd></div>
          <div><dt>Environment</dt><dd>{{ environment ?? "Not reported" }}</dd></div>
          <div><dt>Last check</dt><dd>{{ checkedAt ? checkedAt.toLocaleTimeString() : "Checking…" }}</dd></div>
          <div><dt>Last error</dt><dd>{{ error ?? "None" }}</dd></div>
        </dl>
        <ActivityState nested :state="audit" what="security events" @retry="loadAudit">
          <RowList title="Recent events" :rows="auditItems.slice(0, 5)" empty="No security events recorded." />
        </ActivityState>
      </div>
      <ActivityState v-else nested :state="audit" what="security events" @retry="loadAudit">
        <RowList title="Security events" :rows="auditItems" empty="No security events recorded." />
      </ActivityState>
    </template>

    <!-- Home Node -->
    <template v-else-if="selected === 'node'">
      <p v-if="regError" class="np-note err" role="status">{{ regError }}</p>
      <p v-else-if="regBusy && !reg" class="np-note" role="status">Loading resources…</p>
      <template v-else-if="reg">
        <div v-if="sub === 'overview'" class="np-cols">
          <div class="stack">
            <dl class="np-facts" aria-label="Vitals">
              <template v-if="reg.live_host">
                <div><dt>CPU load</dt><dd>{{ reg.live_host.cpu_percent == null ? "First sample…" : reg.live_host.cpu_percent.toFixed(1) + "%" }}</dd></div>
                <div><dt>Memory in use</dt><dd>{{ gib(reg.live_host.memory_used_bytes) }} / {{ gib(reg.live_host.memory_total_bytes) }}</dd></div>
                <div><dt>Uptime</dt><dd>{{ formatUptime(reg.live_host.uptime_seconds) }}</dd></div>
                <div><dt>Last sample</dt><dd>{{ sampledAt ?? "—" }}</dd></div>
              </template>
              <div><dt>Processor</dt><dd>{{ reg.host.cpu }} · {{ reg.host.cpu_cores }} cores</dd></div>
              <div><dt>System</dt><dd>{{ reg.host.os }} · {{ reg.host.arch }} · {{ reg.host.mem_total_gb }} GB · {{ reg.host.gpu }}</dd></div>
            </dl>
            <p v-if="reg.live_host" class="np-note">Refreshes every 5 seconds while this section is open.</p>
            <p v-else class="np-empty"><strong>Live load needs a newer Core</strong>This Core reports a hardware inventory only, not the current load.</p>
          </div>
          <RowList title="Software" :rows="softwareItems.slice(0, 5)" empty="No software reported.">
            <template v-if="softwareItems.length > 5" #action>
              <button type="button" class="btn btn-ghost btn-sm" @click="sub = 'software'">View all</button>
            </template>
          </RowList>
        </div>
        <RowList v-else title="Software" :rows="softwareItems" empty="No software reported." />
      </template>
    </template>

    <!-- Usage -->
    <template v-else-if="selected === 'usage'">
      <p v-if="regError" class="np-note err" role="status">{{ regError }}</p>
      <p v-else-if="!usage" class="np-note" role="status">Loading usage…</p>
      <div v-else-if="sub === 'overview'" class="np-cols">
        <div class="stack">
          <div class="budget">
            <div class="brow t-sm">
              <span class="t-2">Monthly budget</span>
              <span class="num" :class="{ over: usage.over_budget }">
                {{ eur(usage.spent_eur) }} / {{ eur(usage.budget_eur) }}<span v-if="usage.over_budget"> · cap reached</span>
              </span>
            </div>
            <div class="meter" role="img" :aria-label="`${budgetPct}% of the budget used`">
              <div class="fill" :class="{ over: usage.over_budget }" :style="{ width: budgetPct + '%' }"></div>
            </div>
          </div>
          <dl class="np-facts" aria-label="Spend this month">
            <div><dt>Remaining</dt><dd>{{ eur(usage.remaining_eur) }}</dd></div>
            <div><dt>Reserved</dt><dd>{{ usage.reserved_eur == null ? "—" : eur(usage.reserved_eur) }}</dd></div>
            <div><dt>Tokens</dt><dd>{{ tokens(usage.total_tokens ?? 0) }}</dd></div>
            <div><dt>Input / output</dt><dd>{{ tokens(usage.input_tokens ?? 0) }} / {{ tokens(usage.output_tokens ?? 0) }}</dd></div>
            <div><dt>Cached</dt><dd>{{ tokens(usage.cache_read_tokens ?? 0) }}</dd></div>
            <div><dt>Calls</dt><dd>{{ usage.requests ?? 0 }}</dd></div>
            <div><dt>Days with usage</dt><dd>{{ usage.daily?.length ?? 0 }}</dd></div>
            <div v-if="usage.failures !== undefined"><dt>Failures / fallbacks</dt><dd>{{ usage.failures === null && usage.fallbacks === null ? NOT_MEASURED
              : `${measuredCount(usage.failures)} / ${measuredCount(usage.fallbacks)}` }}</dd></div>
            <div v-if="usage.latency_p50_ms !== undefined"><dt>Latency p50 / p95</dt><dd>{{ formatMs(usage.latency_p50_ms) }} / {{ formatMs(usage.latency_p95_ms) }}</dd></div>
          </dl>
        </div>
        <div class="stack">
          <RowList title="By backend" :rows="backendItems" empty="No usage this month." />
          <RowList v-if="failureItems.length" title="Failures by category" :rows="failureItems" empty="" />
        </div>
      </div>
      <div v-else-if="usage.daily?.length" class="np-cols">
        <section aria-labelledby="chart-cost"><h3 id="chart-cost">Cost per day</h3><SystemUsageChart :rows="usage.daily" mode="cost" /></section>
        <section aria-labelledby="chart-tokens"><h3 id="chart-tokens">Tokens per day</h3><SystemUsageChart :rows="usage.daily" mode="tokens" /></section>
      </div>
      <p v-else class="np-empty">No daily statistics yet.</p>
    </template>

    <!-- Models -->
    <div v-else-if="selected === 'models'" class="models">
      <ModelControls v-if="sub === 'overview'" />
      <ModelRouting v-else />
    </div>

    <!-- Self-improvement -->
    <div v-else-if="selected === 'improve'" class="stack improve">
      <p class="np-note">
        Jarvis looks at its own ecosystem and makes proposals. It never carries anything out itself;
        the Core and <code>Jarvis.md</code> stay manual, changed only by you.
      </p>
      <div class="sd-actions">
        <button v-if="!adviceBusy" type="button" class="btn btn-primary" @click="askSelfImprove">Ask for proposals</button>
        <template v-else>
          <span class="thinking t-sm" role="status"><span class="spinner" aria-hidden="true"></span>Jarvis is thinking</span>
          <span class="t-xs t-2 num">{{ elapsedLabel }}</span>
          <button type="button" class="btn btn-secondary btn-sm" @click="cancelSelfImprove">Cancel</button>
        </template>
      </div>
      <p v-if="adviceBusy" class="np-note">Consulting the configured router · reading the ecosystem and drafting proposals</p>
      <p v-if="adviceCancelled" class="np-note" role="status">Cancelled.</p>
      <p v-if="adviceError" class="np-note err" role="alert">{{ adviceError }}</p>
      <template v-if="advice">
        <p class="summary">{{ advice.summary }}</p>
        <ul class="props">
          <li v-for="(p, i) in advice.proposals" :key="i" class="well">
            <div class="prow">
              <span class="badge">{{ p.category }}</span>
              <span class="ptitle">{{ p.title }}</span>
              <span class="badge" :class="p.requires_approval ? 'neg' : ''">{{ p.requires_approval ? "Needs approval" : "Free" }} · {{ p.cost }}</span>
            </div>
            <p v-if="p.rationale" class="np-note">{{ p.rationale }}</p>
            <ol v-if="p.steps.length" class="steps t-sm t-2">
              <li v-for="(st, j) in p.steps" :key="j">{{ st }}</li>
            </ol>
          </li>
        </ul>
        <p class="np-note">{{ advice.note }}</p>
      </template>
    </div>

    <!-- Services & disk -->
    <ActivityState v-else nested :state="services" what="services" @retry="loadServices" v-slot="{ data }">
      <div class="np-cols">
        <div class="stack">
          <h3>Disks</h3>
          <dl v-if="data.disks.length" class="np-facts" aria-label="Disks">
            <div v-for="disk in data.disks" :key="disk.label">
              <dt>{{ disk.label.charAt(0).toUpperCase() + disk.label.slice(1) }}</dt><dd>{{ diskState(disk)[1] }}</dd>
            </div>
          </dl>
          <p v-else class="np-empty">No disks reported.</p>
        </div>
        <RowList title="Services" :rows="serviceItems" empty="No services reported." />
      </div>
    </ActivityState>
  </NodePage>
</template>

<style scoped>
h3 { margin: 0; }
.stack { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.budget { display: flex; flex-direction: column; gap: 6px; }
.brow { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.brow .over { color: var(--danger-text); }
.meter { height: 6px; border-radius: var(--r-pill); background: var(--bg-sunken); overflow: hidden; }
.meter .fill { height: 100%; background: var(--accent-solid); }
.meter .fill.over { background: var(--danger-solid); }
@media (prefers-reduced-motion: no-preference) {
  .meter .fill { transition: width var(--d-enter) var(--ease-out); }
}
.np-cols section { display: flex; flex-direction: column; gap: 10px; }

.models { min-width: 0; }
.improve { max-width: 900px; }
.sd-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.thinking { display: inline-flex; align-items: center; gap: 8px; color: var(--accent-text); }
.summary { margin: 0; font-size: 15px; }
.props { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.well { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; border-radius: var(--r-md); background: var(--bg-sunken); }
.prow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ptitle { font-weight: 600; }
.steps { margin: 0; padding-left: 18px; }
.steps li { margin: 2px 0; }
</style>
