// Scheduled jobs: Core's contract (/v1/jobs, docs/SCHEDULED_JOBS.md in the
// Core repository), response guards, the editor's draft and the fixed
// owner-facing wording for schedules, statuses and error codes. Pure: no Vue
// or Tauri imports, so tests load this file directly. The Android and iOS
// apps mirror the strings here.
//
// Every write names the revision it builds on (?expected_revision=N); Core
// answers 409 when the job moved on, and the page then reloads. Run text
// from Core (summary, reasons) is display only and always rendered as plain
// text; error codes are shown only through the fixed wording below.
import { validHttpsUrl, validText } from "./roadmaps.ts";

export const TIME_ZONE = "Europe/Amsterdam";
export const NEEDS_CORE = "Needs Jarvis Core v0.0.80 or later.";
export const TIMES_IN_ZONE = `Times are in ${TIME_ZONE} time.`;

export const KINDS = ["prompt", "reminder", "disk_check", "dependency_review"] as const;
export type Kind = (typeof KINDS)[number];
/** The kinds "New job" offers; the one dependency review has its own setup. */
export const NEW_KINDS = ["prompt", "reminder", "disk_check"] as const satisfies readonly Kind[];
export const KIND_LABEL: Record<Kind, string> = {
  prompt: "Research", reminder: "Reminder", disk_check: "Disk check", dependency_review: "Dependency review",
};
export const KIND_DETAIL: Record<Kind, string> = {
  prompt: "Searches the web for your instruction and posts the answer to the job's conversation.",
  reminder: "Posts your text to the job's conversation and sends you a push with the title.",
  disk_check: "Tells you when a Home Node disk is fuller than the threshold. It changes nothing on the host.",
  dependency_review: "Reads the open Dependabot alerts and pull requests of your repositories and puts them in order.",
};

export const CADENCES = ["once", "daily", "weekly", "monthly"] as const;
export type Cadence = (typeof CADENCES)[number];
export const CADENCE_LABEL: Record<Cadence, string> = { once: "Once", daily: "Daily", weekly: "Weekly", monthly: "Monthly" };
/** A dependency review keeps the schedules it had: daily or weekly. */
export const cadencesFor = (kind: Kind): readonly Cadence[] => (kind === "dependency_review" ? ["daily", "weekly"] : CADENCES);
export const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
/** The unit `every` counts in, singular and plural. */
export const EVERY_UNIT: Record<Exclude<Cadence, "once">, [string, string]> = {
  daily: ["day", "days"], weekly: ["week", "weeks"], monthly: ["month", "months"],
};

/** Core's bounds (jobs.rs); Core enforces them, the app explains them early. */
export const LIMITS = {
  title: 60, instruction: 500, text: 500, thresholdMin: 50, thresholdMax: 99, threshold: 80, maxRuns: 100,
  every: { once: 1, daily: 365, weekly: 104, monthly: 24 } as Record<Cadence, number>,
  ownerJobs: 10, startDays: 2 * 366,
};

export const RUN_STATUSES = ["running", "ok", "partial", "failed", "missed", "interrupted"] as const;
export type RunStatus = (typeof RUN_STATUSES)[number];
export const RUN_STATUS_LABEL: Record<RunStatus, string> = {
  running: "Running", ok: "OK", partial: "Partial", failed: "Failed", missed: "Missed", interrupted: "Interrupted",
};
/** Badge tone (components.css); the label always carries the meaning too. */
export const RUN_STATUS_TONE: Record<RunStatus, "" | "pos" | "acc" | "neg" | "dng"> = {
  running: "acc", ok: "pos", partial: "neg", failed: "dng", missed: "", interrupted: "neg",
};

