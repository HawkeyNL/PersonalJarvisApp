<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import JvSegmented from "../components/jv/JvSegmented.vue";
import ItemSheet from "../components/roadmaps/ItemSheet.vue";
import { currentAuthStatus } from "../auth";
import { asOfText } from "../trading";
import {
  AUTHOR_LABEL, LIMITS, STATUSES, STATUS_LABEL, STATUS_TONE, describeChange, describeOp, failureOf, failureText,
  groupByPhase, isDone, isNewer, linkLabel, parsePhases, readHistory, readProposals, readSnapshot, tally, validText, waitingOn,
  writeFailureText, type Failure, type HistoryEntry, type ItemPatch, type Proposal, type Snapshot, type Status,
} from "../roadmaps";
import { coalesce } from "../coalesce";
import { roadmapApi, useRoadmapRefetch } from "../roadmapsApi";

// One roadmap: phases as a vertical rail, what it is waiting on, Jarvis's
// pending proposals and the history. Every write names the revision it was
// made on; a 409 reloads and says so. Content comes from Core only.
const route = useRoute();
const router = useRouter();
const id = computed(() => String(route.params.id ?? ""));

const snap = ref<Snapshot | null>(null);
const failure = ref<Failure | null>(null);
const proposals = ref<Proposal[] | null>(null);
const history = ref<HistoryEntry[] | null>(null);
const historyFailed = ref(false);
const notice = ref("");
const busy = ref(false);
const tab = ref("items"); // items | proposals | history
let seq = 0;

async function load(): Promise<void> {
  const mine = ++seq;
  const [s, p] = await Promise.allSettled([roadmapApi.get(id.value), roadmapApi.proposals(id.value)]);
  if (mine !== seq) return;
  if (s.status === "fulfilled") {
    const next = readSnapshot(s.value);
    if (next) { snap.value = next; failure.value = null; } else if (!snap.value) failure.value = "failed";
  } else if (!snap.value) failure.value = failureOf(s.reason);
  proposals.value = p.status === "fulfilled" ? readProposals(p.value) : null;
  if (tab.value === "history") void loadHistory();
}
async function loadHistory(): Promise<void> {
  try {
    history.value = readHistory(await roadmapApi.history(id.value));
    historyFailed.value = history.value === null;
  } catch {
    historyFailed.value = true;
  }
}
watch(id, () => { snap.value = null; failure.value = null; history.value = null; void load(); }, { immediate: true });
// Focus, reconnect and roadmap.changed reload at most once more while a
// reload runs. One arriving during a write is kept and replayed after it
// when it is newer than what the write left on screen.
const refetch = coalesce(load);
let missed = -1;
function hinted(revision: number): void {
  if (busy.value) missed = Math.max(missed, revision);
  else void refetch();
}
function afterBusy(): void {
  if (missed > (snap.value?.roadmap.revision ?? -1)) void refetch();
  missed = -1;
}
useRoadmapRefetch(
  () => hinted(Number.MAX_SAFE_INTEGER),
  (change) => { if (change.id === id.value && isNewer(change, snap.value?.roadmap.revision)) hinted(change.revision); },
);

const thisDevice = ref<string | null>(null);
currentAuthStatus().then((s) => (thisDevice.value = s.device_id), () => {});
const deviceLabel = (d: string | null) => (!d ? "" : d === thisDevice.value ? "this device" : `device ${d.slice(0, 8)}`);

/** Run one write on `revision` (default: the one on screen). Core answers
 *  with the whole roadmap; after any failure the page reloads and explains. */
async function write(run: (revision: number) => Promise<unknown>, revision?: number): Promise<boolean> {
  if (!snap.value || busy.value) return false;
  const before = revision ?? snap.value.roadmap.revision;
  busy.value = true;
  notice.value = "";
  seq++; // a read already in flight must not overwrite the answer
  try {
    const next = readSnapshot(await run(before));
    if (next) snap.value = next;
    await load();
    return true;
  } catch (error) {
    await load();
    notice.value = writeFailureText(error, (snap.value?.roadmap.revision ?? before) !== before);
    return false;
  } finally {
    busy.value = false;
    afterBusy();
  }
}

// ---- tabs, filter, rail ----------------------------------------------------
watch(tab, (t) => { if (t === "history") void loadHistory(); });
const tabs = computed(() => [
  { id: "items", label: "Items" },
  { id: "proposals", label: proposals.value?.length ? `Proposals (${proposals.value.length})` : "Proposals" },
  { id: "history", label: "History" },
]);

