// Agent actions waiting for the owner (ADR-029 4b). Approving uses the same
// proof as an unlock approval: a local OS check (biometrics, passcode
// fallback), then this device's key signs the action's nonce. Denying only
// cancels and needs no signature.
import { invoke } from "@tauri-apps/api/core";
import { currentAuthStatus } from "./auth";
import { postJsonAuth } from "./api";

export type PendingAction = { pending_id: string; action: string; preview: string; nonce: string; created_at: string };

async function requireAuth(): Promise<void> {
  if (!(await currentAuthStatus()).authenticated) throw new Error("Not signed in");
}

export async function approveAction(item: PendingAction): Promise<void> {
  await requireAuth();
  await invoke("biometric_unlock", { reason: `Approve agent action: ${item.action}`, allowPassword: true });
  const signature = await invoke<string>("auth_sign", { nonceHex: item.nonce });
  await postJsonAuth(`/v1/agent/pending/${encodeURIComponent(item.pending_id)}/approve`, { signature });
}

export async function denyAction(item: PendingAction): Promise<void> {
  await requireAuth();
  await postJsonAuth(`/v1/agent/pending/${encodeURIComponent(item.pending_id)}/deny`, {});
}
