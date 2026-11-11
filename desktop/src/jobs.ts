// Scheduled jobs: Core's contract (/v1/jobs, docs/SCHEDULED_JOBS.md in the
// Core repository), response guards, the editor's draft and the fixed
// owner-facing wording for statuses and error codes. Pure: no Vue or Tauri
// imports, so tests load this file directly.
//
// Every write names the revision it builds on (?expected_revision=N); Core
// answers 409 when the job moved on, and the page then reloads. Run text
// from Core (summary, reasons) is display only and always rendered as plain
// text; error codes are shown only through the fixed wording below.

export const TIME_ZONE = "Europe/Amsterdam";
export const NEEDS_CORE = "Needs Jarvis Core v0.0.80 or later.";

export const KINDS = ["dependency_review"] as const;
export type Kind = (typeof KINDS)[number];
export const KIND_LABEL: Record<Kind, string> = { dependency_review: "Dependency review" };
export const KIND_DETAIL: Record<Kind, string> = {
  dependency_review: "Reads the open Dependabot alerts and pull requests of your repositories and puts them in order.",
};

export type Cadence = "daily" | "weekly";
export const WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export const RUN_STATUSES = ["running", "ok", "partial", "failed", "missed", "interrupted"] as const;
export type RunStatus = (typeof RUN_STATUSES)[number];
export const RUN_STATUS_LABEL: Record<RunStatus, string> = {
  running: "Running", ok: "OK", partial: "Partial", failed: "Failed", missed: "Missed", interrupted: "Interrupted",
};
/** Badge tone (components.css); the label always carries the meaning too. */
export const RUN_STATUS_TONE: Record<RunStatus, "" | "pos" | "acc" | "neg" | "dng"> = {
  running: "acc", ok: "pos", partial: "neg", failed: "dng", missed: "", interrupted: "neg",
};

export type Job = {
  id: string; kind: Kind; cadence: Cadence; weekday: number | null; at_minute: number; enabled: boolean;
  revision: number; roadmap_id: string | null; last_slot_at: string | null; next_run_at: string | null;
};
export type JobList = { configured: boolean; jobs: Job[] };
export type Priority = { ref: string; rank: number; reason: string };
export type Run = {
  id: string; job_id: string; slot_at: string; trigger: "schedule" | "owner"; status: RunStatus;
  error_code: string | null; summary: string | null; summary_source: "claude" | "deterministic" | null;
  priorities: Priority[]; started_at: string; finished_at: string | null;
};
/** A fact a priority points at, reduced to plain display strings. */
export type Fact = { ref: string; title: string; detail: string };
export type RunDetail = Run & { facts: Fact[]; errors: { repo: string; error_code: string }[]; truncated: boolean };

// ---- response guards: anything unexpected reads as null (fail closed) -----

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);
const str = (x: unknown, max = 10_000): x is string => typeof x === "string" && x.length <= max;
const optStr = (x: unknown, max?: number) => x === undefined || x === null || str(x, max);
const int = (x: unknown): x is number => typeof x === "number" && Number.isSafeInteger(x) && x >= 0;
const CODE = /^[a-z0-9_]{1,64}$/;
// Job and run ids go into app routes; Core's are UUIDs.
const isId = (x: unknown): x is string => typeof x === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(x);
const isCode = (x: unknown): x is string => typeof x === "string" && CODE.test(x);
const nul = <T>(x: unknown) => (x ?? null) as T | null;

function readJob(x: unknown): Job | null {
  if (!isObj(x) || !isId(x.id) || !(KINDS as readonly unknown[]).includes(x.kind)) return null;
  if (x.cadence !== "daily" && x.cadence !== "weekly") return null;
  if (x.weekday != null && !(int(x.weekday) && x.weekday >= 1 && x.weekday <= 7)) return null;
  if (!int(x.at_minute) || x.at_minute > 1439 || typeof x.enabled !== "boolean" || !int(x.revision)) return null;
  if (x.params != null && !(isObj(x.params) && optStr(x.params.roadmap_id, 64))) return null;
  if (!optStr(x.last_slot_at, 64) || !optStr(x.next_run_at, 64)) return null;
  return {
    id: x.id, kind: x.kind as Kind, cadence: x.cadence, weekday: nul<number>(x.weekday), at_minute: x.at_minute,
    enabled: x.enabled, revision: x.revision, roadmap_id: nul<string>(isObj(x.params) ? x.params.roadmap_id : null),
    last_slot_at: nul<string>(x.last_slot_at), next_run_at: nul<string>(x.next_run_at),
  };
}