const filter = ref<Status | "all">("all");
const items = computed(() => snap.value?.items ?? []);
const counts = computed(() => tally(items.value));
const shown = computed(() => (filter.value === "all" ? items.value : items.value.filter((i) => i.status === filter.value)));
const groups = computed(() => groupByPhase(snap.value?.roadmap.phases ?? [], shown.value));
const waiting = computed(() => waitingOn(items.value));
const byId = computed(() => new Map(items.value.map((i) => [i.id, i])));
const unmetCount = (depends: string[]) => depends.filter((d) => { const i = byId.value.get(d); return i && !isDone(i.status); }).length;
const firstLine = (text: string | null) => text?.split("\n").find((l) => l.trim()) ?? "";
const archived = computed(() => !!snap.value?.roadmap.archived_at);
// The first phase with open work is "now" on the rail.
const nowPhase = computed(() => groupByPhase(snap.value?.roadmap.phases ?? [], items.value)
  .find((g) => g.phase !== null && g.items.some((i) => !isDone(i.status)))?.phase ?? null);

// ---- add item --------------------------------------------------------------
const newTitle = ref("");
const newPhase = ref<string | null>(null);
watch(snap, (s) => { if (s && newPhase.value !== null && !s.roadmap.phases.includes(newPhase.value)) newPhase.value = null; });
const addError = ref("");
async function addItem(): Promise<void> {
  const title = newTitle.value.trim();
  if (!validText(title, LIMITS.itemTitle)) { addError.value = "The title must be 1 to 200 characters on one line."; return; }
  if (items.value.length >= LIMITS.items) { addError.value = "A roadmap holds at most 300 items."; return; }
  addError.value = "";
  const ok = await write((rev) => roadmapApi.createItem(id.value, rev, { title, ...(newPhase.value ? { phase: newPhase.value } : {}) }));
  if (ok) newTitle.value = "";
}

// ---- item sheet --------------------------------------------------------------
// Mounted per item and revision: a conflict remounts it on Core's new state.
const sheet = ref<{ itemId: string; revision: number; key: number; notice: string } | null>(null);
const sheetItem = computed(() => (sheet.value ? byId.value.get(sheet.value.itemId) ?? null : null));
let sheetKey = 0;
// A reload without the open item (deleted elsewhere) unmounts the sheet
// without its close event; say so instead of hiding every later notice.
// During a write (busy) afterSheetWrite handles the sheet, own deletes too.
watch(sheetItem, (item) => {
  if (sheet.value && !item && snap.value && !busy.value) {
    sheet.value = null;
    notice.value = "This item was deleted elsewhere.";
  }
});
function openSheet(itemId: string): void {
  if (snap.value) sheet.value = { itemId, revision: snap.value.roadmap.revision, key: ++sheetKey, notice: "" };
}
/** Close after a write; after a failure, reopen on Core's state with the
 *  notice inside the sheet (or on the page when the item is gone). */
function afterSheetWrite(ok: boolean): void {
  const s = sheet.value;
  if (!s || ok) { sheet.value = null; return; }
  if (!snap.value || !byId.value.has(s.itemId)) { sheet.value = null; return; }
  sheet.value = { itemId: s.itemId, revision: snap.value.roadmap.revision, key: ++sheetKey, notice: notice.value };
  notice.value = "";
}
async function saveItem(patch: ItemPatch): Promise<void> {
  const s = sheet.value;
  if (s) afterSheetWrite(await write((rev) => roadmapApi.updateItem(id.value, rev, s.itemId, patch), s.revision));
}
async function removeItem(): Promise<void> {
  const s = sheet.value;
  if (s) afterSheetWrite(await write((rev) => roadmapApi.deleteItem(id.value, rev, s.itemId), s.revision));
}

