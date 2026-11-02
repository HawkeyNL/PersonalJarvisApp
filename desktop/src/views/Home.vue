<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { getJson, ApiError } from "../api";
import { currentAuthStatus, currentShellMode, login, clearSession, listDevices, PairingPending, AccountPasswordRequired, AccountActivationRequired, bootstrapFirstDevice, ownKeyFingerprint } from "../auth";
import { configureHomeNode, homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import { shownShellMode } from "../lock";
import JarvisConsole from "../components/JarvisConsole.vue";
import ConversationList from "../components/chat/ConversationList.vue";
import NavIcon, { type IconName } from "../components/NavIcon.vue";
import { accountFailure } from "../accountFailure";
import { conversations, loadConversations } from "../conversations";
import { agentCount, loadOptional, useCoreStatus } from "../coreStatus";
import { createSerialPoller } from "../serialPoller";
import { useAppStore } from "../stores/app";
import {
  agentsCard, approvalsCard, availabilityFromError, contextCard, failureText, healthCard, integrationsCard, originHost, runsCard,
  type Availability, type CardSummary, type CodingSession, type LiveHost, type SoftwareItem,
} from "../hubModel";

// The chat home (canvas D-Home): the conversation list with a compact system
// status, and the chat. Backend health and device-bound login run in the
// background exactly as before, so the chat always has a valid session; the
// onboarding forms take over the page until the device is connected and
// signed in, and nothing app-like shows before that.
const backend = ref<"unconfigured" | "checking" | "ok" | "fout">("checking");
const auth = ref<"checking" | "in" | "uit" | "wachten" | "fout">("checking");

let pollTimer: number | undefined;
let authTrying = false;
const originInput = ref("");
const configBusy = ref(false);
const configError = ref<string | null>(null);
// Before sign-in Settings is out of reach, so a wrong or moved Home Node
// address is changed here.
const changingOrigin = ref(false);
watch(currentShellMode, (mode) => { if (mode === "app") changingOrigin.value = false; });
function cancelOriginChange() {
  changingOrigin.value = false;
  originInput.value = homeNodeConfig.value.origin ?? "";
}
const accountMode = ref<"login" | "activate" | null>(null);
const password = ref("");
const passwordVisible = ref(false);
watch(accountMode, () => { passwordVisible.value = false; });
function hidePasswordOnBackground() {
  if (document.hidden) passwordVisible.value = false;
}
const activationCode = ref("");
const accountError = ref<string | null>(null);
const accountBusy = ref(false);
// Shown while waiting so the owner can compare it on the approving device.
const ownFingerprint = ref<string | null>(null);
watch(auth, async (state) => {
  if (state === "wachten" && !ownFingerprint.value) ownFingerprint.value = await ownKeyFingerprint().catch(() => null);
});
// Background sign-in errors that need owner action elsewhere.
const authError = ref<string | null>(null);

async function submitAccount() {
  accountBusy.value = true;
  accountError.value = null;
  const suppliedPassword = password.value;
  const suppliedCode = activationCode.value;
  password.value = "";
  passwordVisible.value = false;
  activationCode.value = "";
  try {
    if (accountMode.value === "activate") await bootstrapFirstDevice(suppliedCode, suppliedPassword);
    else await login(undefined, suppliedPassword);
    accountMode.value = null;
    await refreshAuth();
  } catch (error) {
    if (error instanceof PairingPending) {
      accountMode.value = null;
      auth.value = "wachten";
    } else {
      accountError.value = accountFailure(error);
    }
  } finally {
    accountBusy.value = false;
  }
}

async function pollBackend() {
  if (!homeNodeConfig.value.configured) {
    backend.value = "unconfigured";
    return;
  }
  try {
    await getJson("/readyz");
    backend.value = "ok";
    if (auth.value !== "in" && !accountMode.value && !accountBusy.value) await refreshAuth();
  } catch {
    backend.value = "fout";
  }
}

function startBackendPolling() {
  if (pollTimer !== undefined) return;
  pollTimer = window.setInterval(pollBackend, 5000);
}

async function saveHomeNode() {
  configBusy.value = true;
  configError.value = null;
  try {
    await configureHomeNode(originInput.value);
    changingOrigin.value = false;
    backend.value = "checking";
    await pollBackend();
    startBackendPolling();
  } catch (error) {
    configError.value = error instanceof Error ? error.message : String(error);
  } finally {
    configBusy.value = false;
  }
}

// Ensure a usable native session exists, logging in (enroll if needed) when absent.
async function ensureSession(): Promise<boolean> {
  let status = await currentAuthStatus();
  if (!status.authenticated) {
    await login();
    status = await currentAuthStatus();
  }
  return status.authenticated;
}

async function refreshAuth() {
  if (authTrying) return;
  authTrying = true;
  try {
    let authenticated = await ensureSession();
    if (!authenticated) {
      auth.value = "uit";
      return;
    }
    // Listing devices validates the token; a stale one (backend restarted) 401s
    // — drop it and log in fresh once, instead of looping on a dead token.
    try {
      await listDevices();
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        await clearSession();
        authenticated = await ensureSession();
        if (!authenticated) {
          auth.value = "uit";
          return;
        }
        await listDevices();
      } else {
        throw e;
      }
    }
    auth.value = "in";
    authError.value = null;
  } catch (error) {
    authError.value = error instanceof ApiError && error.code === "pairing_password_not_set" ? accountFailure(error) : null;
    if (error instanceof AccountPasswordRequired) accountMode.value = "login";
    if (error instanceof AccountActivationRequired) accountMode.value = "activate";
    auth.value = error instanceof PairingPending ? "wachten" : "fout";
  } finally {
    authTrying = false;
  }
}

