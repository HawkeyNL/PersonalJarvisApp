import assert from "node:assert/strict";
import test from "node:test";

import { filterConversations, outcomeTone, sessionState, splitSessions } from "../src/nodeModels.ts";

const NOW = Date.parse("2026-10-04T12:00:00Z");
const ago = (days) => new Date(NOW - days * 86_400_000).toISOString();

test("conversation filter matches the title case-insensitively and by period", () => {
  const list = [
    { id: "a", title: "PR #59 review", updated_at: ago(0.1) },
    { id: "b", title: "Morning brief", updated_at: ago(3) },
    { id: "c", title: "Old review", updated_at: ago(30) },
    { id: "d", title: "", updated_at: "not a date" },
  ];
  assert.deepEqual(filterConversations(list, "", null, NOW).map((c) => c.id), ["a", "b", "c", "d"]);
  assert.deepEqual(filterConversations(list, "  REVIEW ", null, NOW).map((c) => c.id), ["a", "c"]);
  assert.deepEqual(filterConversations(list, "", 7, NOW).map((c) => c.id), ["a", "b"]);
  assert.deepEqual(filterConversations(list, "review", 7, NOW).map((c) => c.id), ["a"]);
});

test("coding sessions split into open and finished with honest labels", () => {
  const list = [{ state: "active" }, { state: "completed" }, { state: "suspended" }, { state: "archived" }, { state: "weird" }];
  const { open, finished } = splitSessions(list);
  assert.deepEqual(open.map((s) => s.state), ["active", "suspended"]);
  assert.deepEqual(finished.map((s) => s.state), ["completed", "archived", "weird"]);
  assert.deepEqual(sessionState("active"), ["ok", "Running"]);
  assert.deepEqual(sessionState("suspended"), ["warn", "Paused"]);
  assert.deepEqual(sessionState("weird"), ["idle", "weird"]);
  assert.deepEqual(sessionState("constructor"), ["idle", "constructor"]);
  assert.deepEqual(sessionState(""), ["idle", "Unknown"]);
});

test("agent audit outcomes map to tones", () => {
  assert.equal(outcomeTone("ok"), "ok");
  assert.equal(outcomeTone("denied"), "warn");
  assert.equal(outcomeTone("error"), "error");
  assert.equal(outcomeTone("pending"), "idle");
});
