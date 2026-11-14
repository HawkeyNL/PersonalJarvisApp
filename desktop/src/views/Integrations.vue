<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvSegmented, { type SegmentItem } from "../components/jv/JvSegmented.vue";
import RowList, { type Row } from "../components/RowList.vue";
import ActivityState from "../components/activity/ActivityState.vue";
import NavIcon from "../components/NavIcon.vue";
import { loadOptional } from "../coreStatus";
import { TONE_BADGE, type Availability, type Tone } from "../hubModel";
import TradingStatus from "../components/trading/TradingStatus.vue";
import { countOf } from "../nodeModels";
import { locked } from "../lock";
import {
  SEVERITY_LABEL, SEVERITY_TONE, asOfText, ibkrLinkState, ibkrStatusFacts, ibkrStatusState, readPortfolio,
  type IbkrStatus, type Portfolio,
} from "../trading";
import { tradingApi, useTradingRead } from "../tradingApi";

// Integrations: the brains, model catalog and host tools Core probed, the
// owner model policy and the IBKR link. Codex, GitHub and OpenSandbox are
// not reported by Core yet.
type Brain = { id: string; label: string; cost: string; available: boolean; note: string };
type CatalogModel = { id: string; backend: string; class: string; cost: string; available: boolean };
type Software = { name: string; present: boolean; version: string | null; detail: string | null };
type Registry = { brains?: Brain[]; models?: CatalogModel[]; software?: Software[]; active_brain?: string };
type PolicyModel = { provider: string; model: string; enabled: boolean; route?: string };

const registry = ref<Availability<Registry>>({ state: "loading" });
const policy = ref<Availability<{ models: PolicyModel[] }>>({ state: "loading" });
// IBKR: Core's status route; an older Core without it (404/405) falls back
// to the portfolio: a portfolio from Core means connected.
const ibkrStatus = ref<Availability<IbkrStatus>>({ state: "loading" });
const statusNow = ref(Date.now());
let statusSeq = 0;
async function loadIbkrStatus() {
  const mine = ++statusSeq;
  ibkrStatus.value = { state: "loading" };
  const next = await tradingApi.ibkrStatus();
  // Only the newest read lands, and none behind the app lock.
  if (mine !== statusSeq || locked.value) return;
  ibkrStatus.value = next;
  statusNow.value = Date.now();
}
// Locking drops the status (fail closed); unlocking reads it again.
watch(locked, (isLocked) => {
  statusSeq++;
  if (isLocked) ibkrStatus.value = { state: "loading" };
  else void loadIbkrStatus();
});
onMounted(loadIbkrStatus);
const ibkrFallback = computed(() => ibkrStatus.value.state === "unsupported");
const ibkrFacts = computed(() => (ibkrStatus.value.state === "ok" ? ibkrStatusFacts(ibkrStatus.value.value, statusNow.value) : []));
const localTime = (iso: string) => new Date(iso).toLocaleString();
const copied = ref<string | null>(null);
let copyTimer: ReturnType<typeof setTimeout> | undefined;
async function copyCommand(key: string, command: string) {
  clearTimeout(copyTimer);
  try {
    await navigator.clipboard.writeText(command);
    copied.value = key;
    copyTimer = setTimeout(() => { copied.value = null; }, 2000);
  } catch {
    copied.value = null; // the command stays selectable
  }
}
onBeforeUnmount(() => clearTimeout(copyTimer));
const { state: ibkr, reload: reloadIbkr } = useTradingRead<Portfolio>({
  live: tradingApi.portfolio,
  fixture: (f, set) => f.portfolioFixture(set),
  read: readPortfolio,
  isEmpty: (p) => p.positions.length === 0,
});
async function load() {
  [registry.value, policy.value] = await Promise.all([
    loadOptional<Registry>("/v1/system/registry"),
    loadOptional<{ models: PolicyModel[] }>("/v1/system/models"),
  ]);
}
onMounted(load);

const reg = computed<Registry>(() => (registry.value.state === "ok" ? registry.value.value : {}));
const brains = computed(() => reg.value.brains ?? []);
const catalog = computed(() => reg.value.models ?? []);
const software = computed(() => reg.value.software ?? []);
const allowed = computed(() => (policy.value.state === "ok" ? policy.value.value.models.filter((m) => m.enabled) : []));
const policyCount = computed(() => (policy.value.state === "ok" ? policy.value.value.models.length : 0));

function status<T>(source: Availability<T>, ok: (value: T) => [Tone, string]): [Tone, string] {
  if (source.state === "ok") return ok(source.value);
  if (source.state === "loading") return ["idle", "Loading…"];
  return source.state === "unsupported" ? ["idle", "Requires newer Core"] : ["warn", "Unavailable"];
}
const planned = (id: string, title: string, icon: NodeItem["icon"], description: string): NodeItem =>
  ({ id, title, icon, description, tone: "idle", status: "Not yet available" });
