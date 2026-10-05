<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { getJsonAuth } from "../api";
import JvUnavailable from "./jv/JvUnavailable.vue";
import {
  MAX_CHAIN, TIERS, canonicalRouting, candidates, isMetered, isSubscription, meteredAfterSubscription, moveEntry,
  routingIssue, routingMode, routingReason, sameRouting, type RouteEntry, type Routing, type RoutingPolicy, type Tier,
} from "../modelRouting";

// Owner routing per tier. The native command re-validates the document,
// requires OS authentication and signs Core's canonical payload; this view
// only edits the typed document.
const TIER_COPY: Record<Tier, [string, string]> = {
  cheap: ["Cheap", "Quick, light requests"],
  default: ["Default", "Most requests"],
  hard: ["Hard", "Complex or deep work"],
};
const policy = ref<RoutingPolicy | null>(null);
const draft = ref<Routing>(canonicalRouting(null));
const loadFailed = ref(false);
const busy = ref(false);
const notice = ref("");
const confirming = ref(false);
const adding = ref<Record<Tier, string>>({ cheap: "", default: "", hard: "" });

const mode = computed(() => (policy.value ? routingMode(policy.value) : "unsupported"));
const dirty = computed(() => !sameRouting(draft.value, canonicalRouting(policy.value?.routing)));
// An unusable routing file can be repaired by re-signing the shown document.
const canApply = computed(() => dirty.value || !!policy.value?.routing_unavailable_reason);
const issue = computed(() => (policy.value ? routingIssue(draft.value, policy.value.models) : null));
const reason = computed(() => routingReason(policy.value?.routing_unavailable_reason));
const editable = computed(() => mode.value === "editable" && !busy.value);
const enabled = computed(() => new Set(policy.value?.models.filter((m) => m.enabled).map((m) => `${m.provider}/${m.model}`)));

function kind(provider: string) {
  return isSubscription(provider) ? "Subscription" : isMetered(provider) ? "Paid API" : "Local";
}
function options(tier: Tier) {
  return candidates(policy.value?.models ?? [], draft.value.tiers[tier]?.chain ?? []);
}

async function refresh() {
  policy.value = await getJsonAuth<RoutingPolicy>("/v1/system/models");
  draft.value = canonicalRouting(policy.value.routing);
}
async function reload() {
  busy.value = true;
  try { await refresh(); loadFailed.value = false; notice.value = ""; }
  catch { loadFailed.value = true; policy.value = null; }
  finally { busy.value = false; }
}

function customize(tier: Tier) { draft.value.tiers[tier] = { chain: [], metered_after_subscription: false }; }
function reset(tier: Tier) { delete draft.value.tiers[tier]; }
function move(tier: Tier, index: number, delta: number) {
  const route = draft.value.tiers[tier];
  if (route) route.chain = moveEntry(route.chain, index, delta);
}
function remove(tier: Tier, index: number) { draft.value.tiers[tier]?.chain.splice(index, 1); }
function add(tier: Tier) {
  const route = draft.value.tiers[tier];
  const pick = options(tier).find((m) => `${m.provider}/${m.model}` === adding.value[tier]);
  if (!route || !pick || route.chain.length >= MAX_CHAIN) return;
  route.chain.push({ provider: pick.provider, model: pick.model });
  adding.value[tier] = "";
}
function discard() { draft.value = canonicalRouting(policy.value?.routing); }

async function confirm() {
  const hash = policy.value?.routing_sha256;
  confirming.value = false;
  if (!hash || busy.value || issue.value) return;
  busy.value = true;
  try {
    await invoke("set_model_routing", { routing: canonicalRouting(draft.value), routingSha256: hash });
    notice.value = "Routing verified and updated.";
  } catch (error) { notice.value = String(error); }
  finally {
    try { await refresh(); } catch { policy.value = null; loadFailed.value = true; }
    busy.value = false;
  }
}
const label = (e: RouteEntry) => `${e.provider}/${e.model}`;
onMounted(reload);
</script>

