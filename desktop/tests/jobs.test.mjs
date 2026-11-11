import assert from "node:assert/strict";
import test from "node:test";

import {
  CHANGED_ELSEWHERE, NEEDS_CORE, RUN_NOW_REFUSED, cadenceText, errorText, failureOf, failureText, jobPatch, minuteToTime, newDraft,
  newJob, readJobList, readRunDetail, readRuns, runFinished, runNowFailureText, timeToMinute, toDraft, writeFailureText, zonedText,
} from "../src/jobs.ts";

// Neutral test data only.
const J1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71";
const U1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c72";
const job = {
  id: J1, kind: "dependency_review", cadence: "weekly", weekday: 1, at_minute: 420, enabled: true, revision: 3,
  params: {}, last_slot_at: null, created_at: "2026-11-01T10:00:00Z", updated_at: "2026-11-02T10:00:00Z",
  next_run_at: "2026-11-16T06:00:00Z",
};
const run = {
  id: U1, job_id: J1, kind: "dependency_review", slot_at: "2026-11-09T06:00:00Z", trigger: "schedule", status: "ok",
  error_code: null, summary: "<b>Two</b> fixes", summary_source: "claude",
  priorities: [{ ref: "a2", rank: 2, reason: "b" }, { ref: "a1", rank: 1, reason: "a" }],
  started_at: "2026-11-09T06:00:01Z", finished_at: "2026-11-09T06:01:00Z",
};
const apiError = (status) => ({ name: "ApiError", status });

test("job list guard accepts Core's shape and fails closed", () => {
  const list = readJobList({ status: "ok", time_zone: "Europe/Amsterdam", jobs: [job] });
  assert.equal(list.configured, true);
  assert.equal(list.jobs[0].roadmap_id, null);
  assert.equal(readJobList({ status: "not_configured", jobs: [{ ...job, params: { roadmap_id: "r1" } }] }).jobs[0].roadmap_id, "r1");
  assert.equal(readJobList({ status: "not_configured", jobs: [] }).configured, false);
  for (const broken of [
    null, [], { jobs: [job] }, { status: "ok" },
    { status: "ok", jobs: [{ ...job, kind: "free_text" }] },
    { status: "ok", jobs: [{ ...job, cadence: "hourly" }] },
    { status: "ok", jobs: [{ ...job, weekday: 8 }] },
    { status: "ok", jobs: [{ ...job, at_minute: 1440 }] },
    { status: "ok", jobs: [{ ...job, revision: "3" }] },
    { status: "ok", jobs: [{ ...job, params: { roadmap_id: 5 } }] },
    { status: "ok", jobs: [{ ...job, id: "../x" }] },
  ]) assert.equal(readJobList(broken), null, JSON.stringify(broken)?.slice(0, 80));
});

test("run guards order priorities, keep text as data and fail closed", () => {
  const [r] = readRuns({ runs: [run] });
  assert.deepEqual(r.priorities.map((p) => p.ref), ["a1", "a2"]);
  assert.equal(r.summary, "<b>Two</b> fixes", "kept verbatim; the page renders it as text");
  for (const broken of [
    { ...run, status: "done" },
    { ...run, trigger: "cron" },
    { ...run, summary_source: "gpt" },
    { ...run, error_code: "Upstream said <b>no</b>" },
    { ...run, job_id: "j1" },
    { ...run, priorities: [{ ref: "a1", rank: "1", reason: "x" }] },
    { ...run, priorities: Array.from({ length: 21 }, (_, i) => ({ ref: `a${i}`, rank: i + 1, reason: "x" })) },
  ]) assert.equal(readRuns({ runs: [broken] }), null, JSON.stringify(broken).slice(0, 80));
});

test("run detail resolves priorities to facts and keeps repo errors", () => {
  const detail = readRunDetail({
    ...run, status: "partial", error_code: "token_needs_dependabot_alerts_read",
    facts: {
      alerts: [{ ref: "a1", repo: "o/r", number: 4, package: "left-pad", severity: "high", patched: "1.3.0", ghsa: "GHSA-x" }],
      pull_requests: [{ ref: "p1", repo: "o/r", number: 9, package: "serde", from: "1.0.1", to: "1.0.2" }],
      errors: [{ repo: "o/s", error_code: "token_needs_dependabot_alerts_read" }, { repo: "o/t", error_code: "Bad text" }],
      truncated: true,
    },
  });
  assert.deepEqual(detail.facts[0], { ref: "a1", title: "left-pad (high)", detail: "o/r#4 · fixed in 1.3.0 · GHSA-x" });
  assert.equal(detail.facts[1].title, "serde 1.0.1 → 1.0.2");
  assert.deepEqual(detail.errors, [{ repo: "o/s", error_code: "token_needs_dependabot_alerts_read" }]);
  assert.equal(detail.truncated, true);
  // Without facts (a failed or missed run) the detail still reads.
  assert.deepEqual(readRunDetail({ ...run, facts: null }).facts, []);
});

