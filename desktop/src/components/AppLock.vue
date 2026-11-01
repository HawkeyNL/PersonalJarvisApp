<script setup lang="ts">
import { biometricUnlock, lockMode, unlocking, lockError, signOutFromLock } from "../lock";

// Locking only shows this screen. The OS prompt starts when the owner presses
// a button, so an idle lock never pops a dialog over other apps.
</script>

<template>
  <div class="lock">
    <div class="card">
      <div class="mark">
        <span class="ring"></span>
        <span class="glyph">J</span>
      </div>
      <h1>Jarvis is locked</h1>
      <!-- Windows: the session is ending; the sign-in screen asks for the account password. -->
      <template v-if="lockMode === 'password'">
        <p class="sub">Sign in again with your account password.</p>
        <button class="primary" @click="signOutFromLock">Sign in</button>
      </template>
      <template v-else>
        <p class="sub">Unlock with Touch ID or your computer password.</p>

        <button class="primary" :disabled="unlocking" @click="biometricUnlock">
          {{ unlocking ? "Verifying…" : "Unlock" }}
        </button>

        <button class="ghost" :disabled="unlocking" @click="signOutFromLock">Sign out instead</button>
        <p class="hint">Signing in again needs your account password.</p>
      </template>

      <p v-if="lockError" class="err">{{ lockError }}</p>
    </div>
  </div>
</template>

<style scoped>
.lock {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--bg-page);
  backdrop-filter: blur(26px) saturate(1.2);
  -webkit-backdrop-filter: blur(26px) saturate(1.2);
}
.card {
  width: min(360px, 92vw);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}
.mark {
  position: relative;
  width: 78px;
  height: 78px;
  display: grid;
  place-items: center;
  margin-bottom: 4px;
}
.mark .ring {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  border: 1.5px solid var(--accent);
  box-shadow: 0 0 26px rgba(var(--accent-rgb), 0.4), inset 0 0 18px rgba(var(--accent-rgb), 0.25);
  animation: pulse 2.6s ease-in-out infinite;
}
.mark .glyph {
  font-family: var(--mono);
  font-size: 30px;
  font-weight: 700;
  color: var(--accent);
  text-shadow: 0 0 14px rgba(var(--accent-rgb), 0.6);
}
h1 {
  font-size: 19px;
  margin: 0;
  letter-spacing: 0.02em;
}
.sub {
  margin: 0 0 6px;
  color: var(--muted);
  font-size: 13px;
}

.primary,
.ghost {
  width: 100%;
  border-radius: 12px;
  padding: 12px 16px;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}
.primary {
  background: var(--accent-solid);
  color: var(--text-on-accent);
  border: none;
}
.primary:disabled {
  opacity: 0.6;
  cursor: default;
}
.ghost {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text);
  font-weight: 500;
}
.ghost:hover {
  border-color: var(--accent);
}

.ghost:disabled {
  opacity: 0.6;
  cursor: default;
}
.hint {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}
.err {
  color: var(--danger-text);
  font-size: 12px;
  margin: 4px 0 0;
}

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 18px rgba(var(--accent-rgb), 0.28), inset 0 0 14px rgba(var(--accent-rgb), 0.2); }
  50% { box-shadow: 0 0 34px rgba(var(--accent-rgb), 0.55), inset 0 0 22px rgba(var(--accent-rgb), 0.32); }
}
@media (prefers-reduced-motion: reduce) {
  .mark .ring {
    animation: none;
  }
}
</style>
