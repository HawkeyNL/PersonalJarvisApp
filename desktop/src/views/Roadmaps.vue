<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import {
  DEFAULT_PHASES, LIMITS, STATUS_LABEL, STATUS_TONE, failureOf, failureText, isNewer, parsePhases, readList, readProposals,
  readSnapshot, tally, validText, writeFailureText, type Failure, type Roadmap, type Tally,
} from "../roadmaps";
import { coalesce } from "../coalesce";
import { roadmapApi, useRoadmapRefetch } from "../roadmapsApi";

// The owner's roadmaps: one card each, with its status tally and pending
// Jarvis proposals. Opening a card shows the roadmap; rename and archive live
// there. Content comes from Core only; this app ships none.
type Card = { roadmap: Roadmap; tally: Tally | null; items: number | null; proposals: number | null };

const state = ref<{ kind: "loading" } | { kind: "failed"; failure: Failure } | { kind: "ok"; cards: Card[] }>({ kind: "loading" });
let seq = 0;

async function load(): Promise<void> {
  const id = ++seq;
  try {
    const roadmaps = readList(await roadmapApi.list());
    if (!roadmaps) throw new Error("invalid");
    // ponytail: one detail and one proposals read per card (at most 20 roadmaps); a summary route in Core if this gets slow.
    const cards = await Promise.all(roadmaps.map(async (roadmap): Promise<Card> => {
      const [snap, props] = await Promise.allSettled([roadmapApi.get(roadmap.id), roadmapApi.proposals(roadmap.id)]);
      const items = snap.status === "fulfilled" ? readSnapshot(snap.value)?.items ?? null : null;
      const proposals = props.status === "fulfilled" ? readProposals(props.value)?.length ?? null : null;
      return { roadmap, tally: items && tally(items), items: items?.length ?? null, proposals };
    }));
    if (id === seq) state.value = { kind: "ok", cards };
  } catch (error) {
    if (id === seq && state.value.kind !== "ok") state.value = { kind: "failed", failure: failureOf(error) };
  }
}
void load();
// A new roadmap, or a newer revision of a listed one, reloads the list.
const refetch = coalesce(load);
useRoadmapRefetch(() => void refetch(), (change) => {
  const card = state.value.kind === "ok" ? state.value.cards.find((c) => c.roadmap.id === change.id) : undefined;
  if (isNewer(change, card?.roadmap.revision)) void refetch();
});

const full = computed(() => state.value.kind === "ok" && state.value.cards.length >= LIMITS.roadmaps);

// ---- create ----------------------------------------------------------------
const router = useRouter();
const creating = ref(false);
const busy = ref(false);
const title = ref("");
const description = ref("");
const phasesText = ref(DEFAULT_PHASES.join("\n"));
const error = ref("");
const titleEl = ref<HTMLInputElement | null>(null);

function openCreate(): void {
  creating.value = true;
  error.value = "";
  requestAnimationFrame(() => titleEl.value?.focus());
}

async function create(): Promise<void> {
  if (busy.value) return;
  const phases = parsePhases(phasesText.value);
  const desc = description.value.trim() ? description.value : undefined;
  if (!validText(title.value.trim(), LIMITS.roadmapTitle)) error.value = "The title must be 1 to 120 characters on one line.";
  else if (desc && !validText(desc, LIMITS.description, true)) error.value = "The description must be at most 2000 characters.";
  else if (!phases) error.value = "Phases: at most 20 different names of 1 to 60 characters, one per line.";
  else {
    error.value = "";
    busy.value = true;
    try {
      const snap = readSnapshot(await roadmapApi.create({ title: title.value.trim(), description: desc, phases }));
      if (snap) await router.push(`/roadmaps/${snap.roadmap.id}`);
      else await load();
      creating.value = false;
      title.value = description.value = "";
      phasesText.value = DEFAULT_PHASES.join("\n");
    } catch (e) {
      error.value = writeFailureText(e, false);
    } finally {
      busy.value = false;
    }
  }
}
</script>

