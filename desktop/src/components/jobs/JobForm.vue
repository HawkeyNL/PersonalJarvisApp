<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import {
  CADENCE_LABEL, EVERY_UNIT, KIND_DETAIL, KIND_LABEL, LIMITS, TIMES_IN_ZONE, WEEKDAYS, cadencesFor, draftIssue, latestStart, ordinal,
  todayInZone, type Draft, type Field, type Kind,
} from "../../jobs";
import { readList, type Roadmap } from "../../roadmaps";
import { roadmapApi } from "../../roadmapsApi";

// The job editor, for a new job and an existing one: with more than one of
// `kinds`, a kind picker first; then the kind's fields, the schedule (once,
// daily, weekly or monthly, every N, a start date, an optional run limit, a
// time in Europe/Amsterdam) and enabled. The rules mirror Core's; Core
// checks them again.
const props = defineProps<{ initial: Draft; kinds?: readonly Kind[]; busy: boolean; submit: string; cancel?: boolean }>();
const emit = defineEmits<{ save: [draft: Draft]; cancel: [] }>();

const draft = ref<Draft>({ ...props.initial });
const issue = ref<{ field: Field; message: string } | null>(null);
const formEl = ref<HTMLFormElement | null>(null);
const today = todayInZone();
const cadences = computed(() => cadencesFor(draft.value.kind));
const fixed = computed(() => draft.value.kind === "dependency_review");
const invalid = (field: Field) => issue.value?.field === field;

function pickKind(kind: Kind): void {
  draft.value.kind = kind;
  issue.value = null;
}

// null while loading or when the roadmaps could not be read.
const roadmaps = ref<Roadmap[] | null>(null);
if (props.initial.kind === "dependency_review") {
  roadmapApi.list().then((x) => (roadmaps.value = readList(x)?.filter((r) => !r.archived_at) ?? null), () => {});
}
const known = (id: string) => roadmaps.value?.some((r) => r.id === id) ?? false;

async function save(): Promise<void> {
  issue.value = draftIssue(draft.value, today);
  if (!issue.value) { emit("save", { ...draft.value }); return; }
  await nextTick();
  formEl.value?.querySelector<HTMLElement>(`[name="${issue.value.field}"]`)?.focus();
}
</script>

