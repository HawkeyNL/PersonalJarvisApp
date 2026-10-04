<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvPanel from "../components/jv/JvPanel.vue";
import JvSegmented, { type SegmentItem } from "../components/jv/JvSegmented.vue";
import JvTile from "../components/jv/JvTile.vue";
import JvActivityList, { type ActivityItem } from "../components/jv/JvActivityList.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";
import { loadOptional } from "../coreStatus";
import { approveAction, denyAction, type PendingAction } from "../agentApprovals";
import { relativeTime, type Availability, type Tone } from "../hubModel";
import { outcomeTone, sessionState, splitSessions, type CodingSessionRow } from "../nodeModels";

// Tasks: coding sessions, agent actions waiting for the owner, and the agent
// audit history. Scheduled and recurring tasks and goals need Core support
// that does not exist yet.
type AuditEntry = { action: string; risk: string; outcome: string; note: string | null; ts: string };
const sessions = ref<Availability<{ sessions: CodingSessionRow[] }>>({ state: "loading" });
const pending = ref<Availability<{ pending: PendingAction[] }>>({ state: "loading" });
const audit = ref<Availability<{ enabled: boolean; entries: AuditEntry[] }>>({ state: "loading" });
const now = ref(Date.now());

async function load() {
  [sessions.value, pending.value, audit.value] = await Promise.all([
    loadOptional<{ sessions: CodingSessionRow[] }>("/v1/coding/sessions"),
    loadOptional<{ pending: PendingAction[] }>("/v1/agent/pending"),
    loadOptional<{ enabled: boolean; entries: AuditEntry[] }>("/v1/agent/audit"),
  ]);
  now.value = Date.now();
}
onMounted(load);

const split = computed(() => splitSessions(sessions.value.state === "ok" ? sessions.value.value.sessions : []));
const running = computed(() => split.value.open.filter((s) => s.state === "active").length);
const waiting = computed(() => (pending.value.state === "ok" ? pending.value.value.pending : []));

function status<T>(source: Availability<T>, ok: () => [Tone, string]): [Tone, string] {
  if (source.state === "ok") return ok();
  if (source.state === "loading") return ["idle", "Loading…"];
  return source.state === "unsupported" ? ["idle", "Requires newer Core"] : ["warn", "Unavailable"];
}
const planned = (id: string, label: string, title: string, icon: NodeItem["icon"], description: string): NodeItem =>
  ({ id, label, title, icon, description, tone: "idle", status: "Not yet available" });
const items = computed<NodeItem[]>(() => {
  const active = status(sessions.value, () => [running.value ? "ok" : "idle", `${running.value} running`]);
  const wait = status(pending.value, () => waiting.value.length
    ? ["warn", `${waiting.value.length} ${waiting.value.length === 1 ? "approval" : "approvals"}`] : ["idle", "Nothing waiting"]);
  const done = status(sessions.value, () => [split.value.finished.length ? "ok" : "idle", `${split.value.finished.length} finished`]);
  return [
    { id: "active", label: "Active", title: "Active", icon: "play", description: "Coding sessions in progress", tone: active[0], status: active[1] },
    planned("scheduled", "Scheduled", "Scheduled", "calendar", "Runs at a set time or day"),
    { id: "waiting", label: "Waiting", title: "Waiting on you", icon: "alert", description: "Agent actions that need your OK", tone: wait[0], status: wait[1] },
    { id: "completed", label: "Completed", title: "Completed", icon: "tasks", description: "Finished sessions and agent history", tone: done[0], status: done[1] },
    planned("recurring", "Recurring", "Recurring", "repeat", "Things Jarvis does every week"),
    planned("goals", "Goals", "Goals", "goal", "Bigger outcomes split into tasks"),
  ];
});
const selected = ref("active");
const sub = ref("overview");
watch(selected, () => { sub.value = "overview"; });
const current = computed(() => items.value.find((item) => item.id === selected.value)!);
const SUB_TABS: SegmentItem[] = [{ id: "overview", label: "Sessions", icon: "code" }, { id: "history", label: "History", icon: "clock" }];

const DETAILS: Record<string, string> = {
  active: "Coding sessions Jarvis is running or has paused on your Home Node.",
  waiting: "Changes an agent proposed. Nothing runs until you approve it on a trusted device.",
  completed: "Coding sessions that ended, and every agent action Core recorded.",
  scheduled: "Core cannot run tasks at a set time yet.",
  recurring: "Core cannot repeat tasks on a schedule yet.",
  goals: "Core does not track goals or split them into tasks yet.",
};

const sessionRows = (list: CodingSessionRow[]): ActivityItem[] => list.map((s) => {
  const [tone, label] = sessionState(s.state);
  return {
    id: s.id, icon: "code", title: s.objective || "Coding session", detail: [label, s.repository].filter(Boolean).join(" · "),
    time: s.updated_at ? relativeTime(s.updated_at, now.value) : undefined, tone, toneLabel: label,
  };
});
const auditRows = computed<ActivityItem[]>(() => (audit.value.state === "ok" ? audit.value.value.entries : []).map((e, i) => ({
  id: `${e.ts}-${i}`, icon: "shield", title: e.action, detail: [e.risk, e.note].filter(Boolean).join(" · "),
  time: relativeTime(e.ts, now.value), tone: outcomeTone(e.outcome), toneLabel: e.outcome,
})));
const pendingRows = computed<ActivityItem[]>(() => waiting.value.map((p) => ({
  id: p.pending_id, icon: "alert", title: p.action, detail: p.preview, time: relativeTime(p.created_at, now.value),
  tone: "warn", toneLabel: "Waiting for approval",
})));
const lastUpdate = computed(() => {
  const first = sessions.value.state === "ok" ? sessions.value.value.sessions[0] : undefined;
  return first?.updated_at ? relativeTime(first.updated_at, now.value) || "—" : "—";
});

