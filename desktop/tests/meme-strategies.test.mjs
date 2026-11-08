import assert from "node:assert/strict";
import test from "node:test";

import {
  STRATEGY_LIMITS, bpsPct, decisionView, isStrategyId, readDecisions, readStrategies, readStrategy, sendDisable, strategyState,
  versionOwner,
} from "../src/memeStrategies.ts";
import { agentStatus, readOverview } from "../src/memecoin.ts";
import { decisionsFixture, overviewFixture, strategiesFixture, strategyFixture } from "../src/memecoinFixtures.ts";

const SETS = ["demo", "worst", "empty", "stale", "disabled", "paused"];
const clone = (x) => JSON.parse(JSON.stringify(x));
const ID = "a-survivor-momentum";

test("every dev fixture passes the strategy and decision guards", () => {
  for (const set of SETS) {
    const list = readStrategies(strategiesFixture(set));
    assert.ok(list, set);
    for (const s of list) assert.ok(readStrategy(strategyFixture(set, s.id), s.id), `${set} ${s.id}`);
    assert.ok(readDecisions(decisionsFixture(set)), set);
  }
});

test("strategy ids follow Core's rule", () => {
  assert.ok(isStrategyId("claude-1000005"));
  for (const bad of ["", "A-b", "a_b", "a/b", "..", "a".repeat(33), 7, null]) assert.equal(isStrategyId(bad), false, String(bad));
});

test("strategy list guard fails closed", () => {
  const good = strategiesFixture("demo").strategies[0];
  const broken = [
    { ...good, id: "Bad" },
    { ...good, family: "d" },
    { ...good, active_version: 0 },
    { ...good, enabled: "true" },
    { ...good, changed_by: "x".repeat(65) },
    { ...good, performance: { ...good.performance, net_usd: 14.82 } },
    { ...good, performance: { ...good.performance, win_rate_bps: 10001 } },
    { ...good, performance: undefined },
  ];
  for (const s of broken) assert.equal(readStrategies({ strategies: [s] }), null, JSON.stringify(s).slice(0, 80));
  assert.equal(readStrategies({ strategies: Array.from({ length: STRATEGY_LIMITS.strategies + 1 }, () => good) }), null);
  assert.equal(readStrategies({}), null);
  const read = readStrategies({ strategies: [{ ...good, family: null, performance: { ...good.performance, profit_factor: undefined } }] });
  assert.equal(read[0].family, null);
  assert.equal(read[0].performance.profit_factor, null);
});

test("strategy detail guard fails closed and only for the asked id", () => {
  const good = strategyFixture("demo", ID);
  assert.ok(readStrategy(good, ID));
  assert.equal(readStrategy(good, "b-base-reclaim"), null);
  const v = good.versions[0];
  const broken = [
    { ...good, active_config_sha256: "XYZ" },
    { ...good, versions: [{ ...v, author: "owner" }] },
    { ...good, versions: [{ ...v, rationale: "x".repeat(601) }] },
    { ...good, versions: [{ ...v, rationale: "evil‮text" }] },
    { ...good, versions: [{ ...v, rationale: "line\nbreak" }] },
    { ...good, versions: [{ ...v, diff: [{ field: "x", from: "a".repeat(STRATEGY_LIMITS.value), to: 1 }] }] },
    { ...good, versions: [{ ...v, diff: "none" }] },
    { ...good, versions: [{ ...v, backtest: { status: "great", mints: 1, points: 2 } }] },
    { ...good, versions: [{ ...v, backtest: { status: "ok", mints: 1, points: 2 } }] },
    { ...good, versions: Array.from({ length: STRATEGY_LIMITS.versions + 1 }, () => v) },
    { ...good, gate: { ...good.gate, net_result_usd: 1 } },
  ];
  for (const x of broken) assert.equal(readStrategy(x, ID), null, JSON.stringify(x).slice(0, 120));
});

test("versions read as diff rows, plain rationale and a parameter list", () => {
  const s = readStrategy(strategyFixture("demo", ID), ID);
  const [top, , seed] = s.versions;
  assert.deepEqual(top.diff, [
    { field: "take_profit_bps", from: "5000", to: "7500" },
    { field: "trailing_stop_bps", from: "null", to: "1500" },
  ]);
  assert.equal(top.author, "claude");
  assert.equal(top.backtest.status, "unbacktested");
  assert.equal(seed.diff, null);
  assert.equal(seed.author, "seed");
  // Identity fields are in the header, not the parameters.
  const fields = top.config.map(([k]) => k);
  assert.ok(fields.includes("take_profit_bps"));
  for (const k of ["id", "version", "parent", "family"]) assert.equal(fields.includes(k), false, k);
  // A field missing on one side reads as a dash, not "undefined".
  const raw = clone(strategyFixture("demo", ID));
  raw.versions[0].diff = [{ field: "trailing_stop_bps", to: 1500 }];
  assert.deepEqual(readStrategy(raw, ID).versions[0].diff, [{ field: "trailing_stop_bps", from: "—", to: "1500" }]);
});