// --- Home data (owner endpoints; an older Core degrades per entry) ----------
const conversationsState = ref<Availability<true>>({ state: "loading" });
const devices = ref<Availability<{ devices: unknown[] }>>({ state: "loading" });
const pending = ref<Availability<{ pending: unknown[] }>>({ state: "loading" });
const sessions = ref<Availability<{ sessions: CodingSession[] }>>({ state: "loading" });
const registry = ref<Availability<{ software: SoftwareItem[]; live_host?: LiveHost }>>({ state: "loading" });
const now = ref(Date.now());

async function refreshHome() {
  if (document.hidden) return;
  const [d, p, c, r] = await Promise.all([
    loadOptional<{ devices: unknown[] }>("/v1/devices"),
    loadOptional<{ pending: unknown[] }>("/v1/agent/pending"),
    loadOptional<{ sessions: CodingSession[] }>("/v1/coding/sessions"),
    loadOptional<{ software: SoftwareItem[]; live_host?: LiveHost }>("/v1/system/registry"),
    loadConversations()
      .then(() => { conversationsState.value = { state: "ok", value: true }; })
      .catch((error) => { conversationsState.value = availabilityFromError(error); }),
  ]);
  devices.value = d; pending.value = p; sessions.value = c; registry.value = r;
  now.value = Date.now();
}
const homePoller = createSerialPoller(refreshHome, 60_000);
watch(auth, (value) => { if (value === "in") homePoller.start(); else homePoller.stop(); }, { immediate: true });

const listError = computed(() => {
  const state = conversationsState.value;
  // A list already on screen (realtime, an earlier poll) stays usable.
  if (conversations.value.length || state.state === "ok" || state.state === "loading") return null;
  return state.state === "unsupported" ? "Conversations need a newer Core." : failureText(state.reason);
});

const online = computed(() => (backend.value === "ok" ? true : backend.value === "fout" ? false : null));
const pick = <T, K extends keyof T>(source: Availability<T>, key: K): Availability<T[K]> =>
  source.state === "ok" ? { state: "ok", value: source.value[key] } : source;

