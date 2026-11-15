<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";
import "../components/trading/trading.css";
import {
  ADDED, ATTRIBUTION, CHAIN_LABEL, DELETE_CONFIRM, EVM_CHAINS, HINT, KIND_LABEL, LIMITS, NO_PRICE, OFF_DETAIL, OFF_TITLE, REFRESH_ASKED,
  REMOVED, STATUS_LABEL, STATUS_TONE, SUBTITLE, TITLE, addFailureText, cooldownText, draftIssue, failureOf, failureText, hiddenText,
  isKeyMaterialRefusal, kindsAvailable, newWallet, otherTokensText, readOverview, removeFailureText, retryAfterSecs, shortAddress, walletErrorText, walletName,
  type Chain, type DraftIssue, type Failure, type Kind, type Overview, type Wallet, type WalletDraft,
} from "../crypto";
import { cryptoApi, useCryptoRefetch } from "../cryptoApi";
import { TONE_BADGE, relativeTime } from "../hubModel";
import { locked } from "../lock";
import { asOfText, formatDecimal, formatMoney, formatPercent, formatPrice } from "../trading";

// Read-only crypto wallets: public addresses the owner adds, their balances
// and EUR value from Core. Nothing here signs or sends; key material is
// refused before any request (crypto.ts draftIssue) and never kept.
const state = ref<{ kind: "loading" } | { kind: "failed"; failure: Failure } | { kind: "ok"; data: Overview }>({ kind: "loading" });
const now = ref(Date.now());
let seq = 0;

async function load(): Promise<void> {
  const id = ++seq;
  let next: typeof state.value;
  try {
    const data = readOverview(await cryptoApi.overview());
    next = data ? { kind: "ok", data } : { kind: "failed", failure: "failed" };
  } catch (error) {
    next = { kind: "failed", failure: failureOf(error) };
  }
  // Only the newest read lands, and none behind the app lock.
  if (id !== seq || locked.value) return;
  state.value = next;
  now.value = Date.now();
}
void load();
useCryptoRefetch(() => void load());

const data = computed(() => (state.value.kind === "ok" ? state.value.data : null));
const kinds = computed(() => kindsAvailable(data.value?.chains_available ?? []));
const evmChains = computed(() => EVM_CHAINS.filter((c) => data.value?.chains_available.includes(c)));

// ---- add ----------------------------------------------------------------------
const draft = ref<WalletDraft>({ kind: "evm", address: "", label: "", chains: [] });
const issue = ref<DraftIssue | null>(null);
const formError = ref("");
const notice = ref("");
const busy = ref(false);
const formEl = ref<HTMLFormElement | null>(null);

/** A kind Core has a chain for, with every available EVM chain ticked. */
function resetKind(kind?: Kind): void {
  const pick = kind && kinds.value.includes(kind) ? kind : kinds.value[0];
  if (pick) draft.value.kind = pick;
  draft.value.chains = [...evmChains.value];
  issue.value = null;
}
watch(() => kinds.value.join(), () => resetKind(draft.value.kind), { immediate: true });

/** Forget what was typed: after a refusal of key material, and on lock. */
function clearInput(): void {
  draft.value.address = "";
  draft.value.label = "";
}

async function add(): Promise<void> {
  if (busy.value) return;
  formError.value = "";
  notice.value = "";
  issue.value = draftIssue(draft.value);
  const body = issue.value ? null : newWallet(draft.value);
  if (!body) {
    // Key material is never kept: the field is emptied, the message is fixed.
    if (issue.value?.key) draft.value[issue.value.field === "label" ? "label" : "address"] = "";
    await nextTick();
    formEl.value?.querySelector<HTMLElement>(`[name="${issue.value?.field}"]`)?.focus();
    return;
  }
  busy.value = true;
  try {
    await cryptoApi.add(body);
    clearInput();
    notice.value = ADDED;
  } catch (error) {
    formError.value = addFailureText(error);
    if (isKeyMaterialRefusal(error)) clearInput();
  } finally {
    busy.value = false;
    await load();
  }
}

