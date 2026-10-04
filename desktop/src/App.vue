<script setup lang="ts">
import { onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAppStore } from "./stores/app";
import AppLock from "./components/AppLock.vue";
import UnlockApprovals from "./components/UnlockApprovals.vue";
import PairingApprovals from "./components/PairingApprovals.vue";
import { locked, initLock, noteActivity } from "./lock";
import { startApprovalPolling, stopApprovalPolling } from "./unlockApprovals";
import { startPairingPolling, stopPairingPolling } from "./pairingApprovals";
import { maybeStartWake, stopWake } from "./voicewake";
import { currentAuthStatus } from "./auth";
import { loadHomeNodeConfig } from "./homeNode";
import { scheduleAutomaticUpdateCheck, startUpdateSync } from "./updates";
import { thinking } from "./assistant";
import { busy as voiceCheckBusy } from "./voiceServer";

const route = useRoute();
const router = useRouter();
const app = useAppStore();

// Ctrl/⌘K opens the chat overlay from anywhere; it lives on the hub.
function onShortcut(event: KeyboardEvent) {
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
  await initLock();
  startApprovalPolling(); // this device can approve other devices' unlocks
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
    <main class="content">
      <RouterView />
    </main>

    <!-- Incoming unlock approvals for other devices (phone side). -->
    <UnlockApprovals />
    <PairingApprovals />
    <!-- Full-screen biometric gate on desktop when the lock is enabled. -->
    <AppLock v-if="locked" />
  </div>
</template>
