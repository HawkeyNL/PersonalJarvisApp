// Pending device pairing approvals. Approving (macOS only) passes only the
// request id: the native layer re-fetches the request, checks its key
// fingerprint, shows it in the Touch ID prompt, signs the pairing protocol and
// posts the approval. Other desktops can only deny.
import { ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { currentAuthStatus } from "./auth";
import { getJsonAuth, postJsonAuth } from "./api";
import { createSerialPoller } from "./serialPoller";
import { verifiedFingerprint } from "./pairingFingerprint";

export type PairingRequest = {
  id: string;
  device_name: string;
  platform: string;
  fingerprint: string;
  nonce: string;
  candidate_public_key: string;
  created_at: number;
  expires_at: number;
  // Absent from Cores before the pairing-details release.
  fingerprint_full?: string | null;
  // Unauthenticated candidate hints; null when unknown.
  ip?: string | null;
  user_agent?: string | null;
  app_version?: string | null;
  model?: string | null;
};
/** A request plus the fingerprint computed here from its candidate key, or
 *  null when the server's fingerprint disagrees (approval is then refused). */
export type CheckedPairingRequest = PairingRequest & { verified_fingerprint: string | null };
export const pairingRequests = ref<CheckedPairingRequest[]>([]);
export const pairingError = ref<string | null>(null);
// Core allows five pairing-list reads per source IP per minute. Leave room for
// manual/other-device requests and never overlap requests across app restarts.
const pairingPoller = createSerialPoller(refresh, 60_000);

async function refresh(): Promise<boolean> {
  const status = await currentAuthStatus();
  if (!status.authenticated) return false;
  try {
    const response = await getJsonAuth<{ requests: PairingRequest[] }>("/v1/auth/pairing/requests");
    pairingRequests.value = await Promise.all(response.requests.map(async (request) => ({
      ...request,
      verified_fingerprint: await verifiedFingerprint(request).catch(() => null),
    })));
    return true;
  } catch { return false; }
}

export async function approvePairing(request: PairingRequest): Promise<void> {
  const status = await currentAuthStatus();
  if (!status.authenticated) throw new Error("not signed in");
  pairingError.value = null;
  try {
    await invoke("auth_approve_pairing", { requestId: request.id });
    pairingRequests.value = pairingRequests.value.filter((item) => item.id !== request.id);
  } catch (error) { pairingError.value = error instanceof Error ? error.message : String(error); }
}

export async function denyPairing(request: PairingRequest): Promise<void> {
  const status = await currentAuthStatus();
  if (!status.authenticated) throw new Error("not signed in");
  await postJsonAuth(`/v1/auth/pairing/requests/${request.id}/deny`, {});
  pairingRequests.value = pairingRequests.value.filter((item) => item.id !== request.id);
}

export function startPairingPolling(): void {
  pairingPoller.start();
}
export function stopPairingPolling(): void { pairingPoller.stop(); }
