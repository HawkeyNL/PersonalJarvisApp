import assert from "node:assert/strict";
import test from "node:test";

import {
  CHANGED_ELSEWHERE, FULL_TEXT, INVALID_TEXT, NEEDS_CORE, PAUSED_AFTER_FAILURES, RUN_NOW_REFUSED, countText, dateText, deleteFailureText, deleteNote,
  draftIssue, errorText, failureOf, failureText, isFinished, jobPatch, jobTitle, latestStart, minuteToTime, newDraft, newJob, nextRunText,
  ordinal, originText, ownerJobCount, readJobAnswer, readJobList, readRunDetail, readRuns, runFinished, runNowFailureText, runsText,
  scheduleText, stateBadge, timeToMinute, toDraft, todayInZone, unsupportedText, writeFailureText, zonedText,
} from "../src/jobs.ts";

// Neutral test data only.
const J1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71";
const U1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c72";
const J2 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c73";
const J3 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c74";
const J4 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c75";
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

const TODAY = "2026-11-13";
// What Core sends for each kind (docs/SCHEDULED_JOBS.md, fields since 0021).
const core = {
  every: 1, start_date: null, max_runs: null, month_day: null, run_count: 0, origin: "owner", owner_id: U1, conversation_id: null,
  expires_at: null, failure_count: 0, paused_reason: null,
};
const prompt = {
  ...job, ...core, id: J2, kind: "prompt", cadence: "monthly", weekday: null, month_day: 1, every: 6, start_date: "2027-01-01",
  max_runs: 2, at_minute: 540, params: { title: "Energy prices", instruction: "Dutch energy price outlook", notify: false },
};
const reminder = {
  ...job, ...core, id: J3, kind: "reminder", cadence: "once", weekday: null, start_date: "2026-12-01", at_minute: 540,
  params: { title: "Call the bank", text: "Ask about the new card." },
};
const disk = { ...job, ...core, id: J4, kind: "disk_check", cadence: "daily", weekday: null, at_minute: 360, params: { threshold_percent: 85 } };

test("job list guard reads every kind and Core's new fields", () => {
  const list = readJobList({ status: "ok", time_zone: "Europe/Amsterdam", jobs: [job, prompt, reminder, disk] });
  assert.equal(list.configured, true);
  assert.equal(list.unsupported, 0);
  const [review, p, r, d] = list.jobs;
  assert.equal(review.params.roadmap_id, null);
  assert.equal(review.every, 1, "an older Core without every reads as every 1");
  assert.equal(review.origin, "owner");
  assert.deepEqual([p.every, p.month_day, p.start_date, p.max_runs, p.params.notify], [6, 1, "2027-01-01", 2, false]);
  assert.equal(r.params.text, "Ask about the new card.");
  assert.equal(d.params.threshold_percent, 85);
  assert.equal(readJobList({ status: "not_configured", jobs: [{ ...job, params: { roadmap_id: "r1" } }] }).jobs[0].params.roadmap_id, "r1");
  assert.equal(readJobList({ status: "not_configured", jobs: [] }).configured, false);
  // A prompt without notify defaults to true, a disk check to 80.
  assert.equal(readJobList({ status: "ok", jobs: [{ ...prompt, params: { title: "t", instruction: "i" } }] }).jobs[0].params.notify, true);
  assert.equal(readJobList({ status: "ok", jobs: [{ ...disk, params: {} }] }).jobs[0].params.threshold_percent, 80);
});

