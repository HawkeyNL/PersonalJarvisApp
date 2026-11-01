// App-lock: a privacy screen in front of the desktop app. It signs nothing.
//
// macOS unlocks with the local OS prompt: Touch ID, or the computer password
// as fallback. It is on by default and can be switched off in Settings.
// Windows never uses Windows Hello: locking ends the session, and unlocking is
// signing in again with the account password. That lock cannot be switched off.
// Linux has no prompt, so the lock is off there.
//
// Only a set-up device locks. A fresh install goes straight to first run.
import { computed, ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { currentAuthStatus, logout } from "./auth";
import { initPlatformCapabilities, platformCapabilities } from "./platform";
import { isSetUp, lockModeFor, shouldLockOnLaunch } from "./lockPolicy";

export const locked = ref(false); // resolved by initLock()
watch(locked, (value) => {
  if (value) void invoke("revoke_model_authorization").catch(() => {
    // Fail closed if native revocation cannot be acknowledged.
    window.location.reload();
  });
}, { flush: "sync" });
export const unlocking = ref(false);
export const lockError = ref<string | null>(null);
export const lockMode = computed(() => lockModeFor(platformCapabilities.value.platform));
/** The local OS prompt lock, which Settings can switch off. */
export const lockAvailable = computed(() => lockMode.value === "biometric");
const lockActive = () =>
  lockMode.value === "password" || (lockAvailable.value && lockEnabled.value);

const LKEY = "jarvis.lock.enabled";
export const lockEnabled = ref(localStorage.getItem(LKEY) !== "false");

// Once unlocked, stay unlocked for the rest of this window session. sessionStorage
// survives hot-reloads and page reloads but clears when the app is closed, so a
// dev code-save never re-prompts for Touch ID, while a fresh launch still locks.
const SESSION_UNLOCKED = "jarvis.unlocked";

// Auto re-lock after this much time without pointer or keyboard input (ms).
const INACTIVITY_MS = 15 * 60 * 1000;
let idleTimer: number | undefined;

/** Decide whether to lock at launch. A Windows lock ends the session at once,
 *  so the next screen asks for the account password. */
export async function initLock(): Promise<void> {
  await initPlatformCapabilities();
  const alreadyUnlocked = sessionStorage.getItem(SESSION_UNLOCKED) === "1";
  // Fail closed: unreadable device state counts as set up.
  const setUp = await currentAuthStatus().then(isSetUp, () => true);
  locked.value = shouldLockOnLaunch({
    mode: lockMode.value, enabled: lockEnabled.value, alreadyUnlocked, setUp,
  });
  if (locked.value && lockMode.value === "password") await signOutFromLock();
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
  if (!lockActive()) return;
  sessionStorage.removeItem(SESSION_UNLOCKED);
  locked.value = true;
  if (lockMode.value === "password") void signOutFromLock();
}

/** Local unlock: biometrics, or the OS password where the OS offers it. */
export async function biometricUnlock(): Promise<boolean> {
  if (unlocking.value || !lockAvailable.value) return false;
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
  if (!INACTIVITY_MS || !lockActive()) return;
  clearTimeout(idleTimer);
  idleTimer = window.setTimeout(() => lockApp(), INACTIVITY_MS);
}

/** Reset the inactivity timer on user interaction while unlocked. */
export function noteActivity(): void {
  if (!locked.value) armIdle();
}
