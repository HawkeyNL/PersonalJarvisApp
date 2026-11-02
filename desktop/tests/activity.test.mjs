import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  CLOSED_GATE, filterRuns, observeReview, outcomeBadge, reviewDone, reviewKey, runBadge, runKpis,
} from "../src/activity.ts";

const item = (over = {}) => ({ pending_id: "a", action: "write_file", preview: "+ line", action_sha256: "f".repeat(64), ...over });
const box = (scrollTop, scrollHeight, clientHeight) => ({ scrollTop, scrollHeight, clientHeight });

test("the review gate opens only once the details reach their end, and stays open", () => {
  const key = reviewKey(item());
  let gate = observeReview(CLOSED_GATE, key, box(0, 900, 300));
  assert.equal(reviewDone(gate, key), false);
  gate = observeReview(gate, key, box(400, 900, 300));
  assert.equal(reviewDone(gate, key), false, "halfway is not the end");
  gate = observeReview(gate, key, box(599.5, 900, 300));
  assert.equal(reviewDone(gate, key), true, "a subpixel short of the end counts");
  gate = observeReview(gate, key, box(0, 900, 300));
  assert.equal(reviewDone(gate, key), true, "scrolling back up keeps it open");
});

test("content that fits opens the gate; an unmeasured box never does", () => {
  const key = reviewKey(item());
  assert.equal(reviewDone(observeReview(CLOSED_GATE, key, box(0, 120, 120)), key), true);
  for (const b of [box(0, 0, 0), box(0, 900, 0), box(NaN, 900, 300), box(0, Infinity, 300), box(0, 900, NaN)]) {
    assert.equal(reviewDone(observeReview(CLOSED_GATE, key, b), key), false, JSON.stringify(b));
  }
  // An unmeasured box does not close a gate that was already open for this review.
  const open = observeReview(CLOSED_GATE, key, box(0, 120, 120));
  assert.equal(reviewDone(observeReview(open, key, box(0, 0, 0)), key), true);
});

test("the gate belongs to one review: other or changed content starts closed", () => {
  const a = reviewKey(item());
  const open = observeReview(CLOSED_GATE, a, box(0, 100, 300));
  assert.equal(reviewDone(open, a), true);
  const b = reviewKey(item({ pending_id: "b" }));
  assert.equal(reviewDone(open, b), false, "another request is not reviewed");
  const changed = reviewKey(item({ preview: "+ other line" }));
  assert.notEqual(changed, a);
  assert.equal(reviewDone(observeReview(open, changed, box(0, 900, 300)), changed), false);
  assert.notEqual(reviewKey(item({ action_sha256: undefined })), a);
  // Moving away and back starts over.
  const away = observeReview(open, b, box(0, 900, 300));
  assert.equal(reviewDone(observeReview(away, a, box(0, 900, 300)), a), false);
  assert.equal(reviewDone(CLOSED_GATE, a), false);
});

test("Approve on desktop still runs through the gate, the Mac signer and the v1 check", () => {
  const board = readFileSync(new URL("../src/components/activity/ApprovalsBoard.vue", import.meta.url), "utf8");
  assert.match(board, /if \(approve && !\(approvable\(item\) && reviewed\(item\)\)\) return;/);
  assert.match(board, /const approvable = \(p: PendingAction\) => signer\.value && canSignApproval\(p\);/);
  assert.match(board, /:disabled="!!deciding \|\| !reviewed\(p\)"/);
  assert.match(board, /approveAction\(item\) : denyAction\(item\)/);
});

test("run filter groups sessions by status", () => {
  const list = [{ id: 1, state: "active" }, { id: 2, state: "suspended" }, { id: 3, state: "completed" }, { id: 4, state: "weird" }];
  const ids = (f) => filterRuns(list, f).map((s) => s.id);
  assert.deepEqual(ids("all"), [1, 2, 3, 4]);
  assert.deepEqual(ids("running"), [1]);
  assert.deepEqual(ids("paused"), [2]);
  assert.deepEqual(ids("finished"), [3, 4]);
  assert.deepEqual(filterRuns([], "running"), []);
});

test("run KPIs come from the data and show unavailable reads as such, never as zero", () => {
  const sessions = [{ state: "active" }, { state: "active" }, { state: "suspended" }, { state: "completed" }];
  const audit = { enabled: true, entries: [{ outcome: "ok" }, { outcome: "error" }, { outcome: "denied" }] };
  assert.deepEqual(runKpis(sessions, 1, audit), [
    { label: "Runs", value: "4", sub: "1 finished" },
    { label: "Running", value: "2", sub: "1 paused" },
    { label: "Waiting on you", value: "1", sub: "approval" },
    { label: "Failed agent actions", value: "1", sub: "of the last 3 recorded" },
  ]);
  const empty = runKpis([], 0, { enabled: false, entries: [] });
  assert.deepEqual(empty.map((k) => [k.value, k.sub]), [["0", "None yet"], ["0", "0 paused"], ["0", "approvals"], ["0", "Agent is off in Core"]]);
  const down = runKpis([], null, null);
  assert.deepEqual(down.slice(2).map((k) => [k.value, k.sub]), [["—", "Unavailable"], ["—", "Unavailable"]]);
  assert.equal(runKpis([], 0, { enabled: true, entries: [] })[3].sub, "Nothing recorded");
});

test("run KPIs format worst-case counts", () => {
  const many = Array.from({ length: 12_345 }, (_, i) => ({ state: i % 2 ? "completed" : "active" }));
  const errors = Array.from({ length: 1_284 }, () => ({ outcome: "error" }));
  const [runs, running, waiting, failed] = runKpis(many, 1_000_000, { enabled: true, entries: errors });
  assert.equal(runs.value, "12,345");
  assert.equal(runs.sub, "6,172 finished");
  assert.equal(running.value, "6,173");
  assert.equal(waiting.value, "1,000,000");
  assert.equal(failed.value, "1,284");
});

test("badges carry a label next to their colour", () => {
  assert.deepEqual(runBadge("active"), { cls: "acc", label: "Running", live: true });
  assert.deepEqual(runBadge("completed"), { cls: "pos", label: "Completed", live: false });
  assert.deepEqual(runBadge("suspended"), { cls: "", label: "Paused", live: false });
  assert.deepEqual(runBadge(""), { cls: "", label: "Unknown", live: false });
  assert.deepEqual(outcomeBadge("ok"), ["pos", "Done"]);
  assert.deepEqual(outcomeBadge("error"), ["neg", "Failed"]);
  assert.deepEqual(outcomeBadge("denied"), ["", "Denied"]);
  assert.deepEqual(outcomeBadge(""), ["", "Unknown"]);
});
