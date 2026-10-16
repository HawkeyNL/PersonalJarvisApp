// App-lock: a privacy screen in front of the desktop app. It signs nothing.
//
// Unlock is the local OS prompt: biometrics, or the computer password the OS
// offers as fallback. Linux has no such prompt, so the lock is off there. If
// the prompt is unavailable (for example Windows Hello not set up), signing
// out ends the session; signing in again needs the account password.
//
// The lock is on by default where the OS prompt exists and can be switched
// off in Settings.
import { computed, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { logout } from "./auth";
import {
  initPlatformCapabilities,
  isDesktop,
  platformCapabilities,
} from "./platform";

export const locked = ref(false); // resolved by initLock()
watch(locked, (value) => {
  if (value) void invoke("revoke_model_authorization").catch(() => {
    // Fail closed if native revocation cannot be acknowledged.
    window.location.reload();
  });
}, { flush: "sync" });
export const unlocking = ref(false);
export const lockError = ref<string | null>(null);
/** Only where the OS offers a local unlock prompt. */
export const lockAvailable = computed(
  () => isDesktop.value && platformCapabilities.value.supportsBiometrics,
);

const LKEY = "jarvis.lock.enabled";
export const lockEnabled = ref(localStorage.getItem(LKEY) !== "false");

// Once unlocked, stay unlocked for the rest of this window session. sessionStorage
// survives hot-reloads and page reloads but clears when the app is closed, so a
// dev code-save never re-prompts for Touch ID, while a fresh launch still locks.
const SESSION_UNLOCKED = "jarvis.unlocked";

// Auto re-lock after this much time without pointer or keyboard input (ms).
const INACTIVITY_MS = 15 * 60 * 1000;
let idleTimer: number | undefined;

/** Decide whether this platform locks in-app (only with a local OS prompt). */
export async function initLock(): Promise<void> {
  await initPlatformCapabilities();
  const alreadyUnlocked = sessionStorage.getItem(SESSION_UNLOCKED) === "1";
  locked.value = lockAvailable.value && lockEnabled.value && !alreadyUnlocked;
}

export function setLockEnabled(v: boolean): void {
  lockEnabled.value = v;
  localStorage.setItem(LKEY, String(v));
  if (!v) unlockApp();
  else if (lockAvailable.value) locked.value = true;
}

export function unlockApp(): void {
  locked.value = false;
  lockError.value = null;
  sessionStorage.setItem(SESSION_UNLOCKED, "1"); // survive reloads this session
  armIdle();
}

export function lockApp(): void {
  if (lockAvailable.value && lockEnabled.value) {
    sessionStorage.removeItem(SESSION_UNLOCKED);
    locked.value = true;
  }
}

/** Local unlock: biometrics, or the OS password where the OS offers it. */
export async function biometricUnlock(): Promise<boolean> {
  if (unlocking.value) return false;
  unlocking.value = true;
  lockError.value = null;
  try {
    await invoke("biometric_unlock", { allowPassword: true });
    unlockApp();
    return true;
  } catch (e) {
    lockError.value = e instanceof Error ? e.message : String(e);
    return false;
  } finally {
    unlocking.value = false;
  }
}

/** Way out when the OS prompt is unavailable: end the session, then reload
 *  to the sign-in screen with no app state left in memory. Stays locked if
 *  the local session could not be cleared. */
export async function signOutFromLock(): Promise<void> {
  lockError.value = null;
  try {
    await logout();
  } catch (e) {
    lockError.value = e instanceof Error ? e.message : String(e);
    return;
  }
  unlockApp();
  window.location.reload();
}

function armIdle(): void {
  if (!INACTIVITY_MS || !lockAvailable.value || !lockEnabled.value) return;
  clearTimeout(idleTimer);
  idleTimer = window.setTimeout(() => lockApp(), INACTIVITY_MS);
}

/** Reset the inactivity timer on user interaction while unlocked. */
export function noteActivity(): void {
  if (!locked.value) armIdle();
}
