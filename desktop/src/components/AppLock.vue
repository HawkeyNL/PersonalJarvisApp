<script setup lang="ts">
import { onMounted, ref } from "vue";
import { biometricUnlock, lockMode, unlocking, lockError, signOutFromLock } from "../lock";

// Locking only shows this screen. The OS prompt starts when the owner presses
// a button, so an idle lock never pops a dialog over other apps. Focus moves
// to this dialog, off the (inert) app behind it; Enter alone starts nothing.
const dialog = ref<HTMLElement | null>(null);
onMounted(() => dialog.value?.focus());
</script>

<template>
  <div ref="dialog" class="lock" role="dialog" aria-modal="true" aria-labelledby="lock-title" aria-describedby="lock-sub" tabindex="-1">
    <div class="lock-card card">
      <span class="tile acc mark" aria-hidden="true">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="3.5" /><circle cx="12" cy="12" r="9" /></svg>
      </span>
      <h1 id="lock-title">Jarvis is locked</h1>
      <!-- Windows: the session is ending; the sign-in screen asks for the account password. -->
      <template v-if="lockMode === 'password'">
        <p id="lock-sub" class="sub">Sign in again with your account password.</p>
        <button type="button" class="btn btn-primary btn-lg btn-block primary" :disabled="unlocking" @click="signOutFromLock">
          {{ unlocking ? "Signing out…" : "Sign in" }}
        </button>
      </template>
      <template v-else>
        <p id="lock-sub" class="sub">Unlock with Touch ID or your computer password.</p>

        <button type="button" class="btn btn-primary btn-lg btn-block primary" :disabled="unlocking" @click="biometricUnlock">
          {{ unlocking ? "Verifying…" : "Unlock" }}
        </button>

        <button type="button" class="btn btn-secondary btn-block" :disabled="unlocking" @click="signOutFromLock">Sign out instead</button>
        <p class="hint t-xs t-2">Signing in again needs your account password.</p>
      </template>

      <p v-if="lockError" class="err" role="alert">{{ lockError }}</p>
    </div>
  </div>
</template>

<style scoped>
.lock {
  position: fixed;
  inset: 0;
  z-index: 400; /* above everything in the app, the skip link included */
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--bg-page);
}
.lock:focus {
  outline: none;
}
.lock-card {
  width: min(400px, 100%);
  padding: 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}
.mark { width: 52px; height: 52px; border-radius: var(--r-lg); margin-bottom: 4px; }
h1 { margin: 0; font-size: 22px; }
p { margin: 0; }
.sub { margin-bottom: 8px; color: var(--text-secondary); font-size: 14px; }
.err { justify-content: center; margin-top: 4px; overflow-wrap: anywhere; }
</style>
