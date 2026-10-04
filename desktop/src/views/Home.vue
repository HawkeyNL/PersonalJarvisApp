<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from "vue";
import { getJson, ApiError } from "../api";
import { currentAuthStatus, login, clearSession, listDevices, PairingPending, AccountPasswordRequired, AccountActivationRequired, bootstrapFirstDevice } from "../auth";
import { configureHomeNode, homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import JarvisConsole from "../components/JarvisConsole.vue";
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";
import JvCommandBar from "../components/jv/JvCommandBar.vue";
import JvOrb from "../components/jv/JvOrb.vue";
import JvModuleCard from "../components/jv/JvModuleCard.vue";
import JvStatusDot from "../components/jv/JvStatusDot.vue";
import NavIcon, { type IconName } from "../components/NavIcon.vue";
import { accountFailure } from "../accountFailure";
import { thinking } from "../assistant";
import { conversations, loadConversations } from "../conversations";
import { wakePulse } from "../voicewake";
import { agentCount, loadOptional } from "../coreStatus";
import { createSerialPoller } from "../serialPoller";
import { useAppStore } from "../stores/app";
import { useFitScale } from "../fitScale";
import {
  agentsCard, availabilityFromError, contextCard, conversationsCard, deriveMood, healthCard, integrationsCard, originHost,
  statusPill, tasksCard,
  type Availability, type CardSummary, type CodingSession, type LiveHost, type SoftwareItem,
} from "../hubModel";

// The hub: the living orb with the seven modules around it. Backend health and
// device-bound login run in the background exactly as before, so the chat
// always has a valid session; the onboarding forms take over the centre until
// the device is connected and signed in.
const backend = ref<"unconfigured" | "checking" | "ok" | "fout">("checking");
const auth = ref<"checking" | "in" | "uit" | "wachten" | "fout">("checking");

let pollTimer: number | undefined;
let authTrying = false;
const originInput = ref("");
const configBusy = ref(false);
const configError = ref<string | null>(null);
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
  } catch (error) {
    if (error instanceof AccountPasswordRequired) accountMode.value = "login";
    if (error instanceof AccountActivationRequired) accountMode.value = "activate";
    auth.value = error instanceof PairingPending ? "wachten" : "fout";
  } finally {
    authTrying = false;
  }
}

// --- Hub data (owner endpoints; an older Core degrades per card) ----------
const conversationsState = ref<Availability<{ updated_at: string }[]>>({ state: "loading" });
const devices = ref<Availability<{ devices: unknown[] }>>({ state: "loading" });
const pending = ref<Availability<{ pending: unknown[] }>>({ state: "loading" });
const sessions = ref<Availability<{ sessions: CodingSession[] }>>({ state: "loading" });
const registry = ref<Availability<{ software: SoftwareItem[]; live_host?: LiveHost }>>({ state: "loading" });
const now = ref(Date.now());

async function refreshHub() {
  if (document.hidden) return;
  const [d, p, c, r] = await Promise.all([
    loadOptional<{ devices: unknown[] }>("/v1/devices"),
    loadOptional<{ pending: unknown[] }>("/v1/agent/pending"),
    loadOptional<{ sessions: CodingSession[] }>("/v1/coding/sessions"),
    loadOptional<{ software: SoftwareItem[]; live_host?: LiveHost }>("/v1/system/registry"),
    loadConversations()
      .then(() => { conversationsState.value = { state: "ok", value: conversations.value }; })
      .catch((error) => { conversationsState.value = availabilityFromError(error); }),
  ]);
  devices.value = d; pending.value = p; sessions.value = c; registry.value = r;
  now.value = Date.now();
}
const hubPoller = createSerialPoller(refreshHub, 60_000);
watch(auth, (value) => { if (value === "in") hubPoller.start(); else hubPoller.stop(); }, { immediate: true });
// Realtime events keep the shared conversation list fresh between polls.
watch(conversations, (list) => {
  if (conversationsState.value.state === "ok") conversationsState.value = { state: "ok", value: list };
});

