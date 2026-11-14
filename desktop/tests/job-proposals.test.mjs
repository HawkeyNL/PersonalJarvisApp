import test from "node:test";
import assert from "node:assert/strict";
import {
  readProposals, withProposals, addJob, addProposalCard, proposalDetail, proposalSchedule, proposalTitle, ADDED, ADD_NOT_CONFIRMED, ADD_INVALID,
} from "../src/jobProposals.ts";
import { FULL_TEXT } from "../src/jobs.ts";

const today = "2026-11-14";
const id = (n) => `00000000-0000-4000-8000-00000000000${n}`;
const prompt = {
  kind: "prompt", cadence: "weekly", weekday: 5, at_minute: 510,
  params: { title: "AI news", instruction: "Summarize this week's AI news", notify: false },
};
const reminder = { kind: "reminder", cadence: "once", start_date: "2026-12-01", at_minute: 540, params: { title: "Call the bank", text: "Ask about the card" } };
const disk = { kind: "disk_check", cadence: "daily", params: { threshold_percent: 90 } };

test("no proposals: absent, empty or not a list", () => {
  assert.deepEqual(readProposals(undefined, today), []);
  assert.deepEqual(readProposals([], today), []);
  assert.deepEqual(readProposals({ id: id(1), body: prompt }, today), []);
});

test("Core's bodies decode with its defaults; the body is kept as sent", () => {
  const out = readProposals([{ id: id(1), body: prompt }, { id: id(2), body: reminder }, { id: id(3), body: disk }], today);
  assert.deepEqual(out.map((p) => p.id), [id(1), id(2), id(3)]);
  assert.equal(out[0].body, prompt);
  assert.equal(out[0].draft.time, "08:30");
  assert.equal(out[0].draft.notify, false);
  assert.equal(out[2].draft.time, "07:00");
  assert.deepEqual(readProposals([{ id: id(4), body: { kind: "disk_check" } }], today).map((p) => p.draft.threshold), [80]);
});

test("at most 3 are used", () => {
  const four = [1, 2, 3, 4].map((n) => ({ id: id(n), body: disk }));
  assert.deepEqual(readProposals(four, today).map((p) => p.id), [id(1), id(2), id(3)]);
});

test("a bad id, unknown kind or cadence, or malformed body is skipped; the rest stays", () => {
  const bad = [
    { id: "not-a-uuid", body: disk },
    { body: disk },
    { id: id(1), body: { ...disk, kind: "dependency_review" } },
    { id: id(2), body: { ...disk, kind: "shell" } },
    { id: id(3), body: { ...disk, cadence: "hourly" } },
    { id: id(4), body: "disk_check" },
    { id: id(5), body: { ...prompt, weekday: "friday" } },
    { id: id(6), body: { ...prompt, at_minute: 1440 } },
    { id: id(7), body: { ...prompt, params: { title: "", instruction: "x" } } },
    { id: id(8), body: { ...prompt, enabled: false } },
    { id: id(9), body: { ...prompt, params: { ...prompt.params, roadmap_id: "x" } } },
    { id: id(1), body: { ...disk, params: { threshold_percent: 10 } } },
    { id: id(2), body: { ...reminder, start_date: "2026-01-01" } },
    { id: id(3), body: { ...reminder, max_runs: 2 } },
    { id: id(4), body: { ...disk, weekday: 1 } },
    { id: id(5), body: { ...prompt, month_day: 1 } },
    { id: id(6), body: { ...reminder, params: { ...reminder.params, notify: true } } },
    { id: id(7), body: { ...disk, params: { threshold_percent: 90, title: "x" } } },
    null,
  ];
  for (const item of bad) assert.deepEqual(readProposals([item], today), [], JSON.stringify(item));
  assert.deepEqual(readProposals([bad[0], { id: id(9), body: disk }, bad[3]], today).map((p) => p.id), [id(9)]);
});

test("each proposal shows once per id", () => {
  const seen = new Set();
  const [a] = readProposals([{ id: id(1), body: disk }], today);
  const cards = withProposals([], seen, [a], "c", "m");
  assert.equal(cards.length, 1);
  assert.equal(cards[0].state, "open");
  assert.equal(withProposals(cards, seen, [a], "c", "m"), cards);
  // Dismissed (gone from the list) stays gone when the event comes again.
  assert.deepEqual(withProposals([], seen, [a], "c", "m"), []);
});