test("one job this build cannot read never fails the list", () => {
  for (const unknown of [
    { ...job, kind: "free_text" },
    { ...job, cadence: "hourly" },
    { ...job, weekday: 8 },
    { ...job, at_minute: 1440 },
    { ...job, revision: "3" },
    { ...job, params: { roadmap_id: 5 } },
    { ...job, id: "../x" },
    { ...prompt, every: 0 },
    { ...prompt, start_date: "1 Dec" },
    { ...prompt, origin: "someone" },
    "junk",
  ]) {
    const list = readJobList({ status: "ok", jobs: [job, unknown] });
    assert.equal(list.jobs.length, 1, JSON.stringify(unknown).slice(0, 80));
    assert.equal(list.unsupported, 1);
  }
  // Fields this build does not know are ignored; lenient display fields fall back.
  const [j] = readJobList({
    status: "ok", jobs: [{ ...prompt, colour: "red", paused_reason: "paused_for_lunch", conversation_id: "not-an-id" }],
  }).jobs;
  assert.equal(j.paused_reason, null);
  assert.equal(j.conversation_id, null);
  // The list itself still fails closed.
  for (const broken of [null, [], { jobs: [job] }, { status: "ok" }, { status: "maybe", jobs: [] }, { status: "ok", jobs: Array(101).fill(job) }]) {
    assert.equal(readJobList(broken), null, JSON.stringify(broken)?.slice(0, 80));
  }
  assert.equal(unsupportedText(1), "1 job this app cannot show yet. Update the app to see it.");
  assert.equal(unsupportedText(2), "2 jobs this app cannot show yet. Update the app to see them.");
});

test("run guards order priorities, keep text as data and skip runs they cannot read", () => {
  const [r] = readRuns({ runs: [run] });
  assert.deepEqual(r.priorities.map((p) => p.ref), ["a1", "a2"]);
  assert.equal(r.summary, "<b>Two</b> fixes", "kept verbatim; the page renders it as text");
  assert.equal(r.kind, "dependency_review");
  assert.equal(readRuns({ runs: [{ ...run, summary_source: "web" }] })[0].summary_source, "web");
  assert.equal(readRuns({ runs: [{ ...run, summary_source: "gpt" }] })[0].summary_source, null, "only a label");
  for (const broken of [
    { ...run, status: "done" },
    { ...run, trigger: "cron" },
    { ...run, error_code: "Upstream said <b>no</b>" },
    { ...run, job_id: "j1" },
    { ...run, priorities: [{ ref: "a1", rank: "1", reason: "x" }] },
    { ...run, priorities: Array.from({ length: 21 }, (_, i) => ({ ref: `a${i}`, rank: i + 1, reason: "x" })) },
  ]) assert.deepEqual(readRuns({ runs: [broken, run] }).map((x) => x.id), [U1], JSON.stringify(broken).slice(0, 80));
  assert.equal(readRuns({ runs: Array(51).fill(run) }), null);
  assert.equal(readRuns({}), null);
});

