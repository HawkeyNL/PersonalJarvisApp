import assert from "node:assert/strict";
import test from "node:test";

import {
  agentStatus, countOf, diskState, filterConversations, formatMs, groupAgents, ibkrState, isTrader, measuredCount,
  outcomeTone, serviceState, servicesSummary, sessionState, splitSessions,
} from "../src/nodeModels.ts";

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

test("IBKR gateway state is labelled without guessing", () => {
  assert.deepEqual(ibkrState({ reachable: false, authenticated: false }), ["warn", "Gateway unreachable"]);
  assert.deepEqual(ibkrState({ reachable: true, authenticated: false }), ["warn", "Not logged in"]);
  assert.deepEqual(ibkrState({ reachable: true, authenticated: true, connected: false }), ["warn", "Logged in, not connected"]);
  assert.deepEqual(ibkrState({ reachable: true, authenticated: true }), ["ok", "Connected"]);
  assert.equal(countOf([{ a: true }, { a: false }, { a: true }], (x) => x.a), "2 of 3");
  assert.equal(countOf([], () => true), "0 of 0");
});

test("agents group alphabetically with ungrouped agents last", () => {
  const groups = groupAgents([
    { name: "Trader", group: "Markets" },
    { name: "Coder", group: null },
    { name: "Researcher", group: "Knowledge" },
    { name: "Clipper", group: "Knowledge" },
    { name: "Voice", group: "  " },
  ]);
  assert.deepEqual(groups.map((g) => [g.group, g.agents.map((a) => a.name)]), [
    ["Knowledge", ["Clipper", "Researcher"]],
    ["Markets", ["Trader"]],
    ["Other", ["Coder", "Voice"]],
  ]);
});

test("agent status reports usage, never a fake live state", () => {
  assert.deepEqual(agentStatus(null), ["idle", "Usage unavailable"]);
  assert.deepEqual(agentStatus(null, "usage_query_failed"), ["idle", "Usage unavailable"]);
  assert.deepEqual(agentStatus(null, "agent_usage_not_instrumented"), ["idle", "Not measured yet"]);
  assert.deepEqual(agentStatus({ requests: 0 }), ["idle", "Not used this month"]);
  assert.deepEqual(agentStatus({ requests: 1 }), ["ok", "1 request this month"]);
  assert.deepEqual(agentStatus({ requests: 12 }), ["ok", "12 requests this month"]);
  assert.equal(isTrader({ id: "trader", name: "Markets" }), true);
  assert.equal(isTrader({ id: "x", name: "Trading Desk" }), true);
  assert.equal(isTrader({ id: "researcher", name: "Research Agent" }), false);
  assert.equal(formatMs(null), "—");
  assert.equal(formatMs(850.4), "850 ms");
  assert.equal(formatMs(1234), "1.2 s");
});

test("service and disk states are labelled from what Core reports", () => {
  assert.deepEqual(serviceState("active"), ["ok", "Running"]);
  assert.deepEqual(serviceState("failed"), ["error", "Failed"]);
  assert.deepEqual(serviceState("not_found"), ["idle", "Not installed"]);
  assert.deepEqual(serviceState("unknown"), ["idle", "Unknown"]);
  assert.deepEqual(serviceState("activating"), ["warn", "Activating"]);
  assert.deepEqual(servicesSummary([]), ["idle", "No services reported"]);
  assert.deepEqual(servicesSummary([{ state: "active" }, { state: "active" }]), ["ok", "2 of 2 running"]);
  assert.deepEqual(servicesSummary([{ state: "active" }, { state: "inactive" }]), ["warn", "1 of 2 running"]);
  assert.deepEqual(servicesSummary([{ state: "active" }, { state: "failed" }]), ["error", "1 failed"]);
  assert.deepEqual(diskState({ label: "data", state: "unknown" }), ["idle", "Unknown"]);
  const GiB = 1024 ** 3;
  assert.deepEqual(diskState({ label: "system", state: "ok", total_bytes: 100 * GiB, free_bytes: 40 * GiB, used_percent: 60 }),
    ["ok", "60% used · 40.0 GiB free of 100.0 GiB"]);
  assert.equal(diskState({ label: "system", state: "ok", total_bytes: 100 * GiB, free_bytes: 15 * GiB, used_percent: 85 })[0], "warn");
  assert.equal(diskState({ label: "system", state: "ok", total_bytes: 100 * GiB, free_bytes: 5 * GiB, used_percent: 95 })[0], "error");
});

test("uninstrumented usage counts read 'Not measured yet', never 0", () => {
  assert.equal(measuredCount(null), "Not measured yet");
  assert.equal(measuredCount(undefined), "—");
  assert.equal(measuredCount(0), "0");
  assert.equal(measuredCount(1234), "1,234");
});
