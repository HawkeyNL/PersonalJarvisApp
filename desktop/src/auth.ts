// Client-side device-bound login.
//
// The private key and signing live in Rust (Tauri commands); this module only
// orchestrates the HTTP flow (enroll -> challenge -> login) and reads back the
// non-secret session state. The private key and bearer token never enter JS.
import { invoke } from "@tauri-apps/api/core";
import { chatSession } from "./chatSession";
import { getJson, getJsonAuth, getJsonWithHeaders, postAuth, postJson, postJsonAuth, postJsonWithHeaders } from "./api";
import { scheduleAutomaticUpdateCheck } from "./updates";

export type AuthStatus = {
  device_id: string | null;
  authenticated: boolean;
  has_key: boolean;
};

export type DeviceItem = {
  id: string;
  name: string;
  platform: string;
  status: string;
};

export function currentAuthStatus(): Promise<AuthStatus> {
  return invoke<AuthStatus>("auth_status");
}

const PAIRING_WAIT_KEY = "jarvis.pairing.wait";
type PairingWait = { request_id: string; nonce: string; expires_at: number };
export class PairingPending extends Error {
  constructor() { super("Wacht op goedkeuring vanaf een vertrouwd Jarvis-apparaat."); }
}

export type AccountStatus = { protocol: number; password_required: boolean; bootstrap_required: boolean };
export function accountStatus(): Promise<AccountStatus> {
  return getJson<AccountStatus>("/v1/auth/account/status");
}
export class AccountPasswordRequired extends Error {}
export class AccountActivationRequired extends Error {}

/// Log in with the local device key. An unknown device creates one bounded
/// pairing request and waits; it can never self-enrol through a session token.
export async function login(enrolledDeviceId?: string, password?: string): Promise<void> {
  const account = await accountStatus();
  if (account.bootstrap_required) throw new AccountActivationRequired();
  if (account.password_required && !password && !sessionStorage.getItem(PAIRING_WAIT_KEY)) throw new AccountPasswordRequired();
  const publicKey = await invoke<string>("auth_public_key");
  const info = await invoke<{ platform: string; name: string }>("device_info");

  const status = await currentAuthStatus();
  let deviceId = enrolledDeviceId ?? status.device_id;

  if (!deviceId) {
    const stored = sessionStorage.getItem(PAIRING_WAIT_KEY);
    if (stored) {
      const waiting = JSON.parse(stored) as PairingWait;
      const status = await getJsonWithHeaders<{ status: string; device_id: string | null }>(
        `/v1/auth/pairing/requests/${waiting.request_id}/status`,
        { "X-Jarvis-Pairing-Nonce": waiting.nonce },
      );
      if (status.status === "approved" && status.device_id) {
        deviceId = status.device_id;
      } else if (status.status === "pending") {
        throw new PairingPending();
      } else {
        sessionStorage.removeItem(PAIRING_WAIT_KEY);
        throw new Error("pairing request is verlopen of afgewezen");
      }
    } else {
      const pairing = await postJson<PairingWait>("/v1/auth/pairing/requests", {
        name: info.name, platform: info.platform, public_key: publicKey,
        password,
      });
      sessionStorage.setItem(PAIRING_WAIT_KEY, JSON.stringify(pairing));
      throw new PairingPending();
    }
  }

  if (account.password_required && !password) throw new AccountPasswordRequired();
  const challenge = await postJson<{ challenge_id: string; nonce: string }>(
    "/v1/auth/challenge",
    { device_id: deviceId },
  );
  const signature = await invoke<string>("auth_sign", {
    nonceHex: challenge.nonce,
  });
  await invoke("auth_complete_login", {
    deviceId,
    challengeId: challenge.challenge_id,
    signature,
    password,
  });
  sessionStorage.removeItem(PAIRING_WAIT_KEY);
  scheduleAutomaticUpdateCheck(true, true);
}

/** Local-LAN first-owner bootstrap. The secret is used once, never persisted,
 * and is expected to come from the root-operated Home Node provisioning flow. */
export async function bootstrapFirstDevice(secret: string, password: string): Promise<void> {
  const publicKey = await invoke<string>("auth_public_key");
  const info = await invoke<{ platform: string; name: string }>("device_info");
  const enrolled = await postJsonWithHeaders<{ device_id: string }>("/v1/auth/bootstrap", {
    name: info.name, platform: info.platform, public_key: publicKey,
    password,
  }, { "X-Jarvis-Bootstrap-Secret": secret });
  await login(enrolled.device_id, password);
}

/** Drop the locally stored session token (keeps the enrolled device + key), so
 *  the next `login()` mints a fresh session. Used to recover from a stale token
 *  (e.g. the backend restarted and forgot the session) instead of looping on 401. */
export async function clearSession(): Promise<void> {
  chatSession.invalidate();
  await invoke("realtime_stop").catch(()=>{});
  await invoke("auth_logout");
}

export async function logout(): Promise<void> {
  chatSession.invalidate();
  await invoke("realtime_stop").catch(()=>{});
  const status = await currentAuthStatus();
  if (status.authenticated) {
    // Best-effort server-side revocation; clear locally regardless.
    try {
      await postAuth("/v1/auth/logout");
    } catch {
      /* ignore */
    }
  }
  await invoke("auth_logout");
}

type AccountApproval = {
  request_id: string; user_id: string; device_id: string;
  action: "password-set" | "device-revoke"; target: string; nonce: string; expires_at: number;
};

async function approveAccount(approval: AccountApproval): Promise<void> {
  const signature = await invoke<string>("auth_sign_account_approval", { approval });
  await postJsonAuth(`/v1/auth/account/requests/${approval.request_id}/approve`, { signature });
}

export async function revokeDevice(deviceId: string): Promise<void> {
  const approval = await postJsonAuth<AccountApproval>(`/v1/devices/${deviceId}/revoke-request`, {});
  if (approval.action !== "device-revoke" || approval.target !== deviceId) throw new Error("Ongeldig intrekkingsverzoek");
  await approveAccount(approval);
}

export async function setAccountPassword(password: string, currentPassword?: string): Promise<void> {
  const approval = await postJsonAuth<AccountApproval>("/v1/auth/account/password/requests", {
    password, current_password: currentPassword,
  });
  if (approval.action !== "password-set" || approval.target !== approval.user_id) throw new Error("Ongeldig wachtwoordverzoek");
  await approveAccount(approval);
  await clearSession();
}

/** Confirm remote revocation before deleting the local signing identity. */
export async function deregisterDevice(): Promise<void> {
  chatSession.invalidate();
  await invoke("realtime_stop").catch(()=>{});
  const status = await currentAuthStatus();
  if (status.authenticated && status.device_id) {
    await revokeDevice(status.device_id);
  } else {
    throw new Error("Meld je aan om dit apparaat veilig in te trekken.");
  }
  await invoke("auth_reset");
}

export async function listDevices(): Promise<DeviceItem[]> {
  const res = await getJsonAuth<{ devices: DeviceItem[] }>("/v1/devices");
  return res.devices;
}
