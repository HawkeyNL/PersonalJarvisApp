<script setup lang="ts">
import { computed } from "vue";
import NavIcon from "../NavIcon.vue";
import ActivityState from "./ActivityState.vue";
import type { PendingAction } from "../../agentApprovals";
import { relativeTime, type Availability } from "../../hubModel";
import type { CodingSessionRow } from "../../nodeModels";
import { filterRuns, runBadge, runKpis, type AuditLog, type RunFilter } from "../../activity";

// D-Runs: the coding sessions Core runs, with KPIs from the reads the page
// already makes. Core reports no agents, duration, tokens or cost per session,
// so the table has only the columns the data has.
const props = defineProps<{
  sessions: Availability<{ sessions: CodingSessionRow[] }>;
  pending: Availability<{ pending: PendingAction[] }>;
  audit: Availability<AuditLog>;
  now: number;
  reload: () => Promise<void>;
}>();
const filter = defineModel<RunFilter>("filter", { required: true });

const list = computed(() => (props.sessions.state === "ok" ? props.sessions.value.sessions : []));
const kpis = computed(() => runKpis(
  list.value,
  props.pending.state === "ok" ? props.pending.value.pending.length : null,
  props.audit.state === "ok" ? props.audit.value : null,
));
const rows = computed(() => filterRuns(list.value, filter.value));
const NONE: Record<RunFilter, string> = { all: "No runs", running: "Nothing running", paused: "Nothing paused", finished: "No finished runs" };
const when = (iso?: string) => {
  const d = iso ? new Date(iso) : null;
  return d && !Number.isNaN(d.getTime()) ? d.toLocaleString("en", { dateStyle: "medium", timeStyle: "short" }) : undefined;
};
</script>

<template>
  <ActivityState :state="sessions" what="runs" forbidden="Coding sessions are turned off in Core." @retry="reload">
    <div class="rn-kpis">
      <div v-for="k in kpis" :key="k.label" class="card rn-kpi">
        <span class="t-sm t-2">{{ k.label }}</span>
        <span class="big">{{ k.value }}</span>
        <span class="t-xs t-2">{{ k.sub }}</span>
      </div>
    </div>

    <section class="card rn-tight" aria-labelledby="rn-title">
      <h2 id="rn-title" class="sr-only">Runs</h2>
      <div v-if="rows.length" class="rn-scroll" tabindex="0" role="region" aria-label="Runs table">
        <table class="tbl">
          <colgroup><col style="width: 60%"><col style="width: 18%"><col style="width: 22%"></colgroup>
          <thead><tr><th scope="col">Run</th><th scope="col">Status</th><th scope="col">Last update</th></tr></thead>
          <tbody>
            <tr v-for="s in rows" :key="s.id">
              <td>
                <span class="rn-line rn-name" :title="s.objective || undefined">{{ s.objective || "Coding session" }}</span>
                <span v-if="s.repository" class="rn-line t-xs t-2" :title="s.repository">{{ s.repository }}</span>
              </td>
              <td>
                <span class="badge" :class="runBadge(s.state).cls">
                  <span v-if="runBadge(s.state).live" class="spinner rn-spin" aria-hidden="true"></span>
                  <span v-else class="dot"></span>{{ runBadge(s.state).label }}
                </span>
              </td>
              <td class="t-sm num" :title="when(s.updated_at)">{{ (s.updated_at && relativeTime(s.updated_at, now)) || "—" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="list.length" class="empty">
        <h3>{{ NONE[filter] }}</h3>
        <p class="t-sm">Nothing matches this status right now.</p>
        <button type="button" class="btn btn-secondary btn-sm" @click="filter = 'all'">Show all runs</button>
      </div>
      <div v-else class="empty">
        <span class="tile acc" aria-hidden="true"><NavIcon name="runs" /></span>
        <h3>No runs yet</h3>
        <p class="t-sm rn-copy">A run starts when you ask Jarvis to work on code. Each one shows up here with its status.</p>
        <RouterLink to="/" class="btn btn-secondary btn-sm">Ask Jarvis</RouterLink>
      </div>
    </section>
  </ActivityState>
</template>

<style scoped>
h3, p { margin: 0; }
.rn-kpis { display: flex; flex-wrap: wrap; gap: 16px; align-items: stretch; }
.rn-kpi { flex: 1 1 200px; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.rn-kpi .big { overflow-wrap: anywhere; }
.card.rn-tight { padding: 0; overflow: hidden; }
.rn-scroll { overflow-x: auto; overscroll-behavior-x: contain; }
.rn-scroll:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }
.tbl { min-width: 560px; table-layout: fixed; }
.tbl :is(th, td):first-child { padding-left: 20px; }
.tbl :is(th, td):last-child { padding-right: 20px; }
.rn-line { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rn-name { font-weight: 500; }
.rn-spin { width: 10px; height: 10px; border-width: 1.5px; }
.rn-copy { max-width: 420px; }
.tile :deep(svg) { width: 20px; height: 20px; }
.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
</style>
