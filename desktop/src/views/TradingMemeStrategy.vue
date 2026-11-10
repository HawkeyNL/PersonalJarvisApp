<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import DevDataToggle from "../components/trading/DevDataToggle.vue";
import "../components/trading/trading.css";
import { asOfText, formatDecimal, signTone } from "../trading";
import { failureText, progressPct, usd } from "../memecoin";
import {
  ENABLE_ELSEWHERE, FAMILY_LABEL, NO_EDGE_TEXT, bpsPct, enableReview, isStrategyId, readStrategy, sendDisable, strategyState,
  type DisableOutcome, type EnableReview,
} from "../memeStrategies";
import { memeApi, onMemeHint, useMemeRead } from "../memecoinApi";
import { initPlatformCapabilities, platformCapabilities } from "../platform";
import { MEME_DATA_SETS, devDataSet } from "../tradingApi";

// One paper strategy: results, gate, version timeline with Core's diff,
// Claude's rationale (AI-written, plain text) and the backtest. Disable is
// immediate; enable is signed natively behind Touch ID, on a Mac only.
const route = useRoute();
const id = computed(() => (isStrategyId(route.params.id) ? route.params.id : null));
const { state, reload } = useMemeRead({
  live: () => (id.value ? memeApi.strategy(id.value) : Promise.reject({ name: "ApiError", status: 404 })),
  fixture: (f, set) => f.strategyFixture(set, id.value ?? ""),
  read: (x) => (id.value ? readStrategy(x, id.value) : null),
});
// Another strategy: never show the previous one's state or buttons under it.
watch(id, () => {
  state.value = { kind: "loading" };
  void reload();
});
onMemeHint((hint) => {
  if (hint.kind === "reconcile" || (hint.kind === "strategy" && hint.id === id.value)) void reload();
});

void initPlatformCapabilities();
const signer = computed(() => platformCapabilities.value.supportsBiometricSigning);

const now = ref(Date.now());
const timer = window.setInterval(() => (now.value = Date.now()), 30_000);
onUnmounted(() => window.clearInterval(timer));

const s = computed(() => (state.value.kind === "ok" ? state.value.data : null));
const st = computed(() => (s.value ? strategyState(s.value) : null));

// ---- disable: confirm, send, then reload ------------------------------------
const disableStep = ref<"idle" | "confirm" | "sending">("idle");
const disableNote = ref<DisableOutcome | null>(null);
const keepBtn = ref<HTMLButtonElement | null>(null);
const noteEl = ref<HTMLElement | null>(null);

async function askDisable(): Promise<void> {
  disableNote.value = null;
  enableStep.value = "idle";
  disableStep.value = "confirm";
  await nextTick();
  keepBtn.value?.focus();
}

async function confirmDisable(): Promise<void> {
  if (disableStep.value !== "confirm" || !id.value) return;
  const target = id.value;
  disableStep.value = "sending";
  disableNote.value = await sendDisable(target, () => memeApi.disable(target));
  disableStep.value = "idle";
  await nextTick();
  noteEl.value?.focus();
  await reload();
}

// ---- enable: native challenge, Touch ID, signature --------------------------
const enableStep = ref<"idle" | "confirm" | "signing">("idle");
/** Fixed when the confirm card opens: what the card shows is what is signed. */
const review = ref<EnableReview | null>(null);
const enableNote = ref<{ ok: boolean; text: string } | null>(null);
const keepDisabledBtn = ref<HTMLButtonElement | null>(null);
const enableNoteEl = ref<HTMLElement | null>(null);

async function askEnable(): Promise<void> {
  if (!s.value) return;
  review.value = enableReview(s.value);
  enableNote.value = null;
  disableStep.value = "idle";
  enableStep.value = "confirm";
  await nextTick();
  keepDisabledBtn.value?.focus();
}