// --- Approve / deny -----------------------------------------------------------
const deciding = ref<string | null>(null);
const decisionError = ref<string | null>(null);
async function decide(id: string, approve: boolean) {
  const item = waiting.value.find((p) => p.pending_id === id);
  if (!item || deciding.value) return;
  deciding.value = id;
  decisionError.value = null;
  try {
    await (approve ? approveAction(item) : denyAction(item));
  } catch (error) {
    decisionError.value = `${approve ? "Approval" : "Denial"} failed: ${error instanceof Error ? error.message : String(error)}`;
  } finally {
    deciding.value = null;
    await load();
  }
}
</script>

<template>
  <NodePage v-model="selected" title="TASKS" subtitle="WHAT JARVIS IS WORKING ON" :items="items">
    <JvPanel :icon="current.icon" :title="current.title" :tone="current.tone" :status="current.status"
      :description="DETAILS[current.id]" :quote="current.id === 'waiting' ? 'Jarvis proposes, you decide.' : undefined">
      <template v-if="['active', 'waiting', 'completed'].includes(current.id)" #action>
        <button type="button" class="ghost-btn" @click="load">Refresh</button>
      </template>

      <!-- Active -->
      <template v-if="current.id === 'active'">
        <JvUnavailable v-if="sessions.state === 'unsupported' || sessions.state === 'error'" title="Coding sessions"
          :kind="sessions.state === 'unsupported' ? 'core-update' : 'error'" icon="code" />
        <p v-else-if="sessions.state === 'loading'" class="muted" role="status">Loading sessions…</p>
        <div v-else class="node-split">
          <section class="node-tiles" aria-label="Task counts">
            <h3 class="eyebrow">AT A GLANCE</h3>
            <div class="node-grid">
              <JvTile icon="play" label="Running" :value="String(running)" />
              <JvTile icon="clock" label="Paused" :value="String(split.open.length - running)" />
              <JvTile icon="alert" label="Waiting on you" :value="pending.state === 'ok' ? String(waiting.length) : 'Unavailable'" />
              <JvTile icon="tasks" label="Finished" :value="String(split.finished.length)" />
              <JvTile icon="shield" label="Agent actions"
                :value="audit.state === 'ok' ? (audit.value.enabled ? 'Enabled' : 'Disabled on Core') : 'Unavailable'" />
              <JvTile icon="calendar" label="Last update" :value="lastUpdate" />
            </div>
          </section>
          <JvActivityList title="Active & paused" :items="sessionRows(split.open).slice(0, 5)" empty="No coding session is running." />
        </div>
      </template>

      <!-- Waiting on you -->
      <template v-else-if="current.id === 'waiting'">
        <JvUnavailable v-if="pending.state === 'unsupported' || pending.state === 'error'" title="Approvals"
          :kind="pending.state === 'unsupported' ? 'core-update' : 'error'" icon="alert" />
        <p v-else-if="pending.state === 'loading'" class="muted" role="status">Loading approvals…</p>
        <template v-else>
          <p class="muted small">Approving asks this device to confirm it is you, then signs the action with its device key.</p>
          <p v-if="decisionError" class="err" role="alert">{{ decisionError }}</p>
          <JvActivityList title="Waiting for approval" :items="pendingRows" empty="Nothing is waiting for you.">
            <template #actions="{ item }">
              <button type="button" class="ghost-btn sm" :disabled="!!deciding" @click="decide(item.id, true)">
                {{ deciding === item.id ? "Confirming…" : "Approve" }}
              </button>
              <button type="button" class="ghost-btn sm danger" :disabled="!!deciding" @click="decide(item.id, false)">Deny</button>
            </template>
          </JvActivityList>
        </template>
      </template>

      <!-- Completed + agent history -->
      <template v-else-if="current.id === 'completed'">
        <JvSegmented v-model="sub" :items="SUB_TABS" :label="`${current.title} sections`" />
        <template v-if="sub === 'overview'">
          <JvUnavailable v-if="sessions.state === 'unsupported' || sessions.state === 'error'" title="Coding sessions"
            :kind="sessions.state === 'unsupported' ? 'core-update' : 'error'" icon="code" />
          <JvActivityList v-else title="Finished sessions" :items="sessionRows(split.finished)"
            :empty="sessions.state === 'loading' ? 'Loading…' : 'No finished sessions yet.'" />
        </template>
        <template v-else>
          <JvUnavailable v-if="audit.state === 'unsupported' || audit.state === 'error'" title="Agent history"
            :kind="audit.state === 'unsupported' ? 'core-update' : 'error'" icon="shield" />
          <JvActivityList v-else title="Agent actions" :items="auditRows"
            :empty="audit.state === 'loading' ? 'Loading…' : 'No agent actions recorded.'" />
        </template>
      </template>

      <JvUnavailable v-else :title="current.title" :icon="current.icon" :detail="DETAILS[current.id]" />
    </JvPanel>
  </NodePage>
</template>

<style scoped>
.small { font-size: var(--fs-12); margin: 0; }
.err { margin: 0; overflow-wrap: anywhere; }
</style>