const items = computed<NodeItem[]>(() => {
  const b = status(registry.value, () => [brains.value.some((x) => x.available) ? "ok" : "idle", `${countOf(brains.value, (x) => x.available)} ready`]);
  const m = status(policy.value, () => [allowed.value.length ? "ok" : "idle", `${allowed.value.length} allowed`]);
  const i = ibkrFallback.value ? ibkrLinkState(ibkr.value) : status(ibkrStatus.value, ibkrStatusState);
  return [
    {
      id: "brains", title: "AI brains", icon: "spark", tone: b[0], status: b[1],
      description: "The brains Core found on your Home Node. Ready means it is set up, not that it is in use.",
      meta: reg.value.active_brain ? `Active brain: ${reg.value.active_brain}` : undefined,
    },
    {
      id: "models", title: "Models", icon: "layers", tone: m[0], status: m[1],
      description: "Allowing a model lets Jarvis choose it; it never selects a model for a running request.",
    },
    {
      id: "ibkr", title: "Interactive Brokers", icon: "trend", tone: i[0], status: i[1],
      description: "Core's read-only link to Interactive Brokers. Jarvis only reads; it never places orders.",
    },
    planned("codex", "Codex", "terminal", "Core does not report the Codex broker status to the app yet."),
    planned("github", "GitHub", "branch", "Core does not report a GitHub connection to the app yet."),
    planned("sandbox", "OpenSandbox", "sandbox", "Core does not report OpenSandbox status to the app yet."),
  ];
});
const selected = ref("brains");
const sub = ref("overview");
watch(selected, () => { sub.value = "overview"; });
const SUB_TABS: Record<string, SegmentItem[]> = {
  brains: [{ id: "overview", label: "Overview" }, { id: "tools", label: "Host tools" }],
  models: [{ id: "overview", label: "Overview" }, { id: "catalog", label: "Catalog" }],
};
const subTabs = computed(() => SUB_TABS[selected.value] ?? []);

const COST: Record<string, string> = { plan: "Subscription", metered: "Pay per use", local: "Local", cheap: "Cheap", mid: "Mid", pricey: "Pricey" };
const brainRows = computed<Row[]>(() => brains.value.map((b) => ({
  id: b.id, title: b.label, detail: [COST[b.cost] ?? b.cost, b.note].filter(Boolean).join(" · "),
  tone: b.available ? "ok" : "idle", status: b.available ? "Ready" : "Not set up",
})));
const toolRows = computed<Row[]>(() => software.value.map((s) => ({
  id: s.name, title: s.name + (s.version ? ` ${s.version}` : ""), detail: s.detail ?? undefined,
  tone: s.present ? "ok" : "idle", status: s.present ? "Present" : "Missing",
})));
const allowedRows = computed<Row[]>(() => allowed.value.map((m) => ({
  id: `${m.provider}/${m.model}`, title: m.model, detail: m.provider + (m.route ? ` · route ${m.route}` : ""), tone: "ok", status: "Allowed",
})));
const catalogRows = computed<Row[]>(() => catalog.value.map((m) => ({
  id: `${m.backend}/${m.id}`, title: m.id, detail: `${m.backend} · ${m.class} · ${COST[m.cost] ?? m.cost}`,
  tone: m.available ? "ok" : "idle", status: m.available ? "Provider set up" : "Provider not set up",
})));
const readyOf = (cost: string) => `${countOf(brains.value.filter((b) => b.cost === cost), (b) => b.available)} ready`;
</script>