const online = computed(() => (backend.value === "ok" ? true : backend.value === "fout" ? false : null));
const pick = <T, K extends keyof T>(source: Availability<T>, key: K): Availability<T[K]> =>
  source.state === "ok" ? { state: "ok", value: source.value[key] } : source;

type Card = { key: string; to: string; icon: IconName; title: string; summary: CardSummary };
const cards = computed<Card[]>(() => [
  { key: "chat", to: "/conversations", icon: "chat", title: "Conversations", summary: conversationsCard(conversationsState.value, now.value) },
  { key: "agents", to: "/agents", icon: "agents", title: "Agents", summary: agentsCard(agentCount.value) },
  { key: "memory", to: "/memory", icon: "memory", title: "Memory", summary: { lines: ["Not yet available", "Needs Core support"], tone: "idle" } },
  { key: "tasks", to: "/tasks", icon: "tasks", title: "Tasks", summary: tasksCard(pick(sessions.value, "sessions"), pick(pending.value, "pending")) },
  { key: "integrations", to: "/integrations", icon: "integrations", title: "Integrations", summary: integrationsCard(registry.value) },
  { key: "context", to: "/context", icon: "context", title: "Context", summary: contextCard(pick(devices.value, "devices")) },
  { key: "health", to: "/health", icon: "health", title: "System Health", summary: healthCard(online.value, registry.value) },
]);
// Before sign-in the owner cards cannot load; say so instead of "Loading…".
const shownCards = computed(() => cards.value.map((card): Card =>
  auth.value === "in" || card.key === "health" || card.key === "memory"
    ? card
    : { ...card, summary: { lines: [auth.value === "checking" ? "Loading…" : "Sign-in required", ""], tone: "idle" } },
));

// --- Chat overlay + orb mood ------------------------------------------------
const app = useAppStore();
const consoleRef = ref<InstanceType<typeof JarvisConsole> | null>(null);
const mood = computed(() => deriveMood({ thinking: thinking.value, listening: !!consoleRef.value?.listening }));
const pill = computed(() => statusPill(mood.value, online.value));
let returnFocus: HTMLElement | null = null;
watch(() => app.consoleOpen, async (open) => {
  if (open) {
    returnFocus = document.activeElement as HTMLElement | null;
    await nextTick();
    consoleRef.value?.focusInput();
  } else {
    returnFocus?.focus?.();
  }
}, { immediate: true });
function openConsole(listen = false) {
  app.consoleOpen = true;
  if (listen) consoleRef.value?.startListening();
}
// "Hey Jarvis" already starts the mic inside the console; show it.
watch(wakePulse, () => { app.consoleOpen = true; });

// Compact (mobile-like) layout below ~1100x760.
const compactQuery = window.matchMedia("(max-width: 1099px), (max-height: 759px)");
const compact = ref(compactQuery.matches);
const onCompact = (event: MediaQueryListEvent) => { compact.value = event.matches; };
compactQuery.addEventListener("change", onCompact);
// The hub always fills the window: the stage scales into the room between
// the top bar and the footer (grows on large screens, shrinks on small ones).
const fitRoom = ref<HTMLElement | null>(null);
const fitContent = ref<HTMLElement | null>(null);
const scale = useFitScale(fitRoom, fitContent, () => (compact.value ? 1 : 1.75));
const footerHost = computed(() => originHost(homeNodeConfig.value.origin));
// Round satellites around the compact orb (offsets from the export).
const ORBIT: Record<string, [number, number]> = {
  chat: [0, -124], memory: [98, -76], integrations: [120, 30], health: [62, 107],
  context: [-62, 107], tasks: [-120, 30], agents: [-98, -76],
};

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
  hubPoller.stop();
  compactQuery.removeEventListener("change", onCompact);
  app.consoleOpen = false;
  password.value = "";
  activationCode.value = "";
});
</script>

