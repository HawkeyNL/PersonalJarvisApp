// Pure helpers for the Activity pages (Approvals and Runs). No Vue or Tauri
// imports, so behavioral tests can load this file directly.
import { sessionState } from "./nodeModels.ts";

const count = (n: number) => new Intl.NumberFormat("en").format(n);

// --- Approvals: the review gate in front of Approve ---------------------------

/** Approve stays off until the full details have been scrolled to their end
 *  (or fit without scrolling), as on iOS and Android. The gate belongs to one
 *  review: `key` names the exact content shown, so other content, or the same
 *  request with changed content, starts closed. Once seen it stays seen. */
export type ReviewGate = { key: string | null; seen: boolean };
export const CLOSED_GATE: ReviewGate = { key: null, seen: false };
export type ScrollBox = { scrollTop: number; scrollHeight: number; clientHeight: number };

/** Identity of a review: everything the details show about the request. */
export const reviewKey = (item: { pending_id: string; action: string; preview: string; action_sha256?: string }) =>
  JSON.stringify([item.pending_id, item.action, item.preview, item.action_sha256 ?? null]);

export function observeReview(gate: ReviewGate, key: string, box: ScrollBox): ReviewGate {
  const seen = gate.key === key && gate.seen;
  const { scrollTop, scrollHeight, clientHeight } = box;
  // A box that is hidden or not laid out yet never counts as seen.
  if (!(clientHeight > 0) || !Number.isFinite(scrollTop) || !Number.isFinite(scrollHeight)) return { key, seen };
  // 1px slack: with fractional zoom scrollTop can stop a subpixel short of the end.
  return { key, seen: seen || scrollTop + clientHeight >= scrollHeight - 1 };
}

/** Whether the gate is open for exactly this review. */
export const reviewDone = (gate: ReviewGate, key: string) => gate.key === key && gate.seen;

/** GET /v1/agent/audit: the latest agent actions Core recorded. */
export type AuditEntry = { action: string; risk: string; outcome: string; note: string | null; ts: string };
export type AuditLog = { enabled: boolean; entries: AuditEntry[] };

/** Badge class and label of an agent audit outcome. */
export function outcomeBadge(outcome: string): [string, string] {
  if (outcome === "ok") return ["pos", "Done"];
  if (outcome === "denied") return ["", "Denied"];
  if (outcome === "error") return ["neg", "Failed"];
  return ["", outcome || "Unknown"];
}

// --- Runs ---------------------------------------------------------------------

export type RunFilter = "all" | "running" | "paused" | "finished";
export const RUN_FILTERS: { id: RunFilter; label: string }[] = [
  { id: "all", label: "All" }, { id: "running", label: "Running" }, { id: "paused", label: "Paused" }, { id: "finished", label: "Finished" },
];

/** Coding sessions in one status group; "finished" is everything not running or paused. */
export function filterRuns<T extends { state: string }>(list: T[], filter: RunFilter): T[] {
  if (filter === "running") return list.filter((s) => s.state === "active");
  if (filter === "paused") return list.filter((s) => s.state === "suspended");
  if (filter === "finished") return list.filter((s) => s.state !== "active" && s.state !== "suspended");
  return list;
}

/** Badge class, label and whether it is live (spinner) for a session state. */
export function runBadge(state: string): { cls: string; label: string; live: boolean } {
  const [tone, label] = sessionState(state);
  if (state === "active") return { cls: "acc", label, live: true };
  return { cls: tone === "ok" ? "pos" : "", label, live: false };
}

export type Kpi = { label: string; value: string; sub: string };

/** The four Runs KPIs, from data the page already reads. `pending` is the
 *  number of waiting approvals and `audit` the agent audit log; `null` means
 *  that read failed, which shows as unavailable, never as zero. */
export function runKpis(
  sessions: { state: string }[], pending: number | null, audit: AuditLog | null,
): Kpi[] {
  const running = filterRuns(sessions, "running").length;
  const paused = filterRuns(sessions, "paused").length;
  const finished = filterRuns(sessions, "finished").length;
  const failed = audit ? audit.entries.filter((e) => e.outcome === "error").length : 0;
  return [
    { label: "Runs", value: count(sessions.length), sub: sessions.length ? `${count(finished)} finished` : "None yet" },
    { label: "Running", value: count(running), sub: `${count(paused)} paused` },
    {
      label: "Waiting on you", value: pending === null ? "—" : count(pending),
      sub: pending === null ? "Unavailable" : pending === 1 ? "approval" : "approvals",
    },
    {
      label: "Failed agent actions", value: audit ? count(failed) : "—",
      sub: !audit ? "Unavailable" : !audit.enabled ? "Agent is off in Core"
        : audit.entries.length ? `of the last ${count(audit.entries.length)} recorded` : "Nothing recorded",
    },
  ];
}