<template>
  <section class="routing">
    <JvUnavailable v-if="loadFailed" title="Model routing" kind="error" icon="branch"
      detail="Model policy unreachable. Check the connection and session." />
    <p v-else-if="!policy" class="muted" role="status">Loading routing…</p>
    <JvUnavailable v-else-if="mode === 'unsupported'" title="Model routing" kind="core-update" icon="branch"
      detail="This Core cannot change routing from the app. Update Core to choose the model order per tier." />
    <template v-else>
      <p class="muted small">
        Routing only orders models per tier. A model still has to be allowed, within budget and healthy;
        a tier on the built-in order keeps Core's own choice. Changes apply to all devices and need OS authentication.
      </p>
      <p v-if="reason" class="warn" role="alert">{{ reason }}</p>
      <p v-if="mode === 'readonly'" class="warn">
        Routing changes are unavailable on this Core right now (privileged broker unavailable or routing file unreadable).
      </p>

      <label class="switch">
        <input type="checkbox" :checked="draft.paid_api === 'allowed'" :disabled="!editable"
          @change="draft.paid_api = ($event.target as HTMLInputElement).checked ? 'allowed' : 'off'" />
        <span><strong>Paid APIs {{ draft.paid_api === "allowed" ? "allowed" : "off" }}</strong>
          <small>Off: only subscriptions and local models, in every tier and the built-in order.</small></span>
      </label>

      <label class="switch">
        <input type="checkbox" :checked="draft.research_web_search === 'on'" :disabled="!editable"
          @change="draft.research_web_search = ($event.target as HTMLInputElement).checked ? 'on' : 'off'" />
        <span><strong>Research web search {{ draft.research_web_search === "on" ? "on" : "off" }}</strong>
          <small>On: explicit Research requests may use the subscription's provider-hosted web search. Never a paid API.</small></span>
      </label>

      <div class="tiers">
        <section v-for="tier in TIERS" :key="tier" class="tier" :aria-label="`${TIER_COPY[tier][0]} tier`">
          <header>
            <div><h4>{{ TIER_COPY[tier][0] }}</h4><small>{{ TIER_COPY[tier][1] }}</small></div>
            <button v-if="!draft.tiers[tier]" type="button" :disabled="!editable" @click="customize(tier)">Customize</button>
            <button v-else type="button" :disabled="!editable" @click="reset(tier)">Reset to built-in</button>
          </header>
          <p v-if="!draft.tiers[tier]" class="muted small">Built-in order.</p>
          <template v-else>
            <ol>
              <li v-for="(entry, i) in draft.tiers[tier]!.chain" :key="label(entry)">
                <span class="pos">{{ i + 1 }}</span>
                <div class="name"><strong>{{ entry.model }}</strong>
                  <small>{{ entry.provider }} · {{ kind(entry.provider) }}<template v-if="!enabled.has(label(entry))"> · blocked</template></small></div>
                <button type="button" :disabled="!editable || i === 0" :aria-label="`Move ${label(entry)} up`" @click="move(tier, i, -1)">↑</button>
                <button type="button" :disabled="!editable || i === draft.tiers[tier]!.chain.length - 1" :aria-label="`Move ${label(entry)} down`" @click="move(tier, i, 1)">↓</button>
                <button type="button" :disabled="!editable" :aria-label="`Remove ${label(entry)}`" @click="remove(tier, i)">✕</button>
              </li>
            </ol>
            <p v-if="!draft.tiers[tier]!.chain.length" class="muted small">Add at least one model, or reset to the built-in order.</p>
            <div class="add">
              <select v-model="adding[tier]" :aria-label="`Add a model to the ${tier} tier`"
                :disabled="!editable || draft.tiers[tier]!.chain.length >= MAX_CHAIN">
                <option value="">Add a discovered model…</option>
                <option v-for="m in options(tier)" :key="label(m)" :value="label(m)">
                  {{ m.model }} ({{ m.provider }}{{ m.enabled ? "" : ", blocked" }})
                </option>
              </select>
              <button type="button" :disabled="!editable || !adding[tier]" @click="add(tier)">Add</button>
            </div>
            <label v-if="meteredAfterSubscription(draft.tiers[tier]!.chain) || draft.tiers[tier]!.metered_after_subscription" class="check">
              <input v-model="draft.tiers[tier]!.metered_after_subscription" type="checkbox" :disabled="!editable" />
              <span>Allow paid APIs after a subscription</span>
            </label>
            <p v-if="draft.tiers[tier]!.metered_after_subscription" class="warn small">
              When the subscription is unavailable or its plan is used up, this tier may fall back to a paid API and cost money.
            </p>
            <p v-if="draft.paid_api === 'off' && draft.tiers[tier]!.chain.some((e) => isMetered(e.provider))" class="muted small">
              Paid APIs are off: paid entries in this tier are skipped.
            </p>
          </template>
        </section>
      </div>

      <p v-if="canApply && issue" class="err" role="alert">{{ issue }}</p>
      <p role="status">{{ notice }}</p>
      <div class="actions">
        <button type="button" :disabled="busy" @click="reload">Refresh</button>
        <button type="button" :disabled="!editable || !dirty" @click="discard">Discard changes</button>
        <button type="button" :disabled="!editable || !canApply || !!issue" @click="confirming = true">Apply routing</button>
      </div>
    </template>

    <div v-if="confirming" class="model-confirm" role="dialog" aria-modal="true" aria-label="Confirm routing change" @keydown.esc="confirming = false">
      <div>
        <h3>Replace model routing?</h3>
        <p>This replaces the routing for all devices{{ draft.paid_api === "off" ? " and turns paid APIs off" : "" }}{{ draft.research_web_search === "on" ? ". Research may use web search" : "" }}. Running requests are not cancelled.</p>
        <ul class="summary">
          <li v-for="tier in TIERS" :key="tier">
            {{ TIER_COPY[tier][0] }}: {{ draft.tiers[tier] ? draft.tiers[tier]!.chain.map(label).join(" → ") : "built-in order" }}
          </li>
        </ul>
        <button autofocus @click="confirming = false">Cancel</button>
        <button @click="confirm">Confirm change</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.routing { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.routing p { margin: 0; overflow-wrap: anywhere; }
.muted { color: var(--text-4); }
.small, small { font-size: var(--fs-12); line-height: 1.5; }
small { display: block; color: var(--text-5); }
.warn { color: var(--warn); }
.err { color: var(--danger); }
.switch, .check { display: flex; align-items: flex-start; gap: 10px; cursor: pointer; }
.switch input, .check input { margin-top: 3px; accent-color: var(--accent); }
.tiers { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.tier { display: flex; flex-direction: column; gap: 10px; min-width: 0; padding: 14px; border: 1px solid var(--line-a30); border-radius: var(--r-12); background: var(--tile-bg); }
.tier header { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
h4 { margin: 0; font-size: var(--fs-14); font-weight: 600; color: var(--text-0); }
ol { list-style: none; margin: 0; padding: 0; }
li { display: flex; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px solid var(--line-a18); }
.pos { flex: none; width: 22px; color: var(--accent); font-variant-numeric: tabular-nums; }
.name { flex: 1; min-width: 0; overflow-wrap: anywhere; font-size: var(--fs-13); }
li button { flex: none; padding: 4px 8px; }
.add { display: flex; gap: 8px; }
.add select { flex: 1; min-width: 0; padding: 8px; }
.actions { display: flex; gap: 12px; flex-wrap: wrap; }
button { padding: 8px 12px; cursor: pointer; border-radius: var(--r-8); }
button:disabled { opacity: 0.5; cursor: default; }
.model-confirm { position: fixed; inset: 0; z-index: 100; background: #000b; display: grid; place-items: center; padding: 20px; }
.model-confirm > div { max-width: 560px; background: #12201b; border: 1px solid #365447; padding: 24px; border-radius: 12px; }
.model-confirm p, .summary { color: var(--text-4); overflow-wrap: anywhere; }
.summary { padding-left: 18px; font-size: var(--fs-12); }
.summary li { display: list-item; border: none; padding: 2px 0; }
@media (max-width: 1099px) { .tiers { grid-template-columns: minmax(0, 1fr); } }
</style>
