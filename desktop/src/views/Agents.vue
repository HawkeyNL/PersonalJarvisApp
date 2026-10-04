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
import { relativeTime, type Availability } from "../hubModel";
import {
  AGENT_USAGE_NOT_INSTRUMENTED, agentStatus, formatMs, groupAgents, isTrader, measuredCount, type AgentInfo, type AgentsResponse,
} from "../nodeModels";

// Agents: the private agent bundle installed on Core (read-only metadata and
// this month's usage). Core reports no live run state, so none is shown.
const source = ref<Availability<AgentsResponse>>({ state: "loading" });
onMounted(async () => { source.value = await loadOptional<AgentsResponse>("/v1/agents"); });

const response = computed(() => (source.value.state === "ok" ? source.value.value : null));
const usageReason = computed(() => response.value?.usage_unavailable_reason ?? null);
const groups = computed(() => groupAgents(response.value?.agents ?? []));
const ordered = computed(() => groups.value.flatMap((g) => g.agents));
const items = computed<NodeItem[]>(() => ordered.value.map((agent) => {
  const [tone, status] = agentStatus(agent.usage, usageReason.value);
  return {
    id: agent.id, label: agent.name, title: agent.name, icon: isTrader(agent) ? "trend" : "agents",
    description: agent.description, tone, status,
  };
}));
const selected = ref("");
watch(ordered, (list) => { if (!list.some((a) => a.id === selected.value)) selected.value = list[0]?.id ?? ""; }, { immediate: true });
const sub = ref("overview");
watch(selected, () => { sub.value = "overview"; });
const agent = computed<AgentInfo | undefined>(() => ordered.value.find((a) => a.id === selected.value));
const current = computed(() => items.value.find((item) => item.id === selected.value));
const SUB_TABS: SegmentItem[] = [
  { id: "overview", label: "Overview", icon: "doc" }, { id: "tools", label: "Tools", icon: "terminal" }, { id: "usage", label: "Usage", icon: "trend" },
];

const number = (n: number) => new Intl.NumberFormat("en").format(n);
const compact = (n: number) => new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(n);
const groupRows = computed(() => groups.value.map((g) => ({
  group: g.group,
  rows: g.agents.map((a): ActivityItem => {
    const [tone, label] = agentStatus(a.usage, usageReason.value);
    return { id: a.id, icon: isTrader(a) ? "trend" : "agents", title: a.name, detail: a.model_policy, tone, toneLabel: label };
  }),
})));
const toolRows = computed<ActivityItem[]>(() => (agent.value?.allowed_tools ?? []).map((tool) => ({
  id: tool, icon: "terminal", title: tool, tone: "ok", toneLabel: "Allowed",
})));
// Without a bundle (or on an older Core) there is nothing to select.
const empty = computed(() => {
  if (source.value.state === "unsupported") return { kind: "core-update" as const, detail: "This Core has no agent overview yet. Update Core to see your agents here." };
  if (source.value.state === "error") return { kind: "error" as const, detail: "Sign in and check the connection to your Home Node." };
  if (response.value && !response.value.agents.length) {
    return response.value.unavailable_reason
      ? { kind: "planned" as const, detail: "Core has no agent bundle installed, so there are no agents to show." }
      : { kind: "planned" as const, detail: "The installed agent bundle has no agents." };
  }
  return null;
});
</script>

<template>
  <NodePage v-model="selected" title="AGENTS" subtitle="YOUR PERSONAL AI SYSTEM" :items="items">
    <JvPanel v-if="agent && current" :icon="current.icon" :title="agent.name" :tone="current.tone" :status="current.status"
      :description="agent.description" :quote="agent.group ? `Group: ${agent.group}` : undefined">
      <template v-if="isTrader(agent)" #action>
        <RouterLink to="/trading" class="ghost-btn">Open Trading<NavIcon name="arrow-right" /></RouterLink>
      </template>

      <JvSegmented v-model="sub" :items="SUB_TABS" :label="`${agent.name} sections`" />

      <div v-if="sub === 'overview'" class="node-split">
        <section class="node-tiles" aria-label="Policy and limits">
          <h3 class="eyebrow">POLICY &amp; LIMITS</h3>
          <div class="node-grid">
            <JvTile icon="layers" label="Model policy" :value="agent.model_policy" />
            <JvTile icon="terminal" label="Allowed tools" :value="String(agent.allowed_tools.length)" />
            <JvTile icon="clock" label="Max runtime" :value="`${number(agent.limits.max_runtime_seconds)} s`" />
            <JvTile icon="repeat" label="Parallel runs" :value="`Up to ${agent.limits.max_parallel_runs}`" />
            <JvTile icon="doc" label="Max context" :value="`${number(agent.limits.max_context_chars)} chars`" />
            <JvTile icon="lines" label="Max output" :value="`${number(agent.limits.max_output_chars)} chars`" />
          </div>
        </section>
        <div class="groups">
          <JvActivityList v-for="g in groupRows" :key="g.group" :title="g.group" :items="g.rows" empty="">
            <template #actions="{ item }">
              <button v-if="item.id !== selected" type="button" class="ghost-btn sm" :aria-label="`Show ${item.title}`" @click="selected = item.id">Show</button>
            </template>
          </JvActivityList>
        </div>
      </div>

      <JvActivityList v-else-if="sub === 'tools'" title="Allowed tools" :items="toolRows" empty="This agent may not use any tools." />

      <template v-else>
        <JvUnavailable v-if="!agent.usage && usageReason === AGENT_USAGE_NOT_INSTRUMENTED" title="Usage" icon="trend"
          detail="Not measured yet: Core does not record which agent made each call." />
        <JvUnavailable v-else-if="!agent.usage" title="Usage" kind="error" icon="trend"
          detail="Core could not read the usage statistics for this month." />
        <div v-else class="node-grid usage">
          <JvTile icon="api" label="Requests this month" :value="number(agent.usage.requests)" />
          <JvTile icon="trend" label="Spent" :value="`€${agent.usage.spent_eur.toFixed(2)}`" />
          <JvTile icon="layers" label="Tokens in / out" :value="`${compact(agent.usage.input_tokens)} / ${compact(agent.usage.output_tokens)}`" />
          <JvTile icon="clock" label="Latency p50 / p95" :value="`${formatMs(agent.usage.latency_p50_ms)} / ${formatMs(agent.usage.latency_p95_ms)}`" />
          <JvTile icon="alert" label="Failures" :value="measuredCount(agent.usage.failures)" />
          <JvTile icon="restart" label="Fallbacks" :value="measuredCount(agent.usage.fallbacks)" />
          <JvTile icon="calendar" label="Last used" :value="agent.usage.last_used ? relativeTime(agent.usage.last_used, Date.now()) || agent.usage.last_used : 'Not this month'" />
        </div>
      </template>
    </JvPanel>

    <div v-else class="solo">
      <p v-if="source.state === 'loading'" class="muted" role="status">Loading agents…</p>
      <JvUnavailable v-else-if="empty" title="Agents" :kind="empty.kind" icon="agents" :detail="empty.detail" />
    </div>
  </NodePage>
</template>

<style scoped>
.groups { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.usage { max-width: 760px; }
.solo { padding: 24px; border-radius: var(--r-26); border: 1.5px solid var(--line-a55); background: var(--panel-bg); }
</style>