export type Params = {
  roadmap_id: string | null; title: string | null; instruction: string | null; text: string | null;
  notify: boolean; threshold_percent: number;
};
export type Job = {
  id: string; kind: Kind; cadence: Cadence; weekday: number | null; month_day: number | null; every: number;
  start_date: string | null; max_runs: number | null; at_minute: number; enabled: boolean; revision: number; params: Params;
  last_slot_at: string | null; run_count: number; origin: "owner" | "jarvis"; conversation_id: string | null;
  expires_at: string | null; failure_count: number; paused_reason: "paused_after_failures" | null; next_run_at: string | null;
};
/** `unsupported`: jobs this build cannot show (a newer kind or cadence). */
export type JobList = { configured: boolean; jobs: Job[]; unsupported: number };
export type Priority = { ref: string; rank: number; reason: string };
export type SummarySource = "claude" | "deterministic" | "web";
export type Run = {
  id: string; job_id: string; kind: string | null; slot_at: string; trigger: "schedule" | "owner"; status: RunStatus;
  error_code: string | null; summary: string | null; summary_source: SummarySource | null;
  priorities: Priority[]; started_at: string; finished_at: string | null;
};
/** A fact a priority points at, reduced to plain display strings. */
export type Fact = { ref: string; title: string; detail: string };
/** A research answer's source: an https link and a short label. */
export type Source = { url: string; label: string };
export type RunDetail = Run & {
  facts: Fact[]; errors: { repo: string; error_code: string }[]; truncated: boolean;
  sources: Source[]; conversation_id: string | null;
};

// ---- response guards ---------------------------------------------------------
// The list is lenient per job: a job this build cannot read (a newer kind or
// cadence, a field out of range) is left out and counted, never failing the
// list. Fields this build does not know are ignored; fields an older Core
// does not send get Core's defaults. Anything else unexpected reads as null.

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);
const str = (x: unknown, max = 10_000): x is string => typeof x === "string" && x.length <= max;
const optStr = (x: unknown, max?: number) => x === undefined || x === null || str(x, max);
const int = (x: unknown): x is number => typeof x === "number" && Number.isSafeInteger(x) && x >= 0;
const intIn = (x: unknown, min: number, max: number): x is number => int(x) && x >= min && x <= max;
const CODE = /^[a-z0-9_]{1,64}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
// Job, run and conversation ids go into app routes and requests; Core's are UUIDs.
const isId = (x: unknown): x is string => typeof x === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(x);
const isCode = (x: unknown): x is string => typeof x === "string" && CODE.test(x);
const nul = <T>(x: unknown) => (x ?? null) as T | null;
const has = (list: readonly unknown[], x: unknown) => list.includes(x);

function readParams(x: unknown): Params | null {
  const p = x ?? {};
  if (!isObj(p)) return null;
  if (!optStr(p.roadmap_id, 64) || !optStr(p.title, 200) || !optStr(p.instruction, 2000) || !optStr(p.text, 2000)) return null;
  if (p.notify != null && typeof p.notify !== "boolean") return null;
  if (p.threshold_percent != null && !intIn(p.threshold_percent, 1, 100)) return null;
  return {
    roadmap_id: nul(p.roadmap_id), title: nul(p.title), instruction: nul(p.instruction), text: nul(p.text),
    notify: p.notify !== false, threshold_percent: (p.threshold_percent as number | undefined) ?? LIMITS.threshold,
  };
}

function readJob(x: unknown): Job | null {
  if (!isObj(x) || !isId(x.id) || !has(KINDS, x.kind) || !has(CADENCES, x.cadence)) return null;
  if (x.weekday != null && !intIn(x.weekday, 1, 7)) return null;
  if (x.month_day != null && !intIn(x.month_day, 1, 31)) return null;
  if (x.every != null && !intIn(x.every, 1, 365)) return null;
  if (x.start_date != null && !(str(x.start_date) && DATE.test(x.start_date))) return null;
  if (x.max_runs != null && !intIn(x.max_runs, 1, LIMITS.maxRuns)) return null;
  if (!intIn(x.at_minute, 0, 1439) || typeof x.enabled !== "boolean" || !int(x.revision)) return null;
  if (x.origin != null && x.origin !== "owner" && x.origin !== "jarvis") return null;
  if ((x.run_count != null && !int(x.run_count)) || (x.failure_count != null && !int(x.failure_count))) return null;
  if (!optStr(x.last_slot_at, 64) || !optStr(x.next_run_at, 64) || !optStr(x.expires_at, 64)) return null;
  const params = readParams(x.params);
  if (!params) return null;
  return {
    id: x.id, kind: x.kind as Kind, cadence: x.cadence as Cadence, weekday: nul(x.weekday), month_day: nul(x.month_day),
    every: (x.every as number | undefined) ?? 1, start_date: nul(x.start_date), max_runs: nul(x.max_runs),
    at_minute: x.at_minute, enabled: x.enabled, revision: x.revision, params,
    last_slot_at: nul(x.last_slot_at), run_count: (x.run_count as number | undefined) ?? 0,
    origin: x.origin === "jarvis" ? "jarvis" : "owner",
    // Only a link to chat: an id that does not fit is dropped, not the job.
    conversation_id: isId(x.conversation_id) ? x.conversation_id : null,
    expires_at: nul(x.expires_at), failure_count: (x.failure_count as number | undefined) ?? 0,
    // An unknown reason still shows the job as paused (enabled is false).
    paused_reason: x.paused_reason === "paused_after_failures" ? "paused_after_failures" : null,
    next_run_at: nul(x.next_run_at),
  };
}

