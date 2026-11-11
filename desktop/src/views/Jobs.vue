<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import JobForm from "../components/jobs/JobForm.vue";
import {
  KINDS, KIND_DETAIL, KIND_LABEL, NOT_CONFIGURED_TEXT, RUN_STATUS_LABEL, RUN_STATUS_TONE, cadenceText, failureOf, failureText,
  newDraft, newJob, readJobAnswer, readJobList, readRuns, writeFailureText, zonedText, type Draft, type Failure, type Job, type Kind,
  type Run,
} from "../jobs";
import { coalesce } from "../coalesce";
import { jobApi, useJobRefetch } from "../jobsApi";

// The owner's scheduled jobs: one row each with its schedule, next run and
// newest run; a kind without a job can be set up here. Content comes from
// Core only; this app ships none.
type Row = { job: Job; last: Run | null | undefined };

const state = ref<{ kind: "loading" } | { kind: "failed"; failure: Failure } | { kind: "ok"; configured: boolean; rows: Row[] }>({ kind: "loading" });
let seq = 0;

async function load(): Promise<void> {
  const id = ++seq;
  try {
    const list = readJobList(await jobApi.list());
    if (!list) throw new Error("invalid");
    // ponytail: one runs read per job (at most one job per kind, one kind today).
    const rows = await Promise.all(list.jobs.map(async (job): Promise<Row> => {
      const runs = await jobApi.runs(job.id).then(readRuns, () => null);
      return { job, last: runs ? runs[0] ?? null : undefined };
    }));
    if (id === seq) state.value = { kind: "ok", configured: list.configured, rows };
  } catch (error) {
    if (id === seq && state.value.kind !== "ok") state.value = { kind: "failed", failure: failureOf(error, true) };
  }
}
void load();
// Any finished run changes a row's newest run (and maybe its next one).
const refetch = coalesce(load);
useJobRefetch(() => void refetch(), () => void refetch());

const missing = computed(() => (state.value.kind === "ok" ? KINDS.filter((k) => !(state.value as { rows: Row[] }).rows.some((r) => r.job.kind === k)) : []));

// ---- set up ------------------------------------------------------------------
const router = useRouter();
const creating = ref<Kind | null>(null);
const busy = ref(false);
const error = ref("");

async function create(kind: Kind, draft: Draft): Promise<void> {
  const body = newJob(kind, draft);
  if (busy.value || !body) return;
  busy.value = true;
  error.value = "";
  try {
    const job = readJobAnswer(await jobApi.create(body));
    creating.value = null;
    if (job) await router.push(`/jobs/${job.id}`);
    else await load();
  } catch (e) {
    error.value = writeFailureText(e, false);
    await load();
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <PageShell title="Jobs" subtitle="Work Core does by itself on a schedule you set.">
    <template #actions>
      <button type="button" class="btn btn-secondary" @click="load">Refresh</button>
    </template>

    <div v-if="state.kind === 'loading'" class="card jb-col" role="status">
      <span class="jb-sr">Loading jobs…</span>
      <span class="skel" style="width: 40%; height: 16px" aria-hidden="true"></span>
      <span class="skel" style="width: 70%; height: 14px" aria-hidden="true"></span>
    </div>
    <div v-else-if="state.kind === 'failed'" class="card jb-col" role="alert">
      <div class="jb-title"><span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span><h3>Could not load jobs</h3></div>
      <p class="t-sm t-2">{{ failureText(state.failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></div>
    </div>
    <template v-else>
      <p v-if="!state.configured" class="jb-banner t-sm" role="status">{{ NOT_CONFIGURED_TEXT }}</p>

      <div v-if="state.rows.length" class="card jb-table">
        <table class="tbl">
          <thead><tr><th scope="col">Job</th><th scope="col">Schedule</th><th scope="col">Next run</th><th scope="col">Last run</th><th scope="col">Status</th></tr></thead>
          <tbody>
            <tr v-for="row in state.rows" :key="row.job.id">
              <th scope="row"><RouterLink :to="`/jobs/${row.job.id}`">{{ KIND_LABEL[row.job.kind] }}</RouterLink></th>
              <td>{{ cadenceText(row.job) }}</td>
              <td>{{ !row.job.enabled ? "Paused" : row.job.next_run_at ? zonedText(row.job.next_run_at) : "Not scheduled" }}</td>
              <td>
                <span v-if="row.last === undefined" class="t-2">Could not be loaded</span>
                <span v-else-if="row.last === null" class="t-2">Not run yet</span>
                <RouterLink v-else :to="`/jobs/runs/${row.last.id}`">{{ zonedText(row.last.started_at) }}</RouterLink>
              </td>
              <td><div class="jb-badges">
                <span class="badge" :class="row.job.enabled ? 'pos' : ''"><span class="dot" aria-hidden="true"></span>{{ row.job.enabled ? "Enabled" : "Paused" }}</span>
                <span v-if="row.last" class="badge" :class="RUN_STATUS_TONE[row.last.status]">Last: {{ RUN_STATUS_LABEL[row.last.status] }}</span>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>

      <section v-for="kind in missing" :key="kind" class="card jb-col" :aria-labelledby="`jb-new-${kind}`">
        <h2 :id="`jb-new-${kind}`">{{ KIND_LABEL[kind] }}</h2>
        <p class="t-sm t-2">{{ KIND_DETAIL[kind] }}</p>
        <JobForm
          v-if="creating === kind" :kind="kind" :initial="newDraft()" :busy="busy" submit="Set up" cancel
          @save="create(kind, $event)" @cancel="creating = null"
        />
        <div v-else><button type="button" class="btn btn-primary" @click="creating = kind; error = ''">Set up</button></div>
        <p v-if="error && creating === kind" class="err" role="alert">{{ error }}</p>
      </section>
    </template>
  </PageShell>
</template>

<style scoped>
p, h2, h3 { margin: 0; }
.jb-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.jb-col { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.jb-title { display: flex; align-items: center; gap: 10px; }
.jb-banner { padding: 12px 16px; border-radius: var(--r-lg); background: var(--negative-subtle); color: var(--negative-text); }
.jb-table { padding: 0; overflow-x: auto; }
.jb-table th[scope="row"] { text-align: left; font-weight: 600; }
.jb-table a { color: var(--accent-text); text-decoration: none; }
.jb-table a:hover { text-decoration: underline; text-underline-offset: 3px; }
.jb-badges { display: flex; gap: 6px; flex-wrap: wrap; }
.badge > .dot { width: 6px; height: 6px; }
</style>
