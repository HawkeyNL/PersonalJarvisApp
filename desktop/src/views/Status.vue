<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import { getJson, getJsonAuth, postJsonAuth } from "../api";
import { currentAuthStatus } from "../auth";
import { homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import SystemUsageChart from "../components/SystemUsageChart.vue";
import ModelControls from "../components/ModelControls.vue";

type Health = { status: string; environment?: string };
type Check = "checking" | "ok" | "fout";
const sections = ["Overzicht", "Hardware", "Modellen", "Verbruik"] as const;
const section = ref<(typeof sections)[number]>("Overzicht");

const livez = ref<Check>("checking");
const readyz = ref<Check>("checking");
const environment = ref<string | null>(null);
const error = ref<string | null>(null);

function dotClass(c: Check): string {
  if (c === "ok") return "dot-ok";
  if (c === "fout") return "dot-err";
  return "dot-todo";
}

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
}

// Host inventory and usage. Model authorization is shown separately from the
// owner-controlled policy; a configured credential is not an enabled model.
interface SoftwareItem {
  name: string;
  present: boolean;
  version: string | null;
  detail: string | null;
}
interface HostInfo {
  os: string;
  arch: string;
  cpu: string;
  cpu_cores: number;
  mem_total_gb: number;
  gpu: string;
}
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
  by_backend: { backend: string; spent_eur: number; total_tokens?: number }[];
  daily?: { day: string; spent_eur: number; total_tokens: number; input_tokens?: number; output_tokens?: number; cache_read_tokens?: number; cache_write_tokens?: number }[];
}

const reg = ref<Registry | null>(null);
const usage = ref<Usage | null>(null);
const regError = ref<string | null>(null);
const regBusy = ref(false);
const sampledAt = computed(() => reg.value?.live_host ? new Date(reg.value.live_host.sampled_at * 1000).toLocaleTimeString("nl-NL") : null);
function gib(bytes: number) { return (bytes / 1024 ** 3).toFixed(1) + " GiB"; }
let hardwareTimer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
async function pollHardware() {
  if (disposed) return;
  if (section.value === "Hardware" && document.visibilityState === "visible" && !regBusy.value) {
    await loadRegistry();
  }
  if (!disposed) hardwareTimer = setTimeout(pollHardware, 5000);
}

function eur(n: number): string {
  return "€" + n.toFixed(2);
}
function tokens(n: number): string {
  return new Intl.NumberFormat("nl-NL", { notation: "compact", maximumFractionDigits: 1 }).format(n);
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
      regError.value = "niet ingelogd";
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

// Self-development (ADR-029 fase 4d): Jarvis proposes improvements to itself.
interface Proposal {
  title: string;
  category: string;
  rationale: string;
  cost: string;
  requires_approval: boolean;
  steps: string[];
}
interface SelfDev {
  summary: string;
  proposals: Proposal[];
  note: string;
}
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
      adviceError.value = "niet ingelogd";
      return;
    }
    advice.value = await postJsonAuth<SelfDev>(
      "/v1/system/self-improve",
      {},
      ctrl.signal,
    );
  } catch (e) {
    // An abort is a deliberate cancel, not an error.
    if (ctrl.signal.aborted) {
      adviceCancelled.value = true;
    } else {
      adviceError.value = String(e);
    }
  } finally {
    adviceBusy.value = false;
    adviceCtrl.value = null;
    clearInterval(adviceTimer);
  }
}

function cancelSelfImprove() {
  adviceCtrl.value?.abort();
}

onMounted(async () => {
  await loadHomeNodeConfig();
  if (disposed) return;
  check();
  loadRegistry();
  hardwareTimer = setTimeout(pollHardware, 5000);
});
onUnmounted(() => {
  disposed = true;
  clearTimeout(hardwareTimer);
  clearInterval(adviceTimer);
  adviceCtrl.value?.abort();
});
</script>