export function readJobList(x: unknown): JobList | null {
  if (!isObj(x) || (x.status !== "ok" && x.status !== "not_configured") || !Array.isArray(x.jobs) || x.jobs.length > 50) return null;
  const jobs = x.jobs.map(readJob).filter((j): j is Job => j !== null);
  return { configured: x.status === "ok", jobs, unsupported: x.jobs.length - jobs.length };
}

/** A create or change answer; null when unreadable (the page then reloads
 *  and says the write is not confirmed). */
export const readJobAnswer = readJob;

const SUMMARY_SOURCES: readonly unknown[] = ["claude", "deterministic", "web"];

function readRun(x: unknown): Run | null {
  if (!isObj(x) || !isId(x.id) || !isId(x.job_id) || !str(x.slot_at, 64) || !str(x.started_at, 64)) return null;
  if ((x.trigger !== "schedule" && x.trigger !== "owner") || !has(RUN_STATUSES, x.status)) return null;
  if ((x.error_code != null && !isCode(x.error_code)) || !optStr(x.summary, 4000) || !optStr(x.finished_at, 64)) return null;
  const raw = x.priorities ?? [];
  if (!Array.isArray(raw) || raw.length > 20) return null;
  const priorities: Priority[] = [];
  for (const p of raw) {
    if (!isObj(p) || !str(p.ref, 16) || !int(p.rank) || !str(p.reason, 600)) return null;
    priorities.push({ ref: p.ref, rank: p.rank, reason: p.reason });
  }
  return {
    id: x.id, job_id: x.job_id, kind: isCode(x.kind) ? x.kind : null, slot_at: x.slot_at, trigger: x.trigger,
    status: x.status as RunStatus, error_code: nul(x.error_code), summary: nul(x.summary),
    // Only a label: a source this build does not know shows none.
    summary_source: SUMMARY_SOURCES.includes(x.summary_source) ? (x.summary_source as SummarySource) : null,
    priorities: priorities.sort((a, b) => a.rank - b.rank), started_at: x.started_at, finished_at: nul(x.finished_at),
  };
}

/** The runs this build can read; a run it cannot (a newer status) is left out. */
export function readRuns(x: unknown): Run[] | null {
  if (!isObj(x) || !Array.isArray(x.runs) || x.runs.length > 50) return null;
  return x.runs.map(readRun).filter((r): r is Run => r !== null);
}

const plain = (x: unknown, max = 200) => (typeof x === "string" && x.length <= max ? x : typeof x === "number" && Number.isFinite(x) ? String(x) : "");
const list = (x: unknown) => (Array.isArray(x) ? x.slice(0, 500).filter(isObj) : []);

/** The facts entries a priority can name (dependency_review: alerts and
 *  pull requests), as plain strings. Facts that do not fit are skipped; a
 *  priority whose ref is not found shows its ref alone. */
function readFacts(facts: Record<string, unknown>): Fact[] {
  const out: Fact[] = [];
  for (const a of list(facts.alerts)) {
    if (!str(a.ref, 16)) continue;
    const title = [plain(a.package), plain(a.severity) && `(${plain(a.severity)})`].filter(Boolean).join(" ");
    const fixed = plain(a.patched) ? `fixed in ${plain(a.patched)}` : "no fixed version yet";
    out.push({ ref: a.ref, title: title || "Alert", detail: [`${plain(a.repo)}#${plain(a.number)}`, fixed, plain(a.ghsa)].filter(Boolean).join(" · ") });
  }
  for (const p of list(facts.pull_requests)) {
    if (!str(p.ref, 16)) continue;
    const bump = plain(p.package) ? `${plain(p.package)} ${plain(p.from)} → ${plain(p.to)}` : plain(p.group) ? `${plain(p.group)} group` : "Pull request";
    out.push({ ref: p.ref, title: bump, detail: `${plain(p.repo)}#${plain(p.number)}` });
  }
  return out;
}