async function confirmEnable(): Promise<void> {
  if (enableStep.value !== "confirm" || !review.value) return;
  const { id: target, version } = review.value;
  enableStep.value = "signing";
  try {
    await memeApi.enable(review.value);
    enableNote.value = { ok: true, text: `Enabled ${target} at version ${version}.` };
  } catch (error) {
    // Native errors are fixed strings without secrets.
    const text = typeof error === "string" ? error.replace(/[^.]$/, "$&.") : "The enable was not confirmed.";
    enableNote.value = { ok: false, text: `${text} Reload to see the current state.` };
  }
  enableStep.value = "idle";
  await nextTick();
  enableNoteEl.value?.focus();
  await reload();
}

// Enabled elsewhere, or a new version or parameter set, while the card is
// open: close it, so the owner reviews again.
watch(s, (current) => {
  const r = review.value;
  if (enableStep.value !== "confirm" || !r) return;
  const next = current && !current.enabled ? enableReview(current) : null;
  if (next?.version === r.version && next.config_sha256 === r.config_sha256) return;
  enableStep.value = "idle";
  enableNote.value = current
    ? { ok: false, text: "The strategy changed while you were reviewing it. Nothing was signed; review it, then enable again." }
    : { ok: false, text: "Could not recheck the strategy, so nothing was signed. Reload, then enable again." };
});

watch([devDataSet, id], () => {
  disableStep.value = "idle";
  enableStep.value = "idle";
  disableNote.value = null;
  enableNote.value = null;
});

const author = (a: "seed" | "claude") => (a === "seed" ? "Seed" : "Claude");
</script>

