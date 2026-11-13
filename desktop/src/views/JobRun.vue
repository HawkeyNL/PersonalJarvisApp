<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { openUrl } from "@tauri-apps/plugin-opener";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import {
  RUN_STATUS_LABEL, RUN_STATUS_TONE, SUMMARY_SOURCE_LABEL, TRIGGER_LABEL, errorText, failureOf, failureText, readRunDetail, zonedText,
  type Failure, type RunDetail,
} from "../jobs";
import { coalesce } from "../coalesce";
import { setCurrent } from "../conversations";
import { jobApi, useJobRefetch } from "../jobsApi";

// One run's report: status, error, summary, a research answer's sources and
// a dependency review's ranked priorities. The summary and reasons are
// Core's display text, shown as plain text only; sources are https links
// (checked in jobs.ts), opened in the system browser.
const route = useRoute();
const router = useRouter();
const id = computed(() => String(route.params.id ?? ""));

const run = ref<RunDetail | null>(null);
const failure = ref<Failure | null>(null);
let seq = 0;

async function load(): Promise<void> {
  const mine = ++seq;
  try {
    const next = readRunDetail(await jobApi.run(id.value));
    if (mine !== seq) return;
    if (next) { run.value = next; failure.value = null; } else if (!run.value) failure.value = "failed";
  } catch (error) {
    if (mine === seq && !run.value) failure.value = failureOf(error);
  }
}
const refetch = coalesce(load);
watch(id, () => { run.value = null; failure.value = null; void load(); }, { immediate: true });
useJobRefetch(() => void refetch(), (hint) => { if (hint.run_id === id.value) void refetch(); });

const facts = computed(() => new Map(run.value?.facts.map((f) => [f.ref, f]) ?? []));

function openChat(conversation: string): void {
  setCurrent(conversation); // chat opens the remembered conversation
  void router.push("/");
}
</script>

<template>
  <PageShell title="Run report">
    <template #crumb>
      <p class="jb-crumb t-sm">
        <RouterLink to="/jobs">Jobs</RouterLink><template v-if="run"> / <RouterLink :to="`/jobs/${run.job_id}`">Job</RouterLink></template>
      </p>
    </template>
    <template #badges>
      <span v-if="run" class="badge" :class="RUN_STATUS_TONE[run.status]">{{ RUN_STATUS_LABEL[run.status] }}</span>
    </template>
    <template #actions>
      <button type="button" class="btn btn-secondary" @click="load">Refresh</button>
      <button v-if="run?.conversation_id" type="button" class="btn btn-secondary" @click="openChat(run.conversation_id)">Open in chat</button>
    </template>

    <div v-if="failure" class="card jb-col" role="alert">
      <div class="jb-title"><span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span><h3>Could not load this run</h3></div>
      <p class="t-sm t-2">{{ failureText(failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></div>
    </div>
    <div v-else-if="!run" class="card jb-col" role="status">
      <span class="jb-sr">Loading the run…</span>
      <span class="skel" style="width: 40%; height: 16px" aria-hidden="true"></span>
    </div>
    <template v-else>
      <section class="card jb-col" aria-label="Run">
        <dl class="jb-dl t-sm">
          <dt>How</dt><dd>{{ TRIGGER_LABEL[run.trigger] }}</dd>
          <dt>Started</dt><dd>{{ zonedText(run.started_at) }}</dd>
          <dt>Ended</dt><dd>{{ run.finished_at ? zonedText(run.finished_at) : run.status === "running" ? "Still running" : "—" }}</dd>
        </dl>
        <p v-if="run.status === 'missed'" class="t-sm">Core was down at this slot for more than a day, so it did not run.</p>
        <p v-if="run.error_code" class="jb-error t-sm" role="status">{{ errorText(run.error_code, run.kind) }}</p>
      </section>

      <section v-if="run.errors.length" class="card jb-col" aria-labelledby="jb-repos">
        <h2 id="jb-repos">Repositories that could not be read</h2>
        <ul class="jb-list t-sm">
          <li v-for="(e, i) in run.errors" :key="i"><span class="mono jb-wrap">{{ e.repo }}</span>: {{ errorText(e.error_code, run.kind) }}</li>
        </ul>
      </section>

      <section v-if="run.summary" class="card jb-col" aria-labelledby="jb-summary">
        <div class="jb-head">
          <h2 id="jb-summary">Summary</h2>
          <span v-if="run.summary_source" class="badge" :class="run.summary_source === 'claude' ? 'acc' : ''">{{ SUMMARY_SOURCE_LABEL[run.summary_source] }}</span>
        </div>
        <p class="t-sm jb-pre">{{ run.summary }}</p>
      </section>

      <section v-if="run.sources.length" class="card jb-col" aria-labelledby="jb-sources">
        <h2 id="jb-sources">Sources</h2>
        <ul class="jb-list t-sm">
          <li v-for="s in run.sources" :key="s.url">
            <a :href="s.url" class="jb-wrap" :title="s.url" @click.prevent="openUrl(s.url)">{{ s.label }}</a>
          </li>
        </ul>
        <p class="t-xs t-2">From the web: opens in your browser.</p>
      </section>

      <section v-if="run.priorities.length" class="card jb-col" aria-labelledby="jb-priorities">
        <h2 id="jb-priorities">Priorities</h2>
        <ol class="jb-list">
          <li v-for="p in run.priorities" :key="p.ref" class="jb-priority">
            <span class="jb-rank mono" aria-hidden="true">{{ p.rank }}</span>
            <div class="jb-col jb-gap">
              <strong class="jb-wrap">{{ facts.get(p.ref)?.title ?? p.ref }}</strong>
              <span v-if="facts.get(p.ref)?.detail" class="t-xs t-2 jb-wrap">{{ facts.get(p.ref)?.detail }}</span>
              <span class="t-sm jb-pre">{{ p.reason }}</span>
            </div>
          </li>
        </ol>
      </section>
      <p v-else-if="run.kind === 'dependency_review' && (run.status === 'ok' || run.status === 'partial')" class="card empty t-sm">No open alerts in the repositories Core could read.</p>
      <p v-if="run.truncated" class="t-xs t-2">Some entries were left out: a full page from GitHub, an alert Core could not read, or the size limit.</p>
    </template>
  </PageShell>
</template>

<style scoped>
p, h2, h3 { margin: 0; }
.jb-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.jb-crumb a, .jb-list a { color: var(--accent-text); text-decoration: none; }
.jb-list a:hover { text-decoration: underline; text-underline-offset: 3px; }
.jb-col { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.jb-gap { gap: 2px; }
.jb-title, .jb-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.jb-dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 16px; margin: 0; }
.jb-dl dt { color: var(--text-secondary); }
.jb-dl dd { margin: 0; }
.jb-error { padding: 12px 16px; border-radius: var(--r-lg); background: var(--danger-subtle); color: var(--danger-text); }
.jb-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.jb-priority { display: flex; gap: 12px; align-items: flex-start; }
.jb-rank { min-width: 24px; text-align: right; color: var(--text-secondary); }
.jb-pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.jb-wrap { overflow-wrap: anywhere; }
</style>