// ---- refresh, with Core's one-minute cooldown --------------------------------
const cooldownUntil = ref(0);
const tick = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;
const cooldown = computed(() => Math.max(0, Math.ceil((cooldownUntil.value - tick.value) / 1000)));

async function refresh(): Promise<void> {
  if (cooldown.value || busy.value) return;
  notice.value = "";
  try {
    await cryptoApi.refresh();
    notice.value = REFRESH_ASKED;
  } catch (error) {
    const secs = retryAfterSecs(error);
    if (secs === null) {
      notice.value = `Not refreshed. ${failureText(failureOf(error))}`;
      return;
    }
    cooldownUntil.value = Date.now() + secs * 1000;
    tick.value = Date.now();
    clearInterval(timer);
    timer = setInterval(() => {
      tick.value = Date.now();
      if (!cooldown.value) clearInterval(timer);
    }, 1000);
  }
}

// ---- remove, after a confirm step ------------------------------------------
const confirming = ref<string | null>(null);

async function remove(wallet: Wallet): Promise<void> {
  if (busy.value) return;
  busy.value = true;
  notice.value = "";
  try {
    await cryptoApi.remove(wallet.id);
    notice.value = REMOVED;
  } catch (error) {
    notice.value = removeFailureText(error) ?? REMOVED;
  } finally {
    busy.value = false;
    confirming.value = null;
    await load();
  }
}

// ---- copy -----------------------------------------------------------------
const copied = ref<string | null>(null);
let copyTimer: ReturnType<typeof setTimeout> | undefined;
async function copy(wallet: Wallet): Promise<void> {
  clearTimeout(copyTimer);
  try {
    await navigator.clipboard.writeText(wallet.address);
    copied.value = wallet.id;
    copyTimer = setTimeout(() => (copied.value = null), 2000);
  } catch {
    copied.value = null; // the address stays readable in its tooltip
  }
}

// Locking drops the wallets and what was typed (fail closed); unlocking reads again.
watch(locked, (isLocked) => {
  seq++;
  if (isLocked) {
    state.value = { kind: "loading" };
    clearInput();
    confirming.value = null;
    notice.value = "";
    formError.value = "";
  } else void load();
});
onBeforeUnmount(() => {
  clearInterval(timer);
  clearTimeout(copyTimer);
});

const chainNames = (chains: Chain[]) => chains.map((c) => CHAIN_LABEL[c]);
const refreshedText = (w: Wallet) =>
  w.last_refresh_at ? `Last refreshed ${relativeTime(w.last_refresh_at, now.value) || "at an unknown time"}` : "Not refreshed yet";
</script>