/** A research answer's sources: https links only (validHttpsUrl), each once,
 *  at most 20; the label is the host and path, cut to 80 characters. */
function readSources(x: unknown): Source[] {
  const out: Source[] = [];
  for (const url of Array.isArray(x) ? x.slice(0, 50) : []) {
    if (typeof url !== "string" || !validHttpsUrl(url) || out.some((s) => s.url === url)) continue;
    const u = new URL(url);
    const label = u.hostname + (u.pathname === "/" ? "" : u.pathname);
    out.push({ url, label: label.length > 80 ? `${label.slice(0, 79)}…` : label });
    if (out.length === 20) break;
  }
  return out;
}

export function readRunDetail(x: unknown): RunDetail | null {
  const run = readRun(x);
  if (!run || !isObj(x)) return null;
  const facts = isObj(x.facts) ? x.facts : {};
  const errors = (Array.isArray(facts.errors) ? facts.errors : [])
    .filter((e): e is Record<string, unknown> => isObj(e) && str(e.repo, 200) && isCode(e.error_code))
    .slice(0, 100).map((e) => ({ repo: e.repo as string, error_code: e.error_code as string }));
  return {
    ...run, facts: readFacts(facts), errors, truncated: facts.truncated === true,
    sources: readSources(facts.sources), conversation_id: isId(facts.conversation_id) ? facts.conversation_id : null,
  };
}

// ---- realtime ----------------------------------------------------------

export type RunFinished = { job_id: string; run_id: string; status: string };

/** A `jobs.run_finished` hint. Only the ids and the status are read; the
 *  refetch is the authority, so a status this build does not know yet still
 *  refetches. Null for any other or malformed event. */
export function runFinished(event: unknown): RunFinished | null {
  if (!isObj(event) || event.type !== "jobs.run_finished" || !isObj(event.payload)) return null;
  const { job_id, run_id, status } = event.payload;
  return isId(job_id) && isId(run_id) && str(status, 64) ? { job_id, run_id, status } : null;
}

// ---- dates and schedule wording -------------------------------------------

export const minuteToTime = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

