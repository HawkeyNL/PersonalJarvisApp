// Pure helpers for the module pages (conversations, tasks, integrations,
// agents, health). No Vue or Tauri imports, so behavioral tests can load this
// file directly.
import type { Tone } from "./hubModel";

const DAY = 86_400_000;

/** Conversations whose title contains `query` (case-insensitive) and, when
 *  `days` is set, were updated within the last `days` days. */
export function filterConversations<T extends { title: string; updated_at: string }>(
  list: T[], query: string, days: number | null, now: number,
): T[] {
  const q = query.trim().toLowerCase();
  const since = days === null ? -Infinity : now - days * DAY;
  return list.filter((c) => (c.title || "").toLowerCase().includes(q) && (Date.parse(c.updated_at) || 0) >= since);
}

// --- Tasks ---------------------------------------------------------------------

export type CodingSessionRow = { id: string; repository?: string; objective?: string; state: string; updated_at?: string };

const SESSION_STATES: Record<string, [Tone, string]> = {
  active: ["ok", "Running"], suspended: ["warn", "Paused"], completed: ["ok", "Completed"],
  cancelled: ["idle", "Cancelled"], archived: ["idle", "Archived"],
};
/** Tone and label of a coding session state; unknown states stay neutral. */
export function sessionState(state: string): [Tone, string] {
  return Object.prototype.hasOwnProperty.call(SESSION_STATES, state) ? SESSION_STATES[state] : ["idle", state || "Unknown"];
}

/** Open (running or paused) vs finished coding sessions, order kept. */
export function splitSessions<T extends { state: string }>(list: T[]): { open: T[]; finished: T[] } {
  const open = list.filter((s) => s.state === "active" || s.state === "suspended");
  return { open, finished: list.filter((s) => !open.includes(s)) };
}

/** Tone of an agent audit outcome ("ok", "denied", "error", …). */
export function outcomeTone(outcome: string): Tone {
  return outcome === "ok" ? "ok" : outcome === "error" ? "error" : outcome === "denied" ? "warn" : "idle";
}

// --- Integrations --------------------------------------------------------------

export type IbkrState = { reachable: boolean; authenticated: boolean; connected?: boolean };
/** Tone and label of the IBKR gateway link. */
export function ibkrState(status: IbkrState): [Tone, string] {
  if (!status.reachable) return ["warn", "Gateway unreachable"];
  if (!status.authenticated) return ["warn", "Not logged in"];
  return status.connected === false ? ["warn", "Logged in, not connected"] : ["ok", "Connected"];
}

/** "3 of 9" style count of items with a truthy flag. */
export function countOf<T>(list: T[], flag: (item: T) => boolean): string {
  return `${list.filter(flag).length} of ${list.length}`;
}