<template>
  <section class="hub" :class="{ compact }">
    <JvBackdrop glow-y="43%" horizon="max(64px, 9vh)" />

    <JvTopBar variant="hub">
      <JvCommandBar :disabled="auth !== 'in'" @open="openConsole()" @mic="openConsole(true)" />
    </JvTopBar>

    <!-- Onboarding / sign-in take over the centre until the device is ready. -->
    <div v-if="backend === 'unconfigured' || (accountMode && backend === 'ok')" class="gate">
      <JvOrb class="gate-orb" :size="182" :label="false" />
      <form v-if="backend === 'unconfigured'" class="connection-setup" @submit.prevent="saveHomeNode">
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
        <button type="submit" :disabled="configBusy">
          {{ configBusy ? "Connecting…" : "Connect and pair" }}
        </button>
        <p v-if="configError" class="config-error" role="alert">{{ configError }}</p>
        <p class="setup-hint">Plain HTTP on localhost is only allowed in a development build.</p>
      </form>
      <form v-if="accountMode && backend === 'ok'" class="connection-setup" @submit.prevent="submitAccount">
        <h2>{{ accountMode === "activate" ? "Activate your first device" : "Sign in to Jarvis" }}</h2>
        <p v-if="accountMode === 'activate'">Use the one-time activation code from your Home Node and choose an account password of at least 15 characters.</p>
        <label v-if="accountMode === 'activate'" for="activation-code">Activation code</label>
        <input v-if="accountMode === 'activate'" id="activation-code" v-model="activationCode" type="text" autocomplete="off" autocapitalize="off" :spellcheck="false" maxlength="256" required />
        <label for="account-password">Account password</label>
        <div class="password-input">
          <input id="account-password" v-model="password" :type="passwordVisible ? 'text' : 'password'" :autocomplete="accountMode === 'activate' ? 'new-password' : 'current-password'" autocapitalize="off" :spellcheck="false" :minlength="accountMode === 'activate' ? 15 : undefined" maxlength="1024" required />
          <button type="button" class="password-toggle" :aria-label="passwordVisible ? 'Hide password' : 'Show password'" :aria-pressed="passwordVisible" aria-controls="account-password" @click="passwordVisible = !passwordVisible">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path v-if="passwordVisible" d="m3 3 18 18"/></svg>
          </button>
        </div>
        <button type="submit" :disabled="accountBusy">{{ accountBusy ? "Working…" : "Continue" }}</button>
        <p v-if="accountError" class="config-error" role="alert">{{ accountError }}</p>
      </form>
    </div>

    <div v-else ref="fitRoom" class="body" :style="{ '--fit': scale }">
      <p v-if="auth === 'wachten'" class="pairing-wait" role="status">
        Waiting for approval from a trusted Jarvis device.
      </p>
      <p v-else-if="backend === 'fout'" class="connection-error" role="status">
        Home Node unreachable at {{ homeNodeConfig.origin }}. Check the network and try again.
      </p>

      <!-- Desktop hub: orb in the middle, seven modules around it. -->
      <div v-if="!compact" ref="fitContent" class="stage">
        <svg class="wires" viewBox="0 0 1060 740" aria-hidden="true">
          <defs>
            <radialGradient id="hubPlanet" cx="35%" cy="30%" r="70%">
              <stop offset="0" stop-color="#d7ffea" /><stop offset=".5" stop-color="#3fe39b" /><stop offset="1" stop-color="#0b5d3c" />
            </radialGradient>
          </defs>
          <g transform="translate(-199 -125)">
            <circle cx="725" cy="473" r="196" fill="none" stroke="rgba(150,255,210,.6)" stroke-width="1.5" />
            <circle cx="725" cy="473" r="208" fill="none" class="a22" stroke-width="1" />
            <circle cx="725" cy="473" r="250" fill="none" class="a30" stroke-width="1" stroke-dasharray="1 6" />
            <ellipse cx="725" cy="473" rx="330" ry="74" transform="rotate(9 725 473)" fill="none" stroke="rgba(120,255,200,.28)" stroke-width="1" />
            <path d="M724 222V282" class="a60" stroke-width="1" />
            <path d="M552 373L478 332M897 373L970 332M536 539L443 563M912 539L1007 563M622 641L574 701M826 641L875 703" class="a55" stroke-width="1" />
            <g class="fill-accent"><circle cx="724" cy="222" r="2.5" /><circle cx="478" cy="332" r="2.5" /><circle cx="970" cy="332" r="2.5" /><circle cx="443" cy="563" r="2.5" /><circle cx="1007" cy="563" r="2.5" /><circle cx="574" cy="701" r="2.5" /><circle cx="875" cy="703" r="2.5" /></g>
            <g fill="rgba(2,20,15,.9)" stroke="rgba(150,255,210,.85)" stroke-width="1.4"><circle cx="552" cy="373" r="6.5" /><circle cx="897" cy="373" r="6.5" /><circle cx="536" cy="539" r="6.5" /><circle cx="912" cy="539" r="6.5" /><circle cx="622" cy="641" r="6.5" /><circle cx="826" cy="641" r="6.5" /></g>
            <g class="fill-accent"><circle cx="552" cy="373" r="2" /><circle cx="897" cy="373" r="2" /><circle cx="536" cy="539" r="2" /><circle cx="912" cy="539" r="2" /><circle cx="622" cy="641" r="2" /><circle cx="826" cy="641" r="2" /></g>
            <circle cx="397" cy="459" r="11" fill="url(#hubPlanet)" stroke="rgba(190,255,225,.8)" stroke-width="1" />
            <circle cx="941" cy="500" r="6" fill="url(#hubPlanet)" stroke="rgba(190,255,225,.8)" stroke-width="1" />
          </g>
        </svg>
        <JvOrb class="orb" :size="346" :mood="mood" />
        <JvModuleCard v-for="card in shownCards" :key="card.key" :class="'card-' + card.key" :to="card.to"
          :icon="card.icon" :title="card.title" :lines="card.summary.lines" :tone="card.summary.tone" />
        <div class="pill" role="status">
          <span class="pill-dot"><JvStatusDot :tone="pill.tone" /></span>
          <span class="pill-label">{{ pill.label }}</span>
          <span class="pill-sep" aria-hidden="true"></span>
          <span class="pill-hint">{{ pill.hint }}</span>
        </div>
      </div>

      <!-- Compact hub (core-mobile): small orb with round links + a card grid. -->
      <div v-else ref="fitContent" class="compact-body">
        <div class="compact-orb">
          <JvOrb :size="182" :mood="mood" />
          <RouterLink v-for="card in shownCards" :key="card.key" :to="card.to" class="orbit-link"
            :aria-label="card.title" :style="{ transform: `translate(${ORBIT[card.key][0]}px, ${ORBIT[card.key][1]}px)` }">
            <NavIcon :name="card.icon" />
          </RouterLink>
        </div>
        <div class="compact-side">
          <div class="pill" role="status">
            <span class="pill-dot"><JvStatusDot :tone="pill.tone" /></span>
            <span class="pill-label">{{ pill.label }}</span>
            <span class="pill-sep" aria-hidden="true"></span>
            <span class="pill-hint">{{ pill.hint }}</span>
          </div>
          <h2 class="eyebrow">YOUR SYSTEM</h2>
          <div class="grid">
            <JvModuleCard v-for="card in shownCards" :key="card.key" class="compact" :to="card.to"
              :icon="card.icon" :title="card.title" :lines="card.summary.lines" :tone="card.summary.tone" />
          </div>
        </div>
      </div>
    </div>

    <!-- Decorative taglines + footer. -->
    <div class="tagline left" aria-hidden="true">THINK<br />BUILD<br />AUTOMATE<br />FURTHER</div>
    <div class="tagline right" aria-hidden="true">A MORE<br />CAPABLE<br />YOU</div>
    <footer class="footer">
      <div class="node">
        <span class="node-icon" aria-hidden="true"><NavIcon name="target" /></span>
        <span class="node-text">
          <span>{{ footerHost || "No Home Node set" }}</span>
          <span class="node-state" :class="{ off: online === false }">{{ online ? "ONLINE" : online === false ? "OFFLINE" : "CONNECTING" }}</span>
        </span>
      </div>
      <div class="motto" aria-hidden="true">“IDEAS COMPOUND HERE”<span class="bar"></span></div>
      <div class="tagline-foot" aria-hidden="true">HIGHER<br />INTELLIGENCE<br />CLOSER</div>
    </footer>

    <!-- Chat overlay: Ctrl/⌘K, the command bar or "Hey Jarvis". The console
         stays mounted while signed in, exactly as before, so wake word and
         realtime chat keep working when the overlay is closed. -->
    <div
      v-if="auth === 'in'"
      v-show="app.consoleOpen"
      class="console-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Chat with Jarvis"
      @keydown.esc="app.consoleOpen = false"
      @click.self="app.consoleOpen = false"
    >
      <div class="console-frame">
        <button type="button" class="console-close" aria-label="Close chat" @click="app.consoleOpen = false">×</button>
        <JarvisConsole ref="consoleRef" :force-open="app.consoleOpen" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.hub { position: relative; height: 100%; display: flex; flex-direction: column; overflow: hidden; }
