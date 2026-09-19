<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { getJson, ApiError } from "../api";
import { currentAuthStatus, login, clearSession, listDevices, PairingPending, AccountPasswordRequired, AccountActivationRequired, bootstrapFirstDevice } from "../auth";
import { configureHomeNode, homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import ReactorCore from "../components/ReactorCore.vue";
import JarvisConsole from "../components/JarvisConsole.vue";
import { accountFailure } from "../accountFailure";

// The homepage is pure Jarvis: a living backdrop + a hover-reveal console.
// Backend health and device-bound login run silently in the background so the
// chat always has a valid session; there is no telemetry UI here anymore
// (System/Trading tabs own that).
const backend = ref<"unconfigured" | "checking" | "ok" | "fout">("checking");
const auth = ref<"checking" | "in" | "uit" | "wachten" | "fout">("checking");
const online = computed(() => backend.value === "ok");

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
  password.value = "";
  activationCode.value = "";
});
</script>

<template>
  <section class="jarvis-home">
    <!-- Full-bleed living backdrop. -->
    <div class="backdrop">
      <ReactorCore name="Jarvis" :active="online" />
    </div>
    <form v-if="backend === 'unconfigured'" class="connection-setup glass" @submit.prevent="saveHomeNode">
      <h2>Verbind met je Home Node</h2>
      <p>Voer de HTTPS-origin van je Home Node in. Jarvis bewaart alleen dit adres lokaal; geen credentials.</p>
      <label for="home-node-origin">Home Node-origin</label>
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
        {{ configBusy ? "Verbinden…" : "Verbinden en koppelen" }}
      </button>
      <p v-if="configError" class="config-error" role="alert">{{ configError }}</p>
      <p class="setup-hint">Lokale HTTP op localhost is uitsluitend toegestaan in een development-build.</p>
    </form>
    <form v-if="accountMode && backend === 'ok'" class="connection-setup glass" @submit.prevent="submitAccount">
      <h2>{{ accountMode === "activate" ? "Activeer je eerste apparaat" : "Aanmelden bij Jarvis" }}</h2>
      <p v-if="accountMode === 'activate'">Gebruik de eenmalige activatiecode van je Home Node en kies een accountwachtwoord van minimaal 15 tekens.</p>
      <label v-if="accountMode === 'activate'" for="activation-code">Activatiecode</label>
      <input v-if="accountMode === 'activate'" id="activation-code" v-model="activationCode" type="text" autocomplete="off" autocapitalize="off" :spellcheck="false" maxlength="256" required />
      <label for="account-password">Accountwachtwoord</label>
      <div class="password-input">
        <input id="account-password" v-model="password" :type="passwordVisible ? 'text' : 'password'" :autocomplete="accountMode === 'activate' ? 'new-password' : 'current-password'" autocapitalize="off" :spellcheck="false" :minlength="accountMode === 'activate' ? 15 : undefined" maxlength="1024" required />
        <button type="button" class="password-toggle" :aria-label="passwordVisible ? 'Wachtwoord verbergen' : 'Wachtwoord tonen'" :aria-pressed="passwordVisible" aria-controls="account-password" @click="passwordVisible = !passwordVisible">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/><path v-if="passwordVisible" d="m3 3 18 18"/></svg>
        </button>
      </div>
      <button type="submit" :disabled="accountBusy">{{ accountBusy ? "Bezig…" : "Doorgaan" }}</button>
      <p v-if="accountError" class="config-error" role="alert">{{ accountError }}</p>
    </form>
    <p v-if="auth === 'wachten'" class="pairing-wait">
      Wacht op goedkeuring vanaf een vertrouwd Jarvis-apparaat.
    </p>
    <p v-else-if="backend === 'fout'" class="connection-error" role="status">
      Home Node niet bereikbaar op {{ homeNodeConfig.origin }}. Controleer het netwerk en probeer opnieuw.
    </p>
    <!-- Floating conversation + hover-reveal input. -->
    <JarvisConsole v-if="auth === 'in'" />
  </section>
</template>

<style scoped>
.jarvis-home {
  position: relative;
  height: 100%;
  min-height: 100%;
}
.pairing-wait {
  position: relative;
  z-index: 1;
  margin: 2rem auto;
  max-width: 28rem;
  text-align: center;
}
.connection-error {
  position: relative;
  z-index: 3;
  width: min(34rem, calc(100% - 2rem));
  margin: 1rem auto;
  padding: 0.75rem 1rem;
  border: 1px solid rgba(248, 113, 113, 0.45);
  border-radius: 0.75rem;
  background: rgba(30, 8, 8, 0.78);
  color: #fecaca;
  text-align: center;
}
.connection-setup {
  position: relative;
  z-index: 4;
  width: min(32rem, calc(100% - 2rem));
  margin: 3rem auto;
  padding: 1.25rem;
}
.connection-setup h2 { margin-top: 0; }
.connection-setup label { display: block; margin: 1rem 0 0.4rem; }
.connection-setup input { width: 100%; box-sizing: border-box; margin-bottom: 0.8rem; }
.password-input { display: flex; align-items: stretch; gap: 8px; margin-bottom: .8rem; }
.password-input input { flex: 1; min-width: 0; margin-bottom: 0; }
.password-toggle { min-width: 44px; min-height: 44px; display: grid; place-items: center; }
.config-error { color: #fecaca; }
.setup-hint { color: var(--muted); font-size: 0.82rem; }

/* The core sits fixed behind everything (under the translucent top bar/dock),
   so the whole screen reads as one living surface. */
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 0;
  display: grid;
  place-items: center;
  --core-size: min(80vh, 780px);
  pointer-events: none;
}
.backdrop::before {
  content: "";
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 50% 44%, rgba(52, 245, 160, 0.1), transparent 60%),
    linear-gradient(rgba(52, 245, 160, 0.028) 1px, transparent 1px) 0 0 / 46px 46px,
    linear-gradient(90deg, rgba(52, 245, 160, 0.028) 1px, transparent 1px) 0 0 / 46px 46px;
  mask-image: radial-gradient(ellipse at 55% 45%, #000 25%, transparent 82%);
  -webkit-mask-image: radial-gradient(ellipse at 55% 45%, #000 25%, transparent 82%);
}
</style>
