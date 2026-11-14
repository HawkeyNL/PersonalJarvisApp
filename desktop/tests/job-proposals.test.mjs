import test from "node:test";
import assert from "node:assert/strict";
import { readProposals, withProposals, addJob, proposalDetail, proposalSchedule, proposalTitle, ADDED } from "../src/jobProposals.ts";
import { NOT_CONFIRMED, FULL_TEXT } from "../src/jobs.ts";

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
  assert.equal(proposalSchedule(p.draft, today), "Every Friday at 08:30");
  assert.equal(proposalSchedule(r.draft, today), "Once on 1 Dec at 09:00");
  assert.equal(proposalDetail(p.draft), "Summarize this week's AI news");
  assert.equal(proposalDetail(r.draft), "Ask about the card");
  assert.equal(proposalDetail(d.draft), "Threshold 90%");
  const long = readProposals([{ id: id(4), body: { ...prompt, params: { title: "x", instruction: "a".repeat(300) } } }], today)[0];
  assert.equal(proposalDetail(long.draft).length, 140);
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

test("an unreadable 2xx is not confirmed; a 409 or other failure lets the owner try again", async () => {
  assert.deepEqual(await addJob(prompt, async () => ({ ok: true })), { state: "unconfirmed", jobId: null, error: NOT_CONFIRMED });
  const full = await addJob(prompt, async () => { throw apiError(409); });
  assert.deepEqual(full, { state: "open", jobId: null, error: `Core refused this. ${FULL_TEXT}` });
  const invalid = await addJob(prompt, async () => { throw apiError(400); });
  assert.equal(invalid.state, "open");
  assert.match(invalid.error, /^Core refused this as invalid/);
});
