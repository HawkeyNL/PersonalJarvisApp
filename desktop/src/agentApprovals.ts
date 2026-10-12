// Agent actions waiting for the owner (ADR-029 4b). Core accepts approvals
// only from a phone; this app can deny, which only cancels and needs no
// signature.
import { currentAuthStatus } from "./auth";
import { postJsonAuth } from "./api";

export type PendingAction = {
  pending_id: string; action: string; preview: string; nonce: string; created_at: string;
};

async function requireAuth(): Promise<void> {
  if (!(await currentAuthStatus()).authenticated) throw new Error("Not signed in");
}

export async function denyAction(item: PendingAction): Promise<void> {
  await requireAuth();
  await postJsonAuth(`/v1/agent/pending/${encodeURIComponent(item.pending_id)}/deny`, {});
}