// ---- edit and archive --------------------------------------------------------
const editing = ref(false);
const edit = ref({ title: "", description: "", phases: "" });
const editError = ref("");
function startEdit(): void {
  if (!snap.value) return;
  const r = snap.value.roadmap;
  edit.value = { title: r.title, description: r.description ?? "", phases: r.phases.join("\n") };
  editError.value = "";
  editing.value = true;
}
async function saveEdit(): Promise<void> {
  const r = snap.value?.roadmap;
  if (!r) return;
  const title = edit.value.title.trim();
  const description = edit.value.description.trim() ? edit.value.description : null;
  const phases = parsePhases(edit.value.phases);
  if (!validText(title, LIMITS.roadmapTitle)) { editError.value = "The title must be 1 to 120 characters on one line."; return; }
  if (description && !validText(description, LIMITS.description, true)) { editError.value = "The description must be at most 2000 characters."; return; }
  if (!phases) { editError.value = "Phases: at most 20 different names of 1 to 60 characters, one per line."; return; }
  const patch: { title?: string; description?: string | null; phases?: string[] } = {};
  if (title !== r.title) patch.title = title;
  if (description !== r.description) patch.description = description;
  if (phases.join("\n") !== r.phases.join("\n")) patch.phases = phases;
  if (!Object.keys(patch).length) { editing.value = false; return; }
  editError.value = "";
  if (await write((rev) => roadmapApi.update(id.value, rev, patch))) editing.value = false;
}
const removedPhases = computed(() => {
  const next = parsePhases(edit.value.phases) ?? [];
  return (snap.value?.roadmap.phases ?? []).filter((p) => !next.includes(p) && items.value.some((i) => i.phase === p));
});

const archiveDialog = ref<HTMLDialogElement | null>(null);
async function archive(): Promise<void> {
  if (await write((rev) => roadmapApi.archive(id.value, rev))) {
    archiveDialog.value?.close();
    await router.push("/roadmaps");
  } else archiveDialog.value?.close();
}

// ---- proposals ---------------------------------------------------------------
const deciding = ref<string | null>(null);
async function decide(p: Proposal, accept: boolean): Promise<void> {
  if (busy.value || !snap.value) return;
  deciding.value = p.id;
  busy.value = true;
  notice.value = "";
  seq++;
  try {
    const answer = await (accept ? roadmapApi.accept(id.value, p.id) : roadmapApi.reject(id.value, p.id));
    const next = accept ? readSnapshot(answer) : null;
    if (next) snap.value = next;
    notice.value = accept ? "Proposal applied." : "Proposal rejected.";
  } catch (error) {
    const e = error as { name?: string; status?: number };
    notice.value = e?.name === "ApiError" && e.status === 409
      ? "Not applied: the roadmap changed since Jarvis proposed this, or a limit is reached. Reloaded."
      : `Not done. ${failureText(failureOf(error))}`;
  } finally {
    await load();
    busy.value = false;
    deciding.value = null;
    afterBusy();
  }
}
const opsOf = (p: Proposal) => p.ops.map((op) => describeOp(op, items.value));

const noticeEl = ref<HTMLElement | null>(null);
watch(notice, async (n) => { if (n) { await nextTick(); noticeEl.value?.focus(); } });
const now = Date.now();
</script>