.body, .gate { position: relative; z-index: 1; }
.tagline { z-index: 1; }

/* --- Desktop stage (export coordinates, offset by 199/125) --- */
/* The room for the stage: the space between top bar and footer. Its size
   sets --fit (see useFitScale); the stage keeps its design coordinates. */
.body { flex: 1; min-height: 0; margin: 8px 0 76px; }
.stage { position: absolute; left: 50%; top: 50%; width: 1060px; height: 740px; transform: translate(-50%, -50%) scale(var(--fit, 1)); }
.wires { position: absolute; inset: 0; width: 100%; height: 100%; overflow: visible; }
.a22 { stroke: rgba(var(--accent-rgb), 0.22); }
.a30 { stroke: rgba(var(--accent-rgb), 0.3); }
.a55 { stroke: rgba(var(--accent-rgb), 0.55); }
.a60 { stroke: rgba(var(--accent-rgb), 0.6); }
.fill-accent { fill: var(--accent); }
.orb { position: absolute !important; left: 353px; top: 175px; }
.stage .jv-card { position: absolute; max-width: 270px; }
.card-chat { left: 528px; top: 6px; min-width: 249px; transform: translateX(-50%); }
.card-agents { right: 781px; top: 138px; min-width: 214px; min-height: 90px; }
.card-memory { left: 779px; top: 139px; min-width: 218px; min-height: 94px; }
.card-tasks { right: 824px; top: 397px; min-width: 227px; min-height: 94px; }
.card-integrations { left: 819px; top: 400px; min-width: 232px; min-height: 94px; }
.card-context { right: 675px; top: 571px; min-width: 222px; min-height: 91px; }
.card-health { left: 674px; top: 571px; min-width: 255px; min-height: 90px; }

