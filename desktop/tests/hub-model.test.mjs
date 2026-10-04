import assert from "node:assert/strict";
import test from "node:test";

import {
  agentsCard,
  availabilityFromError,
  failureText,
  fitScale,
  contextCard,
  conversationsCard,
  countSince,
  deriveMood,
  formatUptime,
  healthCard,
  integrationsCard,
  moodFactor,
  originHost,
  relativeTime,
  statusPill,
  tasksCard,
} from "../src/hubModel.ts";

const NOW = Date.parse("2026-10-04T12:00:00Z");
const ago = (ms) => new Date(NOW - ms).toISOString();
const MIN = 60_000;
const DAY = 86_400_000;

test("mood: thinking wins over listening, and sets the animation speed", () => {
  assert.equal(deriveMood({ thinking: true, listening: true }), "thinking");
  assert.equal(deriveMood({ thinking: false, listening: true }), "listening");
  assert.equal(deriveMood({ thinking: false, listening: false }), "idle");
  assert.deepEqual(["idle", "listening", "thinking"].map(moodFactor), [1, 0.7, 0.4]);
});

test("relative time and counts", () => {
  assert.equal(relativeTime(ago(10_000), NOW), "just now");
  assert.equal(relativeTime(ago(4 * MIN), NOW), "4m ago");
  assert.equal(relativeTime(ago(125 * MIN), NOW), "2h ago");
  assert.equal(relativeTime(ago(3 * DAY + 1), NOW), "3d ago");
  assert.equal(relativeTime("not a date", NOW), "");
  assert.equal(relativeTime(new Date(NOW + 5 * MIN).toISOString(), NOW), "just now");
  const items = [{ updated_at: ago(DAY) }, { updated_at: ago(6 * DAY) }, { updated_at: ago(8 * DAY) }, { updated_at: "bad" }];
  assert.equal(countSince(items, 7, NOW), 2);
});

test("uptime formatting", () => {
  assert.equal(formatUptime(14 * 86_400 + 2 * 3600 + 59), "14d 2h");
  assert.equal(formatUptime(3 * 3600 + 12 * 60), "3h 12m");
  assert.equal(formatUptime(299), "4m");
  assert.equal(formatUptime(-5), "0m");
});

test("failed reads say why: older Core, sign-in, turned off, connection or Core error", () => {
  const error = (reason) => ({ state: "error", reason });
  assert.deepEqual(availabilityFromError({ name: "ApiError", status: 404 }), { state: "unsupported" });
  assert.deepEqual(availabilityFromError({ name: "ApiError", status: 405 }), { state: "unsupported" });
  assert.deepEqual(availabilityFromError({ name: "ApiError", status: 401 }), error("signin"));
  assert.deepEqual(availabilityFromError({ name: "ApiError", status: 403 }), error("forbidden"));
  assert.deepEqual(availabilityFromError({ name: "ApiError", status: 500 }), error("failed"));
  assert.deepEqual(availabilityFromError({ name: "ApiError", status: 503 }), error("failed"));
  assert.deepEqual(availabilityFromError({ name: "NetworkError" }), error("network"));
  // A refused native request (e.g. a path outside the proxy allowlist) is not
  // a connection problem.
  assert.deepEqual(availabilityFromError(new Error("authenticated API path is invalid")), error("failed"));
  assert.deepEqual(availabilityFromError(null), error("failed"));
});

test("only a network failure asks to check the connection", () => {
  assert.equal(failureText("network"), "Check the connection to your Home Node.");
  assert.equal(failureText("signin"), "Sign in again on the Core page.");
  assert.equal(failureText("forbidden", "Agent is turned off in Core."), "Agent is turned off in Core.");
  for (const reason of ["signin", "forbidden", "failed"]) {
    assert.doesNotMatch(failureText(reason), /connection/i);
    assert.doesNotMatch(agentsCard({ state: "error", reason }).lines.join(" "), /connection/i);
  }
  assert.deepEqual(agentsCard({ state: "error", reason: "network" }).lines, ["Unavailable", "Check the connection"]);
  assert.deepEqual(agentsCard({ state: "error", reason: "forbidden" }).lines, ["Unavailable", "Turned off in Core"]);
});

