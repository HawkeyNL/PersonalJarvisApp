<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvPanel from "../components/jv/JvPanel.vue";
import JvSegmented, { type SegmentItem } from "../components/jv/JvSegmented.vue";
import JvTile from "../components/jv/JvTile.vue";
import JvActivityList, { type ActivityItem } from "../components/jv/JvActivityList.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";
import NavIcon from "../components/NavIcon.vue";
import { loadOptional } from "../coreStatus";
import type { Availability, Tone } from "../hubModel";
import TradingStatus from "../components/trading/TradingStatus.vue";
import { countOf } from "../nodeModels";
import { asOfText, ibkrLinkState, readPortfolio, type Portfolio } from "../trading";
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
// IBKR has no status route: a portfolio from Core means connected.
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
const planned = (id: string, label: string, title: string, icon: NodeItem["icon"], description: string): NodeItem =>
  ({ id, label, title, icon, description, tone: "idle", status: "Not yet available" });
const items = computed<NodeItem[]>(() => {
  const b = status(registry.value, () => [brains.value.some((x) => x.available) ? "ok" : "idle", `${countOf(brains.value, (x) => x.available)} ready`]);
  const m = status(policy.value, () => [allowed.value.length ? "ok" : "idle", `${allowed.value.length} allowed`]);
  const i = ibkrLinkState(ibkr.value);
  return [
    { id: "brains", label: "Brains", title: "AI brains", icon: "spark", description: "Who does the thinking, and how it is paid", tone: b[0], status: b[1] },
    { id: "models", label: "Models", title: "Models", icon: "layers", description: "Which models Jarvis may use", tone: m[0], status: m[1] },
    { id: "ibkr", label: "IBKR", title: "Interactive Brokers", icon: "trend", description: "Read-only broker link for trading", tone: i[0], status: i[1] },
    planned("codex", "Codex", "Codex", "terminal", "Second opinion, behind the broker"),
    planned("github", "GitHub", "GitHub", "branch", "Repos, PRs and releases"),
    planned("sandbox", "Sandbox", "OpenSandbox", "sandbox", "Disposable space for code runs"),
  ];
});
const selected = ref("brains");
const sub = ref("overview");
watch(selected, () => { sub.value = "overview"; });
const current = computed(() => items.value.find((item) => item.id === selected.value)!);
const SUB_TABS: Record<string, SegmentItem[]> = {
  brains: [{ id: "overview", label: "Overview", icon: "doc" }, { id: "tools", label: "Host tools", icon: "sandbox" }],
  models: [{ id: "overview", label: "Overview", icon: "doc" }, { id: "catalog", label: "Catalog", icon: "layers" }],
};

const subTabs = computed(() => SUB_TABS[selected.value] ?? []);

const DETAILS: Record<string, string> = {
  brains: "The brains Core found on your Home Node. Ready means it is set up, not that it is in use.",
  models: "Allowing a model lets Jarvis choose it; it never selects a model for a running request.",
  ibkr: "Core's read-only link to Interactive Brokers. Jarvis only reads; it never places orders.",
  codex: "Core does not report the Codex broker status to the app yet.",
  github: "Core does not report a GitHub connection to the app yet.",
  sandbox: "Core does not report OpenSandbox status to the app yet.",
};

const COST: Record<string, string> = { plan: "Subscription", metered: "Pay per use", local: "Local", cheap: "Cheap", mid: "Mid", pricey: "Pricey" };
const brainRows = computed<ActivityItem[]>(() => brains.value.map((b) => ({
  id: b.id, icon: "spark", title: b.label, detail: [COST[b.cost] ?? b.cost, b.note].filter(Boolean).join(" · "),
  tone: b.available ? "ok" : "idle", toneLabel: b.available ? "Ready" : "Not set up",
})));
const toolRows = computed<ActivityItem[]>(() => software.value.map((s) => ({
  id: s.name, icon: "sandbox", title: s.name + (s.version ? ` ${s.version}` : ""), detail: s.detail ?? undefined,
  tone: s.present ? "ok" : "idle", toneLabel: s.present ? "Present" : "Missing",
})));
const allowedRows = computed<ActivityItem[]>(() => allowed.value.map((m) => ({
  id: `${m.provider}/${m.model}`, icon: "layers", title: m.model, detail: m.provider + (m.route ? ` · route ${m.route}` : ""),
  tone: "ok", toneLabel: "Allowed",
})));
const catalogRows = computed<ActivityItem[]>(() => catalog.value.map((m) => ({
  id: `${m.backend}/${m.id}`, icon: "layers", title: m.id, detail: `${m.backend} · ${m.class} · ${COST[m.cost] ?? m.cost}`,
  tone: m.available ? "ok" : "idle", toneLabel: m.available ? "Provider set up" : "Provider not set up",
})));
</script>

