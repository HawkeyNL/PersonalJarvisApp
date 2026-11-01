// Devices table rows from GET /v1/devices (id, name, platform, status,
// created_at). Pure, so it is unit-tested without Vue or Tauri.
import { relativeTime } from "./hubModel.ts";

/** Platforms whose device key signs approvals; mirrors Core's APPROVER_PLATFORMS. */
export const APPROVER_PLATFORMS = ["ios", "android", "macos"];

export type Device = { id: string; name: string; platform: string; status: string; created_at?: number };

export type DeviceRow = {
  id: string;
  name: string;
  platform: string;
  icon: "phone" | "monitor";
  /** The list has no last-seen time, only when the device was linked. */
  linked: string;
  active: boolean;
  status: string;
  isThis: boolean;
  /** Only a Mac revokes (with Touch ID), and never itself: that is Unlink in
   *  Settings, which also erases the local key. Unknown own id: nothing. */
  canRevoke: boolean;
  /** Core refuses to revoke the last active approver; say so instead of offering it. */
  lastApprover: boolean;
};

const PLATFORM_LABELS: Record<string, string> = { macos: "macOS", ios: "iOS", android: "Android", windows: "Windows", linux: "Linux" };

export function platformLabel(platform: string): string {
  return PLATFORM_LABELS[platform] ?? platform;
}

export function deviceRows(devices: Device[], thisDevice: string | null, signer: boolean, now: number): DeviceRow[] {
  const approvers = devices.filter((d) => d.status === "active" && APPROVER_PLATFORMS.includes(d.platform)).length;
  return devices.map((d) => {
    const active = d.status === "active";
    const isThis = d.id === thisDevice;
    const lastApprover = active && APPROVER_PLATFORMS.includes(d.platform) && approvers < 2;
    return {
      id: d.id,
      name: d.name,
      platform: platformLabel(d.platform),
      icon: d.platform === "ios" || d.platform === "android" ? "phone" : "monitor",
      linked: typeof d.created_at === "number" ? relativeTime(new Date(d.created_at * 1000).toISOString(), now) : "",
      active,
      status: active ? "Active" : d.status,
      isThis,
      canRevoke: signer && thisDevice !== null && active && !isThis && !lastApprover,
      lastApprover,
    };
  });
}

/** Why a revoke did not finish; Core answers 409 for the last approver. */
export function revokeFailureText(error: unknown): string {
  const status = (error as { status?: unknown } | null)?.status;
  if (status === 409) return "This is your last approver device. Pair another iOS, Android or macOS device first.";
  return "Revocation not completed. Confirm with Touch ID and try again.";
}
