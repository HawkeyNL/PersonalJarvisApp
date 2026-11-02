<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import NavIcon from "../NavIcon.vue";
import ActivityState from "./ActivityState.vue";
import { approveAction, denyAction, type PendingAction } from "../../agentApprovals";
import { relativeTime, type Availability } from "../../hubModel";
import { canSignApproval } from "../../nodeModels";
import { platformCapabilities } from "../../platform";
import { CLOSED_GATE, observeReview, outcomeBadge, reviewDone, reviewKey, type AuditLog } from "../../activity";

// D-Approvals: agent actions waiting for the owner, with the full action in the
// aside, and the agent audit log as history. Only a Mac approves (Touch ID,
// then the native command re-reads and signs the action); every desktop can
// reject. Approve also waits for the review gate: the full action scrolled to
// its end. Core has no undo for an approval, so none is offered.
const props = defineProps<{
  pending: Availability<{ pending: PendingAction[] }>;
  audit: Availability<AuditLog>;
  view: "pending" | "history";
  now: number;
  reload: () => Promise<void>;
}>();

const signer = computed(() => platformCapabilities.value.supportsBiometricSigning);
const waiting = computed(() => (props.pending.state === "ok" ? props.pending.value.pending : []));
const selectedId = ref<string | null>(null);
const selected = computed(() => waiting.value.find((p) => p.pending_id === selectedId.value) ?? waiting.value[0]);
const selectedKey = computed(() => (selected.value ? reviewKey(selected.value) : null));

// --- Review gate ------------------------------------------------------------------
const gate = ref(CLOSED_GATE);
const body = ref<HTMLElement | null>(null);
function measure() {
  if (body.value && selected.value) gate.value = observeReview(gate.value, reviewKey(selected.value), body.value);
}
// The details box is keyed on the review, so new content never inherits an old
// scroll position; its size is watched so content that fits opens the gate.
let resize: ResizeObserver | undefined;
watch(body, (el) => {
  resize?.disconnect();
  if (el) (resize ??= new ResizeObserver(measure)).observe(el);
});
watch(selectedKey, () => void nextTick(measure), { flush: "post" });
onBeforeUnmount(() => resize?.disconnect());

const approvable = (p: PendingAction) => signer.value && canSignApproval(p);
const reviewed = (p: PendingAction) => reviewDone(gate.value, reviewKey(p));
const hint = (p: PendingAction) => (!approvable(p) || reviewed(p) ? ""
  : p.pending_id === selected.value?.pending_id ? "Scroll the details to the end to approve." : "Open the details to approve.");

// --- Approve / reject (unchanged flow: same calls, same native signing) ----------
const deciding = ref<string | null>(null);
const approving = ref(false);
const decisionError = ref<string | null>(null);
async function decide(id: string, approve: boolean) {
  const item = waiting.value.find((p) => p.pending_id === id);
  if (!item || deciding.value) return;
  // Checked here too, not only on the button: this Mac, a v1 action and a finished review.
  if (approve && !(approvable(item) && reviewed(item))) return;
  deciding.value = id;
  approving.value = approve;
  decisionError.value = null;
  try {
    await (approve ? approveAction(item) : denyAction(item));
  } catch (error) {
    decisionError.value = `${approve ? "Approval" : "Rejection"} failed: ${error instanceof Error ? error.message : String(error)}`;
  } finally {
    deciding.value = null;
    await props.reload();
  }
}

const when = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "Unknown" : d.toLocaleString("en", { dateStyle: "medium", timeStyle: "short" });
};
const OFF = "Agent is turned off in Core.";
</script>

