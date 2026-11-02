<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvSegmented, { type SegmentItem } from "../components/jv/JvSegmented.vue";
import RowList, { type Row } from "../components/RowList.vue";
import ActivityState from "../components/activity/ActivityState.vue";
import NavIcon from "../components/NavIcon.vue";
import { loadOptional } from "../coreStatus";
import { relativeTime, type Availability } from "../hubModel";
import {
  AGENT_USAGE_NOT_INSTRUMENTED, agentStatus, formatMs, groupAgents, isTrader, measuredCount, type AgentInfo, type AgentsResponse,
} from "../nodeModels";

// Agents: the private agent bundle installed on Core (read-only metadata and
// this month's usage). Core reports no live run state, so none is shown.
const source = ref<Availability<AgentsResponse>>({ state: "loading" });
async function load() {
  source.value = { state: "loading" };
  source.value = await loadOptional<AgentsResponse>("/v1/agents");
}
onMounted(load);

const response = computed(() => (source.value.state === "ok" ? source.value.value : null));
const usageReason = computed(() => response.value?.usage_unavailable_reason ?? null);
const ordered = computed(() => groupAgents(response.value?.agents ?? []).flatMap((g) => g.agents));
const items = computed<NodeItem[]>(() => ordered.value.map((agent) => {
  const [tone, status] = agentStatus(agent.usage, usageReason.value);
  return {
    id: agent.id, title: agent.name, icon: isTrader(agent) ? "trend" : "agents", description: agent.description, tone, status,
    meta: agent.group ? `Group: ${agent.group}` : undefined,
  };
}));
const selected = ref("");
watch(ordered, (list) => { if (!list.some((a) => a.id === selected.value)) selected.value = list[0]?.id ?? ""; }, { immediate: true });
const sub = ref("overview");
watch(selected, () => { sub.value = "overview"; });
const agent = computed<AgentInfo | undefined>(() => ordered.value.find((a) => a.id === selected.value));
const SUB_TABS: SegmentItem[] = [{ id: "overview", label: "Overview" }, { id: "tools", label: "Tools" }, { id: "usage", label: "Usage" }];

const number = (n: number) => new Intl.NumberFormat("en").format(n);
const compact = (n: number) => new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(n);
const toolRows = computed<Row[]>(() => (agent.value?.allowed_tools ?? []).map((tool) => ({ id: tool, title: tool, tone: "ok", status: "Allowed" })));
// A bundle with no agents, or no bundle at all.
const noAgents = computed(() => (response.value?.unavailable_reason
  ? "Core has no agent bundle installed, so there are no agents to show."
  : "The installed agent bundle has no agents."));
</script>

<template>
  <NodePage v-model="selected" title="Agents" subtitle="Your personal AI system" :items="items">
    <template v-if="agent && isTrader(agent)" #actions>
      <RouterLink to="/trading" class="btn btn-secondary btn-sm">Open Trading<NavIcon name="arrow-right" /></RouterLink>
    </template>

    <template v-if="agent">
      <JvSegmented v-model="sub" class="np-seg" :items="SUB_TABS" :label="`${agent.name} sections`" />

      <dl v-if="sub === 'overview'" class="np-facts" aria-label="Policy and limits">
        <div><dt>Model policy</dt><dd>{{ agent.model_policy }}</dd></div>
        <div><dt>Allowed tools</dt><dd>{{ agent.allowed_tools.length }}</dd></div>
        <div><dt>Max runtime</dt><dd>{{ number(agent.limits.max_runtime_seconds) }} s</dd></div>
        <div><dt>Parallel runs</dt><dd>Up to {{ agent.limits.max_parallel_runs }}</dd></div>
        <div><dt>Max context</dt><dd>{{ number(agent.limits.max_context_chars) }} chars</dd></div>
        <div><dt>Max output</dt><dd>{{ number(agent.limits.max_output_chars) }} chars</dd></div>
      </dl>

      <RowList v-else-if="sub === 'tools'" title="Allowed tools" :rows="toolRows" empty="This agent may not use any tools." />

      <template v-else>
        <p v-if="!agent.usage" class="np-empty">
          <strong>Usage</strong>
          {{ usageReason === AGENT_USAGE_NOT_INSTRUMENTED ? "Not measured yet: Core does not record which agent made each call." : "Core could not read the usage statistics for this month." }}
        </p>
        <dl v-else class="np-facts" aria-label="Usage this month">
          <div><dt>Requests this month</dt><dd>{{ number(agent.usage.requests) }}</dd></div>
          <div><dt>Spent</dt><dd>€{{ agent.usage.spent_eur.toFixed(2) }}</dd></div>
          <div><dt>Tokens in / out</dt><dd>{{ compact(agent.usage.input_tokens) }} / {{ compact(agent.usage.output_tokens) }}</dd></div>
          <div><dt>Latency p50 / p95</dt><dd>{{ formatMs(agent.usage.latency_p50_ms) }} / {{ formatMs(agent.usage.latency_p95_ms) }}</dd></div>
          <div><dt>Failures</dt><dd>{{ measuredCount(agent.usage.failures) }}</dd></div>
          <div><dt>Fallbacks</dt><dd>{{ measuredCount(agent.usage.fallbacks) }}</dd></div>
          <div><dt>Last used</dt><dd>{{ agent.usage.last_used ? relativeTime(agent.usage.last_used, Date.now()) || agent.usage.last_used : "Not this month" }}</dd></div>
        </dl>
      </template>
    </template>

    <ActivityState v-else :state="source" what="agents" forbidden="Agent is turned off in Core." @retry="load">
      <section class="card empty" aria-labelledby="agents-empty">
        <span class="tile acc" aria-hidden="true"><NavIcon name="agents" /></span>
        <h2 id="agents-empty">No agents yet</h2>
        <p class="t-sm">{{ noAgents }}</p>
      </section>
    </ActivityState>
  </NodePage>
</template>

<style scoped>
.empty h2 { margin: 0; font-size: 15px; line-height: 1.4; color: var(--text-primary); }
.empty p { margin: 0; max-width: 420px; }
</style>