test("decision guard fails closed", () => {
  const good = decisionsFixture("demo").decisions[1];
  const broken = [
    { ...good, kind: "Enter" },
    { ...good, call_id: "a b" },
    { ...good, mint: "not base58 0OIl" },
    { ...good, size_bps: 0 },
    { ...good, size_bps: 10001 },
    { ...good, reason_code: "Bad-Code" },
    { ...good, rationale: "x".repeat(301) },
    { ...good, rationale: "zero​width" },
    { ...good, model: "claude <script>" },
    { ...good, at: "yesterday" },
  ];
  for (const d of broken) assert.equal(readDecisions({ decisions: [d] }), null, JSON.stringify(d).slice(0, 80));
  assert.equal(readDecisions({ decisions: Array.from({ length: 101 }, () => good) }), null);
});

test("call_started rows read as a muted 'Claude called'", () => {
  const [started, enter] = readDecisions(decisionsFixture("demo"));
  assert.deepEqual(decisionView(started), { kind: "Claude called", reason: "", muted: true });
  assert.deepEqual(decisionView(enter), { kind: "Enter", reason: "fresh_high_breakout", muted: false });
  // Only skip/call_started is muted; other skips keep their reason.
  assert.deepEqual(decisionView({ ...enter, kind: "skip", reason_code: "no_slot" }), { kind: "Skipped", reason: "No free slot", muted: false });
  assert.equal(decisionView({ ...enter, kind: "a_new_kind" }).kind, "Other (a_new_kind)");
  assert.equal(decisionView({ ...enter, kind: "toString", reason_code: "constructor" }).kind, "Other (toString)");
  assert.equal(decisionView({ ...enter, reason_code: "constructor" }).reason, "constructor");
});

test("display helpers", () => {
  const list = readStrategies(strategiesFixture("demo"));
  assert.equal(versionOwner(list, 1000004), ID);
  assert.equal(versionOwner(list, 999), null);
  assert.equal(versionOwner(list, null), null);
  assert.equal(bpsPct(4125), "41.25%");
  assert.equal(bpsPct(6000), "60%");
  assert.equal(strategyState(list[0]).label, "Enabled");
  assert.equal(strategyState(list[1]).label, "Disabled by you");
  assert.equal(strategyState(list[3]).label, "Disabled");
});

const at = () => new Date("2026-11-08T10:42:18.000Z");

test("disable succeeds only on Core's confirmation for that strategy", async () => {
  assert.deepEqual(await sendDisable(ID, async () => ({ strategy_id: ID, enabled: false }), at), { ok: true, at: "10:42:18 UTC" });
  for (const answer of [{ strategy_id: "other", enabled: false }, { strategy_id: ID, enabled: true }, {}, null]) {
    const r = await sendDisable(ID, async () => answer, at);
    assert.equal(r.ok, false);
    assert.match(r.message, /did not confirm/);
  }
  const fail = (e) => sendDisable(ID, async () => { throw e; }, at);
  const r503 = await fail({ name: "ApiError", status: 503 });
  assert.equal(r503.status, 503);
  assert.match(r503.message, /restart would undo it/);
  assert.match((await fail({ name: "ApiError", status: 401 })).message, /nothing changed/);
  assert.match((await fail({ name: "ApiError", status: 404 })).message, /no strategy/);
  assert.match((await fail({ name: "ApiError", status: 429 })).message, /Wait a minute/);
  assert.match((await fail({ name: "NetworkError" })).message, /unknown whether/);
});

test("agent status: off, stopped, paused until, backoff, active", () => {
  const now = Date.now();
  const o = readOverview(overviewFixture("demo"));
  assert.equal(agentStatus(o, now).kind, "active");
  assert.match(agentStatus(o, now).detail, /2 of 4 calls this hour, 17 of 48/);
  assert.equal(agentStatus({ ...o, agent: null }, now).kind, "off");
  assert.equal(agentStatus(readOverview(overviewFixture("disabled")), now).kind, "off");
  assert.equal(agentStatus(readOverview(overviewFixture("paused")), now).kind, "stopped");
  const worst = readOverview(overviewFixture("worst"));
  // The trader is halted in "worst" but running; pause outranks backoff.
  const paused = agentStatus({ ...worst, last_error: null }, now);
  assert.equal(paused.kind, "paused");
  assert.equal(paused.until, worst.agent.paused_until);
  const backoff = agentStatus({ ...worst, agent: { ...worst.agent, paused_until: null } }, now);
  assert.equal(backoff.kind, "backoff");
  // A pause or backoff in the past no longer applies.
  const past = new Date(now - 60_000).toISOString();
  assert.equal(agentStatus({ ...o, agent: { ...o.agent, paused_until: past, backoff_until: past } }, now).kind, "active");
  assert.equal(agentStatus({ ...o, agent: { ...o.agent, calling: true } }, now).kind, "calling");
});

test("overview agent guard fails closed; an older Core without it reads as off", () => {
  const good = overviewFixture("demo");
  for (const agent of [{ ...good.agent, calling: "no" }, { ...good.agent, calls_last_hour: -1 }, { ...good.agent, paused_until: "soon" }, "on"]) {
    assert.equal(readOverview({ ...good, agent }), null, JSON.stringify(agent));
  }
  const older = clone(good);
  delete older.agent;
  assert.equal(readOverview(older).agent, null);
});
