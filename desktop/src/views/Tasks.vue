<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import PageShell from "../components/PageShell.vue";
import ApprovalsBoard from "../components/activity/ApprovalsBoard.vue";
import RunsBoard from "../components/activity/RunsBoard.vue";
import { loadOptional } from "../coreStatus";
import type { PendingAction } from "../agentApprovals";
import type { Availability } from "../hubModel";
import type { CodingSessionRow } from "../nodeModels";
import { RUN_FILTERS, type AuditLog, type RunFilter } from "../activity";

// Activity: /approvals (agent actions waiting for the owner, plus the agent
// audit history) and /runs (coding sessions). Both routes share this view; the
// Approvals route passes node "waiting".
const props = defineProps<{ node?: string }>();
const approvals = computed(() => props.node === "waiting");

const sessions = ref<Availability<{ sessions: CodingSessionRow[] }>>({ state: "loading" });
const pending = ref<Availability<{ pending: PendingAction[] }>>({ state: "loading" });
const audit = ref<Availability<AuditLog>>({ state: "loading" });
const now = ref(Date.now());
const loading = ref(false);

// Loads can overlap (Refresh, Try again, after a decision): only the latest one lands.
let generation = 0;
async function load() {
  const mine = ++generation;
  loading.value = true;
  const results = await Promise.all([
    loadOptional<{ sessions: CodingSessionRow[] }>("/v1/coding/sessions"),
    loadOptional<{ pending: PendingAction[] }>("/v1/agent/pending"),
    loadOptional<AuditLog>("/v1/agent/audit"),
  ]);
  if (mine !== generation) return;
  [sessions.value, pending.value, audit.value] = results;
  now.value = Date.now();
  loading.value = false;
}
onMounted(load);

const view = ref<"pending" | "history">("pending");
const runFilter = ref<RunFilter>("all");
const VIEWS = [{ id: "pending", label: "Waiting for you" }, { id: "history", label: "History" }] as const;

const subtitle = computed(() => {
  if (approvals.value) {
    if (pending.value.state !== "ok") return "Agent actions that need your OK";
    const n = pending.value.value.pending.length;
    return n ? `${n} ${n === 1 ? "request is" : "requests are"} waiting for you` : "All handled";
  }
  if (sessions.value.state !== "ok") return "What Jarvis is working on";
  const n = sessions.value.value.sessions.length;
  return n ? `${new Intl.NumberFormat("en").format(n)} ${n === 1 ? "run" : "runs"}` : "Nothing has run yet";
});
</script>

<template>
  <PageShell :title="approvals ? 'Approvals' : 'Runs'" :subtitle="subtitle">
    <template #actions>
      <div v-if="approvals" class="seg" role="group" aria-label="Show">
        <button v-for="v in VIEWS" :key="v.id" type="button" :aria-pressed="view === v.id" @click="view = v.id">{{ v.label }}</button>
      </div>
      <div v-else class="seg" role="group" aria-label="Status">
        <button v-for="f in RUN_FILTERS" :key="f.id" type="button" :aria-pressed="runFilter === f.id" @click="runFilter = f.id">{{ f.label }}</button>
      </div>
      <button type="button" class="btn btn-ghost" :disabled="loading" @click="load">Refresh</button>
    </template>

    <ApprovalsBoard v-if="approvals" :pending="pending" :audit="audit" :view="view" :now="now" :reload="load" />
    <RunsBoard v-else v-model:filter="runFilter" :sessions="sessions" :pending="pending" :audit="audit" :now="now" :reload="load" />
  </PageShell>
</template>
