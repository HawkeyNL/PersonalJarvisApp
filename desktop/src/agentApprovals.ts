// Agent actions waiting for the owner (ADR-029 4b). Approving runs natively
// by id: the Rust command fetches the pending action from the Home Node, asks
// the OS to confirm it is the owner (biometrics, passcode fallback), signs the
// agent-approval-v1 message from that server data and posts it. Denying only
// cancels and needs no signature.
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
  await invoke("auth_approve_agent", { pendingId: item.pending_id });
}

export async function denyAction(item: PendingAction): Promise<void> {
  await requireAuth();
  await postJsonAuth(`/v1/agent/pending/${encodeURIComponent(item.pending_id)}/deny`, {});
}
