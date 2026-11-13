<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import JobForm from "../components/jobs/JobForm.vue";
import {
  FULL_TEXT, KIND_DETAIL, KIND_LABEL, LIMITS, NEW_KINDS, NOT_CONFIGURED_TEXT, NOT_CONFIRMED, RUN_STATUS_LABEL, RUN_STATUS_TONE,
  TIMES_IN_ZONE, countText, failureOf, failureText, jobTitle, newDraft, newJob, nextRunText, originText, ownerJobCount, readJobAnswer,
  readJobList, readRuns, runsText, scheduleText, stateBadge, todayInZone, unsupportedText, writeFailureText, zonedText,
  type Draft, type Failure, type Job, type Run,
} from "../jobs";
import { coalesce } from "../coalesce";
import { jobApi, useJobRefetch } from "../jobsApi";

// The owner's scheduled jobs: one row each with its schedule, next run and
// newest run; new jobs are made here, and the dependency review is set up
// here while there is none. Content comes from Core only; this app ships none.
type Row = { job: Job; last: Run | null | undefined };

const state = ref<
  { kind: "loading" } | { kind: "failed"; failure: Failure } | { kind: "ok"; configured: boolean; rows: Row[]; unsupported: number }
>({ kind: "loading" });
let seq = 0;

async function load(): Promise<void> {
  const id = ++seq;
  try {
    const list = readJobList(await jobApi.list());
    if (!list) throw new Error("invalid");
    // ponytail: one runs read per job, about 14 (10 yours, 3 of Jarvis's, 1 review); a newest-run field on the list if that grows.
    const rows = await Promise.all(list.jobs.map(async (job): Promise<Row> => {
      const runs = await jobApi.runs(job.id).then(readRuns, () => null);
      return { job, last: runs ? runs[0] ?? null : undefined };
    }));
    if (id === seq) state.value = { kind: "ok", configured: list.configured, rows, unsupported: list.unsupported };
  } catch (error) {
    if (id === seq && state.value.kind !== "ok") state.value = { kind: "failed", failure: failureOf(error, true) };
  }
}
void load();
// Any finished run changes a row's newest run (and maybe its next one).
const refetch = coalesce(load);
useJobRefetch(() => void refetch(), () => void refetch());

const today = todayInZone();
const rows = computed(() => (state.value.kind === "ok" ? state.value.rows : []));
const owned = computed(() => ownerJobCount(rows.value.map((r) => r.job)));
const full = computed(() => owned.value >= LIMITS.ownerJobs);
const reviewMissing = computed(() => state.value.kind === "ok" && !rows.value.some((r) => r.job.kind === "dependency_review"));

// ---- create ------------------------------------------------------------------
const router = useRouter();
/** Which form is open: a new job of the owner's kinds, or the review setup. */
const creating = ref<"job" | "review" | null>(null);
const busy = ref(false);
const error = ref("");

function open(which: "job" | "review"): void {
  creating.value = which;
  error.value = "";
}

async function create(draft: Draft): Promise<void> {
  const body = newJob(draft, todayInZone());
  if (busy.value || !body) return;
  busy.value = true;
  error.value = "";
  try {
    const job = readJobAnswer(await jobApi.create(body));
    if (job) {
      creating.value = null;
      await router.push(`/jobs/${job.id}`);
    } else {
      error.value = NOT_CONFIRMED;
      await load();
    }
  } catch (e) {
    error.value = writeFailureText(e, false, draft.kind);
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
      <button
        v-if="state.kind === 'ok'" type="button" class="btn btn-primary" :disabled="full || creating === 'job'"
        :aria-describedby="full ? 'jb-full' : undefined" @click="open('job')"
      >New job</button>
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

      <section v-if="creating === 'job'" class="card jb-col" aria-labelledby="jb-new">
        <h2 id="jb-new">New job</h2>
        <JobForm :initial="newDraft()" :kinds="NEW_KINDS" :busy="busy" submit="Create" cancel @save="create" @cancel="creating = null" />
        <p v-if="error" class="err" role="alert">{{ error }}</p>
      </section>

      <p class="t-sm t-2 jb-meta">
        <span>{{ countText(owned) }}</span>
        <span v-if="full" id="jb-full">{{ FULL_TEXT }}</span>
        <span v-else>{{ TIMES_IN_ZONE }}</span>
      </p>

      <div v-if="state.rows.length" class="card jb-table">
        <table class="tbl">
          <thead><tr><th scope="col">Job</th><th scope="col">Schedule</th><th scope="col">Next run</th><th scope="col">Last run</th><th scope="col">Status</th></tr></thead>
          <tbody>
            <tr v-for="row in state.rows" :key="row.job.id">
              <th scope="row">
                <div class="jb-stack">
                  <RouterLink :to="`/jobs/${row.job.id}`" class="jb-wrap">{{ jobTitle(row.job) }}</RouterLink>
                  <span class="t-xs t-2">{{ KIND_LABEL[row.job.kind] }}</span>
                  <span v-if="originText(row.job)" class="t-xs t-2">{{ originText(row.job) }}</span>
                </div>
              </th>
              <td>
                <div class="jb-stack">
                  <span>{{ scheduleText(row.job, today) }}</span>
                  <span v-if="runsText(row.job)" class="t-xs t-2">{{ runsText(row.job) }}</span>
                </div>
              </td>
              <td>{{ nextRunText(row.job) }}</td>
              <td>
                <span v-if="row.last === undefined" class="t-2">Could not be loaded</span>
                <span v-else-if="row.last === null" class="t-2">Not run yet</span>
                <RouterLink v-else :to="`/jobs/runs/${row.last.id}`">{{ zonedText(row.last.started_at) }}</RouterLink>
              </td>
              <td><div class="jb-badges">
                <span class="badge" :class="stateBadge(row.job).tone"><span class="dot" aria-hidden="true"></span>{{ stateBadge(row.job).label }}</span>
                <span v-if="row.last" class="badge" :class="RUN_STATUS_TONE[row.last.status]">Last: {{ RUN_STATUS_LABEL[row.last.status] }}</span>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else-if="creating !== 'job'" class="card empty t-sm">No jobs yet. Use New job to have Core research, remind you or check the disks on a schedule.</p>
      <p v-if="state.unsupported" class="jb-banner t-sm" role="status">{{ unsupportedText(state.unsupported) }}</p>

      <section v-if="reviewMissing" class="card jb-col" aria-labelledby="jb-new-review">
        <h2 id="jb-new-review">{{ KIND_LABEL.dependency_review }}</h2>
        <p class="t-sm t-2">{{ KIND_DETAIL.dependency_review }}</p>
        <template v-if="creating === 'review'">
          <JobForm :initial="newDraft('dependency_review')" :busy="busy" submit="Set up" cancel @save="create" @cancel="creating = null" />
          <p v-if="error" class="err" role="alert">{{ error }}</p>
        </template>
        <div v-else><button type="button" class="btn btn-secondary" :disabled="creating === 'job'" @click="open('review')">Set up</button></div>
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
.jb-meta { display: flex; gap: 6px 16px; flex-wrap: wrap; }
.jb-table { padding: 0; overflow-x: auto; }
.jb-table th[scope="row"] { text-align: left; font-weight: 600; }
.jb-table a { color: var(--accent-text); text-decoration: none; }
.jb-table a:hover { text-decoration: underline; text-underline-offset: 3px; }
.jb-stack { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.jb-stack .t-xs { font-weight: 400; }
.jb-wrap { overflow-wrap: anywhere; }
.jb-badges { display: flex; gap: 6px; flex-wrap: wrap; }
.badge > .dot { width: 6px; height: 6px; }
</style>