test("cards never invent numbers for missing data", () => {
  assert.deepEqual(agentsCard({ state: "unsupported" }).lines, ["Requires newer Core", ""]);
  assert.deepEqual(agentsCard({ state: "loading" }).lines, ["Loading…", ""]);
  assert.equal(agentsCard({ state: "error", reason: "network" }).tone, "warn");
  assert.deepEqual(agentsCard({ state: "ok", value: 1 }).lines, ["1 agent", "Configured on Core"]);
  assert.deepEqual(agentsCard({ state: "ok", value: 0 }), { lines: ["0 agents", "None configured"], tone: "idle" });
});

test("conversation, task, integration and context summaries", () => {
  const conversations = { state: "ok", value: [{ updated_at: ago(4 * MIN) }, { updated_at: ago(2 * DAY) }, { updated_at: ago(9 * DAY) }] };
  assert.deepEqual(conversationsCard(conversations, NOW), { lines: ["2 this week", "Last: 4m ago"], tone: "ok" });
  assert.equal(conversationsCard({ state: "ok", value: [] }, NOW).lines[0], "No conversations yet");

  const sessions = { state: "ok", value: [{ state: "active" }, { state: "active" }, { state: "completed" }] };
  assert.deepEqual(tasksCard(sessions, { state: "ok", value: [{}] }), { lines: ["2 active", "1 awaiting approval"], tone: "warn" });
  assert.deepEqual(tasksCard(sessions, { state: "unsupported" }).lines, ["2 active", "Approvals unavailable"]);
  assert.equal(tasksCard({ state: "unsupported" }, { state: "ok", value: [] }).lines[0], "Requires newer Core");

  const registry = { state: "ok", value: { software: [{ name: "git", present: true }, { name: "ollama", present: false }] } };
  assert.deepEqual(integrationsCard(registry), { lines: ["1 of 2 present", "1 missing"], tone: "warn" });

  assert.deepEqual(contextCard({ state: "ok", value: [{}, {}] }).lines, ["2 devices linked", "Modes not yet available"]);
});

test("health card and status pill follow Core reachability", () => {
  assert.equal(healthCard(false, { state: "loading" }).tone, "error");
  assert.deepEqual(healthCard(true, { state: "ok", value: {} }).lines, ["Core ready", "Live vitals need newer Core"]);
  const live = { cpu_percent: 12.4, memory_total_bytes: 1, memory_used_bytes: 1, uptime_seconds: 90_000 };
  assert.deepEqual(healthCard(true, { state: "ok", value: { live_host: live } }).lines, ["Up 1d 1h", "Core ready · CPU 12%"]);
  assert.equal(statusPill("thinking", true).label, "Thinking");
  assert.equal(statusPill("idle", false).label, "Offline");
  assert.equal(statusPill("listening", null).label, "Connecting");
});

test("footer shows only the origin host", () => {
  assert.equal(originHost("https://jarvis.home.example:8443"), "jarvis.home.example:8443");
  assert.equal(originHost(null), "");
  assert.equal(originHost("not a url"), "");
});

test("fit scale: the stage fits the room on both axes, within bounds", () => {
  const design = { width: 1060, height: 740 };
  assert.equal(fitScale({ width: 1060, height: 740 }, design, 1.75), 1);
  assert.equal(fitScale({ width: 2000, height: 370 }, design, 1.75), 0.5); // height limits
  assert.equal(fitScale({ width: 530, height: 2000 }, design, 1.75), 0.5); // width limits
  assert.equal(fitScale({ width: 5000, height: 5000 }, design, 1.75), 1.75); // grows, capped
  assert.equal(fitScale({ width: 100, height: 100 }, design, 1.75), 0.4); // never collapses
  assert.equal(fitScale({ width: 0, height: 0 }, design, 1.75), 1); // not laid out yet
});