<template>
  <form ref="formEl" class="jb-form" novalidate @submit.prevent="save" @keydown.esc="emit('cancel')">
    <div v-if="kinds && kinds.length > 1" class="field">
      <span id="jb-kind" class="label">Kind</span>
      <div class="seg" role="group" aria-labelledby="jb-kind">
        <button
          v-for="k in kinds" :key="k" type="button" :aria-pressed="draft.kind === k" @click="pickKind(k)"
        >{{ KIND_LABEL[k] }}</button>
      </div>
      <span class="help">{{ KIND_DETAIL[draft.kind] }}</span>
    </div>

    <template v-if="draft.kind === 'prompt' || draft.kind === 'reminder'">
      <label class="field"><span class="label">Title</span>
        <input
          v-model="draft.title" name="title" class="input" type="text" :maxlength="LIMITS.title" autocomplete="off"
          :aria-invalid="invalid('title')" aria-describedby="jb-title-help"
        />
        <span id="jb-title-help" class="help">
          {{ draft.kind === "prompt" ? "Shown in the push and the job's conversation." : "The push shows the title only." }}
        </span>
      </label>
    </template>
    <label v-if="draft.kind === 'prompt'" class="field"><span class="label">What to research</span>
      <!-- One line: Enter submits instead of breaking the line. -->
      <textarea
        v-model="draft.instruction" name="instruction" class="input" rows="3" :maxlength="LIMITS.instruction"
        :aria-invalid="invalid('instruction')" aria-describedby="jb-instruction-help" @keydown.enter.prevent="save"
      ></textarea>
      <span id="jb-instruction-help" class="help">
        It leaves your Home Node as a web search, so leave out personal or financial details and symbols you hold.
      </span>
    </label>
    <label v-if="draft.kind === 'reminder'" class="field"><span class="label">Text <span class="t-2">(optional)</span></span>
      <textarea
        v-model="draft.text" name="text" class="input" rows="3" :maxlength="LIMITS.text"
        :aria-invalid="invalid('text')" aria-describedby="jb-text-help" @keydown.enter.prevent="save"
      ></textarea>
      <span id="jb-text-help" class="help">Posted to the job's conversation. Without it, the title is posted.</span>
    </label>
    <label v-if="draft.kind === 'disk_check'" class="field jb-narrow"><span class="label">Threshold (%)</span>
      <input
        v-model="draft.threshold" name="threshold" class="input" type="number" inputmode="numeric"
        :min="LIMITS.thresholdMin" :max="LIMITS.thresholdMax" step="1" :aria-invalid="invalid('threshold')" aria-describedby="jb-threshold-help"
      />
      <span id="jb-threshold-help" class="help">You hear about a disk at or above this. {{ LIMITS.thresholdMin }} to {{ LIMITS.thresholdMax }}.</span>
    </label>
    <label v-if="draft.kind === 'dependency_review'" class="field"><span class="label">Propose upgrades to a roadmap <span class="t-2">(optional)</span></span>
      <select v-model="draft.roadmap_id" class="input" :disabled="roadmaps === null && !draft.roadmap_id">
        <option :value="null">No roadmap</option>
        <option v-if="draft.roadmap_id && !known(draft.roadmap_id)" :value="draft.roadmap_id">
          {{ roadmaps === null ? "The roadmap set now" : "A roadmap that is gone or archived" }}
        </option>
        <option v-for="r in roadmaps ?? []" :key="r.id" :value="r.id">{{ r.title }}</option>
      </select>
      <span class="help">Each run then proposes one item per fixable alert. You accept or reject the proposal on the roadmap.</span>
    </label>

    <fieldset class="jb-fieldset">
      <legend class="label">Schedule</legend>
      <div class="jb-row">
        <label class="field"><span class="label">Repeats</span>
          <select v-model="draft.cadence" class="input">
            <option v-for="c in cadences" :key="c" :value="c">{{ CADENCE_LABEL[c] }}</option>
          </select>
        </label>
        <label v-if="!fixed && draft.cadence !== 'once'" class="field"><span class="label">Every</span>
          <span class="jb-unit">
            <input
              v-model="draft.every" name="every" class="input" type="number" inputmode="numeric" min="1"
              :max="LIMITS.every[draft.cadence]" step="1" :aria-invalid="invalid('every')"
            />
            <span class="t-sm t-2">{{ EVERY_UNIT[draft.cadence][Number(draft.every) === 1 ? 0 : 1] }}</span>
          </span>
        </label>
        <label v-if="draft.cadence === 'weekly'" class="field"><span class="label">Day</span>
          <select v-model.number="draft.weekday" class="input">
            <option v-for="(day, i) in WEEKDAYS" :key="day" :value="i + 1">{{ day }}</option>
          </select>
        </label>
        <label v-if="draft.cadence === 'monthly'" class="field"><span class="label">Day of the month</span>
          <select v-model.number="draft.month_day" class="input" aria-describedby="jb-month-help">
            <option v-for="d in 31" :key="d" :value="d">{{ ordinal(d) }}</option>
          </select>
        </label>
        <label class="field"><span class="label">Time</span>
          <input
            v-model="draft.time" name="time" class="input" type="time" step="60" required
            :aria-invalid="invalid('time')" aria-describedby="jb-tz"
          />
        </label>
      </div>
      <div v-if="!fixed" class="jb-row">
        <label class="field">
          <span class="label">{{ draft.cadence === "once" ? "On" : "Starting" }}<span v-if="draft.cadence !== 'once'" class="t-2"> (optional)</span></span>
          <input
            v-model="draft.start_date" name="start_date" class="input" type="date" :min="draft.cadence === 'once' ? today : undefined"
            :max="latestStart(today)" :required="draft.cadence === 'once'" :aria-invalid="invalid('start_date')"
          />
        </label>
        <label v-if="draft.cadence !== 'once'" class="field"><span class="label">Stop after <span class="t-2">(optional)</span></span>
          <span class="jb-unit">
            <input
              v-model="draft.max_runs" name="max_runs" class="input" type="number" inputmode="numeric" min="1"
              :max="LIMITS.maxRuns" step="1" placeholder="No limit" :aria-invalid="invalid('max_runs')"
            />
            <span class="t-sm t-2">runs</span>
          </span>
        </label>
      </div>
      <p v-if="draft.cadence === 'monthly' && draft.month_day > 28" id="jb-month-help" class="help">Shorter months run on their last day.</p>
      <p id="jb-tz" class="help">{{ TIMES_IN_ZONE }}</p>
    </fieldset>

    <label v-if="draft.kind === 'prompt'" class="jb-switch">
      <input v-model="draft.notify" class="switch" type="checkbox" role="switch" />
      <span>Send me a push when a run ends</span>
    </label>
    <label class="jb-switch">
      <input v-model="draft.enabled" class="switch" type="checkbox" role="switch" />
      <span>{{ draft.enabled ? "Enabled: runs on this schedule" : "Paused: runs only when you run it" }}</span>
    </label>
    <p v-if="issue" class="err" role="alert">{{ issue.message }}</p>
    <div class="jb-actions">
      <button v-if="cancel" type="button" class="btn btn-secondary" :disabled="busy" @click="emit('cancel')">Cancel</button>
      <button type="submit" class="btn btn-primary" :disabled="busy" :aria-busy="busy">{{ submit }}</button>
    </div>
  </form>
</template>

<style scoped>
.jb-form { display: flex; flex-direction: column; gap: 14px; max-width: 640px; }
.jb-fieldset { border: 0; margin: 0; padding: 0; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.jb-fieldset > legend { padding: 0; margin-bottom: 12px; font-size: 13px; font-weight: 600; }
.jb-row { display: flex; gap: 12px; flex-wrap: wrap; }
.jb-row .field { flex: 1 1 140px; }
.jb-narrow { max-width: 200px; }
.jb-unit { display: flex; align-items: center; gap: 8px; }
.jb-unit .input { flex: 1 1 auto; }
.jb-switch { display: flex; gap: 10px; align-items: center; font-size: 14px; }
.jb-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
.seg { flex-wrap: wrap; align-self: flex-start; }
p { margin: 0; }
</style>