// The live counts the old hub cards showed, each a link to its page. Agents,
// Integrations and System health are only reachable from here.
useCoreStatus();
type StatusLink = { key: string; to: string; icon: IconName; label: string; summary: CardSummary };
const statusLinks = computed<StatusLink[]>(() => [
  { key: "approvals", to: "/approvals", icon: "approve", label: "Approvals", summary: approvalsCard(pick(pending.value, "pending")) },
  { key: "runs", to: "/runs", icon: "runs", label: "Runs", summary: runsCard(pick(sessions.value, "sessions")) },
  { key: "agents", to: "/agents", icon: "agents", label: "Agents", summary: agentsCard(agentCount.value) },
  { key: "integrations", to: "/integrations", icon: "integrations", label: "Integrations", summary: integrationsCard(registry.value) },
  { key: "devices", to: "/devices", icon: "monitor", label: "Devices", summary: contextCard(pick(devices.value, "devices")) },
  { key: "health", to: "/health", icon: "health", label: "System health", summary: healthCard(online.value, registry.value) },
]);

// The gate (setup and sign-in forms) wins; the app layout needs the app shell.
const gate = computed(() => backend.value === "unconfigured" || changingOrigin.value || (!!accountMode.value && backend.value === "ok"));
const inApp = computed(() => shownShellMode.value === "app" && !gate.value);
const footerHost = computed(() => originHost(homeNodeConfig.value.origin));

// Ctrl/⌘K (App.vue) and New focus the composer. The chat mounts once the
// session is confirmed, so a request made before that waits for it.
const app = useAppStore();
const consoleRef = ref<InstanceType<typeof JarvisConsole> | null>(null);
watch([() => app.consoleOpen, consoleRef], ([requested, chat]) => {
  if (!requested || !chat) return;
  chat.focusInput();
  app.consoleOpen = false;
}, { immediate: true, flush: "post" });

onMounted(async () => {
  const config = await loadHomeNodeConfig();
  originInput.value = config.origin ?? "";
  if (config.configured) {
    await pollBackend();
    startBackendPolling();
  } else {
    backend.value = "unconfigured";
  }
});
onMounted(() => document.addEventListener("visibilitychange", hidePasswordOnBackground));
onBeforeUnmount(() => {
  document.removeEventListener("visibilitychange", hidePasswordOnBackground);
  passwordVisible.value = false;
  clearInterval(pollTimer);
  homePoller.stop();
  app.consoleOpen = false;
  password.value = "";
  activationCode.value = "";
});
</script>