<template>
  <PageShell class="td-page" :title="id ?? 'Strategy'" :subtitle="s?.family ? `Family ${FAMILY_LABEL[s.family]} · paper simulation` : 'Paper simulation'">
    <template #crumb><p class="td-crumb"><RouterLink to="/trading/memecoin">Memecoin</RouterLink> / <RouterLink to="/trading/memecoin/strategies">Strategies</RouterLink></p></template>
    <template #badges>
      <span v-if="st" class="badge" :class="st.tone"><span class="dot" aria-hidden="true"></span>{{ st.label }}</span>
      <span class="badge td-outline">Paper</span>
    </template>
    <template #actions>
      <button type="button" class="btn btn-secondary" @click="reload">Refresh</button>
      <button
        v-if="s?.enabled" type="button" class="btn btn-secondary"
        :aria-expanded="disableStep !== 'idle'" aria-controls="sd-disable" :disabled="disableStep === 'sending'" @click="askDisable"
      >Disable</button>
      <button
        v-else-if="s && signer" type="button" class="btn btn-secondary"
        :aria-expanded="enableStep !== 'idle'" aria-controls="sd-enable" :disabled="enableStep === 'signing'" @click="askEnable"
      >Enable…</button>
      <span v-else-if="s" class="t-sm t-2">{{ ENABLE_ELSEWHERE }}</span>
    </template>

    <p class="td-banner t-sm" role="note">{{ NO_EDGE_TEXT }}</p>

    <section v-if="disableStep !== 'idle'" id="sd-disable" class="card td-col sd-confirm" role="group" aria-labelledby="sd-disable-title" @keydown.esc="disableStep === 'confirm' && (disableStep = 'idle')">
      <h2 id="sd-disable-title">Disable {{ id }}?</h2>
      <ul class="sd-list t-sm t-2">
        <li>It stops opening new paper positions at once.</li>
        <li>Its open paper positions keep exiting by their own rules.</li>
        <li>It stays disabled until you enable it again with a signature from your Mac or phone. Claude cannot enable it.</li>
      </ul>
      <div class="sd-actions">
        <button ref="keepBtn" type="button" class="btn btn-secondary" :disabled="disableStep === 'sending'" @click="disableStep = 'idle'">Keep enabled</button>
        <button type="button" class="btn btn-primary" :aria-busy="disableStep === 'sending'" @click="confirmDisable">
          <span v-if="disableStep === 'sending'" class="spinner" aria-hidden="true"></span>
          {{ disableStep === "sending" ? "Disabling…" : "Disable now" }}
        </button>
      </div>
    </section>
    <p v-if="disableNote" ref="noteEl" tabindex="-1" class="td-banner t-sm" :class="{ warn: !disableNote.ok }" :role="disableNote.ok ? 'status' : 'alert'">
      <template v-if="disableNote.ok"><span><strong>Disabled at {{ disableNote.at }}.</strong> No new paper entries; open positions keep exiting.</span></template>
      <template v-else><span>{{ disableNote.message }}</span></template>
    </p>

    <section v-if="enableStep !== 'idle' && s" id="sd-enable" class="card td-col sd-confirm" role="group" aria-labelledby="sd-enable-title" @keydown.esc="enableStep === 'confirm' && (enableStep = 'idle')">
      <h2 id="sd-enable-title">Enable {{ s.id }} at version {{ review?.version ?? s.active_version }}?</h2>
      <div v-if="review" class="t-sm">
        <span class="t-2">Parameters of version {{ review.version }} (SHA-256)</span>
        <code class="mono sd-hash">{{ review.config_sha256 }}</code>
        <span class="t-xs t-2">You sign this hash. The Touch ID prompt shows its first 8 characters: {{ review.config_sha256.slice(0, 8) }}.</span>
      </div>
      <p v-else class="t-sm" role="alert">Core sent no parameter hash for this version, so nothing can be signed. Reload, then try again.</p>
      <ul class="sd-list t-sm t-2">
        <li>It may open paper positions again, within the profile's limits.</li>
        <li>Your signature enables the strategy, not only this version: while it stays enabled, later versions Claude writes become active without another signature, each checked by Core against the profile's rules. Disable it to stop that.</li>
        <li>Touch ID signs the request on this Mac. The prompt names the strategy and version; if Claude changed the version meanwhile, nothing is signed.</li>
      </ul>
      <div class="sd-actions">
        <button ref="keepDisabledBtn" type="button" class="btn btn-secondary" :disabled="enableStep === 'signing'" @click="enableStep = 'idle'">Keep disabled</button>
        <button type="button" class="btn btn-primary" :aria-busy="enableStep === 'signing'" :disabled="!review" @click="confirmEnable">
          <span v-if="enableStep === 'signing'" class="spinner" aria-hidden="true"></span>
          {{ enableStep === "signing" ? "Waiting for Touch ID…" : "Enable with Touch ID" }}
        </button>
      </div>
    </section>
    <p v-if="enableNote" ref="enableNoteEl" tabindex="-1" class="td-banner t-sm" :class="{ warn: !enableNote.ok }" :role="enableNote.ok ? 'status' : 'alert'">{{ enableNote.text }}</p>

    <div v-if="state.kind === 'loading'" class="card td-col" role="status">
      <span class="sr-only">Loading the strategy…</span>
      <span class="skel" style="width: 40%; height: 14px" aria-hidden="true"></span>
      <span class="skel" style="width: 80%; height: 14px" aria-hidden="true"></span>
    </div>
    <div v-else-if="state.kind === 'failed'" class="card td-col" role="alert">
      <div class="td-title">
        <span class="tile neg" aria-hidden="true"><NavIcon name="alert" /></span>
        <h3>Could not load this strategy</h3>
      </div>
      <p class="t-sm t-2">{{ state.failure === "no-api" ? "Core has no strategy with this id, or this Core has no strategies yet. Update Core on your Home Node." : failureText(state.failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="reload">Try again</button></div>
    </div>
    <template v-else-if="s">
      <div class="td-kpis">
        <div class="card td-kpi">
          <span class="t-sm t-2">Net result</span>
          <span class="big" :class="`td-${signTone(s.performance.net_usd)}`">{{ usd(s.performance.net_usd, { sign: true }) }}</span>
          <span class="t-xs t-2">{{ formatDecimal(String(s.performance.trades)) }} closed trades · costs {{ usd(s.performance.costs_usd) }}</span>
        </div>
        <div class="card td-kpi">
          <span class="t-sm t-2">Per trade</span>
          <span class="big" :class="`td-${signTone(s.performance.expectancy_usd)}`">{{ s.performance.trades ? usd(s.performance.expectancy_usd, { sign: true }) : "—" }}</span>
          <span class="t-xs t-2">win rate {{ s.performance.trades ? bpsPct(s.performance.win_rate_bps) : "—" }} · profit factor {{ s.performance.profit_factor === null ? "—" : formatDecimal(s.performance.profit_factor, { places: 2 }) }}</span>
        </div>
        <div class="card td-kpi">
          <span class="t-sm t-2">Max drawdown</span>
          <span class="big">{{ usd(s.performance.max_drawdown_usd) }}</span>
          <span class="t-xs t-2">of the cumulative net result</span>
        </div>
      </div>

      <section class="card td-col" aria-labelledby="sd-gate">
        <div class="td-card-head">
          <h2 id="sd-gate">Paper gate, version {{ s.active_version }}</h2>
          <span v-if="s.gate" class="t-xs t-2">snapshot <time :datetime="s.gate.at">{{ asOfText(s.gate.at, now) }}</time></span>
        </div>
        <template v-if="s.gate">
          <div class="sd-meters">
            <div v-for="row in [
              { label: 'Closed trades', have: s.gate.closed_trades, need: s.gate.min_closed_trades },
              { label: 'Days', have: s.gate.duration_days, need: s.gate.min_duration_days },
              { label: 'Trading days', have: s.gate.trading_days, need: s.gate.min_trading_days },
            ]" :key="row.label" class="sd-meter">
              <div class="td-card-head t-sm">
                <span>{{ row.label }}</span>
                <span class="mono">{{ formatDecimal(String(row.have)) }} of {{ formatDecimal(String(row.need)) }}</span>
              </div>
              <div class="td-meter" aria-hidden="true"><i :style="{ width: `${progressPct(row.have, row.need)}%` }"></i></div>
            </div>
            <div class="sd-meter">
              <div class="td-card-head t-sm">
                <span>Net per closed trade</span>
                <span class="mono" :class="`td-${signTone(s.gate.net_expectancy_usd)}`">{{ usd(s.gate.net_expectancy_usd, { sign: true }) }}</span>
              </div>
            </div>
          </div>
          <p class="td-note">A report only: meeting it changes nothing, and this app cannot trade.</p>
        </template>
        <p v-else class="t-sm t-2">No gate snapshot for this version yet. Core writes one every hour.</p>
      </section>

      <section class="card td-col" aria-labelledby="sd-versions">
        <div class="td-card-head">
          <h2 id="sd-versions">Versions</h2>
          <span class="t-sm t-2">newest first, up to 20</span>
        </div>
        <p v-if="!s.versions.length" class="empty t-sm">No versions listed.</p>
        <ol v-else class="sd-timeline">
          <li v-for="v in s.versions" :key="v.version" class="sd-version">
            <div class="td-card-head">
              <h3 class="t-sm sd-vtitle">Version {{ v.version }}</h3>
              <span class="sd-badges">
                <span class="badge" :class="{ acc: v.author === 'claude' }">{{ author(v.author) }}</span>
                <span v-if="v.version === s.active_version" class="badge pos"><span class="dot" aria-hidden="true"></span>Active</span>
              </span>
            </div>
            <p class="t-xs t-2"><time :datetime="v.created_at">{{ asOfText(v.created_at, now) }}</time><template v-if="v.parent_version !== null"> · from version {{ v.parent_version }}</template></p>

            <div v-if="v.diff" class="td-scroll" tabindex="0" role="region" :aria-label="`Changes in version ${v.version}`">
              <table class="tbl sd-diff">
                <thead><tr><th scope="col">Changed field</th><th scope="col">Was</th><th scope="col">Now</th></tr></thead>
                <tbody>
                  <tr v-if="!v.diff.length"><td colspan="3" class="t-sm t-2">No parameter changed.</td></tr>
                  <tr v-for="d in v.diff" :key="d.field">
                    <td class="mono t-sm">{{ d.field }}</td><td class="mono t-sm sd-val">{{ d.from }}</td><td class="mono t-sm sd-val">{{ d.to }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-else class="t-xs t-2">{{ v.parent_version === null ? "First version: nothing to compare." : "Core could not compare this version with its parent." }}</p>

            <figure v-if="v.rationale" class="sd-rationale">
              <figcaption class="t-xs">Claude's reasoning · AI-written text, shown as written; display only</figcaption>
              <blockquote class="t-sm">{{ v.rationale }}</blockquote>
            </figure>

            <p class="t-sm">
              <strong>Backtest:</strong>
              <template v-if="!v.backtest"> none recorded.</template>
              <template v-else-if="v.backtest.status === 'ok'">
                {{ formatDecimal(String(v.backtest.trades)) }} trades on {{ v.backtest.mints }} tokens ·
                net <span :class="`td-${signTone(v.backtest.net_usd)}`">{{ usd(v.backtest.net_usd, { sign: true }) }}</span> ·
                per trade {{ usd(v.backtest.expectancy_usd, { sign: true }) }} · win rate {{ bpsPct(v.backtest.win_rate_bps) }} ·
                max drawdown {{ usd(v.backtest.max_drawdown_usd) }}
              </template>
              <template v-else-if="v.backtest.status === 'unbacktested'"> unbacktested: too little stored data ({{ v.backtest.mints }} tokens, {{ formatDecimal(String(v.backtest.points)) }} points).</template>
              <template v-else> failed to run (the Core log says why).</template>
            </p>

            <details v-if="v.config" class="sd-config">
              <summary class="t-sm">Parameters</summary>
              <dl class="t-sm">
                <div v-for="[field, value] in v.config" :key="field"><dt class="mono">{{ field }}</dt><dd class="mono sd-val">{{ value }}</dd></div>
              </dl>
            </details>
          </li>
        </ol>
        <p class="td-note">Changes are computed by Core. A backtest replays stored observations of recently migrated tokens; it is a sanity check, not a forecast.</p>
      </section>
    </template>
    <DevDataToggle :sets="MEME_DATA_SETS" />
  </PageShell>
</template>

<style scoped>
.sd-confirm { box-shadow: var(--shadow-border), 0 0 0 2px var(--focus-ring); }
.sd-list { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 4px; line-height: 1.55; }
.sd-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.sd-meters { display: flex; flex-wrap: wrap; gap: 12px 20px; }
.sd-meter { flex: 1 1 200px; display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.sd-timeline { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 12px; }
.sd-version { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; border-radius: var(--r-md); background: var(--bg-sunken); min-width: 0; }
.sd-vtitle { font-weight: 600; }
.sd-badges { display: flex; gap: 6px; }
.sd-diff { min-width: 480px; }
.sd-val { max-width: 360px; overflow-wrap: anywhere; }
.sd-hash { display: block; margin: 2px 0; overflow-wrap: anywhere; }
.sd-rationale { margin: 0; display: flex; flex-direction: column; gap: 4px; }
.sd-rationale figcaption { color: var(--text-secondary); }
.sd-rationale blockquote { margin: 0; padding: 8px 12px; border-left: 2px solid var(--border-strong); overflow-wrap: anywhere; }
.sd-config summary { cursor: pointer; color: var(--accent-text); }
.sd-config dl { margin: 8px 0 0; display: flex; flex-direction: column; gap: 4px; }
.sd-config dl > div { display: flex; gap: 12px; justify-content: space-between; }
.sd-config dd { margin: 0; text-align: right; }
</style>