export function readJobList(x: unknown): JobList | null {
  if (!isObj(x) || (x.status !== "ok" && x.status !== "not_configured") || !Array.isArray(x.jobs) || x.jobs.length > 50) return null;
  const jobs = x.jobs.map(readJob);
  return jobs.every(Boolean) ? { configured: x.status === "ok", jobs: jobs as Job[] } : null;
}

export const readJobAnswer = readJob;

function readRun(x: unknown): Run | null {
  if (!isObj(x) || !isId(x.id) || !isId(x.job_id) || !str(x.slot_at, 64) || !str(x.started_at, 64)) return null;
  if ((x.trigger !== "schedule" && x.trigger !== "owner") || !(RUN_STATUSES as readonly unknown[]).includes(x.status)) return null;
  if ((x.error_code != null && !isCode(x.error_code)) || !optStr(x.summary, 4000) || !optStr(x.finished_at, 64)) return null;
  if (x.summary_source != null && x.summary_source !== "claude" && x.summary_source !== "deterministic") return null;
  const raw = x.priorities ?? [];
  if (!Array.isArray(raw) || raw.length > 20) return null;
  const priorities: Priority[] = [];
  for (const p of raw) {
    if (!isObj(p) || !str(p.ref, 16) || !int(p.rank) || !str(p.reason, 600)) return null;
    priorities.push({ ref: p.ref, rank: p.rank, reason: p.reason });
  }
  return {
    id: x.id, job_id: x.job_id, slot_at: x.slot_at, trigger: x.trigger, status: x.status as RunStatus,
    error_code: nul<string>(x.error_code), summary: nul<string>(x.summary),
    summary_source: nul<"claude" | "deterministic">(x.summary_source), priorities: priorities.sort((a, b) => a.rank - b.rank),
    started_at: x.started_at, finished_at: nul<string>(x.finished_at),
  };
}

export function readRuns(x: unknown): Run[] | null {
  if (!isObj(x) || !Array.isArray(x.runs) || x.runs.length > 50) return null;
  const runs = x.runs.map(readRun);
  return runs.every(Boolean) ? (runs as Run[]) : null;
}

const plain = (x: unknown, max = 200) => (typeof x === "string" && x.length <= max ? x : typeof x === "number" && Number.isFinite(x) ? String(x) : "");

/** The facts entries a priority can name (dependency_review: alerts and
 *  pull requests), as plain strings. Facts that do not fit are skipped; a
 *  priority whose ref is not found shows its ref alone. */