.pill {
  display: flex; align-items: center; gap: 22px; height: 44px; box-sizing: border-box; padding: 0 20px 0 14px;
  border-radius: var(--r-22); border: 1px solid rgba(160, 255, 215, 0.14); background: rgba(6, 22, 18, 0.7);
  white-space: nowrap;
}
.stage .pill { position: absolute; left: 525px; top: 690px; min-width: 354px; transform: translateX(-50%); }
.pill-dot {
  width: 28px; height: 28px; flex: none; box-sizing: border-box; display: grid; place-items: center;
  border-radius: 50%; border: 1.5px solid rgba(var(--accent-rgb), 0.6);
}
.pill-label { font-size: 13.5px; font-weight: 600; letter-spacing: 0.08em; color: var(--accent); }
.pill-sep { width: 1px; height: 16px; background: rgba(160, 255, 215, 0.3); }
.pill-hint { font-size: 11px; letter-spacing: 0.05em; color: var(--text-4); }

.pairing-wait, .connection-error {
  position: absolute; z-index: 3; top: 6px; left: 50%; transform: translateX(-50%);
  width: min(34rem, calc(100% - 2rem)); margin: 0; padding: 0.75rem 1rem; box-sizing: border-box;
  border-radius: var(--r-12); text-align: center; font-size: var(--fs-13);
}
.pairing-wait { border: 1px solid var(--line-a45); background: rgba(5, 36, 28, 0.9); color: var(--text-1); }
.connection-error { border: 1px solid rgba(248, 113, 113, 0.45); background: rgba(30, 8, 8, 0.85); color: #fecaca; }

/* --- Onboarding gate --- */
/* A tall sign-in form scrolls inside the gate; `safe` keeps its top reachable. */
.gate {
  flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; align-items: center;
  justify-content: center; justify-content: safe center; gap: 28px; padding: 24px 16px 96px;
}
.connection-setup {
  width: min(32rem, 100%); box-sizing: border-box; padding: 22px 24px;
  border-radius: var(--r-22); border: 1.5px solid var(--line-a55); background: var(--panel-bg);
  box-shadow: 0 0 30px rgba(var(--accent-rgb), 0.18);
}
.connection-setup h2 { margin: 0 0 8px; font-size: var(--fs-20); font-weight: 500; letter-spacing: 0.04em; color: var(--text-0); }
.connection-setup p { color: var(--text-3); font-size: var(--fs-13); line-height: 1.5; }
.connection-setup label { display: block; margin: 1rem 0 0.4rem; font-size: var(--fs-12); color: var(--text-4); }
.connection-setup input { width: 100%; box-sizing: border-box; margin-bottom: 0.8rem; border-color: var(--line-a30); background: var(--field-bg); }
.password-input { display: flex; align-items: stretch; gap: 8px; margin-bottom: .8rem; }
.password-input input { flex: 1; min-width: 0; margin-bottom: 0; }
.password-toggle { min-width: 44px; min-height: 44px; display: grid; place-items: center; }
.config-error { color: #fecaca !important; }
.setup-hint { color: var(--muted) !important; font-size: 0.82rem !important; }

/* --- Decorations --- */
.tagline { position: absolute; top: 150px; font-size: 10px; letter-spacing: 0.32em; line-height: 16.5px; color: var(--text-4); }
.tagline.left { left: 66px; }
.tagline.left::before { content: ""; position: absolute; left: -23px; top: 2px; width: 2px; height: 92px; background: linear-gradient(180deg, rgba(150, 255, 210, 0.7), rgba(var(--accent-rgb), 0.15)); }
.tagline.left::after { content: ""; position: absolute; left: -10px; top: 105px; width: 15px; height: 1px; background: rgba(150, 255, 210, 0.7); }
.tagline.right { right: 57px; top: 158px; text-align: right; }
.tagline.right::after { content: ""; position: absolute; right: -18px; top: 4px; width: 1px; height: 40px; background: rgba(150, 255, 210, 0.5); }
.footer {
  position: absolute; z-index: 1; left: 33px; right: 36px; bottom: 24px;
  display: grid; grid-template-columns: 1fr auto 1fr; align-items: end; pointer-events: none;
}
.node { display: flex; align-items: center; gap: 20px; }
.node-icon {
  width: 40px; height: 40px; box-sizing: border-box; display: grid; place-items: center; border-radius: 50%;
  border: 1.5px solid rgba(160, 255, 215, 0.3); background: rgba(3, 22, 18, 0.8); color: var(--accent);
}
.node-icon :deep(svg) { width: 18px; height: 18px; }
.node-text { display: flex; flex-direction: column; font-size: 11px; letter-spacing: 0.16em; line-height: 16px; color: var(--text-2); }
.node-state { font-size: 11px; font-weight: 600; letter-spacing: 0.22em; color: var(--accent); }
.node-state.off { color: var(--danger); }
.motto { display: flex; flex-direction: column; align-items: center; gap: 16px; font-size: 11.5px; letter-spacing: 0.5em; padding-left: 0.5em; color: var(--text-1); }
.motto .bar { width: 36px; height: 2px; margin-left: -0.5em; background: rgba(244, 255, 250, 0.8); }
.tagline-foot { justify-self: end; text-align: right; font-size: 11px; letter-spacing: 0.28em; line-height: 15px; color: var(--text-4); }
@media (max-width: 1299px) { .tagline { display: none; } }

/* --- Compact hub --- */
.compact .body { margin: 0; }
.compact-body {
  width: 100%; box-sizing: border-box; padding: 16px 16px 20px; transform: scale(var(--fit, 1)); transform-origin: top center;
  display: flex; flex-wrap: wrap; justify-content: center; align-items: center; gap: 16px 48px;
}
.compact-orb { position: relative; width: 320px; height: 320px; display: grid; place-items: center; flex: none; }
.compact-orb::before {
  content: ""; position: absolute; inset: 36px; border-radius: 50%;
  border: 1.3px solid rgba(150, 255, 210, 0.5); box-shadow: 0 0 0 10px rgba(var(--accent-rgb), 0.06);
}
.orbit-link {
  position: absolute; left: calc(50% - 23px); top: calc(50% - 23px); width: 46px; height: 46px; box-sizing: border-box;
  display: grid; place-items: center; border-radius: 50%; border: 1.5px solid rgba(var(--accent-rgb), 0.6);
  background: rgba(3, 24, 19, 0.95); color: var(--accent);
}
.orbit-link :deep(svg) { width: 20px; height: 20px; }
.compact-side { width: min(560px, 100%); display: flex; flex-direction: column; gap: 18px; }
.compact-side .pill { align-self: flex-start; }
.eyebrow { margin: 0 0 -8px; font-size: 11px; font-weight: 400; letter-spacing: 0.3em; color: var(--text-5); }
.grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.grid .jv-card:last-child { grid-column: span 2; }
.compact .footer, .compact .tagline { display: none; }

/* --- Chat overlay --- */
.console-overlay {
  position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 24px;
  background: rgba(1, 9, 10, 0.82);
}
.console-frame {
  position: relative; width: min(780px, 100%); height: min(78vh, 720px);
  border-radius: var(--r-26); border: 1.5px solid var(--line-a55); background: var(--panel-bg);
  box-shadow: 0 0 40px rgba(var(--accent-rgb), 0.2);
}
.console-close {
  position: absolute; top: 12px; right: 14px; z-index: 3; width: 36px; height: 36px; padding: 0;
  border-radius: 50%; border: 1px solid var(--line-a30); background: transparent; color: var(--text-2);
  font-size: 22px; line-height: 1; font-weight: 400;
}
.console-frame :deep(.stack) { left: 24px; right: 24px; top: 56px; bottom: 132px; width: auto; justify-content: flex-end; }
.console-frame :deep(.transcript) { max-height: none; min-height: 0; flex: 0 1 auto; }
.console-frame :deep(.zone) { width: 100%; height: auto; padding: 0 24px 24px; }
.console-frame :deep(.peek) { display: none; }
.console-frame :deep(.row) { flex-wrap: wrap; }
</style>
