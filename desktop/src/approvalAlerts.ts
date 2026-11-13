// Making a new pairing or unlock request noticeable. The desktop has no OS
// notifications (that needs tauri-plugin-notification), so the shell flashes
// the taskbar, shows a banner and puts the waiting count in the window title.
import { displayHint } from "./pairingFingerprint.ts";

export type WaitingRequest = { kind: "pairing" | "unlock"; id: string; device_name: string };

/** The newest request not seen before, or null, and the keys to remember.
 *  Only current keys are kept, so the set is never larger than Core's lists. */
export function nextAlert(seen: ReadonlySet<string>, current: readonly WaitingRequest[]) {
  const keys = current.map((request) => `${request.kind}:${request.id}`);
  const fresh = current.filter((_, i) => !seen.has(keys[i]));
  return { alert: fresh.length ? fresh[fresh.length - 1] : null, seen: new Set(keys) };
}

/** Banner text; the device name is candidate-supplied, so bounded plain text. */
export function alertText(request: WaitingRequest): string {
  return request.kind === "pairing"
    ? `New device wants to pair: ${displayHint(request.device_name, 40)} (unverified)`
    : "A device asks to unlock";
}

/** The window title with the waiting count in front, as "(2) Jarvis". */
export function titleWithCount(title: string, count: number): string {
  const base = title.replace(/^\(\d+\) /, "");
  return count > 0 ? `(${count}) ${base}` : base;
}
