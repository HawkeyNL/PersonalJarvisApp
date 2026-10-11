// Pending device pairing approvals. The local OS authenticates the owner before
// the native layer signs the narrowly-scoped pairing protocol.
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
  if (!status.authenticated || !status.device_id) throw new Error("not signed in");
  pairingError.value = null;
  try {
    // Recompute from the exact key that is signed; never trust the list state.
    await verifiedFingerprint(request);
    const signature = await invoke<string>("auth_sign_pairing_approval", {
      candidateName: request.device_name,
      requestId: request.id, nonceHex: request.nonce,
      candidatePublicKeyHex: request.candidate_public_key,
      userId: await ownerId(), approverDeviceId: status.device_id, expiresAt: request.expires_at,
    });
    await postJsonAuth(`/v1/auth/pairing/requests/${request.id}/approve`, { signature });
    pairingRequests.value = pairingRequests.value.filter((item) => item.id !== request.id);
  } catch (error) { pairingError.value = error instanceof Error ? error.message : String(error); }
}

// The API intentionally owns the user binding. Devices learn it from the
// authenticated session endpoint rather than a candidate-supplied field.
async function ownerId(): Promise<string> {
  const response = await getJsonAuth<{ user_id: string }>("/v1/auth/me");
  return response.user_id;
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