test("a research run's report keeps https sources and its conversation", () => {
  const detail = readRunDetail({
    ...run, kind: "prompt", summary_source: "web", priorities: [],
    facts: {
      conversation_id: J3, message_id: J4,
      sources: [
        "https://example.org/a", "https://example.org/a", "http://example.org/b", "javascript:alert(1)", "https://user:pw@example.org/",
        "https://example.org/", 5, `https://example.org/${"x".repeat(100)}`,
      ],
    },
  });
  assert.deepEqual(detail.sources.slice(0, 2), [
    { url: "https://example.org/a", label: "example.org/a" }, { url: "https://example.org/", label: "example.org" },
  ]);
  assert.equal(detail.sources.length, 3);
  assert.equal(detail.sources[2].label.length, 80);
  assert.ok(detail.sources[2].label.endsWith("…"));
  assert.equal(detail.conversation_id, J3);
  const many = Array.from({ length: 30 }, (_, i) => `https://example.org/${i}`);
  assert.equal(readRunDetail({ ...run, facts: { sources: many } }).sources.length, 20);
  assert.equal(readRunDetail({ ...run, facts: { conversation_id: "../x" } }).conversation_id, null);
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

test("error codes get fixed owner-facing wording, per kind where it differs", () => {
  assert.match(errorText("token_needs_dependabot_alerts_read"), /"Dependabot alerts: Read-only"/);
  assert.match(errorText("not_configured", "dependency_review"), /GitHub token or the repository list/);
  assert.match(errorText("not_configured", "prompt"), /Research is off/);
  assert.match(errorText("timed_out", "prompt"), /5 minutes/);
  assert.match(errorText("timed_out", "reminder"), /15 minutes/);
  assert.match(errorText("not_configured"), /turned off on your Home Node/);
  for (const code of ["private_instruction", "holdings_unavailable", "research_unavailable", "conversation_unavailable", "disk_unavailable", "internal"]) {
    assert.notEqual(errorText(code, "prompt"), errorText("something_new"), code);
  }
  assert.equal(errorText("something_new"), "Core reported an error this app does not know yet.");
  assert.equal(errorText("toString"), "Core reported an error this app does not know yet.");
  assert.equal(errorText("toString", "toString"), "Core reported an error this app does not know yet.");
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
  // 06:00 UTC in November is 07:00 in Amsterdam.
  assert.match(zonedText("2026-11-16T06:00:00Z"), /07:00/);
  assert.equal(zonedText("nope"), "an unknown time");
  assert.equal(todayInZone(new Date("2026-11-13T23:30:00Z")), "2026-11-14", "Amsterdam is past midnight already");
  assert.equal(latestStart("2026-11-13"), "2028-11-14");
  assert.deepEqual([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31].map(ordinal), ["1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"]);
  assert.equal(dateText("2026-12-01", TODAY), "1 Dec");
  assert.equal(dateText("2027-01-01", TODAY), "1 Jan 2027");
});

test("schedules read as words", () => {
  const s = (fields) => scheduleText({ ...core, weekday: null, ...fields }, TODAY);
  assert.equal(s({ cadence: "once", start_date: "2026-12-01", at_minute: 540 }), "Once on 1 Dec at 09:00");
  assert.equal(s({ cadence: "daily", at_minute: 0 }), "Daily at 00:00");
  assert.equal(s({ cadence: "daily", every: 3, start_date: "2026-11-01", at_minute: 420 }), "Every 3 days at 07:00");
  assert.equal(s({ cadence: "weekly", weekday: 7, at_minute: 1290 }), "Every Sunday at 21:30");
  assert.equal(s({ cadence: "weekly", weekday: 5, every: 2, start_date: "2026-11-01", at_minute: 480 }), "Every 2 weeks on Friday at 08:00");
  assert.equal(s({ cadence: "monthly", month_day: 31, at_minute: 420 }), "Monthly on the 31st at 07:00");
  assert.equal(s({ cadence: "monthly", month_day: 1, every: 6, start_date: "2026-01-01", at_minute: 540 }), "Every 6 months on the 1st at 09:00");
  assert.equal(
    s({ cadence: "monthly", month_day: 1, every: 6, start_date: "2027-01-01", at_minute: 540 }),
    "Every 6 months on the 1st at 09:00, from 1 Jan 2027", "a start date ahead is said",
  );
  assert.equal(runsText({ max_runs: 2, run_count: 2 }), "2 of 2 runs done");
  assert.equal(runsText({ max_runs: null, run_count: 4 }), null);
});

test("job state: paused after failures, finished, Jarvis's own, counts", () => {
  const [p, r] = readJobList({ status: "ok", jobs: [prompt, reminder] }).jobs;
  assert.equal(jobTitle(p), "Energy prices");
  assert.equal(jobTitle(readJobList({ status: "ok", jobs: [job] }).jobs[0]), "Dependency review");
  assert.deepEqual(stateBadge(p), { label: "Enabled", tone: "pos" });
  const failing = { ...p, enabled: false, failure_count: 3, paused_reason: "paused_after_failures" };
  assert.equal(stateBadge(failing).label, PAUSED_AFTER_FAILURES);
  assert.equal(PAUSED_AFTER_FAILURES, "Paused after 3 failed runs");
  assert.equal(nextRunText(failing), PAUSED_AFTER_FAILURES);
  assert.equal(nextRunText({ ...p, next_run_at: null }), "Not scheduled");
  assert.equal(stateBadge({ ...p, enabled: false }).label, "Paused");
  assert.equal(isFinished({ ...p, enabled: false, run_count: 2 }), true);
  assert.equal(isFinished({ ...r, enabled: false, last_slot_at: "2026-12-01T08:00:00Z" }), true);
  assert.equal(stateBadge({ ...r, enabled: false, last_slot_at: null }).label, "Paused", "disabled before its day");
  const jarvis = { ...p, origin: "jarvis", expires_at: "2026-12-13T10:00:00Z" };
  assert.equal(originText(jarvis), "Set up by Jarvis · expires 13 Dec");
  assert.equal(originText(p), null);
  const review = readJobList({ status: "ok", jobs: [job] }).jobs[0];
  assert.equal(ownerJobCount([review, p, r, jarvis]), 2, "neither the review nor Jarvis's own count");
  assert.equal(countText(2), "2 of 10 jobs");
  assert.equal(countText(2, 1), "2 of 10 jobs, not counting any this app cannot show");
  assert.equal(deleteNote({ ...p, conversation_id: J3 }), "Its runs are deleted too. Its conversation stays in chat.");
  assert.equal(deleteNote(p), "Its runs are deleted too.");
});

// Core refuses a body naming a field of its own (origin, owner_id, ...).
const CLIENT_FIELDS = ["kind", "cadence", "weekday", "month_day", "every", "start_date", "max_runs", "at_minute", "enabled", "params"];
const PARAM_FIELDS = ["roadmap_id", "title", "instruction", "text", "notify", "threshold_percent"];
const onlyClientFields = (body) => {
  for (const k of Object.keys(body)) assert.ok(CLIENT_FIELDS.includes(k), `sent ${k}`);
  for (const k of Object.keys(body.params ?? {})) assert.ok(PARAM_FIELDS.includes(k), `sent params.${k}`);
};

test("create bodies per kind send only client fields", () => {
  const research = { ...newDraft("prompt"), title: " AI news ", instruction: "Summarize this week's AI news", time: "08:00" };
  assert.deepEqual(newJob(research, TODAY), {
    kind: "prompt", cadence: "weekly", weekday: 1, at_minute: 480, enabled: true,
    params: { title: "AI news", instruction: "Summarize this week's AI news", notify: true },
  });
  const remind = { ...newDraft("reminder"), cadence: "once", start_date: "2026-12-01", time: "09:00", title: "Call the bank", text: "Ask about the new card." };
  assert.deepEqual(newJob(remind, TODAY), {
    kind: "reminder", cadence: "once", start_date: "2026-12-01", at_minute: 540, enabled: true,
    params: { title: "Call the bank", text: "Ask about the new card." },
  });
  assert.deepEqual(newJob({ ...remind, text: "  " }, TODAY).params, { title: "Call the bank" });
  assert.deepEqual(newJob({ ...newDraft("disk_check"), cadence: "daily", time: "06:00", threshold: 85 }, TODAY), {
    kind: "disk_check", cadence: "daily", at_minute: 360, enabled: true, params: { threshold_percent: 85 },
  });
  const halfYear = { ...research, cadence: "monthly", month_day: 1, every: 6, start_date: "2027-01-01", max_runs: 2, notify: false };
  assert.deepEqual(newJob(halfYear, TODAY), {
    kind: "prompt", cadence: "monthly", month_day: 1, every: 6, start_date: "2027-01-01", max_runs: 2, at_minute: 480, enabled: true,
    params: { title: "AI news", instruction: "Summarize this week's AI news", notify: false },
  });
  assert.deepEqual(newJob({ ...newDraft("dependency_review"), cadence: "daily", weekday: 4, every: 3, max_runs: 2, start_date: "2027-01-01" }, TODAY), {
    kind: "dependency_review", cadence: "daily", at_minute: 420, enabled: true, params: {},
  }, "a review keeps its daily or weekly schedule; no every, start date or limit");
  for (const body of [newJob(research, TODAY), newJob(remind, TODAY), newJob(halfYear, TODAY)]) onlyClientFields(body);
  assert.equal(newJob({ ...research, time: "" }, TODAY), null);
});

test("the editor checks Core's bounds early", () => {
  const ok = { ...newDraft("prompt"), title: "AI news", instruction: "News" };
  const field = (d) => draftIssue(d, TODAY)?.field ?? null;
  assert.equal(field(ok), null);
  assert.equal(field({ ...ok, title: "" }), "title");
  assert.equal(field({ ...ok, title: "x".repeat(61) }), "title");
  assert.equal(field({ ...ok, title: "a\u200Bb" }), "title", "no hidden characters");
  assert.equal(field({ ...ok, instruction: "two\nlines" }), "instruction");
  assert.equal(field({ ...ok, instruction: "x".repeat(501) }), "instruction");
  assert.equal(field({ ...newDraft("reminder"), title: "Call", text: "" }), null, "text is optional");
  assert.equal(field({ ...newDraft("reminder"), title: "Call", text: "x".repeat(501) }), "text");
  for (const threshold of [49, 100, "", "8.5"]) assert.equal(field({ ...newDraft("disk_check"), threshold }), "threshold", String(threshold));
  assert.equal(field({ ...newDraft("disk_check"), threshold: 50 }), null);
  assert.equal(field({ ...ok, cadence: "daily", every: 366 }), "every");
  assert.equal(field({ ...ok, cadence: "daily", every: 365 }), null);
  assert.equal(field({ ...ok, cadence: "weekly", every: 105 }), "every");
  assert.equal(field({ ...ok, cadence: "monthly", every: 25 }), "every");
  assert.equal(field({ ...ok, every: 0 }), "every");
  assert.equal(field({ ...ok, max_runs: 101 }), "max_runs");
  assert.equal(field({ ...ok, max_runs: "" }), null, "no limit");
  assert.equal(field({ ...ok, cadence: "once", every: 0, max_runs: 999, start_date: "2026-12-01" }), null, "a one-time job ignores both");
  assert.equal(field({ ...ok, cadence: "once" }), "start_date");
  assert.equal(field({ ...ok, cadence: "once", start_date: "2026-11-12" }), "start_date", "in the past");
  assert.equal(field({ ...ok, cadence: "once", start_date: "2026-11-12", enabled: false }), null, "a paused one-time job may be past");
  assert.equal(field({ ...ok, start_date: "2026-02-30" }), "start_date");
  assert.equal(field({ ...ok, start_date: latestStart(TODAY) }), null);
  assert.equal(field({ ...ok, start_date: "2028-11-15" }), "start_date", "over two years ahead");
  assert.equal(field({ ...ok, time: "7:00" }), "time");
  assert.equal(field({ ...newDraft("dependency_review"), every: 0, start_date: "nope" }), null, "a review has none of those");
});

test("the editor sends only what changed and never a daily weekday", () => {
  const [j] = readJobList({ status: "ok", jobs: [job] }).jobs;
  assert.equal(jobPatch(j, toDraft(j)), null);
  assert.deepEqual(jobPatch(j, { ...toDraft(j), cadence: "daily" }), { cadence: "daily" });
  assert.deepEqual(jobPatch(j, { ...toDraft(j), weekday: 3, time: "08:15", enabled: false }), { weekday: 3, at_minute: 495, enabled: false });
  assert.deepEqual(jobPatch(j, { ...toDraft(j), roadmap_id: "r1" }), { params: { roadmap_id: "r1" } });
  const linked = { ...j, params: { ...j.params, roadmap_id: "r1" } };
  assert.deepEqual(jobPatch(linked, { ...toDraft(linked), roadmap_id: null }), { params: {} }, "empty params clear the roadmap");
  const daily = { ...j, cadence: "daily", weekday: null };
  assert.deepEqual(jobPatch(daily, { ...toDraft(daily), cadence: "weekly" }), { cadence: "weekly", weekday: 1 });
});

test("changes to the new kinds send only what changed, params whole", () => {
  const [p, r, d] = readJobList({ status: "ok", jobs: [prompt, reminder, disk] }).jobs;
  for (const x of [p, r, d]) assert.equal(jobPatch(x, toDraft(x)), null);
  assert.deepEqual(jobPatch(p, { ...toDraft(p), max_runs: "" }), { max_runs: null }, "null clears the limit");
  assert.deepEqual(jobPatch(p, { ...toDraft(p), start_date: "" }), { start_date: null });
  assert.deepEqual(jobPatch(p, { ...toDraft(p), every: 3 }), { every: 3 });
  assert.deepEqual(jobPatch(p, { ...toDraft(p), month_day: 15 }), { month_day: 15 });
  // every falls back to 1 when the cadence changes, so a kept 6 is sent.
  assert.deepEqual(jobPatch(p, { ...toDraft(p), cadence: "weekly" }), { cadence: "weekly", weekday: 1, every: 6 });
  assert.deepEqual(jobPatch(p, { ...toDraft(p), cadence: "weekly", every: 1 }), { cadence: "weekly", weekday: 1 });
  // A one-time job drops every and the run limit by itself.
  assert.deepEqual(jobPatch(p, { ...toDraft(p), cadence: "once" }), { cadence: "once" });
  assert.deepEqual(jobPatch(p, { ...toDraft(p), notify: true }), {
    params: { title: "Energy prices", instruction: "Dutch energy price outlook", notify: true },
  });
  assert.deepEqual(jobPatch(r, { ...toDraft(r), text: "" }), { params: { title: "Call the bank" } }, "cleared text goes");
  assert.deepEqual(jobPatch(r, { ...toDraft(r), start_date: "2026-12-02" }), { start_date: "2026-12-02" });
  assert.deepEqual(jobPatch(d, { ...toDraft(d), threshold: 90 }), { params: { threshold_percent: 90 } });
  assert.deepEqual(jobPatch(d, { ...toDraft(d), threshold: "90" }), { params: { threshold_percent: 90 } });
  for (const x of [p, r, d]) onlyClientFields(jobPatch(x, { ...toDraft(x), enabled: false, time: "10:00", title: "New", threshold: 70 }));
});

test("an older Core and refusals get fixed wording", () => {
  assert.equal(failureOf(apiError(404), true), "no-api");
  assert.equal(failureOf(apiError(404)), "not-found");
  assert.match(failureText("no-api"), new RegExp(NEEDS_CORE.replace(/\./g, "\\.")));
  assert.equal(NEEDS_CORE, "Needs Jarvis Core v0.0.87 or later.");
  assert.equal(runNowFailureText(apiError(429)), RUN_NOW_REFUSED);
  assert.match(runNowFailureText(apiError(409)), /scheduler is off/);
  // A 409 on a change is always a revision conflict; on a create it means no room.
  assert.equal(writeFailureText(apiError(409), true), CHANGED_ELSEWHERE);
  assert.match(writeFailureText(apiError(409), false, "dependency_review"), /dependency review already exists/);
  assert.equal(writeFailureText(apiError(409), false, "prompt"), `Core refused this. ${FULL_TEXT}`);
  assert.equal(writeFailureText(apiError(400), false, "prompt"), INVALID_TEXT);
  // A Core before v0.0.87 refuses a kind it does not know with 422; on a change it stays generic.
  assert.equal(
    writeFailureText(apiError(422), false, "reminder"),
    "Not saved: your Core does not know this kind of job yet. Update Core on your Home Node.",
  );
  assert.match(writeFailureText(apiError(422), true), /^Not saved\. Core returned an answer/);
  assert.match(writeFailureText(apiError(503), true), /holdings could not be read/);
  assert.match(writeFailureText({ name: "NetworkError" }, true), /reloaded to check/);
  assert.equal(readJobAnswer({ deleted: true }), null, "an unreadable answer is not a confirmation");
  assert.equal(deleteFailureText(apiError(404)), null, "already gone counts as deleted");
  assert.equal(deleteFailureText(apiError(409)), CHANGED_ELSEWHERE);
  assert.match(deleteFailureText({ name: "NetworkError" }), /unknown whether the job was deleted/);
  assert.equal(deleteFailureText(apiError(503)), `Not deleted. ${failureText("unavailable")}`);
});