<template>
  <PageShell class="td-page" :title="TITLE" :subtitle="SUBTITLE">
    <template #actions>
      <button
        v-if="state.kind === 'ok'" type="button" class="btn btn-secondary" :disabled="cooldown > 0"
        :aria-describedby="cooldown ? 'cr-cooldown' : undefined" @click="refresh"
      >Refresh</button>
    </template>

    <div v-if="state.kind === 'loading'" class="card td-col" role="status">
      <span class="sr-only">Loading your wallets…</span>
      <span class="skel" style="width: 40%; height: 16px" aria-hidden="true"></span>
      <span class="skel" style="width: 70%; height: 28px" aria-hidden="true"></span>
    </div>

    <div v-else-if="state.kind === 'failed'" class="card td-col" :role="state.failure === 'off' ? 'status' : 'alert'">
      <div class="td-title">
        <span class="tile" :class="{ neg: state.failure !== 'off' }" aria-hidden="true"><NavIcon :name="state.failure === 'off' ? 'coin' : 'alert'" /></span>
        <h2>{{ state.failure === "off" ? OFF_TITLE : "Could not load your wallets" }}</h2>
      </div>
      <p class="t-sm t-2 cr-detail">{{ state.failure === "off" ? OFF_DETAIL : failureText(state.failure) }}</p>
      <div><button type="button" class="btn btn-secondary btn-sm" @click="load">Try again</button></div>
    </div>

    <template v-else>
      <p v-if="notice" class="td-banner" role="status"><span class="t-sm">{{ notice }}</span></p>
      <p v-if="cooldown" id="cr-cooldown" class="td-note" role="status">{{ cooldownText(cooldown) }}</p>

      <div class="td-kpis">
        <div class="card td-kpi">
          <span class="t-sm t-2">Crypto value</span>
          <span class="big">{{ formatMoney(state.data.total_eur, "EUR") }}</span>
          <span class="t-xs t-2">{{ state.data.wallets.length ? `as of ${asOfText(state.data.as_of)}` : "No wallets yet" }}</span>
        </div>
        <div class="card td-kpi">
          <span class="t-sm t-2">Wallets</span>
          <span class="big">{{ state.data.wallets.length }}</span>
          <span class="t-xs t-2">read-only, public addresses</span>
        </div>
      </div>
      <p v-if="state.data.hidden" class="td-banner warn" role="status"><span class="t-sm">{{ hiddenText(state.data.hidden) }}</span></p>

      <section class="card td-col" aria-labelledby="cr-add">
        <h2 id="cr-add">Add a wallet</h2>
        <p class="t-sm t-2">{{ HINT }}</p>
        <p v-if="!kinds.length" class="t-sm t-2">No chain is on in Core, so no wallet can be added.</p>
        <form v-else ref="formEl" class="td-col cr-form" novalidate @submit.prevent="add">
          <div class="field">
            <span id="cr-kind" class="label">Chain</span>
            <div class="seg cr-seg" role="group" aria-labelledby="cr-kind">
              <button v-for="k in kinds" :key="k" type="button" :aria-pressed="draft.kind === k" @click="resetKind(k)">{{ KIND_LABEL[k] }}</button>
            </div>
          </div>
          <fieldset v-if="draft.kind === 'evm'" class="cr-fieldset">
            <legend class="label">Chains</legend>
            <div class="cr-checks">
              <label v-for="c in evmChains" :key="c" class="cr-check">
                <input v-model="draft.chains" type="checkbox" name="chains" :value="c" :aria-invalid="issue?.field === 'chains'" />
                <span>{{ CHAIN_LABEL[c] }}</span>
              </label>
            </div>
          </fieldset>
          <label class="field"><span class="label">Address</span>
            <input
              v-model="draft.address" name="address" class="input mono" type="text" autocomplete="off" autocapitalize="off"
              spellcheck="false" :maxlength="LIMITS.address * 4" :aria-invalid="issue?.field === 'address'"
            />
          </label>
          <label class="field"><span class="label">Label <span class="t-2">(optional)</span></span>
            <input
              v-model="draft.label" name="label" class="input" type="text" autocomplete="off" :maxlength="LIMITS.label"
              :aria-invalid="issue?.field === 'label'"
            />
          </label>
          <p v-if="issue" class="err" role="alert">{{ issue.message }}</p>
          <p v-if="formError" class="err" role="alert">{{ formError }}</p>
          <div><button type="submit" class="btn btn-primary" :disabled="busy" :aria-busy="busy">Add wallet</button></div>
        </form>
      </section>

      <section class="td-col" aria-labelledby="cr-wallets">
        <h2 id="cr-wallets">Wallets</h2>
        <div v-if="!state.data.wallets.length" class="card empty">
          <h3>No wallets yet</h3>
          <p class="t-sm">Add a public address above to see its balances here.</p>
        </div>
        <article v-for="w in state.data.wallets" :key="w.id" class="card td-col" :aria-label="walletName(w)">
          <div class="td-card-head">
            <div class="cr-name">
              <h3 class="cr-wrap">{{ walletName(w) }}</h3>
              <span class="cr-addr">
                <span class="mono t-sm" :title="w.address">{{ shortAddress(w.address) }}</span>
                <button type="button" class="btn btn-ghost btn-sm" :aria-label="`Copy the address of ${walletName(w)}`" @click="copy(w)">
                  {{ copied === w.id ? "Copied" : "Copy" }}
                </button>
              </span>
            </div>
            <div class="cr-value">
              <span class="big">{{ formatMoney(w.value_eur, "EUR") }}</span>
              <span class="badge" :class="TONE_BADGE[STATUS_TONE[w.status]]"><span class="dot" aria-hidden="true"></span>{{ STATUS_LABEL[w.status] }}</span>
            </div>
          </div>
          <div class="cr-badges">
            <span v-for="c in chainNames(w.chains)" :key="c" class="badge td-outline">{{ c }}</span>
          </div>
          <p class="td-note">{{ refreshedText(w) }}</p>
          <p v-if="w.error" class="t-sm cr-error" role="note">{{ walletErrorText(w.error) }}</p>
          <p v-if="w.other_tokens" class="td-note">{{ otherTokensText(w.other_tokens) }}</p>
          <div v-if="w.balances.length" class="td-scroll" tabindex="0" role="region" :aria-label="`Balances of ${walletName(w)}`">
            <table class="tbl">
              <thead><tr><th scope="col">Asset</th><th scope="col">Chain</th><th scope="col" class="r">Amount</th><th scope="col" class="r">Value</th></tr></thead>
              <tbody>
                <tr v-for="(b, i) in w.balances" :key="i">
                  <td class="mono sym">{{ b.symbol }}</td>
                  <td>{{ b.chain ? CHAIN_LABEL[b.chain] : "Other" }}</td>
                  <td class="r">{{ formatDecimal(b.amount) }}</td>
                  <td class="r">{{ b.value_eur ? formatMoney(b.value_eur, "EUR") : NO_PRICE }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="cr-actions" @keydown.esc="confirming = null">
            <template v-if="confirming === w.id">
              <span class="t-sm" role="alert">{{ DELETE_CONFIRM }}</span>
              <button type="button" class="btn btn-danger-quiet btn-sm" :disabled="busy" :aria-busy="busy" @click="remove(w)">Remove</button>
              <button type="button" class="btn btn-secondary btn-sm" :disabled="busy" @click="confirming = null">Cancel</button>
            </template>
            <button v-else type="button" class="btn btn-ghost btn-sm" :disabled="busy" @click="confirming = w.id">Remove wallet</button>
          </div>
        </article>
      </section>

      <section v-if="state.data.assets.length" class="card td-col" aria-labelledby="cr-assets">
        <h2 id="cr-assets">By asset</h2>
        <div class="td-scroll" tabindex="0" role="region" aria-label="Crypto by asset">
          <table class="tbl">
            <thead>
              <tr><th scope="col">Asset</th><th scope="col" class="r">Amount</th><th scope="col" class="r">EUR price</th><th scope="col" class="r">Value</th><th scope="col" class="r">Weight</th></tr>
            </thead>
            <tbody>
              <tr v-for="a in state.data.assets" :key="a.symbol">
                <td class="mono sym">{{ a.symbol }}</td>
                <td class="r">{{ formatDecimal(a.amount) }}</td>
                <td class="r">{{ a.price_eur ? formatPrice(a.price_eur, "EUR") : "—" }}</td>
                <td class="r">{{ a.value_eur ? formatMoney(a.value_eur, "EUR") : NO_PRICE }}</td>
                <td class="r">{{ a.weight_pct ? formatPercent(a.weight_pct, { sign: false }) : "—" }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <p class="td-note">{{ ATTRIBUTION }}</p>
    </template>
  </PageShell>
</template>

<style scoped>
.cr-detail { max-width: 64ch; line-height: 1.55; overflow-wrap: anywhere; }
.cr-form { max-width: 640px; }
.cr-seg { flex-wrap: wrap; align-self: flex-start; }
.cr-fieldset { border: 0; margin: 0; padding: 0; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.cr-fieldset > legend { padding: 0; margin-bottom: 8px; font-size: 13px; font-weight: 500; }
.cr-checks { display: flex; flex-wrap: wrap; gap: 8px 16px; }
.cr-check { display: flex; align-items: center; gap: 6px; font-size: 14px; }
.cr-name { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.cr-wrap { overflow-wrap: anywhere; }
.cr-addr { display: flex; align-items: center; gap: 6px; }
.cr-value { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.cr-value .big { overflow-wrap: anywhere; min-width: 0; }
.cr-badges { display: flex; flex-wrap: wrap; gap: 6px; }
.cr-error { color: var(--danger-text); }
.cr-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.sym { font-weight: 600; }
</style>