<template>
  <section class="view system-view">
    <header class="system-heading">
      <div>
        <h1>Systeem</h1>
        <p class="muted">Je Home Node, modeltoegang en verbruik in één overzicht.</p>
      </div>
      <button @click="check">Controleer verbinding</button>
    </header>
    <nav class="system-sections" aria-label="Systeemonderdelen">
      <button v-for="item in sections" :key="item" :aria-pressed="section === item"
        :class="{ selected: section === item }" @click="section = item">{{ item }}</button>
    </nav>

    <div v-if="section === 'Overzicht'" class="panel connection-panel">
      <h2 class="phead">VERBINDING <span class="hint">Home Node</span></h2>
      <div class="connection-grid">
        <div class="endpoint"><span class="k">Server</span>
          <code>{{ homeNodeConfig.origin ?? "niet geconfigureerd" }}</code>
        </div>

    <ul class="status-list">
      <li>
        <span class="dot" :class="dotClass(livez)"></span>
        <div><span class="k">Bereikbaar · /livez</span><strong>{{ livez }}</strong></div>
      </li>
      <li>
        <span class="dot" :class="dotClass(readyz)"></span>
        <div><span class="k">Gereed · /readyz</span><strong>{{ readyz }}</strong>
          <span v-if="environment" class="muted"> · {{ environment }}</span>
        </div>
      </li>
    </ul>
      </div>
      <p v-if="error" class="muted err" role="status">Laatste fout: {{ error }}</p>
    </div>

    <div class="panel resources-panel" v-if="section !== 'Modellen' && (reg || regError || regBusy)">
      <h2 class="phead section-wide">
        HOME NODE &amp; VERBRUIK
      </h2>
      <p v-if="regError" class="muted err">{{ regError }}</p>
      <p v-if="regBusy && !reg" class="muted section-wide" role="status">Resources laden…</p>
      <template v-else-if="reg">
        <!-- Monthly spend vs the hard budget (ADR-027). -->
        <div v-if="usage && (section === 'Verbruik' || section === 'Overzicht')" class="budget">
          <h3>Verbruik deze maand</h3>
          <div class="brow">
            <span class="k">maandbudget</span>
            <span :class="{ over: usage.over_budget }">
              {{ eur(usage.spent_eur) }} / {{ eur(usage.budget_eur) }}
              <span v-if="usage.over_budget" class="capped">· plafond bereikt</span>
            </span>
          </div>
          <div class="bar">
            <div
              class="fill"
              :class="{ over: usage.over_budget }"
              :style="{ width: budgetPct + '%' }"
            ></div>
          </div>
          <div class="token-summary">
            <span><strong>{{ tokens(usage.total_tokens ?? 0) }}</strong> tokens</span>
            <span>{{ tokens(usage.input_tokens ?? 0) }} input</span>
            <span>{{ tokens(usage.output_tokens ?? 0) }} output</span>
            <span>{{ tokens(usage.cache_read_tokens ?? 0) }} cached</span>
            <span>{{ usage.requests ?? 0 }} calls</span>
          </div>
          <div v-if="usage.by_backend.length" class="bk">
            <span v-for="b in usage.by_backend" :key="b.backend" class="bkchip">
              {{ b.backend }} · {{ tokens(b.total_tokens ?? 0) }} · {{ eur(b.spent_eur) }}
            </span>
          </div>
        </div>
        <template v-if="section === 'Verbruik'">
          <div v-if="usage?.daily?.length" class="resource-group section-wide usage-charts">
            <div><h3>Kosten per dag</h3><SystemUsageChart :rows="usage.daily" mode="cost" /></div>
            <div><h3>Tokens per dag</h3><SystemUsageChart :rows="usage.daily" mode="tokens" /></div>
          </div>
          <p v-else class="muted section-wide">Nog geen dagstatistieken beschikbaar.</p>
        </template>

        <div v-if="section === 'Hardware'" class="host">
          <h3>Hardware &amp; besturingssysteem</h3>
          <dl class="host-grid">
            <div><dt>Processor</dt><dd>{{ reg.host.cpu }}</dd></div>
            <div><dt>Cores</dt><dd>{{ reg.host.cpu_cores }}</dd></div>
            <div><dt>Geheugen</dt><dd>{{ reg.host.mem_total_gb }} GB</dd></div>
            <div><dt>GPU</dt><dd>{{ reg.host.gpu }}</dd></div>
            <div><dt>Besturingssysteem</dt><dd>{{ reg.host.os }}</dd></div>
            <div><dt>Architectuur</dt><dd>{{ reg.host.arch }}</dd></div>
          </dl>
        </div>

        <div v-if="section === 'Hardware'" class="resource-group">
          <h3>Live Home Node</h3>
          <template v-if="reg.live_host">
            <dl class="host-grid">
              <div><dt>CPU-belasting</dt><dd>{{ reg.live_host.cpu_percent == null ? 'Eerste meting…' : reg.live_host.cpu_percent.toFixed(1) + '%' }}</dd></div>
              <div><dt>Geheugen in gebruik</dt><dd>{{ gib(reg.live_host.memory_used_bytes) }} / {{ gib(reg.live_host.memory_total_bytes) }}</dd></div>
              <div><dt>Uptime</dt><dd>{{ Math.floor(reg.live_host.uptime_seconds / 3600) }} uur {{ Math.floor(reg.live_host.uptime_seconds % 3600 / 60) }} min</dd></div>
              <div><dt>Laatste meting</dt><dd>{{ sampledAt }}</dd></div>
            </dl>
            <p class="muted small">Verversing elke 5 seconden zolang dit tabblad zichtbaar is.</p>
          </template>
          <p v-else class="muted">Deze Core-versie levert nog geen live hardwaremetingen. De hardwaregegevens zijn een inventarisatie, geen actuele belasting.</p>
        </div>
        <div v-if="section === 'Hardware'" class="resource-group section-wide">
        <h3>Software</h3>
        <div class="sw">
          <span
            v-for="s in reg.software"
            :key="s.name"
            class="chip"
            :class="s.present ? 'on' : 'off'"
            :title="s.detail || ''"
          >
            {{ s.name }}<span v-if="s.version" class="ver"> {{ s.version }}</span>
          </span>
        </div>
        </div>

        <div class="section-wide resource-actions">
        <button :disabled="regBusy" @click="loadRegistry(true)">
          {{ regBusy ? "verversen…" : "Ververs resources" }}
        </button>
        </div>
      </template>
    </div>

    <div v-if="section === 'Modellen'" class="panel models-panel">
      <ModelControls />
    </div>

    <!-- Self-development (ADR-029 4d): Jarvis proposes improvements to itself. -->
    <div v-if="section === 'Overzicht'" class="panel">
      <h2 class="phead">
        ZELFVERBETERING <span class="hint">Jarvis stelt voor · jij keurt goed</span>
      </h2>
      <p class="muted small">
        Jarvis bekijkt zijn eigen ecosysteem en doet voorstellen. Hij voert niets
        zelf uit — de Core en <code>Jarvis.md</code> blijven handmatig, alleen door jou.
      </p>
      <div class="sd-actions">
        <button v-if="!adviceBusy" @click="askSelfImprove">
          Vraag om verbetervoorstellen
        </button>
        <template v-else>
          <span class="thinking">
            Jarvis denkt na<span class="dots"><span></span><span></span><span></span></span>
          </span>
          <span class="sd-elapsed">{{ elapsedLabel }}</span>
          <button class="ghost" @click="cancelSelfImprove">Annuleren</button>
        </template>
      </div>
      <p v-if="adviceBusy" class="muted small sd-status">
        raadpleegt de geconfigureerde router · leest het ecosysteem en stelt
        verbetervoorstellen op
      </p>
      <p v-if="adviceCancelled" class="muted small">Geannuleerd.</p>
      <p v-if="adviceError" class="muted err">{{ adviceError }}</p>
      <template v-if="advice">
        <p class="active">{{ advice.summary }}</p>
        <ul class="props">
          <li v-for="(p, i) in advice.proposals" :key="i">
            <div class="prow">
              <span class="mclass mc-mid">{{ p.category }}</span>
              <span class="ptitle">{{ p.title }}</span>
              <span
                class="cost"
                :class="p.requires_approval ? 'cost-metered' : 'cost-local'"
              >
                {{ p.requires_approval ? "goedkeuring" : "vrij" }} · {{ p.cost }}
              </span>
            </div>
            <p v-if="p.rationale" class="muted small note">{{ p.rationale }}</p>
            <ol v-if="p.steps.length" class="steps">
              <li v-for="(st, j) in p.steps" :key="j">{{ st }}</li>
            </ol>
          </li>
        </ul>
        <p class="muted small note">{{ advice.note }}</p>
      </template>
    </div>
  </section>