<template>
  <NodePage v-model="selected" title="Integrations" subtitle="The systems Jarvis can reach" :items="items">
    <template v-if="['brains', 'models', 'ibkr'].includes(selected)" #actions>
      <RouterLink v-if="selected === 'models'" to="/health?node=models" class="btn btn-secondary btn-sm">Manage access<NavIcon name="arrow-right" /></RouterLink>
      <template v-else-if="selected === 'ibkr'">
        <button v-if="!ibkrFallback" type="button" class="btn btn-secondary btn-sm" @click="loadIbkrStatus">Refresh</button>
        <RouterLink to="/trading" class="btn btn-secondary btn-sm">Open trading desk<NavIcon name="arrow-right" /></RouterLink>
      </template>
      <button v-else type="button" class="btn btn-secondary btn-sm" @click="load">Refresh</button>
    </template>

    <JvSegmented v-if="subTabs.length" v-model="sub" class="np-seg" :items="subTabs" :label="`${items.find((i) => i.id === selected)?.title} sections`" />

    <!-- AI brains + host tools (registry) -->
    <ActivityState v-if="selected === 'brains'" nested :state="registry" what="the registry" @retry="load">
      <div v-if="sub === 'overview'" class="np-cols">
        <dl class="np-facts" aria-label="Brains at a glance">
          <div><dt>Active brain</dt><dd>{{ reg.active_brain || "Not reported" }}</dd></div>
          <div><dt>Brains ready</dt><dd>{{ countOf(brains, (b) => b.available) }}</dd></div>
          <div><dt>Subscription</dt><dd>{{ readyOf("plan") }}</dd></div>
          <div><dt>Pay per use</dt><dd>{{ readyOf("metered") }}</dd></div>
          <div><dt>Local</dt><dd>{{ readyOf("local") }}</dd></div>
          <div><dt>Host tools</dt><dd>{{ countOf(software, (s) => s.present) }} present</dd></div>
        </dl>
        <RowList title="Brains" :rows="brainRows" empty="Core reported no brains." />
      </div>
      <RowList v-else title="Host tools" :rows="toolRows" empty="Core reported no host tools." />
    </ActivityState>

    <!-- Models: owner policy + catalog -->
    <template v-else-if="selected === 'models'">
      <ActivityState v-if="sub === 'overview'" nested :state="policy" what="the model policy" @retry="load">
        <div class="np-cols">
          <dl class="np-facts" aria-label="Owner policy">
            <div><dt>Allowed</dt><dd>{{ allowed.length }}</dd></div>
            <div><dt>Blocked</dt><dd>{{ policyCount - allowed.length }}</dd></div>
            <div><dt>In catalog</dt><dd>{{ registry.state === "ok" ? catalog.length : "Unavailable" }}</dd></div>
            <div><dt>Changes</dt><dd>Under System health</dd></div>
          </dl>
          <RowList title="Allowed models" :rows="allowedRows" empty="No model is allowed yet." />
        </div>
      </ActivityState>
      <template v-else>
        <p class="np-note">Set up means Core has a key or local install for the provider. Whether Jarvis may use a model is the owner policy.</p>
        <ActivityState nested :state="registry" what="the model catalog" @retry="load">
          <RowList title="Catalog" :rows="catalogRows" empty="Core reported no models." />
        </ActivityState>
      </template>
    </template>

    <!-- IBKR: status, and what to fix. Commands are only shown to copy; never run, linked or opened. -->
    <ActivityState v-else-if="selected === 'ibkr' && !ibkrFallback" nested :state="ibkrStatus" what="the IBKR status" @retry="loadIbkrStatus" v-slot="{ data }">
      <div class="np-cols">
        <div class="ib-facts">
          <dl class="np-facts" aria-label="IBKR status">
            <div v-for="fact in ibkrFacts" :key="fact.label">
              <dt>{{ fact.label }}</dt>
              <dd :title="fact.at ? localTime(fact.at) : undefined"><code v-if="fact.code" class="mono">{{ fact.code }}</code>{{ fact.value }}</dd>
            </div>
          </dl>
          <p v-if="data.ibkr" class="np-note">The brokerage session is only needed for charts.</p>
        </div>
        <section class="ib-fix" aria-labelledby="ib-fix-title">
          <h3 id="ib-fix-title">What to fix</h3>
          <ul v-if="data.problems.length" class="ib-problems">
            <li v-for="(p, i) in data.problems" :key="`${i}-${p.code}`">
              <div class="ib-summary">
                <span class="badge" :class="TONE_BADGE[SEVERITY_TONE[p.severity]]">{{ SEVERITY_LABEL[p.severity] }}</span>
                <span>{{ p.summary }}</span>
              </div>
              <div v-for="(c, j) in p.commands" :key="j" class="ib-cmd">
                <code class="mono">{{ c }}</code>
                <button type="button" class="btn btn-secondary btn-sm" :aria-label="`Copy command: ${c}`" @click="copyCommand(`${i}-${j}`, c)">{{ copied === `${i}-${j}` ? "Copied" : "Copy" }}</button>
              </div>
            </li>
          </ul>
          <p v-else class="np-empty">Nothing to fix.</p>
          <p v-if="data.problems.some((p) => p.commands.length)" class="np-note">Run these yourself on the Home Node. Jarvis never runs them.</p>
        </section>
      </div>
    </ActivityState>
    <TradingStatus v-else-if="selected === 'ibkr'" :state="ibkr" what="the IBKR link" compact @retry="reloadIbkr" v-slot="{ data }">
      <dl class="np-facts" aria-label="IBKR link">
        <div><dt>Connection</dt><dd>{{ ibkrLinkState(ibkr)[1] }}</dd></div>
        <div><dt>Last sync</dt><dd>{{ asOfText(data.as_of) }}</dd></div>
        <div><dt>Positions</dt><dd>{{ data.positions.length }}</dd></div>
        <div><dt>Access</dt><dd>Read-only</dd></div>
      </dl>
    </TradingStatus>

    <p v-else class="np-empty"><strong>Not yet available</strong>Nothing to show until Core reports it.</p>
  </NodePage>
</template>

<style scoped>
.ib-facts { display: flex; flex-direction: column; gap: 10px; }
.ib-fix { min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.ib-fix h3 { margin: 0; }
.ib-problems { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.ib-problems li { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; border-radius: var(--r-md); box-shadow: var(--shadow-border); }
.ib-summary { display: flex; align-items: center; gap: 10px; font-size: 14px; }
.ib-cmd { display: flex; align-items: center; gap: 8px; }
.ib-cmd code { flex: 1; min-width: 0; padding: 6px 8px; border-radius: 6px; background: var(--bg-sunken); font-size: 12px; overflow-wrap: anywhere; user-select: text; }
.np-facts dd code { margin-right: 4px; font-size: 13px; }
</style>