<template>
  <NodePage v-model="selected" title="INTEGRATIONS" subtitle="THE SYSTEMS JARVIS CAN REACH" :items="items">
    <JvPanel :icon="current.icon" :title="current.title" :tone="current.tone" :status="current.status" :description="DETAILS[current.id]"
      :quote="current.id === 'brains' && reg.active_brain ? `Active brain: ${reg.active_brain}` : undefined">
      <template #action>
        <RouterLink v-if="current.id === 'models'" to="/health?node=models" class="ghost-btn">Manage access<NavIcon name="arrow-right" /></RouterLink>
        <RouterLink v-else-if="current.id === 'ibkr'" to="/trading" class="ghost-btn">Open trading desk<NavIcon name="arrow-right" /></RouterLink>
        <button v-else-if="current.id === 'brains'" type="button" class="ghost-btn" @click="load">Refresh</button>
      </template>

      <JvSegmented v-if="subTabs.length" v-model="sub" :items="subTabs" :label="`${current.title} sections`" />

      <!-- AI brains + host tools (registry) -->
      <template v-if="current.id === 'brains'">
        <JvUnavailable v-if="registry.state === 'unsupported' || registry.state === 'error'" title="Registry"
          :kind="registry.state === 'unsupported' ? 'core-update' : 'error'" icon="spark" />
        <p v-else-if="registry.state === 'loading'" class="muted" role="status">Loading registry…</p>
        <div v-else-if="sub === 'overview'" class="node-split">
          <section class="node-tiles" aria-label="Brains">
            <h3 class="eyebrow">AT A GLANCE</h3>
            <div class="node-grid">
              <JvTile icon="spark" label="Active brain" :value="reg.active_brain || 'Not reported'" />
              <JvTile icon="health" label="Brains ready" :value="countOf(brains, (b) => b.available)" />
              <JvTile icon="user" label="Subscription" :value="countOf(brains.filter((b) => b.cost === 'plan'), (b) => b.available) + ' ready'" />
              <JvTile icon="trend" label="Pay per use" :value="countOf(brains.filter((b) => b.cost === 'metered'), (b) => b.available) + ' ready'" />
              <JvTile icon="home" label="Local" :value="countOf(brains.filter((b) => b.cost === 'local'), (b) => b.available) + ' ready'" />
              <JvTile icon="sandbox" label="Host tools" :value="countOf(software, (s) => s.present) + ' present'" />
            </div>
          </section>
          <JvActivityList title="Brains" :items="brainRows" empty="Core reported no brains." />
        </div>
        <JvActivityList v-else title="Host tools" :items="toolRows" empty="Core reported no host tools." />
      </template>

      <!-- Models: owner policy + catalog -->
      <template v-else-if="current.id === 'models'">
        <template v-if="sub === 'overview'">
          <JvUnavailable v-if="policy.state === 'unsupported' || policy.state === 'error'" title="Model policy"
            :kind="policy.state === 'unsupported' ? 'core-update' : 'error'" icon="layers" />
          <p v-else-if="policy.state === 'loading'" class="muted" role="status">Loading model policy…</p>
          <div v-else class="node-split">
            <section class="node-tiles" aria-label="Model access">
              <h3 class="eyebrow">OWNER POLICY</h3>
              <div class="node-grid">
                <JvTile icon="health" label="Allowed" :value="String(allowed.length)" />
                <JvTile icon="shield" label="Blocked" :value="String(policyCount - allowed.length)" />
                <JvTile icon="layers" label="In catalog" :value="registry.state === 'ok' ? String(catalog.length) : 'Unavailable'" />
                <JvTile icon="key" label="Changes" value="Under System Health" />
              </div>
            </section>
            <JvActivityList title="Allowed models" :items="allowedRows" empty="No model is allowed yet." />
          </div>
        </template>
        <template v-else>
          <p class="muted small">Set up means Core has a key or local install for the provider. Whether Jarvis may use a model is the owner policy.</p>
          <JvUnavailable v-if="registry.state === 'unsupported' || registry.state === 'error'" title="Model catalog"
            :kind="registry.state === 'unsupported' ? 'core-update' : 'error'" icon="layers" />
          <JvActivityList v-else title="Catalog" :items="catalogRows" :empty="registry.state === 'loading' ? 'Loading…' : 'Core reported no models.'" />
        </template>
      </template>

      <!-- IBKR -->
      <template v-else-if="current.id === 'ibkr'">
        <TradingStatus :state="ibkr" what="the IBKR link" @retry="reloadIbkr" v-slot="{ data }">
          <div class="node-grid ibkr">
            <JvTile icon="link-2" label="Connection" :value="current.status" />
            <JvTile icon="clock" label="Last sync" :value="asOfText(data.as_of)" />
            <JvTile icon="trend" label="Positions" :value="String(data.positions.length)" />
            <JvTile icon="shield" label="Access" value="Read-only" />
          </div>
        </TradingStatus>
      </template>

      <JvUnavailable v-else :title="current.title" :icon="current.icon" :detail="DETAILS[current.id]" />
    </JvPanel>
  </NodePage>
</template>

<style scoped>
.small { font-size: var(--fs-12); margin: 0; }
.ibkr { max-width: 544px; }
</style>