<template>
  <ActivityState v-if="view === 'pending'" :state="pending" what="approvals" :forbidden="OFF" @retry="reload">
    <p v-if="decisionError" class="err" role="alert">{{ decisionError }}</p>
    <p v-if="!signer" class="ap-banner t-sm" role="note">
      <strong>View only on this computer.</strong> Approve on your iPhone or Android phone, or on a Mac with Touch ID. You can reject here.
    </p>

    <div v-if="selected" class="ap-layout">
      <section class="ap-list" aria-label="Waiting for you">
        <article v-for="p in waiting" :key="p.pending_id" class="card ap-card" :class="{ on: p.pending_id === selected.pending_id }">
          <div class="ap-row">
            <span class="tile acc" aria-hidden="true"><NavIcon name="shield" /></span>
            <div class="ap-text">
              <button type="button" class="ap-title" :aria-pressed="p.pending_id === selected.pending_id" aria-controls="ap-detail"
                @click="selectedId = p.pending_id">{{ p.action }}</button>
              <span class="t-sm t-2">{{ relativeTime(p.created_at, now) || "Time unknown" }}</span>
            </div>
            <span class="badge acc"><span class="dot"></span>Waiting for you</span>
          </div>
          <p class="ap-summary t-sm">{{ p.preview }}</p>
          <div class="ap-actions">
            <span v-if="hint(p)" :id="`ap-hint-${p.pending_id}`" class="t-xs t-2 ap-hint">{{ hint(p) }}</span>
            <button type="button" class="btn btn-secondary" :disabled="!!deciding" :aria-busy="deciding === p.pending_id && !approving"
              :aria-label="`Reject ${p.action}`" @click="decide(p.pending_id, false)">
              <span v-if="deciding === p.pending_id && !approving" class="spinner" aria-hidden="true"></span>
              {{ deciding === p.pending_id && !approving ? "Rejecting…" : "Reject" }}
            </button>
            <template v-if="signer">
              <button v-if="approvable(p)" type="button" class="btn btn-primary" :disabled="!!deciding || !reviewed(p)"
                :aria-busy="deciding === p.pending_id && approving" :aria-describedby="hint(p) ? `ap-hint-${p.pending_id}` : undefined"
                :aria-label="`Approve ${p.action}`" @click="decide(p.pending_id, true)">
                <span v-if="deciding === p.pending_id && approving" class="spinner" aria-hidden="true"></span>
                {{ deciding === p.pending_id && approving ? "Confirming…" : "Approve" }}
              </button>
              <span v-else class="t-sm t-2">Approve requires newer Core</span>
            </template>
          </div>
        </article>
      </section>

      <aside id="ap-detail" class="card ap-aside" aria-labelledby="ap-detail-title">
        <span class="eyebrow">Details</span>
        <h2 id="ap-detail-title" class="ap-wrap">{{ selected.action }}</h2>
        <dl class="ap-facts t-sm">
          <div><dt class="t-2">Requested</dt><dd>{{ when(selected.created_at) }}</dd></div>
          <div><dt class="t-2">Action hash</dt><dd class="mono">{{ selected.action_sha256 ?? "Not sent by this Core" }}</dd></div>
          <div><dt class="t-2">Request</dt><dd class="mono">{{ selected.pending_id }}</dd></div>
        </dl>
        <div class="ap-full">
          <div class="ap-full-head">
            <span class="t-sm ap-label">Full action</span>
            <span v-if="approvable(selected)" class="t-xs t-2">{{ reviewed(selected) ? "Read to the end" : "Scroll to the end" }}</span>
          </div>
          <pre :key="selectedKey!" ref="body" class="ap-body mono" tabindex="0" role="region" aria-label="Full action"
            @scroll="measure">{{ selected.preview }}</pre>
        </div>
        <p class="ap-banner t-sm">
          <template v-if="signer">Nothing runs until you approve. Approve asks for Touch ID, then signs this exact action with this Mac's device key. An approval cannot be undone.</template>
          <template v-else>This computer cannot approve. Use your phone or a Mac with Touch ID.</template>
        </p>
        <p class="sr-only" aria-live="polite">{{ approvable(selected) && reviewed(selected) ? "You have read everything. Approve is available." : "" }}</p>
      </aside>
    </div>

    <div v-else class="card empty">
      <span class="tile pos" aria-hidden="true"><NavIcon name="approve" /></span>
      <h3>Nothing is waiting for you</h3>
      <p class="t-sm">New requests from agents show up here and on your phone.</p>
    </div>
  </ActivityState>

  <ActivityState v-else :state="audit" what="agent history" :forbidden="OFF" @retry="reload">
    <template #default="{ data }">
      <p v-if="!data.enabled" class="ap-banner t-sm" role="note">Agent actions are turned off in Core. Earlier entries stay listed.</p>
      <ul v-if="data.entries.length" class="ap-history" aria-label="Agent history">
        <li v-for="(e, i) in data.entries" :key="`${e.ts}-${i}`" class="card ap-row">
          <span class="tile" :class="outcomeBadge(e.outcome)[0]" aria-hidden="true"><NavIcon name="shield" /></span>
          <div class="ap-text">
            <span class="ap-name">{{ e.action }}</span>
            <span class="t-sm t-2 ap-wrap">{{ [e.risk, e.note, relativeTime(e.ts, now)].filter(Boolean).join(" · ") }}</span>
          </div>
          <span class="badge" :class="outcomeBadge(e.outcome)[0]"><span class="dot"></span>{{ outcomeBadge(e.outcome)[1] }}</span>
        </li>
      </ul>
      <div v-else class="card empty">
        <span class="tile" aria-hidden="true"><NavIcon name="shield" /></span>
        <h3>No agent actions yet</h3>
        <p class="t-sm">Approved, rejected and automatic agent actions show up here.</p>
      </div>
    </template>
  </ActivityState>
</template>

<style scoped>
p, h2, h3, dl, dd { margin: 0; }
.ap-layout { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }
.ap-list { flex: 1 1 420px; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.ap-card { display: flex; flex-direction: column; gap: 12px; }
.ap-card.on { box-shadow: 0 0 0 2px var(--focus-ring); }
.ap-row { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.ap-text { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ap-title {
  all: unset; cursor: pointer; font-weight: 600; color: var(--text-primary); overflow-wrap: anywhere; border-radius: 4px;
}
.ap-title:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.ap-name { font-weight: 600; overflow-wrap: anywhere; }
.ap-wrap { overflow-wrap: anywhere; }
.ap-summary {
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  color: var(--text-secondary); overflow-wrap: anywhere; white-space: pre-line;
}
.ap-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.ap-hint { margin-right: auto; }
.tile :deep(svg) { width: 20px; height: 20px; }

.ap-aside { flex: 0 0 320px; min-width: 0; display: flex; flex-direction: column; gap: 14px; position: sticky; top: 20px; }
@media (max-width: 960px) { .ap-aside { flex: 1 1 100%; position: static; } }
.ap-facts { display: flex; flex-direction: column; gap: 8px; }
.ap-facts > div { display: flex; justify-content: space-between; gap: 16px; }
.ap-facts dt { flex: none; }
.ap-facts dd { min-width: 0; text-align: right; overflow-wrap: anywhere; }
.ap-full { display: flex; flex-direction: column; gap: 6px; }
.ap-full-head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
.ap-label { font-weight: 500; }
.ap-body {
  margin: 0; padding: 12px 14px; border-radius: 10px; background: var(--bg-sunken); color: var(--text-primary);
  font-size: 12.5px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere;
  max-height: 300px; overflow-y: auto; overscroll-behavior: contain;
}
.ap-body:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.ap-banner { padding: 12px 14px; border-radius: var(--r-md); background: var(--accent-subtle); color: var(--text-primary); overflow-wrap: anywhere; }
.ap-history { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 12px; }
.err { overflow-wrap: anywhere; }
.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
</style>
