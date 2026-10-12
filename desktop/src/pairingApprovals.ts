// Pending device pairing requests. Core accepts approvals only from a phone,
// so this app shows them and can deny them.
import { ref } from "vue";
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
 *  null when the server's fingerprint disagrees. */
export type CheckedPairingRequest = PairingRequest & { verified_fingerprint: string | null };
export const pairingRequests = ref<CheckedPairingRequest[]>([]);
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