/** "HH:MM" from a time input; null when it is not a time of day. */
export function timeToMinute(text: string): number | null {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(text);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

/** Today in the schedule's zone, "YYYY-MM-DD". */
export function todayInZone(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

/** A real calendar date "YYYY-MM-DD" (2026-02-30 is not). */
function parseDate(ymd: string): Date | null {
  if (!DATE.test(ymd)) return null;
  const d = new Date(`${ymd}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === ymd ? d : null;
}

export function addDays(ymd: string, days: number): string {
  const d = parseDate(ymd);
  return d ? new Date(d.getTime() + days * 86_400_000).toISOString().slice(0, 10) : ymd;
}

/** The latest start date Core takes: two years ahead. */
export const latestStart = (today: string) => addDays(today, LIMITS.startDays);

/** "1 Dec", or "1 Dec 2027" outside today's year. */
export function dateText(ymd: string, today: string): string {
  if (!parseDate(ymd)) return ymd;
  const [y, m, d] = ymd.split("-");
  return `${Number(d)} ${MONTHS[Number(m) - 1]}${y === today.slice(0, 4) ? "" : ` ${y}`}`;
}

export function ordinal(n: number): string {
  const teen = n % 100 >= 11 && n % 100 <= 13;
  return `${n}${teen ? "th" : (["th", "st", "nd", "rd"][n % 10] ?? "th")}`;
}

type ScheduleFields = Pick<Job, "cadence" | "weekday" | "month_day" | "every" | "start_date" | "at_minute">;

/** The schedule in words, without the zone (TIMES_IN_ZONE says it once):
 *  "Once on 1 Dec at 09:00", "Every 2 weeks on Friday at 08:00", "Every 6
 *  months on the 1st at 09:00", plus ", from 1 Jan 2027" while a repeating
 *  job's start date is ahead. */
export function scheduleText(job: ScheduleFields, today: string): string {
  const at = ` at ${minuteToTime(job.at_minute)}`;
  const n = job.every;
  if (job.cadence === "once") return job.start_date ? `Once on ${dateText(job.start_date, today)}${at}` : `Once${at}`;
  const day = WEEKDAYS[(job.weekday ?? 1) - 1];
  const base = job.cadence === "daily" ? (n > 1 ? `Every ${n} days` : "Daily")
    : job.cadence === "weekly" ? (n > 1 ? `Every ${n} weeks on ${day}` : `Every ${day}`)
    : `${n > 1 ? `Every ${n} months` : "Monthly"} on the ${ordinal(job.month_day ?? 1)}`;
  const from = job.start_date && job.start_date > today ? `, from ${dateText(job.start_date, today)}` : "";
  return `${base}${at}${from}`;
}

/** "2 of 5 runs done" for a job with a run limit; null without one. */
export const runsText = (job: Pick<Job, "max_runs" | "run_count">): string | null =>
  job.max_runs ? `${Math.min(job.run_count, job.max_runs)} of ${job.max_runs} runs done` : null;

/** A moment from Core, shown in the schedule's zone. */
export function zonedText(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "an unknown time";
  return d.toLocaleString("en-GB", {
    timeZone: TIME_ZONE, weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit",
  });
}

/** "13 Dec": a moment's day in the schedule's zone. */
export function dayText(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "an unknown day" : d.toLocaleDateString("en-GB", { timeZone: TIME_ZONE, day: "numeric", month: "short" });
}

// ---- job state wording -------------------------------------------------------

export const jobTitle = (job: Job) => job.params.title ?? KIND_LABEL[job.kind];

export const PAUSED_AFTER_FAILURES = "Paused after 3 failed runs";

/** A one-time job after its slot, or a job that reached its run limit:
 *  Core disabled it because it is done, not paused. */
export const isFinished = (job: Job) =>
  !job.enabled && ((job.cadence === "once" && job.last_slot_at !== null) || (job.max_runs !== null && job.run_count >= job.max_runs));

/** The job's state badge: label and tone. */
export function stateBadge(job: Job): { label: string; tone: "" | "pos" | "dng" } {
  if (job.enabled) return { label: "Enabled", tone: "pos" };
  if (job.paused_reason === "paused_after_failures") return { label: PAUSED_AFTER_FAILURES, tone: "dng" };
  return { label: isFinished(job) ? "Finished" : "Paused", tone: "" };
}

/** When it runs next, for the list. */
export function nextRunText(job: Job): string {
  if (!job.enabled) return stateBadge(job).label;
  return job.next_run_at ? zonedText(job.next_run_at) : "Not scheduled";
}

/** "Set up by Jarvis · expires 13 Dec" for a job Jarvis made; null for yours. */
export function originText(job: Job): string | null {
  if (job.origin !== "jarvis") return null;
  return job.expires_at ? `Set up by Jarvis · expires ${dayText(job.expires_at)}` : "Set up by Jarvis";
}

/** The owner's jobs that count towards Core's 10 (not the dependency review,
 *  not Jarvis's own). */
export const ownerJobCount = (jobs: Job[]) => jobs.filter((j) => j.origin === "owner" && j.kind !== "dependency_review").length;
export const countText = (n: number) => `${n} of ${LIMITS.ownerJobs} jobs`;
export const FULL_TEXT = `You have ${LIMITS.ownerJobs} jobs, the most Core keeps. Delete one to add another.`;
export const unsupportedText = (n: number) =>
  `${n === 1 ? "1 job" : `${n} jobs`} this app cannot show yet. Update the app to see ${n === 1 ? "it" : "them"}.`;

export const deleteNote = (job: Job) =>
  job.conversation_id ? "Its runs are deleted too. Its conversation stays in chat." : "Its runs are deleted too.";

// ---- editor ------------------------------------------------------------------

/** Number inputs hand over a number, or the text when it does not parse. */
type NumberField = number | string;
export type Draft = {
  kind: Kind; cadence: Cadence; weekday: number; month_day: number; every: NumberField; start_date: string;
  max_runs: NumberField; time: string; enabled: boolean;
  roadmap_id: string | null; title: string; instruction: string; text: string; notify: boolean; threshold: NumberField;
};

export const newDraft = (kind: Kind = "prompt"): Draft => ({
  kind, cadence: "weekly", weekday: 1, month_day: 1, every: 1, start_date: "", max_runs: "", time: "07:00", enabled: true,
  roadmap_id: null, title: "", instruction: "", text: "", notify: true, threshold: LIMITS.threshold,
});

export const toDraft = (job: Job): Draft => ({
  kind: job.kind, cadence: job.cadence, weekday: job.weekday ?? 1, month_day: job.month_day ?? 1, every: job.every,
  start_date: job.start_date ?? "", max_runs: job.max_runs ?? "", time: minuteToTime(job.at_minute), enabled: job.enabled,
  roadmap_id: job.params.roadmap_id, title: job.params.title ?? "", instruction: job.params.instruction ?? "",
  text: job.params.text ?? "", notify: job.params.notify, threshold: job.params.threshold_percent,
});

/** A whole number from a number input; "" for an empty one, null for junk. */
function count(x: NumberField): number | "" | null {
  const s = String(x).trim();
  if (!s) return "";
  return /^\d{1,6}$/.test(s) ? Number(s) : null;
}

export type Field = "title" | "instruction" | "text" | "threshold" | "every" | "start_date" | "max_runs" | "time";
export type DraftIssue = { field: Field; message: string };

/** The first rule the draft breaks (Core's bounds, checked early), or null. */
export function draftIssue(d: Draft, today: string): DraftIssue | null {
  const issue = (field: Field, message: string): DraftIssue => ({ field, message });
  if (d.kind === "prompt" || d.kind === "reminder") {
    if (!validText(d.title, LIMITS.title)) return issue("title", `Give it a title of 1 to ${LIMITS.title} characters on one line.`);
  }
  if (d.kind === "prompt" && !validText(d.instruction, LIMITS.instruction)) {
    return issue("instruction", `Say what to research in 1 to ${LIMITS.instruction} characters on one line.`);
  }
  if (d.kind === "reminder" && d.text.trim() && !validText(d.text, LIMITS.text)) {
    return issue("text", `The text can be at most ${LIMITS.text} characters on one line.`);
  }
  if (d.kind === "disk_check") {
    const t = count(d.threshold);
    if (typeof t !== "number" || t < LIMITS.thresholdMin || t > LIMITS.thresholdMax) {
      return issue("threshold", `Pick a threshold from ${LIMITS.thresholdMin} to ${LIMITS.thresholdMax} percent.`);
    }
  }
  if (d.kind !== "dependency_review") {
    if (d.cadence !== "once") {
      const every = count(d.every);
      const most = LIMITS.every[d.cadence];
      if (typeof every !== "number" || every < 1 || every > most) {
        return issue("every", `Repeat every 1 to ${most} ${EVERY_UNIT[d.cadence][1]}.`);
      }
      const max = count(d.max_runs);
      if (max === null || (max !== "" && (max < 1 || max > LIMITS.maxRuns))) {
        return issue("max_runs", `Stop after 1 to ${LIMITS.maxRuns} runs, or leave it empty.`);
      }
    }
    if (d.cadence === "once" && !d.start_date) return issue("start_date", "Pick the day it runs.");
    if (d.start_date && (!parseDate(d.start_date) || d.start_date > latestStart(today))) {
      return issue("start_date", "Pick a date at most two years ahead.");
    }
    if (d.cadence === "once" && d.enabled && d.start_date < today) return issue("start_date", "Pick a day from today on.");
  }
  if (timeToMinute(d.time) === null) return issue("time", "Pick a time of day.");
  return null;
}

export type BodyParams = {
  roadmap_id?: string; title?: string; instruction?: string; text?: string; notify?: boolean; threshold_percent?: number;
};
type Schedule = {
  cadence: Cadence; weekday?: number; month_day?: number; every?: number; start_date?: string | null;
  max_runs?: number | null; at_minute: number; enabled: boolean;
};
/** Only the fields a client may send; Core refuses a body with its own. */
export type NewJob = Schedule & { kind: Kind; params: BodyParams };
export type JobPatch = Partial<Schedule> & { params?: BodyParams };

/** The kind's params, complete: a change replaces the stored ones. */
function paramsOf(d: Draft): BodyParams {
  switch (d.kind) {
    case "prompt": return { title: d.title.trim(), instruction: d.instruction.trim(), notify: d.notify };
    case "reminder": return { title: d.title.trim(), ...(d.text.trim() ? { text: d.text.trim() } : {}) };
    case "disk_check": return { threshold_percent: Number(count(d.threshold)) };
    case "dependency_review": return d.roadmap_id ? { roadmap_id: d.roadmap_id } : {};
  }
}

/** The schedule as Core stores it from this draft: no every, start date or
 *  run limit for a dependency review, no run limit for a one-time job. */
function scheduleOf(d: Draft) {
  const fixed = d.kind === "dependency_review";
  const repeats = !fixed && d.cadence !== "once";
  const max = count(d.max_runs);
  return {
    every: repeats ? Number(count(d.every)) : 1,
    start_date: !fixed && d.start_date ? d.start_date : null,
    max_runs: repeats && typeof max === "number" ? max : null,
    at_minute: timeToMinute(d.time) ?? 0,
  };
}

/** The create body; null while the draft breaks a rule. A weekday goes only
 *  with a weekly job and a month day only with a monthly one (Core refuses
 *  either elsewhere). */
export function newJob(d: Draft, today: string): NewJob | null {
  if (draftIssue(d, today)) return null;
  const s = scheduleOf(d);
  return {
    kind: d.kind, cadence: d.cadence,
    ...(d.cadence === "weekly" ? { weekday: d.weekday } : {}),
    ...(d.cadence === "monthly" ? { month_day: d.month_day } : {}),
    ...(s.every > 1 ? { every: s.every } : {}),
    ...(s.start_date ? { start_date: s.start_date } : {}),
    ...(s.max_runs !== null ? { max_runs: s.max_runs } : {}),
    at_minute: s.at_minute, enabled: d.enabled, params: paramsOf(d),
  };
}

/** Only what changed, so an unchanged editor sends nothing (null), measured
 *  against what Core keeps when a field is left out: `every` falls back to 1
 *  and a one-time job drops its run limit when the cadence changes. Null
 *  clears a start date or run limit. Changed params go whole. */
export function jobPatch(job: Job, d: Draft): JobPatch | null {
  const patch: JobPatch = {};
  const s = scheduleOf(d);
  const cadenceChanged = d.cadence !== job.cadence;
  if (cadenceChanged) patch.cadence = d.cadence;
  if (d.cadence === "weekly" && d.weekday !== job.weekday) patch.weekday = d.weekday;
  if (d.cadence === "monthly" && d.month_day !== job.month_day) patch.month_day = d.month_day;
  if (d.kind !== "dependency_review") {
    if (d.cadence !== "once" && s.every !== (cadenceChanged ? 1 : job.every)) patch.every = s.every;
    if (s.start_date !== job.start_date) patch.start_date = s.start_date;
    if (s.max_runs !== (d.cadence === "once" ? null : job.max_runs)) patch.max_runs = s.max_runs;
  }
  if (s.at_minute !== job.at_minute) patch.at_minute = s.at_minute;
  if (d.enabled !== job.enabled) patch.enabled = d.enabled;
  const params = paramsOf(d);
  if (JSON.stringify(params) !== JSON.stringify(paramsOf(toDraft(job)))) patch.params = params;
  return Object.keys(patch).length ? patch : null;
}

// ---- fixed wording ---------------------------------------------------------

const ERROR_TEXT: Record<string, string> = {
  not_configured: "Not set up on Core: something this job needs is turned off on your Home Node.",
  timed_out: "The run took longer than 15 minutes and was stopped.",
  interrupted: "Core stopped during the run, so it did not finish.",
  invalid_result: "Core refused to store the result of this run.",
  internal: "Something went wrong in Core during the run.",
  private_instruction:
    "Not searched: the title or instruction may hold personal or financial details or a symbol you hold. Change the job to leave them out.",
  holdings_unavailable: "Not searched: Core could not read your holdings to check the instruction for private details.",
  research_unavailable: "The web search did not run or was stopped.",
  conversation_unavailable: "The answer could not be stored in the job's conversation.",
  disk_unavailable: "No Home Node disk could be read.",
  token_needs_dependabot_alerts_read:
    "GitHub refused the Dependabot alerts. Give the GitHub token the \"Dependabot alerts: Read-only\" permission for the repository, and turn on Dependabot alerts in its settings.",
  github_access_refused: "GitHub refused the list of pull requests. The repository's alerts were kept.",
  github_token_refused: "GitHub refused the token. Check that it is valid and has not expired.",
  github_rate_limited: "GitHub's rate limit was reached. The next run tries again.",
  github_answer_too_large: "A GitHub answer was larger than 1 MiB and was not read.",
  github_redirect_refused: "GitHub redirected to another host, which Core refuses.",
  github_unavailable: "GitHub could not be reached or answered with an error.",
  roadmap_unavailable: "The roadmap is gone or archived, so no proposal was made.",
  roadmap_changed: "The roadmap changed while the run read it. The next run proposes again.",
  roadmap_limit: "The roadmap holds its maximum of items or pending proposals, so no proposal was made.",
  roadmap_proposal_failed: "The roadmap proposal could not be made.",
};
/** Where a code means something else for one kind of job. */
const KIND_ERROR_TEXT: Record<string, Record<string, string>> = {
  dependency_review: {
    not_configured: "Not set up on Core: the GitHub token or the repository list (JARVIS_GITHUB_REPOS) is missing on your Home Node.",
  },
  prompt: {
    not_configured: "Not searched: Research is off, or no subscription worker can run it.",
    timed_out: "The web search took longer than 5 minutes and was stopped.",
  },
};

const own = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);

/** The owner-facing text for an error code; never the code's own words. */
export function errorText(code: string, kind?: string | null): string {
  const byKind = kind && own(KIND_ERROR_TEXT, kind) ? KIND_ERROR_TEXT[kind] : {};
  if (own(byKind, code)) return byKind[code];
  return own(ERROR_TEXT, code) ? ERROR_TEXT[code] : "Core reported an error this app does not know yet.";
}

export const SUMMARY_SOURCE_LABEL: Record<SummarySource, string> = {
  claude: "Written by Claude", deterministic: "Written by Core's template", web: "From a web search",
};
export const TRIGGER_LABEL = { schedule: "Scheduled", owner: "Run by you" } as const;

export const NOT_CONFIGURED_TEXT =
  "Core's scheduler is off: it could not read the Europe/Amsterdam time zone on your Home Node. Jobs can be changed, but none runs.";

export type Failure = "no-api" | "unreachable" | "signin" | "not-found" | "unavailable" | "failed";

/** `list`: the failure is on GET /v1/jobs, where 404 means an older Core. */
export function failureOf(error: unknown, list = false): Failure {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "NetworkError") return "unreachable";
  if (e?.name === "ApiError") {
    if (e.status === 401) return "signin";
    if (e.status === 404) return list ? "no-api" : "not-found";
    if (e.status === 405 || e.status === 501) return "no-api";
    if (e.status === 503) return "unavailable";
  }
  return "failed";
}

export function failureText(f: Failure): string {
  switch (f) {
    case "no-api": return `${NEEDS_CORE} Update Core on your Home Node.`;
    case "unreachable": return "No answer from your Home Node. Check the connection.";
    case "signin": return "Your session is not valid. Sign in again on the Core page.";
    case "not-found": return "This does not exist on your Home Node (any more).";
    case "unavailable": return "Core could not read the jobs just now. Try again in a minute.";
    case "failed": return "Core returned an answer this app does not understand, or an error. Try again later.";
  }
}

export const RUN_NOW_REFUSED = "Not started: a job runs on request at most once an hour, and not while a run is queued or running.";

/** The notice after a refused "Run now". */
export function runNowFailureText(error: unknown): string {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "ApiError" && e.status === 429) return RUN_NOW_REFUSED;
  if (e?.name === "ApiError" && e.status === 409) return "Not started: Core's scheduler is off, so no job runs.";
  if (e?.name === "NetworkError") return "No answer from your Home Node. It is unknown whether the run was queued; reloaded to check.";
  return `Not started. ${failureText(failureOf(error))}`;
}

export const CHANGED_ELSEWHERE = "Changed elsewhere, reloaded.";
/** A write Core answered with 2xx but an answer this build cannot read. */
export const NOT_CONFIRMED = "Not confirmed; reloaded to check.";
export const INVALID_TEXT =
  "Core refused this as invalid: for example a one-time job that is not in the future, an archived roadmap, or a research title or instruction that may hold personal or financial details. Check the fields and try again.";

/** The notice after a failed write. A 409 on a change or delete is a
 *  revision conflict; on a create, no room: a second dependency review, or
 *  the owner's 10 jobs. */
export function writeFailureText(error: unknown, update: boolean, kind?: Kind): string {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "ApiError" && e.status === 409) {
    if (update) return CHANGED_ELSEWHERE;
    return kind === "dependency_review" ? "Core refused this: a dependency review already exists." : `Core refused this. ${FULL_TEXT}`;
  }
  if (e?.name === "ApiError" && e.status === 400) return INVALID_TEXT;
  if (e?.name === "ApiError" && e.status === 503) {
    return "Not saved: Core could not check this just now (its database or your holdings could not be read). Try again in a minute.";
  }
  if (e?.name === "NetworkError") return "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check.";
  return `Not saved. ${failureText(failureOf(error))}`;
}
