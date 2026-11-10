<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { openUrl } from "@tauri-apps/plugin-opener";
import {
  LIMITS, STATUSES, STATUS_LABEL, STATUS_TONE, addLink, dependencyOptions, draftIssue, itemPatch, linkHref, linkLabel,
  parseLink, toDraft, type Item, type ItemPatch,
} from "../../roadmaps";

// One item in a side sheet (a native modal dialog: focus trap, Esc, inert
// page). The parent mounts it fresh per item and revision, writes the patch
// and remounts it after a conflict, so the draft always starts from Core.
// Notes are plain text: rendered by interpolation only, never as HTML.
const props = defineProps<{ item: Item; items: Item[]; phases: string[]; busy: boolean; notice: string }>();
const emit = defineEmits<{ save: [patch: ItemPatch]; remove: []; close: [] }>();

const dialog = ref<HTMLDialogElement | null>(null);
onMounted(() => dialog.value?.showModal());

const draft = ref(toDraft(props.item));
// Against the item as opened: a live reload must not turn a change made
// elsewhere into an edit. Saving then meets Core's 409 and remounts.
const opened = props.item;
const patch = computed(() => itemPatch(opened, draft.value));
const issue = computed(() => draftIssue(draft.value));
const byId = computed(() => new Map(props.items.map((i) => [i.id, i])));
const options = computed(() => dependencyOptions(props.items, props.item.id));
// A phase Core no longer has still shows, so the select never hides a value.
const phaseChoices = computed(() =>
  props.item.phase && !props.phases.includes(props.item.phase) ? [...props.phases, props.item.phase] : props.phases);

function toggleDep(id: string, on: boolean): void {
  const deps = draft.value.depends_on.filter((d) => d !== id);
  draft.value.depends_on = on ? [...deps, id] : deps;
}

const linkText = ref("");
const linkHint = ref<"pr" | "issue">("pr");
const linkError = ref("");
function addTypedLink(): void {
  const parsed = parseLink(linkText.value, linkHint.value);
  if (!parsed.ok) { linkError.value = parsed.error; return; }
  const next = addLink(draft.value.links, parsed.link);
  if (!next) { linkError.value = draft.value.links.length >= LIMITS.links ? "At most 10 links." : "That link is already there."; return; }
  draft.value.links = next;
  linkText.value = linkError.value = "";
}
function removeLink(index: number): void {
  draft.value.links = draft.value.links.filter((_, i) => i !== index);
}
function open(href: string | null): void {
  if (href) void openUrl(href);
}

const confirmDelete = ref(false);
function save(): void {
  if (patch.value && !issue.value && !props.busy) emit("save", patch.value);
}
</script>

