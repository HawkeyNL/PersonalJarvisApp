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

// --- Agents ----------------------------------------------------------------------

export type AgentUsage = {
  requests: number; input_tokens: number; output_tokens: number; total_tokens: number; spent_eur: number;
  failures?: number; fallbacks?: number; latency_p50_ms?: number | null; latency_p95_ms?: number | null; last_used?: string | null;
};
export type AgentInfo = {
  id: string; name: string; group: string | null; description: string; model_policy: string; allowed_tools: string[];
  limits: { max_runtime_seconds: number; max_context_chars: number; max_output_chars: number; max_parallel_runs: number };
  usage: AgentUsage | null;
};
export type AgentsResponse = {
  bundle_id: string | null; agent_count: number; unavailable_reason: string | null; usage_unavailable_reason?: string | null; agents: AgentInfo[];
};

/** Agents grouped by `group` (alphabetical, ungrouped last), names sorted. */
export function groupAgents<T extends { name: string; group: string | null }>(agents: T[]): { group: string; agents: T[] }[] {
  const groups = new Map<string, T[]>();
  for (const agent of [...agents].sort((a, b) => a.name.localeCompare(b.name))) {
    const key = agent.group?.trim() || "";
    groups.set(key, [...(groups.get(key) ?? []), agent]);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => (a === "" ? 1 : b === "" ? -1 : a.localeCompare(b)))
    .map(([group, list]) => ({ group: group || "Other", agents: list }));
}

/** What an agent's status line may honestly say: its usage this month, not a
 *  live Active/Idle state (Core does not report one). */
export function agentStatus(usage: AgentUsage | null): [Tone, string] {
  if (!usage) return ["idle", "Usage unavailable"];
  if (!usage.requests) return ["idle", "Not used this month"];
  return ["ok", `${usage.requests} ${usage.requests === 1 ? "request" : "requests"} this month`];
}

/** The trading agent gets the "Open Trading" button. */
export function isTrader(agent: { id: string; name: string }): boolean {
  return /trad(er|ing)/i.test(agent.id) || /trad(er|ing)/i.test(agent.name);
}

/** "850 ms", "1.2 s"; "—" when Core has no measurement. */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return "—";
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;
}
