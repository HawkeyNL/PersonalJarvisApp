<script setup lang="ts">
import { computed } from "vue";
import { ADDED, proposalDescription, proposalGroups, proposalHeading, proposalSummary, type RoadmapCard } from "../../roadmapProposals";
import { STATUS_LABEL, STATUS_TONE, linkLabel } from "../../roadmaps";
import { addRoadmapProposal, dismissRoadmapProposal } from "../../assistant";
import { locked } from "../../lock";

// One roadmap a chat answer proposed, under that answer. Nothing is made
// until Add. All text is plain: links show as labels and never open.
const props = defineProps<{ card: RoadmapCard }>();
const heading = computed(() => proposalHeading(props.card));
const description = computed(() => proposalDescription(props.card));
const groups = computed(() => proposalGroups(props.card));
const busy = computed(() => props.card.state === "adding");
</script>

<template>
  <div class="proposal" role="group" :aria-label="heading">
    <strong class="wrap">{{ heading }}</strong>
    <span class="t-xs t-2">{{ proposalSummary(card) }}</span>
    <span v-if="description" class="t-sm wrap notes">{{ description }}</span>
    <div class="groups">
      <section v-for="g in groups" :key="g.phase ?? ''">
        <h3 class="t-xs t-2">{{ g.phase ?? "No phase" }}</h3>
        <ul v-if="g.items.length">
          <li v-for="item in g.items" :key="item.id">
            <span class="row">
              <span class="badge" :class="STATUS_TONE[item.status]"><span class="dot" aria-hidden="true"></span>{{ STATUS_LABEL[item.status] }}</span>
              <span class="t-sm wrap">{{ item.title }}</span>
            </span>
            <span v-if="item.notes" class="t-xs t-2 wrap notes">{{ item.notes }}</span>
            <span v-if="item.links.length" class="t-xs t-2 mono wrap">{{ item.links.map(linkLabel).join(", ") }}</span>
          </li>
        </ul>
      </section>
    </div>
    <p v-if="card.state === 'added' && card.viewId" class="t-sm" role="status">
      {{ ADDED }} · <RouterLink :to="`/roadmaps/${card.viewId}`">View roadmap</RouterLink>
    </p>
    <p v-else-if="card.state === 'unconfirmed'" class="t-sm" role="status">
      {{ card.error }} <RouterLink to="/roadmaps">Check Roadmaps</RouterLink>
    </p>
    <p v-else-if="card.error" class="err t-xs" role="alert">{{ card.error }}</p>
    <div class="actions">
      <button
        v-if="card.state === 'open' || busy" type="button" class="btn btn-secondary btn-sm"
        :disabled="locked || busy" :aria-busy="busy" @click="addRoadmapProposal(card.id)"
      >Add</button>
      <button type="button" class="btn btn-ghost btn-sm" :disabled="busy" @click="dismissRoadmapProposal(card.id)">Dismiss</button>
    </div>
  </div>
</template>

<style scoped>
.proposal {
  align-self: flex-start; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 10px 12px;
  border-radius: var(--r-md); background: var(--bg-sunken); max-width: 100%;
}
.wrap { overflow-wrap: anywhere; }
.groups { display: flex; flex-direction: column; gap: 8px; max-height: 360px; overflow-y: auto; align-self: stretch; margin-top: 4px; }
.groups h3 { margin: 0 0 2px; font-weight: 600; }
.groups ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.groups li { display: flex; flex-direction: column; gap: 2px; }
.row { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.row .badge { flex: none; }
.notes { white-space: pre-wrap; }
.actions { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px; }
.proposal p { margin: 0; }
.proposal a { color: var(--accent-text); }
</style>
