// Client-side device-bound login.
//
// The private key and signing live in Rust (Tauri commands); this module only
// orchestrates the HTTP flow (enroll -> challenge -> login) and reads back the
// non-secret session state. The private key and bearer token never enter JS.
import { computed, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { chatSession } from "./chatSession";
import { homeNodeConfig, homeNodeOrigin } from "./homeNode";
import { getJson, getJsonAuth, getJsonWithHeaders, postAuth, postJson, postJsonAuth, postJsonWithHeaders } from "./api";
import { initPlatformCapabilities } from "./platform";
import { lockModeFor } from "./lockPolicy";
import { scheduleAutomaticUpdateCheck } from "./updates";
import { groupFingerprint, keyFingerprint } from "./pairingFingerprint";
import { latestWins, shellMode } from "./shellMode";

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

// The last session state read from native code; null until read or unreadable.
// Reads can resolve out of order: only a newer read or sign-out overrides.
const signedIn = ref<boolean | null>(null);
const signedInWrites = latestWins<boolean | null>((value) => { signedIn.value = value; });
const markSignedOut = () => signedInWrites.apply(signedInWrites.begin(), false);
/** Setup, sign-in or the full app; drives the sidebar and the router guard. */
export const currentShellMode = computed(() =>
  shellMode({ configured: homeNodeConfig.value.configured, authenticated: signedIn.value }));

export async function currentAuthStatus(): Promise<AuthStatus> {
  const seq = signedInWrites.begin();
  try {
    const status = await invoke<AuthStatus>("auth_status");
    signedInWrites.apply(seq, status.authenticated);
    return status;
  } catch (error) {
    signedInWrites.apply(seq, null); // unreadable: fail closed
    throw error;
  }
}

const PAIRING_WAIT_KEY = "jarvis.pairing.wait";
type PairingWait = { request_id: string; nonce: string; expires_at: number };
export class PairingPending extends Error {
  constructor() { super("Waiting for approval from your phone, a Mac or the Home Node."); }
}

export type AccountStatus = { protocol: number; password_required: boolean; bootstrap_required: boolean };
export function accountStatus(): Promise<AccountStatus> {
  return getJson<AccountStatus>("/v1/auth/account/status");
}
export class AccountPasswordRequired extends Error {}
export class AccountActivationRequired extends Error {}

/// Log in with the local device key. An unknown device creates one bounded
/// pairing request and waits; it can never self-enrol through a session token.
export async function login(enrolledDeviceId?: string, password?: string, boundOrigin?: string): Promise<void> {
  const currentOrigin = await homeNodeOrigin();
  const expectedOrigin = boundOrigin ?? currentOrigin;
  if (currentOrigin !== expectedOrigin) throw new Error("Home Node changed; sign in again");
  const account = await accountStatus();
  if (account.bootstrap_required) throw new AccountActivationRequired();
  // Windows always signs in with the account password, whatever Core reports.
  const passwordRequired = account.password_required
    || lockModeFor((await initPlatformCapabilities()).platform) === "password";
  if (passwordRequired && !password && !sessionStorage.getItem(PAIRING_WAIT_KEY)) throw new AccountPasswordRequired();
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
        // Approval is already authoritative even if the following login fails
        // or the owner closes the app before entering the password again.
        await invoke("auth_remember_enrolled_device", { deviceId, expectedOrigin });
        sessionStorage.removeItem(PAIRING_WAIT_KEY);
      } else if (status.status === "pending") {
        throw new PairingPending();
      } else {
        sessionStorage.removeItem(PAIRING_WAIT_KEY);
        throw new Error("pairing request expired or was denied");
      }
    } else {
      const pairing = await postJson<PairingWait>("/v1/auth/pairing/requests", {
        name: info.name, platform: info.platform, public_key: publicKey,
        password,
      }, expectedOrigin);
      sessionStorage.setItem(PAIRING_WAIT_KEY, JSON.stringify(pairing));
      throw new PairingPending();
    }
  }

  if (passwordRequired && !password) throw new AccountPasswordRequired();
  // Challenge, signature and login run natively; the signature never reaches JS.
  await invoke("auth_login", { password, expectedOrigin });
  sessionStorage.removeItem(PAIRING_WAIT_KEY);
  scheduleAutomaticUpdateCheck(true, true);
}

/** This device's key fingerprint, for the owner to compare with the one the
 *  approving device shows. */
export async function ownKeyFingerprint(): Promise<string> {
  return groupFingerprint(await keyFingerprint(await invoke<string>("auth_public_key")));
}

/** Local-LAN first-owner bootstrap. The secret is used once, never persisted,
 * and is expected to come from the root-operated Home Node provisioning flow. */
export async function bootstrapFirstDevice(secret: string, password: string): Promise<void> {
  const expectedOrigin = await homeNodeOrigin();
  const publicKey = await invoke<string>("auth_public_key");
  const info = await invoke<{ platform: string; name: string }>("device_info");
  const enrolled = await postJsonWithHeaders<{ device_id: string }>("/v1/auth/bootstrap", {
    name: info.name, platform: info.platform, public_key: publicKey,
    password,
  }, { "X-Jarvis-Bootstrap-Secret": secret }, expectedOrigin);
  await invoke("auth_remember_enrolled_device", { deviceId: enrolled.device_id, expectedOrigin });
  await login(enrolled.device_id, password, expectedOrigin);
}

/** Drop the locally stored session token (keeps the enrolled device + key), so
 *  the next `login()` mints a fresh session. Used to recover from a stale token
 *  (e.g. the backend restarted and forgot the session) instead of looping on 401. */
export async function clearSession(): Promise<void> {
  chatSession.invalidate();
  await invoke("realtime_stop").catch(()=>{});
  await invoke("auth_logout");
  markSignedOut();
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
  markSignedOut();
}

// Account administration (macOS only): Core creates the request, native code
// signs it after Touch ID, and this page posts the signature.
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
  if (approval.action !== "device-revoke" || approval.target !== deviceId) throw new Error("Invalid revocation request");
  await approveAccount(approval);
}

export async function setAccountPassword(password: string, currentPassword?: string): Promise<void> {
  const approval = await postJsonAuth<AccountApproval>("/v1/auth/account/password/requests", {
    password, current_password: currentPassword,
  });
  if (approval.action !== "password-set" || approval.target !== approval.user_id) throw new Error("Invalid password request");
  await approveAccount(approval);
  await clearSession();
}

/** On a Mac, revoke this device with a Touch ID approval before erasing the
 *  local signing identity. Elsewhere sign out and erase it; the device stays
 *  active on the Home Node until `jarvis devices revoke <id>` runs there. */
export async function deregisterDevice(): Promise<void> {
  if ((await initPlatformCapabilities()).supportsBiometricSigning) {
    chatSession.invalidate();
    await invoke("realtime_stop").catch(()=>{});
    const status = await currentAuthStatus();
    if (!status.authenticated || !status.device_id) throw new Error("Sign in to revoke this device safely.");
    await revokeDevice(status.device_id);
  } else {
    await logout();
  }
  await invoke("auth_reset");
  markSignedOut();
}

export async function listDevices(): Promise<DeviceItem[]> {
  const res = await getJsonAuth<{ devices: DeviceItem[] }>("/v1/devices");
  return res.devices;
}