<template>
  <section class="home" :class="{ app: inApp }">
    <h1 class="brand" :class="{ 'sr-only': inApp }">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="12" cy="12" r="3.5" /><circle cx="12" cy="12" r="9" /></svg>
      {{ inApp ? "Chat with Jarvis" : "Jarvis" }}
    </h1>

    <template v-if="inApp">
      <ConversationList :loading="conversationsState.state === 'loading'" :error="listError" :now="now"
        @new="consoleRef?.focusInput()">
        <template #footer>
          <nav class="strip" aria-labelledby="strip-title">
            <h2 id="strip-title" class="strip-title t-xs t-2">System</h2>
            <ul>
              <li v-for="link in statusLinks" :key="link.key">
                <RouterLink :to="link.to" class="strip-link" :title="link.summary.lines.filter(Boolean).join(' · ')">
                  <NavIcon :name="link.icon" />
                  <span class="strip-label">{{ link.label }}</span>
                  <span class="strip-value t-xs t-2"><span class="dot" :class="link.summary.tone" aria-hidden="true"></span>{{ link.summary.lines[0] }}</span>
                </RouterLink>
              </li>
            </ul>
          </nav>
        </template>
      </ConversationList>
      <div class="pane">
        <p v-if="authError" class="notice err" role="alert">{{ authError }}</p>
        <p v-else-if="backend === 'fout'" class="notice err" role="status">
          Home Node unreachable at {{ homeNodeConfig.origin }}. Check the network and try again.
        </p>
        <!-- Mounted while signed in, so wake word and realtime chat keep working. -->
        <JarvisConsole v-if="auth === 'in'" ref="consoleRef" />
        <p v-else-if="backend !== 'fout' && !authError" class="empty connecting" role="status">
          <span class="spinner" aria-hidden="true"></span>Connecting to your Home Node…
        </p>
      </div>
    </template>

    <template v-else>
      <!-- Onboarding / sign-in take over until the device is ready. -->
      <div v-if="gate" class="gate">
      <form v-if="backend === 'unconfigured' || changingOrigin" class="connection-setup card" @submit.prevent="saveHomeNode">
        <h2>Connect to your Home Node</h2>
        <p>Enter the HTTPS origin of your Home Node. Jarvis stores only this address locally, never credentials.</p>
        <label for="home-node-origin">Home Node origin</label>
        <input
          id="home-node-origin"
          v-model.trim="originInput"
          type="url"
          inputmode="url"
          autocomplete="url"
          placeholder="https://jarvis.home.example"
          required
        />
        <button type="submit" class="btn btn-primary" :disabled="configBusy">
          {{ configBusy ? "Connecting…" : "Connect and pair" }}
        </button>
        <p v-if="configError" class="config-error" role="alert">{{ configError }}</p>
        <p class="setup-hint">Plain HTTP on localhost is only allowed in a development build.</p>
        <button v-if="changingOrigin" type="button" class="btn btn-secondary" @click="cancelOriginChange">Cancel</button>
      </form>
      <form v-else-if="accountMode && backend === 'ok'" class="connection-setup card" @submit.prevent="submitAccount">
        <h2>{{ accountMode === "activate" ? "Activate your first device" : "Sign in to Jarvis" }}</h2>
        <p v-if="accountMode === 'activate'">Use the one-time activation code from your Home Node and choose an account password of at least 15 characters.</p>
        <label v-if="accountMode === 'activate'" for="activation-code">Activation code</label>
        <input v-if="accountMode === 'activate'" id="activation-code" v-model="activationCode" type="text" autocomplete="off" autocapitalize="off" :spellcheck="false" maxlength="256" required />
        <label for="account-password">Account password</label>
        <div class="password-input">
          <input id="account-password" v-model="password" :type="passwordVisible ? 'text' : 'password'" :autocomplete="accountMode === 'activate' ? 'new-password' : 'current-password'" autocapitalize="off" :spellcheck="false" :minlength="accountMode === 'activate' ? 15 : undefined" maxlength="1024" required />
          <button type="button" class="btn btn-secondary password-toggle" :aria-label="passwordVisible ? 'Hide password' : 'Show password'" :aria-pressed="passwordVisible" aria-controls="account-password" @click="passwordVisible = !passwordVisible">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path v-if="passwordVisible" d="m3 3 18 18"/></svg>
          </button>
        </div>
        <button type="submit" class="btn btn-primary" :disabled="accountBusy">{{ accountBusy ? "Working…" : "Continue" }}</button>
        <p v-if="accountError" class="config-error" role="alert">{{ accountError }}</p>
      </form>
      </div>
      <div v-else class="gate">
        <p v-if="auth === 'wachten'" class="notice" role="status">
          Waiting for approval from your phone, a Mac or the Home Node.
          <template v-if="ownFingerprint"><br />Check that it shows this key fingerprint: <code>{{ ownFingerprint }}</code></template>
        </p>
        <p v-else-if="authError" class="notice err" role="alert">{{ authError }}</p>
        <p v-else-if="backend === 'fout'" class="notice err" role="status">
          Home Node unreachable at {{ homeNodeConfig.origin }}. Check the network and try again.
        </p>
        <p v-else class="empty connecting" role="status"><span class="spinner" aria-hidden="true"></span>Connecting to your Home Node…</p>
      </div>
      <footer class="footer">
        <span class="dot" :class="online ? 'ok' : online === false ? 'error' : 'idle'" aria-hidden="true"></span>
        <span class="t-sm">{{ footerHost || "No Home Node set" }}</span>
        <span v-if="footerHost" class="t-sm t-2">{{ online ? "Online" : online === false ? "Offline" : "Connecting" }}</span>
        <button v-if="shownShellMode !== 'app' && backend !== 'unconfigured' && !changingOrigin" type="button"
          class="btn btn-secondary btn-sm" @click="changingOrigin = true">Change Home Node</button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.home { position: relative; height: 100%; display: flex; flex-direction: column; }
