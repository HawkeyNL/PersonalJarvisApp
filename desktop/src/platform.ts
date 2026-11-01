import { computed, readonly, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";

export type Platform = "linux" | "macos" | "windows" | "unknown";

export interface PlatformCapabilities {
  platform: Platform;
  supportsBiometrics: boolean;
  /** Touch ID without a password fallback: the only desktop that may sign. */
  supportsBiometricSigning: boolean;
  supportsWakeWord: boolean;
  supportsBackgroundAudio: boolean;
  supportsNotifications: boolean;
  supportsLocalSecureStorage: boolean;
  supportsDesktopWindowFeatures: boolean;
}

export function capabilitiesFor(platform: Platform): PlatformCapabilities {
  const desktop = platform !== "unknown";
  return {
    platform,
    // Windows Hello is off; Windows unlocks with the account password.
    supportsBiometrics: platform === "macos",
    supportsBiometricSigning: platform === "macos",
    supportsWakeWord: desktop,
    supportsBackgroundAudio: false,
    supportsNotifications: false,
    supportsLocalSecureStorage: desktop,
    supportsDesktopWindowFeatures: desktop,
  };
}

const current = ref<PlatformCapabilities>(capabilitiesFor("unknown"));
let initialization: Promise<PlatformCapabilities> | null = null;

export const platformCapabilities = readonly(current);
export const isDesktop = computed(() => current.value.supportsDesktopWindowFeatures);

export function initPlatformCapabilities(): Promise<PlatformCapabilities> {
  if (initialization) return initialization;
  initialization = invoke<{ platform: string }>("device_info")
    .then(({ platform }) => {
      const supported: Platform = ["linux", "macos", "windows"].includes(platform)
        ? (platform as Platform)
        : "unknown";
      current.value = capabilitiesFor(supported);
      return current.value;
    })
    .catch(() => current.value);
  return initialization;
}
