<script setup lang="ts">
import { ref } from "vue";
import { TIME_ZONE, WEEKDAYS, timeToMinute, type Draft, type Kind } from "../../jobs";
import { readList, type Roadmap } from "../../roadmaps";
import { roadmapApi } from "../../roadmapsApi";

// The schedule editor, for a new job and an existing one: daily or weekly,
// the weekday, a time in Europe/Amsterdam, enabled, and for a dependency
// review the roadmap its runs propose upgrades to.
const props = defineProps<{ kind: Kind; initial: Draft; busy: boolean; submit: string; cancel?: boolean }>();
const emit = defineEmits<{ save: [draft: Draft]; cancel: [] }>();

const draft = ref<Draft>({ ...props.initial });
const error = ref("");

// null while loading or when the roadmaps could not be read.
const roadmaps = ref<Roadmap[] | null>(null);
if (props.kind === "dependency_review") {
  roadmapApi.list().then((x) => (roadmaps.value = readList(x)?.filter((r) => !r.archived_at) ?? null), () => {});
}
const known = (id: string) => roadmaps.value?.some((r) => r.id === id) ?? false;

function save(): void {
  if (timeToMinute(draft.value.time) === null) { error.value = "Pick a time of day."; return; }
  error.value = "";
  emit("save", { ...draft.value });
}
</script>

<template>
  <form class="jb-form" @submit.prevent="save" @keydown.esc="emit('cancel')">
    <div class="jb-row">
      <label class="field"><span class="label">Repeats</span>
        <select v-model="draft.cadence" class="input">
          <option value="weekly">Weekly</option>
          <option value="daily">Daily</option>
        </select>
      </label>
      <label v-if="draft.cadence === 'weekly'" class="field"><span class="label">Day</span>
        <select v-model.number="draft.weekday" class="input">
          <option v-for="(day, i) in WEEKDAYS" :key="day" :value="i + 1">{{ day }}</option>
        </select>
      </label>
      <label class="field"><span class="label">Time</span>
        <input v-model="draft.time" class="input" type="time" step="60" required :aria-invalid="!!error" aria-describedby="jb-tz" />
        <span id="jb-tz" class="help">{{ TIME_ZONE }} time</span>
      </label>
    </div>
    <label v-if="kind === 'dependency_review'" class="field"><span class="label">Propose upgrades to a roadmap <span class="t-2">(optional)</span></span>
      <select v-model="draft.roadmap_id" class="input" :disabled="roadmaps === null && !draft.roadmap_id">
        <option :value="null">No roadmap</option>
        <option v-if="draft.roadmap_id && !known(draft.roadmap_id)" :value="draft.roadmap_id">
          {{ roadmaps === null ? "The roadmap set now" : "A roadmap that is gone or archived" }}
        </option>
        <option v-for="r in roadmaps ?? []" :key="r.id" :value="r.id">{{ r.title }}</option>
      </select>
      <span class="help">Each run then proposes one item per fixable alert. You accept or reject the proposal on the roadmap.</span>
    </label>
    <label class="jb-switch">
      <input v-model="draft.enabled" class="switch" type="checkbox" role="switch" />
      <span>{{ draft.enabled ? "Enabled: runs on this schedule" : "Paused: runs only when you run it" }}</span>
    </label>
    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <div class="jb-actions">
      <button v-if="cancel" type="button" class="btn btn-secondary" :disabled="busy" @click="emit('cancel')">Cancel</button>
      <button type="submit" class="btn btn-primary" :disabled="busy" :aria-busy="busy">{{ submit }}</button>
    </div>
  </form>
</template>

<style scoped>
.jb-form { display: flex; flex-direction: column; gap: 14px; max-width: 640px; }
.jb-row { display: flex; gap: 12px; flex-wrap: wrap; }
.jb-row .field { flex: 1 1 140px; }
.jb-switch { display: flex; gap: 10px; align-items: center; font-size: 14px; }
.jb-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
p { margin: 0; }
</style>
