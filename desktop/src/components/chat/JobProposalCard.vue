<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import JobForm from "../jobs/JobForm.vue";
import { ADDED, proposalDetail, proposalSchedule, proposalTitle, type ProposalCard } from "../../jobProposals";
import { KIND_LABEL, newJob, todayInZone, type Draft } from "../../jobs";
import { addProposal, dismissProposal } from "../../assistant";
import { locked } from "../../lock";

// One job a chat answer proposed, under that answer. Nothing is made until
// Add (the proposal as is) or Add in the editor (the edited job).
const props = defineProps<{ card: ProposalCard }>();
const today = todayInZone();
const title = computed(() => proposalTitle(props.card.draft));
const detail = computed(() => proposalDetail(props.card.draft));
const busy = computed(() => props.card.state === "adding");

const dialog = ref<HTMLDialogElement | null>(null);
const editing = ref(false);
async function edit(): Promise<void> {
  if (locked.value) return;
  editing.value = true;
  await nextTick();
  dialog.value?.showModal();
}
watch(() => props.card.state, (state) => { if (state === "added" || state === "unconfirmed") dialog.value?.close(); });

function save(draft: Draft): void {
  const body = newJob(draft, todayInZone());
  if (body) void addProposal(props.card.id, { body, draft: { ...draft } });
}
</script>

<template>
  <div class="proposal" role="group" :aria-label="`Proposed job: ${title}`">
    <div class="head">
      <span class="badge">{{ KIND_LABEL[card.draft.kind] }}</span>
      <strong class="wrap">{{ title }}</strong>
    </div>
    <span class="t-xs t-2">{{ proposalSchedule(card.draft, today) }}</span>
    <span v-if="detail" class="t-xs t-2 wrap">{{ detail }}</span>
    <p v-if="card.state === 'added'" class="t-sm" role="status">
      {{ ADDED }} · <RouterLink :to="`/jobs/${card.jobId}`">View job</RouterLink>
    </p>
    <p v-else-if="card.state === 'unconfirmed'" class="t-sm" role="status">
      {{ card.error }} <RouterLink to="/jobs">Check Jobs</RouterLink>
    </p>
    <p v-else-if="card.error && !editing" class="err t-xs" role="alert">{{ card.error }}</p>
    <div class="actions">
      <template v-if="card.state === 'open' || busy">
        <button type="button" class="btn btn-secondary btn-sm" :disabled="locked || busy" :aria-busy="busy" @click="addProposal(card.id)">Add</button>
        <button type="button" class="btn btn-ghost btn-sm" :disabled="locked || busy" @click="edit">Edit</button>
      </template>
      <button type="button" class="btn btn-ghost btn-sm" :disabled="busy" @click="dismissProposal(card.id)">Dismiss</button>
    </div>

    <dialog v-if="editing" ref="dialog" class="dialog editor" :aria-labelledby="`jp-${card.id}`" @close="editing = false">
      <h2 :id="`jp-${card.id}`">Proposed job</h2>
      <JobForm :initial="card.draft" :busy="busy" submit="Add" cancel @save="save" @cancel="dialog?.close()" />
      <p v-if="card.error" class="err" role="alert">{{ card.error }}</p>
    </dialog>
  </div>
</template>

<style scoped>
.proposal {
  align-self: flex-start; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 10px 12px;
  border-radius: var(--r-md); background: var(--bg-sunken); max-width: 100%;
}
.head { display: flex; align-items: center; gap: 8px; min-width: 0; }
.wrap { overflow-wrap: anywhere; }
.actions { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px; }
.proposal p { margin: 0; }
.proposal a { color: var(--accent-text); }
.editor { max-width: 680px; max-height: 92vh; overflow-y: auto; }
.editor h2 { margin: 0; }
</style>