<template>
  <PageShell title="Roadmaps" subtitle="Your plans: what is live, ready, in progress, planned or blocked.">
    <template #actions>
      <button type="button" class="btn btn-secondary" @click="load">Refresh</button>
      <button type="button" class="btn btn-primary" :disabled="full || creating" @click="openCreate">New roadmap</button>
    </template>
    <p v-if="full" class="t-sm t-2">You have 20 roadmaps, the most Core keeps open. Archive one to start another.</p>

    <form v-if="creating" class="card rm-form" aria-labelledby="rm-new" @submit.prevent="create" @keydown.esc="creating = false">
      <h2 id="rm-new">New roadmap</h2>
      <label class="field"><span class="label">Title</span>
        <input ref="titleEl" v-model="title" class="input" maxlength="120" required :aria-invalid="!!error && !title.trim()" />
      </label>
      <label class="field"><span class="label">Description <span class="t-2">(optional)</span></span>
        <textarea v-model="description" class="input" maxlength="2000" rows="2"></textarea>
      </label>
      <label class="field"><span class="label">Phases</span>
        <textarea v-model="phasesText" class="input" rows="3"></textarea>
        <span class="help">One per line, in order. Items sit in one phase or none.</span>
      </label>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <div class="rm-actions">
        <button type="button" class="btn btn-secondary" :disabled="busy" @click="creating = false">Cancel</button>
        <button type="submit" class="btn btn-primary" :aria-busy="busy">
          <span v-if="busy" class="spinner" aria-hidden="true"></span>Create
        </button>
      </div>
    </form>

    <div v-if="state.kind === 'loading'" class="card rm-col" role="status">
      <span class="rm-sr">Loading roadmaps…</span>
      <span class="skel" style="width: 40%; height: 16px" aria-hidden="true"></span>
      <span class="skel" style="width: 70%; height: 14px" aria-hidden="true"></span>
    </div>
    <div v-else-if="state.kind === 'failed'" class="card rm-col" role="alert">
      <div class="rm-title"><span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span><h3>Could not load roadmaps</h3></div>
      <p class="t-sm t-2">{{ failureText(state.failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></div>
    </div>
    <div v-else-if="!state.cards.length" class="card empty">
      <h3>No roadmaps yet</h3>
      <p class="t-sm">Start one for a project: its phases, its items and what each is waiting on.</p>
    </div>
    <ul v-else class="rm-cards">
      <li v-for="card in state.cards" :key="card.roadmap.id" class="card rm-card">
        <div class="rm-card-head">
          <h2><RouterLink :to="`/roadmaps/${card.roadmap.id}`">{{ card.roadmap.title }}</RouterLink></h2>
          <span v-if="card.proposals" class="badge acc">{{ card.proposals }} {{ card.proposals === 1 ? "proposal" : "proposals" }}</span>
        </div>
        <p v-if="card.roadmap.description" class="t-sm t-2 rm-desc">{{ card.roadmap.description }}</p>
        <div v-if="card.tally?.length" class="rm-chips" aria-label="Items per status">
          <span v-for="t in card.tally" :key="t.status" class="badge" :class="STATUS_TONE[t.status]">
            <span class="dot" aria-hidden="true"></span>{{ t.count }} {{ STATUS_LABEL[t.status].toLowerCase() }}
          </span>
        </div>
        <p class="t-xs t-2">{{ card.items === null ? "Items could not be loaded" : card.items === 0 ? "No items yet" : `${card.items} ${card.items === 1 ? "item" : "items"}` }}</p>
      </li>
    </ul>
  </PageShell>
</template>

<style scoped>
.rm-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.rm-col, .rm-form { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.rm-form { max-width: 640px; }
.rm-form h2, .rm-card h2, .rm-col h3 { margin: 0; }
.rm-title { display: flex; align-items: center; gap: 10px; }
.rm-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
.rm-cards { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.rm-card { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.rm-card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.rm-card h2 { min-width: 0; overflow-wrap: anywhere; }
.rm-card h2 a { color: var(--text-primary); text-decoration: none; }
.rm-card h2 a:hover { color: var(--accent-text); text-decoration: underline; text-underline-offset: 3px; }
.rm-desc { margin: 0; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.rm-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.badge > .dot { width: 6px; height: 6px; }
p { margin: 0; }
</style>
