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

// --- Integrations --------------------------------------------------------------

/** "3 of 9" style count of items with a truthy flag. */
export function countOf<T>(list: T[], flag: (item: T) => boolean): string {
  return `${list.filter(flag).length} of ${list.length}`;
}

/** Only the agent-approval-v1 message may be signed; a pending action from an
 *  older Core (raw nonce, no action hash) cannot be approved from this app. */
export function canSignApproval(item: { approval_message?: string; action_sha256?: string }): boolean {
  return item.approval_message === "agent-approval-v1" && /^[0-9a-f]{64}$/.test(item.action_sha256 ?? "");
}

// --- Agents ----------------------------------------------------------------------

export type AgentUsage = {
  requests: number; input_tokens: number; output_tokens: number; total_tokens: number; spent_eur: number;
  // `null` = Core does not measure this yet (never a measured zero).
  failures?: number | null; fallbacks?: number | null; latency_p50_ms?: number | null; latency_p95_ms?: number | null; last_used?: string | null;
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

/** Core's reason for `usage: null` when it does not record per-agent usage yet. */
export const AGENT_USAGE_NOT_INSTRUMENTED = "agent_usage_not_instrumented";
export const NOT_MEASURED = "Not measured yet";

/** What an agent's status line may honestly say: its usage this month, not a
 *  live Active/Idle state (Core does not report one). */
export function agentStatus(usage: AgentUsage | null, unavailableReason?: string | null): [Tone, string] {
  if (!usage) return ["idle", unavailableReason === AGENT_USAGE_NOT_INSTRUMENTED ? NOT_MEASURED : "Usage unavailable"];
  if (!usage.requests) return ["idle", "Not used this month"];
  return ["ok", `${usage.requests} ${usage.requests === 1 ? "request" : "requests"} this month`];
}

/** The trading agent gets the "Open Trading" button. */
export function isTrader(agent: { id: string; name: string }): boolean {
  return /trad(er|ing)/i.test(agent.id) || /trad(er|ing)/i.test(agent.name);
}

/** A usage count Core may not measure yet: `null` is "Not measured yet",
 *  never 0; `undefined` (an older Core without the field) is "—". */
export function measuredCount(n: number | null | undefined): string {
  if (n === null) return NOT_MEASURED;
  return n === undefined ? "—" : new Intl.NumberFormat("en").format(n);
}

/** "850 ms", "1.2 s"; "—" when Core has no measurement. */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return "—";
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;
}

// --- Health: services and disks ------------------------------------------------------

/** Tone and label of a systemd unit state as Core reports it. */
export function serviceState(state: string): [Tone, string] {
  if (state === "active") return ["ok", "Running"];
  if (state === "failed") return ["error", "Failed"];
  if (state === "not_found") return ["idle", "Not installed"];
  if (!state || state === "unknown") return ["idle", "Unknown"];
  return ["warn", state.charAt(0).toUpperCase() + state.slice(1)];
}

/** Satellite status for the services tab. */
export function servicesSummary(services: { state: string }[]): [Tone, string] {
  if (!services.length) return ["idle", "No services reported"];
  const failed = services.filter((s) => s.state === "failed").length;
  if (failed) return ["error", `${failed} failed`];
  const running = services.filter((s) => s.state === "active").length;
  return [running === services.length ? "ok" : "warn", `${running} of ${services.length} running`];
}

export type Disk = { label: string; state: string; total_bytes?: number; free_bytes?: number; used_percent?: number };
const gib = (n: number) => `${(n / 1024 ** 3).toFixed(1)} GiB`;
/** Tone and one-line summary of a disk; full disks warn early. */
export function diskState(disk: Disk): [Tone, string] {
  if (disk.state !== "ok" || disk.used_percent === undefined || disk.free_bytes === undefined || disk.total_bytes === undefined) {
    return ["idle", "Unknown"];
  }
  const tone: Tone = disk.used_percent >= 90 ? "error" : disk.used_percent >= 80 ? "warn" : "ok";
  return [tone, `${disk.used_percent}% used · ${gib(disk.free_bytes)} free of ${gib(disk.total_bytes)}`];
}

export type LiveHost = { sampled_at: number; cpu_percent: number | null; memory_total_bytes: number; memory_used_bytes: number; uptime_seconds: number };
/** One resource bar: `percent` null when Core has no reading. */
export type Meter = { id: string; label: string; percent: number | null; tone: Tone; detail: string };
const loadTone = (percent: number): Tone => (percent >= 90 ? "error" : percent >= 80 ? "warn" : "ok");
const spaceDetail = (used: number, total: number) => `${gib(used)} used · ${gib(total - used)} free of ${gib(total)}`;
/** CPU, memory and disk bars for the Home Node, in that order; each warns at 80% and 90%. */
export function resourceMeters(live: LiveHost | undefined, disks: Disk[]): Meter[] {
  const meters: Meter[] = [];
  if (live) {
    const cpu = live.cpu_percent;
    meters.push(cpu == null
      ? { id: "cpu", label: "CPU", percent: null, tone: "idle", detail: "First sample…" }
      : { id: "cpu", label: "CPU", percent: Math.round(cpu), tone: loadTone(cpu), detail: `${cpu.toFixed(1)}% in use` });
    const { memory_used_bytes: used, memory_total_bytes: total } = live;
    meters.push(total > 0
      ? { id: "memory", label: "Memory", percent: Math.round((used / total) * 100), tone: loadTone((used / total) * 100), detail: spaceDetail(used, total) }
      : { id: "memory", label: "Memory", percent: null, tone: "idle", detail: "Unknown" });
  }
  disks.forEach((disk, i) => {
    const id = `disk-${disk.label}-${i}`;
    const label = `Disk · ${disk.label}`;
    const [tone] = diskState(disk);
    meters.push(tone === "idle"
      ? { id, label, percent: null, tone, detail: "Unknown" }
      : { id, label, percent: Math.round(disk.used_percent!), tone, detail: spaceDetail(disk.total_bytes! - disk.free_bytes!, disk.total_bytes!) });
  });
  return meters;
}