function readFacts(facts: Record<string, unknown>): Fact[] {
  const out: Fact[] = [];
  const list = (x: unknown) => (Array.isArray(x) ? x.slice(0, 500).filter(isObj) : []);
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

export function readRunDetail(x: unknown): RunDetail | null {
  const run = readRun(x);
  if (!run || !isObj(x)) return null;
  const facts = isObj(x.facts) ? x.facts : {};
  const errors = (Array.isArray(facts.errors) ? facts.errors : [])
    .filter((e): e is Record<string, unknown> => isObj(e) && str(e.repo, 200) && isCode(e.error_code))
    .slice(0, 100).map((e) => ({ repo: e.repo as string, error_code: e.error_code as string }));
  return { ...run, facts: readFacts(facts), errors, truncated: facts.truncated === true };
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

// ---- schedule and editor ---------------------------------------------------

export const minuteToTime = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

/** "HH:MM" from a time input; null when it is not a time of day. */
export function timeToMinute(text: string): number | null {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(text);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

export function cadenceText(job: Pick<Job, "cadence" | "weekday" | "at_minute">): string {
  const at = `${minuteToTime(job.at_minute)} (${TIME_ZONE})`;
  return job.cadence === "daily" ? `Daily at ${at}` : `Every ${WEEKDAYS[(job.weekday ?? 1) - 1]} at ${at}`;
}

/** A moment from Core, shown in the schedule's zone. */
export function zonedText(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "an unknown time";
  return d.toLocaleString("en-GB", {
    timeZone: TIME_ZONE, weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit",
  });
}

export type Draft = { cadence: Cadence; weekday: number; time: string; enabled: boolean; roadmap_id: string | null };

export const newDraft = (): Draft => ({ cadence: "weekly", weekday: 1, time: "07:00", enabled: true, roadmap_id: null });
export const toDraft = (job: Job): Draft => ({
  cadence: job.cadence, weekday: job.weekday ?? 1, time: minuteToTime(job.at_minute), enabled: job.enabled, roadmap_id: job.roadmap_id,
});

export type JobPatch = { cadence?: Cadence; weekday?: number; at_minute?: number; enabled?: boolean; params?: { roadmap_id?: string } };
export type NewJob = { kind: Kind; cadence: Cadence; weekday?: number; at_minute: number; enabled: boolean; params: { roadmap_id?: string } };

const params = (roadmapId: string | null) => (roadmapId ? { roadmap_id: roadmapId } : {});

/** The create body; null when the time is not valid. A daily job sends no
 *  weekday (Core refuses one). */
export function newJob(kind: Kind, draft: Draft): NewJob | null {
  const at_minute = timeToMinute(draft.time);
  if (at_minute === null) return null;
  return {
    kind, cadence: draft.cadence, ...(draft.cadence === "weekly" ? { weekday: draft.weekday } : {}),
    at_minute, enabled: draft.enabled, params: params(draft.roadmap_id),
  };
}

/** Only what changed, so an unchanged editor sends nothing (null). Empty
 *  params clear the roadmap. A weekday is sent only for a weekly job. */
export function jobPatch(job: Job, draft: Draft): JobPatch | null {
  const patch: JobPatch = {};
  const at = timeToMinute(draft.time);
  if (draft.cadence !== job.cadence) patch.cadence = draft.cadence;
  if (draft.cadence === "weekly" && draft.weekday !== job.weekday) patch.weekday = draft.weekday;
  if (at !== null && at !== job.at_minute) patch.at_minute = at;
  if (draft.enabled !== job.enabled) patch.enabled = draft.enabled;
  if (draft.roadmap_id !== job.roadmap_id) patch.params = params(draft.roadmap_id);
  return Object.keys(patch).length ? patch : null;
}

// ---- fixed wording ---------------------------------------------------------

const ERROR_TEXT: Record<string, string> = {
  not_configured: "Not set up on Core: the GitHub token or the repository list (JARVIS_GITHUB_REPOS) is missing on your Home Node.",
  timed_out: "The run took longer than 15 minutes and was stopped.",
  interrupted: "Core stopped during the run, so it did not finish.",
  invalid_result: "Core refused to store the result of this run.",
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

/** The owner-facing text for an error code; never the code's own words. */
export const errorText = (code: string): string =>
  Object.prototype.hasOwnProperty.call(ERROR_TEXT, code) ? ERROR_TEXT[code] : "Core reported an error this app does not know yet.";

export const SUMMARY_SOURCE_LABEL = { claude: "Written by Claude", deterministic: "Written by Core's template" } as const;
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

/** The notice after a failed write. A 409 on a change is a revision
 *  conflict; on a create, a second job of the same kind. */
export function writeFailureText(error: unknown, update: boolean): string {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "ApiError" && e.status === 409) return update ? CHANGED_ELSEWHERE : "Core refused this: a job of this kind already exists.";
  if (e?.name === "ApiError" && e.status === 400) return "Core refused this as invalid, for example a roadmap that is archived. Check the fields and try again.";
  if (e?.name === "NetworkError") return "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check.";
  return `Not saved. ${failureText(failureOf(error))}`;
}
