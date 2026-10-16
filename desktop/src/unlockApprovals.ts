// Unlock approvals. This device long-polls for pending unlock requests from
// the user's other devices. Approving (macOS only) first shows three numbers
// the native layer builds from the request it re-fetches by id; the owner
// picks the one the other device shows. Native code checks the pick: a wrong
// one denies the request; the right one asks for Touch ID (no password
// fallback), signs the v1 unlock approval with the device key and posts it.
// Other desktops can only deny.
import { ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { currentAuthStatus } from "./auth";
import { getJsonAuth, postJsonAuth } from "./api";

export interface UnlockReq {
  id: string;
  device_name: string;
  platform: string;
  nonce: string;
  created_at: number;
}

export const pending = ref<UnlockReq[]>([]);
export const approving = ref<string | null>(null);
export const approvalError = ref<string | null>(null);
// The request the owner is picking a number for, and its three choices.
export const picking = ref<{ id: string; choices: number[] } | null>(null);

let polling = false;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Fetch pending requests. `wait` (secs) long-polls: returns as soon as one
 *  appears, or after the timeout empty. Returns whether the fetch succeeded. */
async function fetchPending(wait: number): Promise<boolean> {
  const status = await currentAuthStatus();
  if (!status.authenticated) {
    pending.value = [];
    return false;
  }
  try {
    const res = await getJsonAuth<{ requests: UnlockReq[] }>(
      `/v1/auth/unlock/pending?wait=${wait}`,
    );
    pending.value = res.requests;
    return true;
  } catch {
    return false; // transient — keep the last snapshot
  }
}

export function refreshPending(): Promise<boolean> {
  return fetchPending(0);
}

/** Show the three numbers for this request; approval waits for a pick. */
export async function approve(req: UnlockReq): Promise<void> {
  if (approving.value) return;
  approvalError.value = null;
  picking.value = null;
  approving.value = req.id;
  try {
    const status = await currentAuthStatus();
    if (!status.authenticated) throw new Error("not signed in");
    const choices = await invoke<number[]>("auth_unlock_choices", { requestId: req.id });
    picking.value = { id: req.id, choices };
  } catch (e) {
    approvalError.value = e instanceof Error ? e.message : String(e);
  } finally {
    approving.value = null;
  }
}

/** The owner's pick. Native code denies on a wrong one, else prompts and signs. */
export async function pick(id: string, choice: number): Promise<void> {
  if (approving.value || picking.value?.id !== id) return;
  approvalError.value = null;
  picking.value = null;
  approving.value = id;
  try {
    await invoke("auth_approve_unlock", { requestId: id, choice });
    pending.value = pending.value.filter((r) => r.id !== id);
  } catch (e) {
    approvalError.value = e instanceof Error ? e.message : String(e);
    await refreshPending(); // a wrong pick denied it
  } finally {
    approving.value = null;
  }
}

export async function deny(req: UnlockReq): Promise<void> {
  approvalError.value = null;
  try {
    const status = await currentAuthStatus();
    if (!status.authenticated) throw new Error("not signed in");
    await postJsonAuth(`/v1/auth/unlock/${req.id}/deny`, {});
    pending.value = pending.value.filter((r) => r.id !== req.id);
    if (picking.value?.id === req.id) picking.value = null;
  } catch (e) {
    approvalError.value = e instanceof Error ? e.message : String(e);
  }
}

export function startApprovalPolling(): void {
  if (polling) return;
  polling = true;
  (async () => {
    while (polling) {
      const ok = await fetchPending(20);
      if (!ok) await sleep(1500); // network error — back off
      else if (pending.value.length) await sleep(3000); // already showing one — poll gently
      // else: empty — the long-poll already held ~20s, so loop straight away
    }
  })();
}

export function stopApprovalPolling(): void {
  polling = false;
}