<template>
  <dialog ref="dialog" class="dialog rm-sheet" aria-labelledby="rm-sheet-title" @close="emit('close')">
    <form class="rm-sheet-body" @submit.prevent="save">
      <div class="rm-sheet-head">
        <h2 id="rm-sheet-title">{{ item.title }}</h2>
        <button type="button" class="btn btn-ghost btn-sm" @click="dialog?.close()">Close</button>
      </div>
      <p v-if="notice" class="rm-notice t-sm" role="status">{{ notice }}</p>

      <label class="field"><span class="label">Title</span>
        <input v-model="draft.title" class="input" maxlength="200" required />
      </label>
      <div class="rm-row">
        <label class="field"><span class="label">Status</span>
          <select v-model="draft.status" class="input">
            <option v-for="s in STATUSES" :key="s" :value="s">{{ STATUS_LABEL[s] }}</option>
          </select>
        </label>
        <label class="field"><span class="label">Phase</span>
          <select v-model="draft.phase" class="input">
            <option :value="null">No phase</option>
            <option v-for="p in phaseChoices" :key="p" :value="p">{{ p }}</option>
          </select>
        </label>
      </div>
      <p v-if="item.auto_status" class="t-sm t-2">
        GitHub last derived
        <span class="badge" :class="STATUS_TONE[item.auto_status]"><span class="dot" aria-hidden="true"></span>{{ STATUS_LABEL[item.auto_status] }}</span>
        <template v-if="item.auto_status !== draft.status"> · your status wins</template>
      </p>

      <label class="field"><span class="label">{{ draft.status === "blocked" ? "Notes: what is it waiting on?" : "Notes" }}</span>
        <textarea v-model="draft.notes" class="input" rows="4" maxlength="4000"></textarea>
        <span class="help">Plain text. {{ draft.status === "blocked" ? "The first line shows as the reason under Waiting on." : "" }}</span>
      </label>

      <fieldset class="rm-fieldset">
        <legend class="label">Depends on</legend>
        <p v-if="!options.length && !draft.depends_on.length" class="t-sm t-2">No other items to depend on.</p>
        <div v-else class="rm-deps">
          <label v-for="o in options" :key="o.id" class="rm-check t-sm">
            <input
              type="checkbox" :checked="draft.depends_on.includes(o.id)"
              :disabled="!draft.depends_on.includes(o.id) && draft.depends_on.length >= LIMITS.dependsOn"
              @change="toggleDep(o.id, ($event.target as HTMLInputElement).checked)"
            />
            <span>{{ o.title }}</span>
            <span class="badge" :class="STATUS_TONE[o.status]">{{ STATUS_LABEL[o.status] }}</span>
          </label>
          <!-- A dependency Core still has but the picker would no longer offer. -->
          <label v-for="id in draft.depends_on.filter((d) => !options.some((o) => o.id === d))" :key="id" class="rm-check t-sm">
            <input type="checkbox" checked @change="toggleDep(id, false)" />
            <span>{{ byId.get(id)?.title ?? "An item that no longer exists" }}</span>
          </label>
        </div>
      </fieldset>

      <fieldset class="rm-fieldset">
        <legend class="label">Links</legend>
        <ul v-if="draft.links.length" class="rm-links">
          <li v-for="(link, i) in draft.links" :key="i" class="t-sm">
            <a v-if="linkHref(link)" :href="linkHref(link)!" class="mono" @click.prevent="open(linkHref(link))">{{ linkLabel(link) }}</a>
            <span v-else class="mono">{{ linkLabel(link) }}</span>
            <span v-if="link.state" class="badge" title="State GitHub last reported">GitHub: {{ link.state }}</span>
            <button type="button" class="btn btn-ghost btn-sm" :aria-label="`Remove ${linkLabel(link)}`" @click="removeLink(i)">Remove</button>
          </li>
        </ul>
        <div class="rm-link-add">
          <select v-model="linkHint" class="input rm-hint" aria-label="Kind for owner/name#12">
            <option value="pr">PR</option>
            <option value="issue">Issue</option>
          </select>
          <input
            v-model="linkText" class="input" placeholder="GitHub link, owner/name#12, owner/name@v1.2.0 or https URL"
            aria-label="Link to add" :aria-invalid="!!linkError" @keydown.enter.prevent="addTypedLink"
          />
          <button type="button" class="btn btn-secondary" :disabled="!linkText.trim() || draft.links.length >= LIMITS.links" @click="addTypedLink">Add</button>
        </div>
        <p v-if="linkError" class="err" role="alert">{{ linkError }}</p>
      </fieldset>

      <p v-if="issue" class="err" role="alert">{{ issue }}</p>
      <div class="rm-sheet-foot">
        <template v-if="confirmDelete">
          <span class="t-sm">Delete this item? Items that depend on it no longer will.</span>
          <button type="button" class="btn btn-secondary btn-sm" @click="confirmDelete = false">Keep</button>
          <button type="button" class="btn btn-danger btn-sm" :aria-busy="busy" @click="emit('remove')">Delete</button>
        </template>
        <button v-else type="button" class="btn btn-danger-quiet btn-sm" :disabled="busy" @click="confirmDelete = true">Delete item</button>
        <span class="rm-grow"></span>
        <button type="submit" class="btn btn-primary" :disabled="!patch || !!issue" :aria-busy="busy">
          <span v-if="busy" class="spinner" aria-hidden="true"></span>Save
        </button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.rm-sheet {
  margin: 0 0 0 auto; height: 100vh; max-height: 100vh; max-width: 520px; border-radius: var(--r-xl) 0 0 var(--r-xl);
  overflow-y: auto; padding: 0;
}
.rm-sheet-body { display: flex; flex-direction: column; gap: 16px; padding: 24px; }
.rm-sheet-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.rm-sheet-head h2 { margin: 0; overflow-wrap: anywhere; }
.rm-notice { margin: 0; padding: 10px 14px; border-radius: var(--r-md); background: var(--negative-subtle); }
.rm-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.rm-fieldset { border: 0; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.rm-fieldset legend { padding: 0; margin-bottom: 8px; font-size: 13px; font-weight: 500; }
.rm-deps { display: flex; flex-direction: column; gap: 6px; max-height: 220px; overflow-y: auto; padding: 4px; border-radius: var(--r-md); background: var(--bg-sunken); }
.rm-check { display: flex; align-items: center; gap: 8px; min-width: 0; }
.rm-check > span:first-of-type { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.rm-links { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.rm-links li { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 0; }
.rm-links a { color: var(--accent-text); overflow-wrap: anywhere; }
.rm-link-add { display: flex; gap: 8px; }
.rm-hint { width: 96px; flex: none; }
.rm-sheet-foot { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rm-grow { flex: 1; }
.badge > .dot { width: 6px; height: 6px; }
p { margin: 0; }
</style>
