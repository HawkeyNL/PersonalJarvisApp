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
