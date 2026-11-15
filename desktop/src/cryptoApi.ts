// Crypto wallet calls (contract and guards in crypto.ts). They go through the
// native auth_request command like every other owner endpoint; Vue never sees
// the session bearer. Answers are raw: the page passes them through the guards.
import { onMounted, onUnmounted } from "vue";
import { listen } from "@tauri-apps/api/event";
import { deleteAuth, getJsonAuth, postJsonAuth } from "./api";
import { currentAuthStatus } from "./auth";
import { isCryptoUpdated, isId, type NewWallet } from "./crypto";

/** No call without a session: the page shows "sign in again" instead. */
async function signedIn(): Promise<void> {
  if (!(await currentAuthStatus()).authenticated) throw { name: "ApiError", status: 401 };
}

export const cryptoApi = {
  overview: async () => (await signedIn(), getJsonAuth<unknown>("/v1/trading/crypto")),
  /** IBKR plus the wallets; 404/405 on a Core before v0.0.90. */
  total: async () => (await signedIn(), getJsonAuth<unknown>("/v1/trading/total")),
  add: async (body: NewWallet) => (await signedIn(), postJsonAuth<unknown>("/v1/trading/crypto/wallets", body)),
  remove: async (id: string) => {
    if (!isId(id)) throw new Error("invalid wallet id");
    await signedIn();
    return deleteAuth(`/v1/trading/crypto/wallets/${id}`);
  },
  /** 202 when queued; 429 with retry_after_secs within Core's minute. */
  refresh: async () => (await signedIn(), postJsonAuth<unknown>("/v1/trading/crypto/refresh", {})),
};

/** Reload when the window regains focus, the realtime socket (re)connects or
 *  Core says the wallets changed (`trading.crypto.updated`). */
export function useCryptoRefetch(reload: () => void): void {
  let unlisten: (() => void) | undefined;
  let gone = false;
  const onFocus = () => reload();
  onMounted(() => {
    window.addEventListener("focus", onFocus);
    // Promise.resolve first: outside Tauri, listen throws instead of rejecting.
    Promise.resolve().then(() => listen<{ type?: unknown }>("jarvis-realtime", (event) => {
      if (event.payload?.type === "connection.ready" || isCryptoUpdated(event.payload)) reload();
    })).then((stop) => (gone ? stop() : (unlisten = stop)), () => { /* browser preview: focus only */ });
  });
  onUnmounted(() => {
    gone = true;
    window.removeEventListener("focus", onFocus);
    unlisten?.();
  });
}