<template>
  <PageShell :title="snap?.roadmap.title ?? 'Roadmap'">
    <template #crumb><p class="rm-crumb t-sm"><RouterLink to="/roadmaps">Roadmaps</RouterLink></p></template>
    <template #badges>
      <span v-if="archived" class="badge">Archived</span>
    </template>
    <template #subtitle>
      <p v-if="snap?.roadmap.description" class="rm-sub t-sm t-2">{{ snap.roadmap.description }}</p>
    </template>
    <template #actions>
      <button type="button" class="btn btn-secondary" :disabled="busy" @click="load">Refresh</button>
      <template v-if="snap && !archived">
        <button type="button" class="btn btn-secondary" :disabled="busy || editing" @click="startEdit">Edit</button>
        <button type="button" class="btn btn-secondary" :disabled="busy" @click="archiveDialog?.showModal()">Archive</button>
      </template>
    </template>

    <p v-if="notice && !sheet" ref="noticeEl" tabindex="-1" class="rm-banner t-sm" role="status">{{ notice }}</p>

    <div v-if="failure" class="card rm-col" role="alert">
      <div class="rm-title"><span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span><h3>Could not load this roadmap</h3></div>
      <p class="t-sm t-2">{{ failureText(failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></div>
    </div>
    <div v-else-if="!snap" class="card rm-col" role="status">
      <span class="rm-sr">Loading the roadmap…</span>
      <span class="skel" style="width: 40%; height: 16px" aria-hidden="true"></span>
      <span class="skel" style="width: 80%; height: 14px" aria-hidden="true"></span>
    </div>
    <template v-else>
      <form v-if="editing" class="card rm-col rm-form" aria-labelledby="rm-edit" @submit.prevent="saveEdit" @keydown.esc="editing = false">
        <h2 id="rm-edit">Edit roadmap</h2>
        <label class="field"><span class="label">Title</span><input v-model="edit.title" class="input" maxlength="120" required /></label>
        <label class="field"><span class="label">Description <span class="t-2">(optional)</span></span>
          <textarea v-model="edit.description" class="input" rows="2" maxlength="2000"></textarea>
        </label>
        <label class="field"><span class="label">Phases</span>
          <textarea v-model="edit.phases" class="input" rows="3"></textarea>
          <span class="help">One per line, in order.</span>
        </label>
        <p v-if="removedPhases.length" class="t-sm rm-warn" role="status">
          Removing {{ removedPhases.join(", ") }} moves its items to No phase.
        </p>
        <p v-if="editError" class="err" role="alert">{{ editError }}</p>
        <div class="rm-actions">
          <button type="button" class="btn btn-secondary" :disabled="busy" @click="editing = false">Cancel</button>
          <button type="submit" class="btn btn-primary" :aria-busy="busy">Save</button>
        </div>
      </form>

      <div v-if="counts.length" class="rm-chips" aria-label="Items per status">
        <span v-for="t in counts" :key="t.status" class="badge" :class="STATUS_TONE[t.status]">
          <span class="dot" aria-hidden="true"></span>{{ t.count }} {{ STATUS_LABEL[t.status].toLowerCase() }}
        </span>
      </div>

      <JvSegmented v-model="tab" class="rm-tabs" :items="tabs" label="Roadmap sections" controls="rm-panel" />

      <div id="rm-panel" role="tabpanel" class="rm-col">
        <template v-if="tab === 'items'">
          <section v-if="waiting.length" class="card rm-col rm-waiting" aria-labelledby="rm-waiting">
            <h2 id="rm-waiting">Waiting on</h2>
            <ul class="rm-list">
              <li v-for="w in waiting" :key="w.item.id">
                <button type="button" class="rm-link-btn" @click="openSheet(w.item.id)">{{ w.item.title }}</button>
                <span v-if="w.item.status === 'blocked'" class="badge dng"><span class="dot" aria-hidden="true"></span>Blocked</span>
                <span v-if="w.item.status === 'blocked' && firstLine(w.item.notes)" class="t-sm t-2 rm-wrap">{{ firstLine(w.item.notes) }}</span>
                <span v-if="w.unmet.length" class="t-sm t-2 rm-wrap">waits on {{ w.unmet.map((d) => d.title).join(", ") }}</span>
              </li>
            </ul>
          </section>

          <div class="rm-toolbar">
            <div class="seg rm-filter" role="group" aria-label="Show status">
              <button type="button" :aria-pressed="filter === 'all'" @click="filter = 'all'">All {{ items.length }}</button>
              <button
                v-for="s in STATUSES" :key="s" type="button" :aria-pressed="filter === s"
                :disabled="!counts.some((t) => t.status === s) && filter !== s" @click="filter = s"
              >{{ STATUS_LABEL[s] }} {{ counts.find((t) => t.status === s)?.count ?? 0 }}</button>
            </div>
          </div>

          <form v-if="!archived" class="rm-add" @submit.prevent="addItem">
            <input v-model="newTitle" class="input" maxlength="200" placeholder="New item" aria-label="New item title" :aria-invalid="!!addError" />
            <select v-model="newPhase" class="input rm-phase" aria-label="Phase for the new item">
              <option :value="null">No phase</option>
              <option v-for="p in snap.roadmap.phases" :key="p" :value="p">{{ p }}</option>
            </select>
            <button type="submit" class="btn btn-primary" :disabled="!newTitle.trim() || busy">Add item</button>
          </form>
          <p v-if="addError" class="err" role="alert">{{ addError }}</p>

          <p v-if="!items.length" class="card empty t-sm">No items yet. Add the first one above.</p>
          <ol v-else class="rm-rail">
            <li v-for="g in groups" :key="g.phase ?? ''" class="rm-step" :class="{ now: g.phase !== null && g.phase === nowPhase }">
              <div class="rm-step-head">
                <h2>{{ g.phase ?? "No phase" }}</h2>
                <span v-if="g.phase !== null && g.phase === nowPhase" class="badge acc">Now</span>
                <span class="t-sm t-2">{{ g.items.length }} {{ g.items.length === 1 ? "item" : "items" }}</span>
              </div>
              <p v-if="!g.items.length" class="t-sm t-2">{{ filter === "all" ? "Nothing here yet." : "Nothing with this status." }}</p>
              <ul v-else class="rm-items">
                <li v-for="item in g.items" :key="item.id" class="card rm-item">
                  <div class="rm-item-main">
                    <button type="button" class="rm-link-btn rm-item-title" @click="openSheet(item.id)">{{ item.title }}</button>
                    <span class="badge" :class="STATUS_TONE[item.status]"><span class="dot" aria-hidden="true"></span>{{ STATUS_LABEL[item.status] }}</span>
                    <span v-if="item.auto_status && item.auto_status !== item.status" class="badge td-outline" :title="`GitHub last derived ${STATUS_LABEL[item.auto_status]}`">
                      auto: {{ STATUS_LABEL[item.auto_status] }}
                    </span>
                  </div>
                  <div v-if="item.links.length || unmetCount(item.depends_on) || firstLine(item.notes)" class="rm-item-meta t-sm t-2">
                    <span v-if="unmetCount(item.depends_on)">waits on {{ unmetCount(item.depends_on) }}</span>
                    <span v-for="(link, i) in item.links" :key="i" class="rm-linkchip">
                      <span class="mono">{{ linkLabel(link) }}</span><span v-if="link.state" class="badge">{{ link.state }}</span>
                    </span>
                    <span v-if="firstLine(item.notes)" class="rm-wrap">{{ firstLine(item.notes) }}</span>
                  </div>
                </li>
              </ul>
            </li>
          </ol>
        </template>

        <template v-else-if="tab === 'proposals'">
          <p v-if="proposals === null" class="card t-sm" role="alert">Could not load proposals. <button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></p>
          <p v-else-if="!proposals.length" class="card empty t-sm">No pending proposals. When Jarvis suggests changes, they wait here for you.</p>
          <section v-for="p in proposals ?? []" :key="p.id" class="card rm-col" :aria-labelledby="`rm-p-${p.id}`">
            <div class="rm-step-head">
              <h2 :id="`rm-p-${p.id}`">Proposal from Jarvis</h2>
              <span class="t-sm t-2">made {{ asOfText(p.created_at, now) }} · expires {{ asOfText(p.expires_at, now) }}</span>
              <span v-if="p.base_revision !== snap.roadmap.revision" class="badge neg">Made on an older version</span>
            </div>
            <p v-if="p.rationale" class="t-sm rm-pre"><span class="t-2">Jarvis's reason: </span>{{ p.rationale }}</p>
            <div v-for="(op, i) in opsOf(p)" :key="i" class="rm-op">
              <h3 class="t-sm"><span class="badge" :class="op.kind === 'create' ? 'acc' : ''">{{ op.kind === "create" ? "New item" : "Change" }}</span> {{ op.title }}</h3>
              <table v-if="op.rows.length" class="tbl rm-diff">
                <thead><tr><th scope="col">Field</th><th scope="col">Before</th><th scope="col">After</th></tr></thead>
                <tbody>
                  <tr v-for="row in op.rows" :key="row.field"><th scope="row">{{ row.field }}</th><td class="rm-pre">{{ row.before }}</td><td class="rm-pre">{{ row.after }}</td></tr>
                </tbody>
              </table>
            </div>
            <div v-if="!archived" class="rm-actions">
              <button type="button" class="btn btn-secondary" :disabled="busy" @click="decide(p, false)">Reject</button>
              <button type="button" class="btn btn-primary" :disabled="busy && deciding !== p.id" :aria-busy="deciding === p.id" @click="decide(p, true)">Accept</button>
            </div>
          </section>
        </template>

        <template v-else>
          <p v-if="historyFailed" class="card t-sm" role="alert">Could not load the history. <button type="button" class="btn btn-secondary btn-sm" @click="loadHistory">Try again</button></p>
          <p v-else-if="history === null" class="card t-sm t-2" role="status">Loading history…</p>
          <p v-else-if="!history.length" class="card empty t-sm">No changes yet.</p>
          <ol v-else class="card rm-history">
            <li v-for="c in history" :key="c.id">
              <time class="t-sm t-2 mono" :datetime="c.at">{{ asOfText(c.at, now) }}</time>
              <span class="badge" :class="{ acc: c.author === 'jarvis', pos: c.author === 'github' }">{{ AUTHOR_LABEL[c.author] }}</span>
              <span class="t-sm rm-wrap">{{ describeChange(c) }}</span>
              <span v-if="c.device_id" class="t-xs t-2">{{ deviceLabel(c.device_id) }}</span>
            </li>
          </ol>
          <p v-if="history?.length" class="t-xs t-2">The 50 newest changes.</p>
        </template>
      </div>
    </template>

    <ItemSheet
      v-if="sheet && sheetItem && snap" :key="sheet.key" :item="sheetItem" :items="snap.items" :phases="snap.roadmap.phases"
      :busy="busy" :notice="sheet.notice" @save="saveItem" @remove="removeItem" @close="sheet = null"
    />

    <dialog ref="archiveDialog" class="dialog" aria-labelledby="rm-archive-title">
      <h2 id="rm-archive-title">Archive this roadmap?</h2>
      <p class="t-sm t-2">It leaves your list and can no longer be changed. Core keeps it and its history; nothing is deleted.</p>
      <div class="rm-actions">
        <button type="button" class="btn btn-secondary" autofocus @click="archiveDialog?.close()">Cancel</button>
        <button type="button" class="btn btn-danger" :aria-busy="busy" @click="archive">Archive</button>
      </div>
    </dialog>
  </PageShell>
</template>

<style scoped>
p, h2, h3 { margin: 0; }
.rm-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.rm-crumb a { color: var(--accent-text); text-decoration: none; }
.rm-sub { margin-top: 4px; max-width: 72ch; white-space: pre-line; overflow-wrap: anywhere; }
.rm-col { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.rm-form { max-width: 640px; }
.rm-title { display: flex; align-items: center; gap: 10px; }
.rm-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
.rm-banner { padding: 12px 16px; border-radius: var(--r-lg); background: var(--accent-subtle); }
.rm-warn { color: var(--negative-text); }
.rm-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.rm-tabs { align-self: flex-start; }
.rm-toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.rm-filter { flex-wrap: wrap; }
.rm-filter button:disabled { color: var(--text-disabled); cursor: default; }
.rm-add { display: flex; gap: 8px; flex-wrap: wrap; max-width: 760px; }
.rm-add .input:first-child { flex: 1 1 280px; }
.rm-phase { width: 180px; flex: none; }
.rm-waiting { border-left: 3px solid var(--danger-solid); }
.rm-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.rm-list li { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; min-width: 0; }
.rm-link-btn {
  border: 0; padding: 0; background: none; font: inherit; font-weight: 600; color: var(--text-primary); cursor: pointer; text-align: left;
  overflow-wrap: anywhere;
}
.rm-link-btn:hover { color: var(--accent-text); text-decoration: underline; text-underline-offset: 3px; }
.rm-wrap { overflow-wrap: anywhere; min-width: 0; }

/* Rail: a vertical line with one node per phase; the "now" phase is filled. */
.rm-rail { list-style: none; margin: 0 0 0 7px; padding: 0; border-left: 2px solid var(--border); }
.rm-step { position: relative; padding: 0 0 24px 24px; display: flex; flex-direction: column; gap: 10px; }
.rm-step::before {
  content: ""; position: absolute; left: -8px; top: 5px; width: 14px; height: 14px; border-radius: var(--r-pill);
  background: var(--bg-page); border: 2px solid var(--accent-solid); box-sizing: border-box;
}
.rm-step.now::before { background: var(--accent-solid); }
.rm-step-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.rm-items { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.card.rm-item { padding: 12px 16px; display: flex; flex-direction: column; gap: 6px; }
.rm-item-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 0; }
.rm-item-title { flex: 0 1 auto; min-width: 0; }
.rm-item-meta { display: flex; gap: 6px 14px; flex-wrap: wrap; align-items: center; }
.rm-linkchip { display: inline-flex; gap: 6px; align-items: center; }
.badge.td-outline { background: transparent; box-shadow: inset 0 0 0 1px var(--border-strong); }

.rm-op { display: flex; flex-direction: column; gap: 6px; }
.rm-op h3 { display: flex; gap: 8px; align-items: center; overflow-wrap: anywhere; }
.rm-diff th[scope="row"] { font-weight: 500; color: var(--text-secondary); white-space: nowrap; }
.rm-pre { white-space: pre-wrap; overflow-wrap: anywhere; }

.rm-history { list-style: none; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.rm-history li { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
.badge > .dot { width: 6px; height: 6px; }
</style>
