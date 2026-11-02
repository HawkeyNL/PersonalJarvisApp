// Pure app-lock decisions, kept apart from lock.ts so they can be tested.
import type { Platform } from "./platform";

/** How this desktop locks: a local OS prompt, the account password, or not at all. */
export type LockMode = "biometric" | "password" | "off";

export function lockModeFor(platform: Platform): LockMode {
  if (platform === "macos") return "biometric";
  // Windows Hello is not used: its prompt did not get keyboard input and
  // could leave modifier keys stuck. Windows signs in with the account password.
  if (platform === "windows") return "password";
  return "off";
}

export type DeviceState = {
  device_id: string | null;
  authenticated: boolean;
  has_key: boolean;
};

/** Set up means a live session, or an enrolled device that still holds its
 *  key. A saved Home Node address alone is not set up. */
export function isSetUp(state: DeviceState): boolean {
  return state.authenticated || (state.device_id !== null && state.has_key);
}

/** Something to lock: a set-up device, or for the password lock a live session
 *  to end (without one the sign-in screen already asks for the password).
 *  Unreadable device state (null) counts as something to lock: fail closed. */
export function hasSomethingToLock(mode: LockMode, state: DeviceState | null): boolean {
  if (state === null) return true;
  return mode === "password" ? state.authenticated : isSetUp(state);
}

/** Lock at launch only when there is something to protect. The password lock
 *  cannot be switched off. */
export function shouldLockOnLaunch(input: {
  mode: LockMode;
  enabled: boolean;
  alreadyUnlocked: boolean;
  setUp: boolean;
}): boolean {
  if (input.mode === "off" || input.alreadyUnlocked || !input.setUp) return false;
  return input.mode === "password" || input.enabled;
}

/** Global shortcuts (Ctrl/⌘K) act only in the app shell, never behind the
 *  app lock: the shell under the lock takes no input at all. */
export function acceptsShortcuts(mode: string, locked: boolean): boolean {
  return mode === "app" && !locked;
}

/** What to do with a pending Ctrl/⌘K request to focus the chat composer. It
 *  waits while the session is checked and the chat mounts. It is dropped while
 *  locked, and when sign-in fails or waits for approval, so a later unlock or
 *  sign-in never pulls focus unless the user asks again. */
export function composerFocus(input: { locked: boolean; auth: string; chatReady: boolean }): "focus" | "wait" | "drop" {
  if (input.locked || (input.auth !== "checking" && input.auth !== "in")) return "drop";
  return input.chatReady ? "focus" : "wait";
}