test("error codes get fixed owner-facing wording", () => {
  assert.match(errorText("token_needs_dependabot_alerts_read"), /"Dependabot alerts: Read-only"/);
  assert.match(errorText("not_configured"), /GitHub token or the repository list/);
  assert.equal(errorText("something_new"), "Core reported an error this app does not know yet.");
  assert.equal(errorText("toString"), "Core reported an error this app does not know yet.");
});

test("jobs.run_finished is read for ids and status only", () => {
  const event = { type: "jobs.run_finished", payload: { job_id: J1, run_id: U1, status: "failed", summary: "x" } };
  assert.deepEqual(runFinished(event), { job_id: J1, run_id: U1, status: "failed" });
  // A status a newer Core adds still refetches.
  assert.equal(runFinished({ ...event, payload: { job_id: J1, run_id: U1, status: "skipped" } }).run_id, U1);
  for (const other of [
    { type: "roadmap.changed", payload: { id: "r1", revision: 1 } },
    { type: "jobs.run_finished", payload: { job_id: J1, run_id: 5, status: "ok" } },
    { type: "jobs.run_finished", payload: { job_id: J1, run_id: "u1", status: "ok" } },
    { type: "jobs.run_finished", payload: { job_id: J1, run_id: U1 } },
    { type: "jobs.run_finished" },
    null,
  ]) assert.equal(runFinished(other), null);
});

test("schedule text and time conversion", () => {
  assert.equal(minuteToTime(420), "07:00");
  assert.equal(minuteToTime(1439), "23:59");
  assert.equal(timeToMinute("07:30"), 450);
  for (const bad of ["", "24:00", "7:30", "07:60"]) assert.equal(timeToMinute(bad), null);
  assert.equal(cadenceText({ cadence: "weekly", weekday: 7, at_minute: 1290 }), "Every Sunday at 21:30 (Europe/Amsterdam)");
  assert.equal(cadenceText({ cadence: "daily", weekday: null, at_minute: 0 }), "Daily at 00:00 (Europe/Amsterdam)");
  // 06:00 UTC in November is 07:00 in Amsterdam.
  assert.match(zonedText("2026-11-16T06:00:00Z"), /07:00/);
  assert.equal(zonedText("nope"), "an unknown time");
});

test("the editor sends only what changed and never a daily weekday", () => {
  const [j] = readJobList({ status: "ok", jobs: [job] }).jobs;
  assert.equal(jobPatch(j, toDraft(j)), null);
  assert.deepEqual(jobPatch(j, { ...toDraft(j), cadence: "daily" }), { cadence: "daily" });
  assert.deepEqual(jobPatch(j, { ...toDraft(j), weekday: 3, time: "08:15", enabled: false }), { weekday: 3, at_minute: 495, enabled: false });
  assert.deepEqual(jobPatch(j, { ...toDraft(j), roadmap_id: "r1" }), { params: { roadmap_id: "r1" } });
  const linked = { ...j, roadmap_id: "r1" };
  assert.deepEqual(jobPatch(linked, { ...toDraft(linked), roadmap_id: null }), { params: {} }, "empty params clear the roadmap");
  const daily = { ...j, cadence: "daily", weekday: null };
  assert.deepEqual(jobPatch(daily, { ...toDraft(daily), cadence: "weekly" }), { cadence: "weekly", weekday: 1 });
  assert.deepEqual(newJob("dependency_review", { ...newDraft(), cadence: "daily", weekday: 4 }), {
    kind: "dependency_review", cadence: "daily", at_minute: 420, enabled: true, params: {},
  });
  assert.equal(newJob("dependency_review", { ...newDraft(), time: "" }), null);
});

test("an older Core and refusals get fixed wording", () => {
  assert.equal(failureOf(apiError(404), true), "no-api");
  assert.equal(failureOf(apiError(404)), "not-found");
  assert.match(failureText("no-api"), new RegExp(NEEDS_CORE.replace(/\./g, "\\.")));
  assert.equal(NEEDS_CORE, "Needs Jarvis Core v0.0.80 or later.");
  assert.equal(runNowFailureText(apiError(429)), RUN_NOW_REFUSED);
  assert.match(runNowFailureText(apiError(409)), /scheduler is off/);
  // A 409 on a change is always a revision conflict; only a create can mean "exists".
  assert.equal(writeFailureText(apiError(409), true), CHANGED_ELSEWHERE);
  assert.match(writeFailureText(apiError(409), false), /already exists/);
});
