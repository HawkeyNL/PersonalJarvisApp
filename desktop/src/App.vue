<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAppStore } from "./stores/app";
import AppLock from "./components/AppLock.vue";
import AppSidebar from "./components/AppSidebar.vue";
import UnlockApprovals from "./components/UnlockApprovals.vue";
import PairingApprovals from "./components/PairingApprovals.vue";
import { locked, shownShellMode as mode, initLock, noteActivity } from "./lock";
import { startApprovalPolling, stopApprovalPolling } from "./unlockApprovals";
import { startPairingPolling, stopPairingPolling } from "./pairingApprovals";
import { maybeStartWake, stopWake } from "./voicewake";
import { currentAuthStatus, currentShellMode } from "./auth";
import { loadHomeNodeConfig } from "./homeNode";
import { scheduleAutomaticUpdateCheck, startUpdateSync } from "./updates";
import { thinking } from "./assistant";
import { busy as voiceCheckBusy } from "./voiceServer";

const route = useRoute();
const router = useRouter();
const app = useAppStore();

// Before a Home Node is set and a session exists, only the setup/sign-in
// screen shows. Leaving the app (sign-out, expired session) returns there.
watch(currentShellMode, (value, previous) => {
  if (value === "app") {
    app.notice = null;
  } else if (previous === "app") {
    app.consoleOpen = false;
    void router.replace("/");
  }
});

// Skip link: focus the page itself, past the sidebar. A plain #main jump would
// also be read by the router as a navigation.
const main = ref<HTMLElement | null>(null);
const skipToContent = () => main.value?.focus();

// Ctrl/⌘K opens the chat overlay from anywhere; it lives on the hub.
function onShortcut(event: KeyboardEvent) {
  if (mode.value !== "app") return;
  if (event.key.toLowerCase() !== "k" || !(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return;
  event.preventDefault();
  app.consoleOpen = true;
  if (route.path !== "/") void router.push("/");
}

onMounted(async () => {
  window.addEventListener("keydown", onShortcut);
  startUpdateSync({ reply: () => thinking.value, voiceCheck: () => voiceCheckBusy.value });
  // Configuration/auth reads are local, and the delayed network update check
  // is deliberately detached so startup and ordinary Jarvis use never wait.
  void (async () => {
    try {
      const config = await loadHomeNodeConfig();
      const auth = await currentAuthStatus();
      scheduleAutomaticUpdateCheck(config.configured, auth.authenticated);
    } catch {
      // Missing/corrupt local update state is surfaced passively in Settings.
    }
  })();
  if (await initLock()) return; // Windows sign-out reload is under way
  startApprovalPolling(); // other devices' unlock requests (a Mac approves them)
  startPairingPolling();
  maybeStartWake(); // resume "Hey Jarvis" if it was enabled
  window.addEventListener("pointerdown", noteActivity, { passive: true });
  window.addEventListener("keydown", noteActivity);
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onShortcut);
  stopApprovalPolling();
  stopPairingPolling();
  stopWake();
  window.removeEventListener("pointerdown", noteActivity);
  window.removeEventListener("keydown", noteActivity);
});
</script>

<template>
  <div class="app">
    <a v-if="mode === 'app'" class="skip" href="#main" @click.prevent="skipToContent">Skip to content</a>
    <p v-if="mode !== 'app' && app.notice" class="notice" role="status">
      {{ app.notice }} <button type="button" @click="app.notice = null">Dismiss</button>
    </p>
    <div class="shell">
      <AppSidebar v-if="mode === 'app'" />
      <main id="main" ref="main" class="content" tabindex="-1">
        <RouterView />
      </main>
    </div>

    <!-- Other devices' unlock and pairing requests; a Mac approves them. -->
    <UnlockApprovals />
    <PairingApprovals />
    <!-- Full-screen lock: Touch ID on macOS; on Windows the session ends and sign-in needs the account password. -->
    <AppLock v-if="locked" />
  </div>
</template>

<style scoped>
.shell { flex: 1; min-height: 0; display: flex; }
.shell > .content { min-width: 0; }
.content:focus { outline: none; }
.notice { position: relative; z-index: 2; margin: 0; padding: 10px 16px; background: var(--bg-surface); color: var(--text-primary); text-align: center; }
/* Hidden until focused; the first Tab stop on every page. */
.skip {
  position: absolute; left: 12px; top: 12px; z-index: 300; padding: 8px 14px; border-radius: var(--r-md);
  background: var(--bg-surface); color: var(--text-primary); box-shadow: var(--shadow-pop); text-decoration: none;
  transform: translateY(calc(-100% - 16px));
}
.skip:focus-visible { transform: none; }
</style>
