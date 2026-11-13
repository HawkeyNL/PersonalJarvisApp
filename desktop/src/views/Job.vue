<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import JobForm from "../components/jobs/JobForm.vue";
import {
  KIND_DETAIL, KIND_LABEL, NOT_CONFIGURED_TEXT, NOT_CONFIRMED, RUN_STATUS_LABEL, RUN_STATUS_TONE, TIMES_IN_ZONE, TRIGGER_LABEL,
  deleteNote, errorText, failureOf, failureText, jobPatch, jobTitle, originText, readJobAnswer, readJobList, readRuns,
  runNowFailureText, runsText, scheduleText, stateBadge, toDraft, todayInZone, writeFailureText, zonedText,
  type Draft, type Failure, type Job, type JobPatch, type Run,
} from "../jobs";
import { coalesce } from "../coalesce";
import { setCurrent } from "../conversations";
import { jobApi, useJobRefetch } from "../jobsApi";

// One job: its state, editor, Run now, pause, delete and the newest runs.
// Every write names the revision it was made on; a 409 reloads and says so.
const route = useRoute();
const router = useRouter();
const id = computed(() => String(route.params.id ?? ""));

const job = ref<Job | null>(null);
const configured = ref(true);
const failure = ref<Failure | null>(null);
const runs = ref<Run[] | null>(null);
const notice = ref("");
const busy = ref(false);
const today = todayInZone();
const confirming = ref(false); // the delete confirm step (below)
let seq = 0;

async function load(): Promise<void> {
  const mine = ++seq;
  // Core has no single-job read; the list holds at most 14 jobs.
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
watch(id, () => { job.value = null; failure.value = null; runs.value = null; confirming.value = false; void load(); }, { immediate: true });
useJobRefetch(() => void refetch(), (hint) => { if (hint.job_id === id.value) void refetch(); });

/** One change: busy, then reload; `done` is the notice when Core confirms. */
async function write(run: () => Promise<unknown>, done: string): Promise<void> {
  busy.value = true;
  notice.value = "";
  seq++; // a read already in flight must not overwrite the answer
  try {
    const next = readJobAnswer(await run());
    if (next) job.value = next;
    await load();
    notice.value = next ? done : NOT_CONFIRMED;
  } catch (error) {
    await load();
    notice.value = writeFailureText(error, true);
  } finally {
    busy.value = false;
  }
}

function patch(p: JobPatch, done: string): void {
  const j = job.value;
  if (j && !busy.value) void write(() => jobApi.update(j.id, j.revision, p), done);
}

function save(draft: Draft): void {
  const j = job.value;
  if (!j || busy.value) return;
  const p = jobPatch(j, draft);
  if (!p) { notice.value = "Nothing changed."; return; }
  patch(p, "Saved.");
}

const togglePause = () => job.value && patch({ enabled: !job.value.enabled }, job.value.enabled ? "Paused." : "Enabled.");

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

function openChat(): void {
  const conversation = job.value?.conversation_id;
  if (!conversation) return;
  setCurrent(conversation); // chat opens the remembered conversation
  void router.push("/");
}

// ---- delete, after a confirm step -------------------------------------------
const cancelEl = ref<HTMLButtonElement | null>(null);
const deleteEl = ref<HTMLButtonElement | null>(null);

async function askDelete(): Promise<void> {
  confirming.value = true;
  await nextTick();
  cancelEl.value?.focus();
}
async function cancelDelete(): Promise<void> {
  if (busy.value) return;
  confirming.value = false;
  await nextTick();
  deleteEl.value?.focus();
}
async function remove(): Promise<void> {
  const j = job.value;
  if (!j || busy.value) return;
  busy.value = true;
  notice.value = "";
  seq++;
  try {
    await jobApi.remove(j.id, j.revision);
    await router.push("/jobs");
  } catch (error) {
    confirming.value = false;
    await load();
    notice.value = writeFailureText(error, true);
  } finally {
    busy.value = false;
  }
}

const noticeEl = ref<HTMLElement | null>(null);
watch(notice, async (n) => { if (n) { await nextTick(); noticeEl.value?.focus(); } });
</script>

<template>
  <PageShell :title="job ? jobTitle(job) : 'Job'">
    <template #crumb><p class="jb-crumb t-sm"><RouterLink to="/jobs">Jobs</RouterLink></p></template>
    <template #badges>
      <template v-if="job">
        <span class="badge" :class="stateBadge(job).tone">{{ stateBadge(job).label }}</span>
        <span class="badge">{{ KIND_LABEL[job.kind] }}</span>
      </template>
    </template>
    <template #subtitle>
      <p v-if="job" class="t-sm t-2">{{ KIND_DETAIL[job.kind] }}</p>
    </template>
    <template #actions>
      <button type="button" class="btn btn-secondary" :disabled="busy" @click="load">Refresh</button>
      <template v-if="job">
        <button v-if="job.conversation_id" type="button" class="btn btn-secondary" @click="openChat">Open in chat</button>
        <button type="button" class="btn btn-secondary" :disabled="busy" @click="togglePause">{{ job.enabled ? "Pause" : "Enable" }}</button>
        <button type="button" class="btn btn-primary" :disabled="busy || !configured" @click="runNow">Run now</button>
      </template>
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
        <p class="t-sm">
          {{ scheduleText(job, today) }}.
          <template v-if="runsText(job)">{{ runsText(job) }}.</template>
          <template v-if="job.enabled && job.next_run_at">Next run {{ zonedText(job.next_run_at) }}.</template>
        </p>
        <p v-if="job.paused_reason === 'paused_after_failures'" class="jb-error t-sm">
          Core paused this job after 3 failed runs in a row. Check the latest run, change the job if needed, then enable it again.
        </p>
        <p v-if="originText(job)" class="t-sm t-2">{{ originText(job) }}. Jarvis made this job for itself; it goes away when it expires.</p>
        <p class="t-xs t-2">{{ TIMES_IN_ZONE }}</p>
        <!-- Remounted per revision: a change elsewhere resets the editor to Core's state. -->
        <JobForm :key="job.revision" :initial="toDraft(job)" :busy="busy" submit="Save" @save="save" />
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
              <td class="t-sm t-2 jb-wrap">{{ run.error_code ? errorText(run.error_code, run.kind) : run.priorities.length ? `${run.priorities.length} priorities` : "" }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="runs?.length" class="t-xs t-2">The 20 newest runs. Core keeps runs for 180 days.</p>
      </section>

      <section class="card jb-col" aria-labelledby="jb-delete" @keydown.esc="cancelDelete">
        <h2 id="jb-delete">Delete</h2>
        <p class="t-sm t-2">{{ deleteNote(job) }}</p>
        <div class="jb-actions">
          <template v-if="confirming">
            <button type="button" class="btn btn-danger-quiet" :disabled="busy" :aria-busy="busy" @click="remove">Delete for good</button>
            <button ref="cancelEl" type="button" class="btn btn-secondary" :disabled="busy" @click="cancelDelete">Cancel</button>
          </template>
          <button v-else ref="deleteEl" type="button" class="btn btn-ghost" :disabled="busy" @click="askDelete">Delete job</button>
        </div>
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
.jb-error { padding: 12px 16px; border-radius: var(--r-lg); background: var(--danger-subtle); color: var(--danger-text); }
.jb-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.jb-wrap { overflow-wrap: anywhere; }
</style>