</template>

<style scoped>
.panel {
  margin-top: 22px;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px 16px;
  background: rgba(14, 30, 22, 0.5);
  backdrop-filter: blur(14px) saturate(1.3);
  -webkit-backdrop-filter: blur(14px) saturate(1.3);
  max-width: 640px;
}
.phead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family: var(--mono);
  font-size: 11px;
  letter-spacing: 0.18em;
  color: var(--accent);
  margin-bottom: 12px;
}
.hint {
  font-size: 9px;
  color: var(--muted);
  letter-spacing: 0.08em;
}
.active {
  margin: 0 0 12px;
  font-size: 13px;
}
.budget {
  margin: 0 0 14px;
  font-size: 12px;
}
.brow {
  display: flex;
  justify-content: space-between;
  margin-bottom: 5px;
}
.brow .over {
  color: #f87171;
}
.capped {
  font-size: 10px;
  letter-spacing: 0.06em;
}
.bar {
  height: 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  overflow: hidden;
}
.bar .fill {
  height: 100%;
  background: var(--accent);
  transition: width 0.3s ease;
}
.bar .fill.over {
  background: #f87171;
}
.token-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 5px 12px;
  margin-top: 8px;
  color: var(--muted);
  font: 10px var(--mono);
}
.token-summary strong { color: var(--text); }
.bk {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.bkchip {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.04em;
  color: var(--muted);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 7px;
}
.models {
  border-top: 1px solid var(--border);
  padding-top: 10px;
  margin-bottom: 14px;
}
.models .k {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.12em;
  color: var(--muted);
  margin-bottom: 8px;
}
.models ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.models li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.models li.off {
  opacity: 0.4;
}
.models li.off .mid {
  text-decoration: line-through;
}
.mclass {
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.05em;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid var(--border);
  min-width: 62px;
  text-align: center;
}
.mc-light {
  color: #60a5fa;
  border-color: rgba(96, 165, 250, 0.5);
}
.mc-mid {
  color: var(--accent);
  border-color: var(--accent);
}
.mc-heavy {
  color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.5);
}
.mc-reasoning {
  color: #c084fc;
  border-color: rgba(192, 132, 252, 0.5);
}
.mid {
  flex: 1;
  font-family: var(--mono);
  font-size: 11px;
}
.mcost {
  font-size: 9.5px;
  color: var(--muted);
  letter-spacing: 0.05em;
}
.brains {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.brains li {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.blabel {
  font-size: 13px;
}
.cost {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.06em;
  padding: 2px 7px;
  border-radius: 999px;
  border: 1px solid var(--border);
}
.cost-plan {
  color: var(--accent);
  border-color: var(--accent);
}
.cost-metered {
  color: #fbbf24;
  border-color: rgba(251, 191, 36, 0.5);
}
.cost-local {
  color: #60a5fa;
  border-color: rgba(96, 165, 250, 0.5);
}
.note {
  margin-left: auto;
}
.host {
  font-size: 12px;
  color: var(--text);
  padding: 8px 0;
  border-top: 1px solid var(--border);
}
.host .k,
.sw .k {
  font-family: var(--mono);
  font-size: 9.5px;
  letter-spacing: 0.14em;
  color: var(--muted);
  margin-right: 8px;
}
.sw {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin: 4px 0 14px;
}
.chip {
  font-size: 11px;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  color: var(--muted);
}
.chip.on {
  color: var(--text);
  border-color: var(--accent-2, var(--accent));
}
.chip.off {
  opacity: 0.5;
  text-decoration: line-through;
}
.ver {
  opacity: 0.7;
}
.small {
  font-size: 12px;
}
.err {
  color: #f87171;
}
.props {
  list-style: none;
  padding: 0;
  margin: 12px 0 8px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.props > li {
  border-left: 2px solid var(--border);
  padding-left: 10px;
}
.prow {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ptitle {
  font-size: 13px;
  font-weight: 600;
}
.steps {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--muted);
}
.steps li {
  margin: 2px 0;
}

/* Self-development action row: a live "thinking" indicator + a cancel button. */
.sd-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.sd-elapsed {
  font-family: var(--mono);
  font-size: 12px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
.sd-status {
  margin: 8px 0 0;
}
.thinking {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  color: var(--accent);
}
.thinking .dots {
  display: inline-flex;
  gap: 4px;
  margin-left: 8px;
}
.thinking .dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  opacity: 0.4;
  animation: sdpulse 1.1s infinite ease-in-out;
}
.thinking .dots span:nth-child(2) {
  animation-delay: 0.18s;
}
.thinking .dots span:nth-child(3) {
  animation-delay: 0.36s;
}
@keyframes sdpulse {
  0%, 60%, 100% { opacity: 0.3; transform: translateY(0); }
  30% { opacity: 1; transform: translateY(-2px); }
}
button.ghost {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--muted);
  font-weight: 500;
}
button.ghost:hover {
  filter: none;
  border-color: #f87171;
  color: #f87171;
}
@media (prefers-reduced-motion: reduce) {
  .thinking .dots span {
    animation: none;
  }
}

/* Page-local layout; no changes to other client views. */
.system-view { max-width: 1180px; display: grid; gap: 20px; overflow-wrap: anywhere; }
.system-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; }
.system-heading h1 { margin-bottom: 6px; }
.system-heading p { margin: 0; }
.system-sections { display: flex; flex-wrap: wrap; gap: 8px; }
.system-sections button { background: transparent; color: var(--muted); border: 1px solid var(--border); }
.system-sections button.selected { color: var(--accent); border-color: var(--accent); background: var(--panel); }
.system-sections button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.panel { max-width: none; min-width: 0; margin-top: 0; padding: 22px; }
.phead { margin: 0 0 16px; flex-wrap: wrap; gap: 8px; }
.connection-grid { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1.4fr); align-items: center; gap: 20px; }
.endpoint { min-width: 0; font-size: 13px; }
.k, .host-grid dt { display: block; color: var(--muted); font-size: 11px; margin-bottom: 6px; }
.status-list { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); margin: 0; }
.status-list li { padding: 12px; border-radius: 10px; background: rgba(255,255,255,.025); }
.status-list strong { font-size: 13px; color: var(--text); }
.resources-panel { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 18px; }
.section-wide { grid-column: 1 / -1; }
.resource-group, .host, .budget { min-width: 0; margin: 0; border: 1px solid var(--border); border-radius: 12px; padding: 18px; }
h3 { margin: 0 0 16px; font-size: 13px; font-weight: 600; }
.host-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 18px; margin: 0; }
.host-grid dd { margin: 0; font-size: 13px; line-height: 1.5; }
.resource-actions { display: flex; justify-content: flex-end; }
.usage-charts { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 24px; }
.usage-charts > div { min-width: 0; }
.brow { flex-wrap: wrap; gap: 8px; }
.brains .note { flex-basis: 100%; margin-left: 17px; }
.models ul { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px 18px; }
.models li { display: grid; grid-template-columns: auto minmax(0,1fr) auto; padding: 10px; border-radius: 8px; background: rgba(255,255,255,.025); }
@media (max-width: 760px) {
  .connection-grid, .resources-panel, .models ul, .usage-charts { grid-template-columns: minmax(0,1fr); }
  .panel { padding: 16px; }
}
@media (max-width: 420px) {
  .status-list, .host-grid { grid-template-columns: minmax(0,1fr); }
  .system-heading button { width: 100%; }
}
</style>
