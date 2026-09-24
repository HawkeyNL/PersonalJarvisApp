// Pending device pairing approvals. The local OS authenticates the owner before
// the native layer signs the narrowly-scoped pairing protocol.
import { ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { currentAuthStatus } from "./auth";
import { getJsonAuth, postJsonAuth } from "./api";
import { createSerialPoller } from "./serialPoller";

export type PairingRequest = {
  id: string;
  device_name: string;
  platform: string;
  fingerprint: string;
  nonce: string;
  candidate_public_key: string;
  created_at: number;
  expires_at: number;
};
export const pairingRequests = ref<PairingRequest[]>([]);
export const pairingError = ref<string | null>(null);
// Core allows five pairing-list reads per source IP per minute. Leave room for
// manual/other-device requests and never overlap requests across app restarts.
const pairingPoller = createSerialPoller(refresh, 60_000);

async function refresh(): Promise<boolean> {
  const status = await currentAuthStatus();
  if (!status.authenticated) return false;
  try {
    const response = await getJsonAuth<{ requests: PairingRequest[] }>("/v1/auth/pairing/requests");
    pairingRequests.value = response.requests;
    return true;
  } catch { return false; }
}

export async function approvePairing(request: PairingRequest): Promise<void> {
  const status = await currentAuthStatus();
  if (!status.authenticated || !status.device_id) throw new Error("niet ingelogd");
  pairingError.value = null;
  try {
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
  if (!status.authenticated) throw new Error("niet ingelogd");
  await postJsonAuth(`/v1/auth/pairing/requests/${request.id}/deny`, {});
  pairingRequests.value = pairingRequests.value.filter((item) => item.id !== request.id);
}

export function startPairingPolling(): void {
  pairingPoller.start();
}
export function stopPairingPolling(): void { pairingPoller.stop(); }
