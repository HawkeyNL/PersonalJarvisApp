<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useRoute } from "vue-router";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import JobForm from "../components/jobs/JobForm.vue";
import {
  KIND_DETAIL, KIND_LABEL, NOT_CONFIGURED_TEXT, RUN_STATUS_LABEL, RUN_STATUS_TONE, TRIGGER_LABEL, cadenceText, errorText, failureOf,
  failureText, jobPatch, readJobAnswer, readJobList, readRuns, runNowFailureText, toDraft, writeFailureText, zonedText,
  type Draft, type Failure, type Job, type Run,
} from "../jobs";
import { coalesce } from "../coalesce";
import { jobApi, useJobRefetch } from "../jobsApi";

// One job: its schedule editor, Run now and the newest runs. Every write
// names the revision it was made on; a 409 reloads and says so.
const route = useRoute();
const id = computed(() => String(route.params.id ?? ""));

const job = ref<Job | null>(null);
const configured = ref(true);
const failure = ref<Failure | null>(null);
const runs = ref<Run[] | null>(null);
const notice = ref("");
const busy = ref(false);
let seq = 0;

async function load(): Promise<void> {
  const mine = ++seq;
  // Core has no single-job read; the list holds at most one job per kind.
  const [l, r] = await Promise.allSettled([jobApi.list(), jobApi.runs(id.value)]);
  if (mine !== seq) return;
  if (l.status === "fulfilled") {
    const list = readJobList(l.value);
    const found = list?.jobs.find((j) => j.id === id.value) ?? null;
    if (list) configured.value = list.configured;
    if (found) { job.value = found; failure.value = null; } else { job.value = null; failure.value = list ? "not-found" : "failed"; }
  } else if (!job.value) failure.value = failureOf(l.reason, true);
  runs.value = r.status === "fulfilled" ? readRuns(r.value) : null;
}
const refetch = coalesce(load);
watch(id, () => { job.value = null; failure.value = null; runs.value = null; void load(); }, { immediate: true });
useJobRefetch(() => void refetch(), (hint) => { if (hint.job_id === id.value) void refetch(); });

async function save(draft: Draft): Promise<void> {
  const j = job.value;
  if (!j || busy.value) return;
  const patch = jobPatch(j, draft);
  if (!patch) { notice.value = "Nothing changed."; return; }
  busy.value = true;
  notice.value = "";
  seq++; // a read already in flight must not overwrite the answer
  try {
    const next = readJobAnswer(await jobApi.update(j.id, j.revision, patch));
    if (next) job.value = next;
    await load();
    notice.value = "Saved.";
  } catch (error) {
    await load();
    notice.value = writeFailureText(error, (job.value?.revision ?? j.revision) !== j.revision);
  } finally {
    busy.value = false;
  }
}

async function runNow(): Promise<void> {
  const j = job.value;
  if (!j || busy.value) return;
  busy.value = true;
  notice.value = "";
  try {
    await jobApi.runNow(j.id);
    notice.value = "Started. The run shows up below when it ends.";
  } catch (error) {
    notice.value = runNowFailureText(error);
  } finally {
    busy.value = false;
    void refetch();
  }
}

const noticeEl = ref<HTMLElement | null>(null);
watch(notice, async (n) => { if (n) { await nextTick(); noticeEl.value?.focus(); } });
</script>

<template>
  <PageShell :title="job ? KIND_LABEL[job.kind] : 'Job'">
    <template #crumb><p class="jb-crumb t-sm"><RouterLink to="/jobs">Jobs</RouterLink></p></template>
    <template #badges>
      <span v-if="job" class="badge" :class="job.enabled ? 'pos' : ''">{{ job.enabled ? "Enabled" : "Paused" }}</span>
    </template>
    <template #subtitle>
      <p v-if="job" class="t-sm t-2">{{ KIND_DETAIL[job.kind] }}</p>
    </template>
    <template #actions>
      <button type="button" class="btn btn-secondary" :disabled="busy" @click="load">Refresh</button>
      <button v-if="job" type="button" class="btn btn-primary" :disabled="busy || !configured" @click="runNow">
        Run now
      </button>
    </template>

    <p v-if="notice" ref="noticeEl" tabindex="-1" class="jb-banner t-sm" role="status">{{ notice }}</p>
    <p v-if="!configured" class="jb-banner neg t-sm" role="status">{{ NOT_CONFIGURED_TEXT }}</p>

    <div v-if="failure" class="card jb-col" role="alert">
      <div class="jb-title"><span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span><h3>Could not load this job</h3></div>
      <p class="t-sm t-2">{{ failureText(failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></div>
    </div>
    <div v-else-if="!job" class="card jb-col" role="status">
      <span class="jb-sr">Loading the job…</span>
      <span class="skel" style="width: 40%; height: 16px" aria-hidden="true"></span>
      <span class="skel" style="width: 80%; height: 14px" aria-hidden="true"></span>
    </div>
    <template v-else>
      <section class="card jb-col" aria-labelledby="jb-schedule">
        <h2 id="jb-schedule">Schedule</h2>
        <p class="t-sm t-2">
          {{ cadenceText(job) }}.
          <template v-if="job.enabled && job.next_run_at">Next run {{ zonedText(job.next_run_at) }}.</template>
        </p>
        <!-- Remounted per revision: a change elsewhere resets the editor to Core's state. -->
        <JobForm :key="job.revision" :kind="job.kind" :initial="toDraft(job)" :busy="busy" submit="Save" @save="save" />
      </section>

      <section class="card jb-col" aria-labelledby="jb-runs">
        <h2 id="jb-runs">Runs</h2>
        <p v-if="runs === null" class="t-sm" role="alert">Could not load the runs. <button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></p>
        <p v-else-if="!runs.length" class="t-sm t-2">No runs yet.</p>
        <table v-else class="tbl">
          <thead><tr><th scope="col">Started</th><th scope="col">How</th><th scope="col">Status</th><th scope="col">Detail</th></tr></thead>
          <tbody>
            <tr v-for="run in runs" :key="run.id">
              <td class="n"><RouterLink :to="`/jobs/runs/${run.id}`">{{ zonedText(run.started_at) }}</RouterLink></td>
              <td>{{ TRIGGER_LABEL[run.trigger] }}</td>
              <td><span class="badge" :class="RUN_STATUS_TONE[run.status]">{{ RUN_STATUS_LABEL[run.status] }}</span></td>
              <td class="t-sm t-2 jb-wrap">{{ run.error_code ? errorText(run.error_code) : run.priorities.length ? `${run.priorities.length} priorities` : "" }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="runs?.length" class="t-xs t-2">The 20 newest runs. Core keeps runs for 180 days.</p>
      </section>
    </template>
  </PageShell>
</template>

<style scoped>
p, h2, h3 { margin: 0; }
.jb-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.jb-crumb a, .tbl a { color: var(--accent-text); text-decoration: none; }
.tbl a:hover { text-decoration: underline; text-underline-offset: 3px; }
.jb-col { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.jb-title { display: flex; align-items: center; gap: 10px; }
.jb-banner { padding: 12px 16px; border-radius: var(--r-lg); background: var(--accent-subtle); }
.jb-banner.neg { background: var(--negative-subtle); color: var(--negative-text); }
.jb-wrap { overflow-wrap: anywhere; }
</style>