test("the card's wording", () => {
  const [p, r, d] = readProposals([{ id: id(1), body: prompt }, { id: id(2), body: reminder }, { id: id(3), body: disk }], today);
  assert.equal(proposalTitle(p.draft), "AI news");
  assert.equal(proposalTitle(d.draft), "Disk check");
  assert.equal(proposalSchedule(p.draft, today), "Every Friday at 08:30 · No push");
  assert.equal(proposalSchedule(r.draft, today), "Once on 1 Dec at 09:00");
  assert.equal(proposalSchedule(d.draft, today), "Daily at 07:00");
  const limited = (body) => readProposals([{ id: id(5), body }], today)[0].draft;
  assert.equal(proposalSchedule(limited({ ...disk, at_minute: 480, max_runs: 2 }), today), "Daily at 08:00, 2 runs");
  assert.equal(proposalSchedule(limited({ ...disk, max_runs: 1 }), today), "Daily at 07:00, 1 run");
  const editedOnce = { ...limited({ ...disk, max_runs: 3 }), cadence: "once", start_date: "2026-12-01" };
  assert.equal(proposalSchedule(editedOnce, today), "Once on 1 Dec at 07:00");
  assert.equal(proposalSchedule(limited({ ...prompt, max_runs: 3 }), today), "Every Friday at 08:30, 3 runs · No push");
  assert.equal(proposalSchedule(limited({ ...prompt, params: { ...prompt.params, notify: true } }), today), "Every Friday at 08:30");
  assert.deepEqual(readProposals([{ id: id(5), body: { ...disk, params: { notify: false } } }], today), []);
  assert.equal(proposalDetail(p.draft), "Summarize this week's AI news");
  assert.equal(proposalDetail(r.draft), "Ask about the card");
  assert.equal(proposalDetail(d.draft), "Threshold 90%");
  const long = readProposals([{ id: id(4), body: { ...prompt, params: { title: "x", instruction: "a".repeat(300) } } }], today)[0];
  assert.equal(proposalDetail(long.draft), "a".repeat(300));
});

const job = { id: id(7), kind: "prompt", cadence: "weekly", weekday: 5, at_minute: 510, enabled: true, revision: 0, params: {} };
const apiError = (status) => Object.assign(new Error("x"), { name: "ApiError", status });

test("Add sends exactly the body and links the new job", async () => {
  const sent = [];
  const result = await addJob(prompt, async (body) => { sent.push(JSON.stringify(body)); return job; });
  assert.deepEqual(sent, [JSON.stringify(prompt)]);
  assert.deepEqual(result, { state: "added", jobId: id(7), error: "" });
  assert.equal(ADDED, "Added to Jobs");
});

test("an unreadable 2xx or no answer is not confirmed; a refusal lets the owner try again", async () => {
  const unconfirmed = { state: "unconfirmed", jobId: null, error: ADD_NOT_CONFIRMED };
  assert.deepEqual(await addJob(prompt, async () => ({ ok: true })), unconfirmed);
  assert.deepEqual(await addJob(prompt, async () => { throw Object.assign(new Error("x"), { name: "NetworkError" }); }), unconfirmed);
  // The answer broke after Core may have made the job, or a gateway gave up.
  assert.deepEqual(await addJob(prompt, async () => { throw new Error("Home Node response is invalid"); }), unconfirmed);
  for (const status of [500, 502, 503, 504]) assert.deepEqual(await addJob(prompt, async () => { throw apiError(status); }), unconfirmed);
  assert.equal(ADD_NOT_CONFIRMED, "Not confirmed: it may have been added.");
  const full = await addJob(prompt, async () => { throw apiError(409); });
  assert.deepEqual(full, { state: "open", jobId: null, error: `Core refused this. ${FULL_TEXT}` });
  assert.deepEqual(await addJob(prompt, async () => { throw apiError(400); }), { state: "open", jobId: null, error: ADD_INVALID });
  assert.equal(ADD_INVALID, "Core refused this job as invalid, for example a time that has passed. Edit it and try again.");
});

const card = () => ({ ...readProposals([{ id: id(1), body: prompt }], today)[0], conversationId: "c", messageId: "m", state: "open", jobId: null, error: "x" });
const guards = (over = {}) => ({ locked: () => false, current: () => true, create: async () => job, ...over });

test("Add guards: a double click sends once; the card is adding before the request", async () => {
  const c = card();
  let calls = 0;
  let release;
  const g = guards({ create: () => { calls++; return new Promise((r) => { release = () => r(job); }); } });
  const first = addProposalCard(c, undefined, g);
  assert.equal(c.state, "adding");
  assert.equal(c.error, "");
  await addProposalCard(c, undefined, g);
  release();
  await first;
  assert.equal(calls, 1);
  assert.deepEqual([c.state, c.jobId], ["added", id(7)]);
});

test("Add guards: the lock, a missing or finished card send nothing; an edit sends the edited body", async () => {
  let calls = 0;
  const create = async () => { calls++; return job; };
  const locked = card();
  await addProposalCard(locked, undefined, guards({ locked: () => true, create }));
  assert.equal(locked.state, "open");
  await addProposalCard(undefined, undefined, guards({ create }));
  await addProposalCard({ ...card(), state: "unconfirmed" }, undefined, guards({ create }));
  assert.equal(calls, 0);
  const sent = [];
  const body = { ...prompt, params: { ...prompt.params, title: "Edited" } };
  const shown = card();
  const draft = { ...shown.draft, title: "Edited" };
  await addProposalCard(shown, { body, draft }, guards({ create: async (b) => { sent.push(b); return job; } }));
  assert.deepEqual(sent, [body]);
  assert.equal(proposalTitle(shown.draft), "Edited");
});

test("Add guards: a result after leaving the session or conversation is dropped; no answer is unconfirmed", async () => {
  const left = card();
  await addProposalCard(left, undefined, guards({ current: () => false }));
  assert.deepEqual([left.state, left.jobId], ["adding", null]);
  const lost = card();
  await addProposalCard(lost, undefined, guards({ create: async () => { throw Object.assign(new Error("x"), { name: "NetworkError" }); } }));
  assert.deepEqual([lost.state, lost.error], ["unconfirmed", ADD_NOT_CONFIRMED]);
});
