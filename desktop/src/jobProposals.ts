// Jobs a chat answer proposed (`job_proposals` on `assistant.completed`, see
// "From chat" in Core's docs/SCHEDULED_JOBS.md). Each body is a checked
// `POST /v1/jobs` body; nothing exists on Core until the owner clicks Add (the
// body as is) or saves an edited one. Cards live in memory only, for the
// conversation on screen. Pure: tests load this file directly.
import {
  KIND_LABEL, LIMITS, NEW_KINDS, NOT_CONFIRMED, CADENCES, draftIssue, minuteToTime, newDraft, readJobAnswer, scheduleText,
  timeToMinute, writeFailureText, type Draft, type NewJob,
} from "./jobs.ts";

export const MAX_PROPOSALS = 3;
export const ADDED = "Added to Jobs";

export type Proposal = { id: string; body: Record<string, unknown>; draft: Draft };
export type CardState = "open" | "adding" | "added" | "unconfirmed";
export type ProposalCard = Proposal & {
  conversationId: string; messageId: string; state: CardState; jobId: string | null; error: string;
};

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);
const isId = (x: unknown): x is string => typeof x === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(x);
const int = (x: unknown) => typeof x === "number" && Number.isSafeInteger(x);
const optInt = (x: unknown) => x == null || int(x);
const optStr = (x: unknown) => x == null || typeof x === "string";
// What a chat marker can carry (no `enabled`, no roadmap): the card shows
// all of it, so what is added is what was shown.
const BODY_KEYS = ["kind", "cadence", "weekday", "month_day", "every", "start_date", "max_runs", "at_minute", "params"];
const PARAM_KEYS = ["title", "instruction", "text", "notify", "threshold_percent"];

/** The editor's draft for a body, with Core's defaults for what it leaves
 *  out; null when a field has the wrong type or the job breaks a rule. */
function proposalDraft(body: Record<string, unknown>, today: string): Draft | null {
  const p = body.params ?? {};
  if (!isObj(p) || !Object.keys(body).every((k) => BODY_KEYS.includes(k)) || !Object.keys(p).every((k) => PARAM_KEYS.includes(k))) return null;
  if (!(NEW_KINDS as readonly unknown[]).includes(body.kind) || (body.cadence != null && !(CADENCES as readonly unknown[]).includes(body.cadence))) return null;
  if (![body.weekday, body.month_day, body.every, body.max_runs, body.at_minute, p.threshold_percent].every(optInt)) return null;
  if (![body.start_date, p.title, p.instruction, p.text].every(optStr) || (p.notify != null && typeof p.notify !== "boolean")) return null;
  const at = (body.at_minute as number | undefined) ?? 420;
  if (at < 0 || at > 1439) return null;
  const d: Draft = {
    ...newDraft(body.kind as Draft["kind"]),
    cadence: (body.cadence as Draft["cadence"] | undefined) ?? "weekly",
    weekday: (body.weekday as number | undefined) ?? 1, month_day: (body.month_day as number | undefined) ?? 1,
    every: (body.every as number | undefined) ?? 1, start_date: (body.start_date as string | undefined) ?? "",
    max_runs: (body.max_runs as number | undefined) ?? "", time: minuteToTime(at),
    title: (p.title as string | undefined) ?? "", instruction: (p.instruction as string | undefined) ?? "",
    text: (p.text as string | undefined) ?? "", notify: p.notify !== false,
    threshold: (p.threshold_percent as number | undefined) ?? LIMITS.threshold,
  };
  if (d.weekday < 1 || d.weekday > 7 || d.month_day < 1 || d.month_day > 31) return null;
  return draftIssue(d, today) ? null : d;
}

/** The proposals of an `assistant.completed` payload: at most 3, each with a
 *  UUID and a body this build can show and would accept itself. Anything
 *  else is skipped, never failing the message. */
export function readProposals(x: unknown, today: string): Proposal[] {
  if (!Array.isArray(x)) return [];
  const out: Proposal[] = [];
  for (const item of x.slice(0, MAX_PROPOSALS)) {
    if (!isObj(item) || !isId(item.id) || !isObj(item.body) || out.some((p) => p.id === item.id)) continue;
    const draft = proposalDraft(item.body, today);
    if (draft) out.push({ id: item.id, body: item.body, draft });
  }
  return out;
}

/** `cards` plus the proposals not shown before (`seen`, which this updates). */
export function withProposals(
  cards: ProposalCard[], seen: Set<string>, proposals: Proposal[], conversationId: string, messageId: string,
): ProposalCard[] {
  const fresh = proposals.filter((p) => !seen.has(p.id));
  for (const p of fresh) seen.add(p.id);
  return fresh.length
    ? [...cards, ...fresh.map((p) => ({ ...p, conversationId, messageId, state: "open" as const, jobId: null, error: "" }))]
    : cards;
}

// ---- wording -----------------------------------------------------------------

const cut = (s: string, max = 140) => (s.length > max ? `${s.slice(0, max - 1)}…` : s);

export const proposalTitle = (d: Draft) => d.title || KIND_LABEL[d.kind];

export const proposalSchedule = (d: Draft, today: string) => scheduleText({
  cadence: d.cadence, weekday: d.weekday, month_day: d.month_day, every: Number(d.every),
  start_date: d.start_date || null, at_minute: timeToMinute(d.time) ?? 0,
}, today);

/** The instruction, text or threshold, as one short plain line; null when none. */
export function proposalDetail(d: Draft): string | null {
  if (d.kind === "prompt") return cut(d.instruction);
  if (d.kind === "reminder") return d.text ? cut(d.text) : null;
  return `Threshold ${d.threshold}%`;
}

// ---- add -----------------------------------------------------------------------

export type AddResult = Pick<ProposalCard, "state" | "jobId" | "error">;

/** Sends `body` exactly as given. A 2xx this build cannot read is not
 *  confirmed (Add stays off: the job may exist); a failure lets the owner
 *  try again. */
export async function addJob(body: NewJob, create: (body: NewJob) => Promise<unknown>): Promise<AddResult> {
  try {
    const job = readJobAnswer(await create(body));
    return job ? { state: "added", jobId: job.id, error: "" } : { state: "unconfirmed", jobId: null, error: NOT_CONFIRMED };
  } catch (e) {
    return { state: "open", jobId: null, error: writeFailureText(e, false) };
  }
}