.home.app { flex-direction: row; }
.brand { margin: 0; padding: 28px 36px 0; display: flex; align-items: center; gap: 10px; font-size: 22px; }
.brand svg { color: var(--accent-text); }
.pane { flex: 999 1 520px; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.notice {
  margin: 16px 32px 0; padding: 12px 16px; border-radius: var(--r-md);
  background: var(--bg-surface); box-shadow: var(--shadow-border); font-size: 14px; color: var(--text-primary);
}
.notice.err { color: var(--danger-text); }
.connecting { flex: 1 1 auto; flex-direction: row; justify-content: center; }
.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

/* Compact system status under the conversation list. */
.strip { flex: none; padding-top: 10px; border-top: 1px solid var(--separator); }
.strip-title { margin: 0; padding: 0 10px 4px; font-weight: 500; }
.strip ul { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; }
.strip-link {
  display: flex; align-items: center; gap: 10px; min-height: 32px; padding: 0 10px; border-radius: var(--r-md);
  color: var(--text-primary); font-size: 13px; text-decoration: none; transition: background-color var(--d-hover) ease;
}
.strip-link :deep(svg) { flex: none; width: 16px; height: 16px; color: var(--text-secondary); }
.strip-label { flex: none; }
.strip-value { margin-left: auto; min-width: 0; display: inline-flex; align-items: center; gap: 6px; overflow: hidden; white-space: nowrap; }
@media (hover: hover) and (pointer: fine) {
  .strip-link:hover { background: var(--bg-hover); }
}
.strip-link:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }
.dot { flex: none; width: 8px; height: 8px; border-radius: var(--r-pill); background: var(--text-disabled); }
.dot.ok { background: var(--positive-solid); }
.dot.warn { background: var(--negative-solid); }
.dot.error { background: var(--danger-solid); }

/* --- Onboarding gate --- */
/* A tall sign-in form scrolls inside the gate; `safe` keeps its top reachable. */
.gate {
  flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; align-items: center;
  justify-content: center; justify-content: safe center; gap: 20px; padding: 24px 16px;
}
.gate .notice { margin: 0; width: min(34rem, 100%); text-align: center; }
.connection-setup { width: min(32rem, 100%); padding: 24px; border-radius: var(--r-xl); }
.connection-setup h2 { margin: 0 0 8px; }
.connection-setup p { margin: 0 0 8px; color: var(--text-secondary); font-size: 14px; line-height: 1.5; }
.connection-setup label { display: block; margin: 16px 0 6px; font-size: 13px; font-weight: 500; }
.connection-setup input { width: 100%; min-height: 40px; margin-bottom: 12px; }
.connection-setup .btn + .btn { margin-left: 8px; }
.password-input { display: flex; align-items: stretch; gap: 8px; margin-bottom: 12px; }
.password-input input { flex: 1; min-width: 0; margin-bottom: 0; }
.password-toggle { min-width: 44px; min-height: 44px; display: grid; place-items: center; }
.config-error { color: var(--danger-text) !important; }
.setup-hint { font-size: 13px !important; }
.footer { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 16px 36px 24px; }
</style>
