// Agent actions waiting for the owner (ADR-029 4b). Approving runs natively:
// the Rust command asks the OS to confirm it is the owner (biometrics,
// passcode fallback), then signs the domain-separated agent-approval-v1
// message (pending id, nonce, action hash, this device). Denying only cancels
// and needs no signature.
import { invoke } from "@tauri-apps/api/core";
import { currentAuthStatus } from "./auth";
import { postJsonAuth } from "./api";
import { canSignApproval } from "./nodeModels";

export type PendingAction = {
  pending_id: string; action: string; preview: string; nonce: string; created_at: string;
  // Older Cores omit these; their actions cannot be approved from this app.
  action_sha256?: string; approval_message?: string;
};

async function requireAuth(): Promise<void> {
  if (!(await currentAuthStatus()).authenticated) throw new Error("Not signed in");
}

export async function approveAction(item: PendingAction): Promise<void> {
  if (!canSignApproval(item)) throw new Error("Requires newer Core");
  await requireAuth();
  const signature = await invoke<string>("auth_sign_agent_approval", {
    pendingId: item.pending_id,
    action: item.action,
    nonceHex: item.nonce,
    actionSha256Hex: item.action_sha256,
  });
  await postJsonAuth(`/v1/agent/pending/${encodeURIComponent(item.pending_id)}/approve`, { signature });
}

export async function denyAction(item: PendingAction): Promise<void> {
  await requireAuth();
  await postJsonAuth(`/v1/agent/pending/${encodeURIComponent(item.pending_id)}/deny`, {});
}
