import assert from "node:assert/strict";
import test from "node:test";

import {
  MEME_LIMITS, failureText, goalPct, memeFailure, memeStatus, progressPct, readCandidates, readOverview, readTrades,
  reasonLabel, REJECT_REASONS, reportAge, sendPause, shortMint, stuckCount, usd,
} from "../src/memecoin.ts";
import { STALE_AFTER_MS } from "../src/trading.ts";
import { candidatesFixture, overviewFixture, tradesFixture } from "../src/memecoinFixtures.ts";

const SETS = ["demo", "worst", "empty", "stale", "disabled", "paused"];
const clone = (x) => JSON.parse(JSON.stringify(x));

test("every dev fixture passes the guards", () => {
  for (const set of SETS) {
    assert.ok(readOverview(overviewFixture(set)), set);
    assert.ok(readCandidates(candidatesFixture(set)), set);
    assert.ok(readTrades(tradesFixture(set)), set);
  }
});

test("overview without a report is the off or starting shape", () => {
  const off = readOverview({ mode: "paper", enabled: false, running: false, paused: false, paused_at: null, last_error: null, resume: "x" });
  assert.equal(off.report, null);
  assert.equal(memeStatus(off).kind, "disabled");
});

test("overview guard fails closed", () => {
  const good = overviewFixture("demo");
  const broken = [
    null, [], "paper",
    { ...good, mode: "live" }, // never show anything that is not paper
    { ...good, mode: undefined },
    { ...good, enabled: "true" },
    { ...good, paused_at: "yesterday" },
    { ...good, last_error: "x".repeat(MEME_LIMITS.text + 1) },
    { ...good, equity_usd: 270.11 }, // money must be a decimal string
    { ...good, equity_usd: "1e3" },
    { ...good, as_of: undefined }, // half a report
    { ...good, pnl: { ...good.pnl, ai_cost_usd: null } },
    { ...good, gate: { ...good.gate, closed_trades: -1 } },
    { ...good, gate: { ...good.gate, passed: "no" } },
    { ...good, goal: { ...good.goal, multiple: 1.08 } },
    { ...good, open_positions: [{ ...good.open_positions[0], mint: "not base58 0OIl" }] },
    { ...good, open_positions: [{ ...good.open_positions[0], stuck: undefined }] },
    { ...good, open_positions: Array.from({ length: MEME_LIMITS.positions + 1 }, () => good.open_positions[0]) },
  ];
  for (const x of broken) assert.equal(readOverview(x), null, JSON.stringify(x)?.slice(0, 80));
});

test("optional fields read as null", () => {
  const raw = clone(overviewFixture("demo"));
  delete raw.paused_at;
  delete raw.goal.multiple;
  delete raw.open_positions[0].value_usd;
  const o = readOverview(raw);
  assert.equal(o.paused_at, null);
  assert.equal(o.report.goal.multiple, null);
  assert.equal(o.report.open_positions[0].value_usd, null);
});

test("candidate and trade guards are deep and capped", () => {
  const c = candidatesFixture("demo").candidates[0];
  assert.equal(readCandidates({ candidates: [{ ...c, verdict: "maybe" }] }), null);
  assert.equal(readCandidates({ candidates: [{ ...c, verdict: "reject", reason: null }] }), null);
  assert.equal(readCandidates({ candidates: [{ ...c, verdict: "accept", reason: "lp_not_secured" }] }), null);
  assert.equal(readCandidates({ candidates: [{ ...c, price_usd: 0.02 }] }), null);
  assert.equal(readCandidates({ candidates: Array.from({ length: MEME_LIMITS.list + 1 }, () => c) }), null);
  assert.deepEqual(readCandidates({ candidates: [] }), []);
  assert.equal(readCandidates({}), null);
  const t = tradesFixture("demo").trades[0];
  assert.equal(readTrades({ trades: [{ ...t, net_pnl_usd: -4.92 }] }), null);
  assert.equal(readTrades({ trades: [{ ...t, closed_at: "" }] }), null);
  assert.equal(readTrades({ trades: "none" }), null);
});

test("status picks the most restrictive state", () => {
  const o = readOverview(overviewFixture("demo"));
  assert.equal(memeStatus(o).kind, "running");
  assert.equal(memeStatus({ ...o, enabled: false, paused: true }).kind, "disabled");
  assert.equal(memeStatus({ ...o, running: false, last_error: "paper trader did not start; see the Core log" }).kind, "stopped");
  assert.match(memeStatus({ ...o, running: false, last_error: "paper trader did not start; see the Core log" }).detail, /did not start/);
  assert.equal(memeStatus({ ...o, paused: true, last_error: "tick timed out" }).kind, "paused");
  assert.match(memeStatus({ ...o, paused: true }).detail, /still valued and exited/);
  // A failing tick is not hidden behind the pause, and exits are not claimed.
  const pausedFailing = memeStatus({ ...o, paused: true, last_error: "tick timed out" }).detail;
  assert.match(pausedFailing, /tick timed out/);
  assert.doesNotMatch(pausedFailing, /still valued/);
  assert.equal(memeStatus({ ...o, last_error: "tick timed out" }).kind, "halted");
  assert.equal(memeStatus({ ...o, report: null }).kind, "starting");
  assert.equal(memeStatus(readOverview(overviewFixture("paused"))).kind, "paused");
  assert.equal(memeStatus(readOverview(overviewFixture("disabled"))).kind, "disabled");
  assert.equal(memeStatus(readOverview(overviewFixture("worst"))).kind, "halted");
  // Nothing says "live" except to deny it.
  for (const s of [o, { ...o, enabled: false }, { ...o, paused: true }, { ...o, running: false }]) {
    assert.doesNotMatch(memeStatus(s).detail + memeStatus(s).label, /\blive\b/i);
  }
});

test("stale and stuck are flagged separately from the status", () => {
  const now = Date.now();
  const o = readOverview(overviewFixture("demo"));
  assert.equal(reportAge(o, now), null);
  const old = { ...o, report: { ...o.report, as_of: new Date(now - STALE_AFTER_MS - 1000).toISOString() } };
  assert.ok(reportAge(old, now) > STALE_AFTER_MS);
  assert.equal(reportAge({ ...o, report: null }, now), null);
  assert.equal(stuckCount(o), 0);
  assert.equal(stuckCount(readOverview(overviewFixture("worst"))), 2);
  assert.ok(reportAge(readOverview(overviewFixture("stale")), now) > STALE_AFTER_MS);
});

test("read failures map to what the owner can do", () => {
  assert.equal(memeFailure({ name: "NetworkError" }), "unreachable");
  assert.equal(memeFailure({ name: "ApiError", status: 404 }), "no-api");
  assert.equal(memeFailure({ name: "ApiError", status: 401 }), "signin");
  assert.equal(memeFailure({ name: "ApiError", status: 503 }), "unavailable");
  assert.equal(memeFailure({ name: "ApiError", status: 500 }), "failed");
  assert.equal(memeFailure(new Error("x")), "failed");
  assert.match(failureText("signin"), /Sign in/);
});

const at = () => new Date("2026-11-01T10:42:18.000Z");

test("pause succeeds only on Core's confirmation", async () => {
  let calls = 0;
  const ok = await sendPause(async () => { calls++; return { paused: true }; }, at);
  assert.deepEqual(ok, { ok: true, at: "10:42:18 UTC" });
  assert.equal(calls, 1);
  for (const answer of [{ paused: false }, {}, null, "ok", { paused: "true" }]) {
    const r = await sendPause(async () => answer, at);
    assert.equal(r.ok, false, JSON.stringify(answer));
    assert.match(r.message, /did not confirm/);
  }
});

test("pause 401: nothing changed, sign in again", async () => {
  const r = await sendPause(async () => { throw { name: "ApiError", status: 401 }; }, at);
  assert.equal(r.ok, false);
  assert.match(r.message, /10:42:18 UTC/);
  assert.match(r.message, /HTTP 401/);
  assert.match(r.message, /nothing changed/);
  assert.match(r.message, /Sign in/);
});

test("pause errors say when, what Core answered and what holds", async () => {
  const fail = (e) => sendPause(async () => { throw e; }, at);
  const r503 = await fail({ name: "ApiError", status: 503 });
  assert.match(r503.message, /HTTP 503/);
  assert.match(r503.message, /restart would undo it/);
  assert.equal(r503.status, 503);
  const r404 = await fail({ name: "ApiError", status: 404 });
  assert.match(r404.message, /no memecoin pause/);
  const r500 = await fail({ name: "ApiError", status: 500 });
  assert.match(r500.message, /HTTP 500.*not confirmed/);
  const net = await fail({ name: "NetworkError" });
  assert.match(net.message, /unknown whether the pause arrived/);
  const other = await fail(new Error("Home Node changed"));
  assert.match(other.message, /could not be sent/);
  for (const r of [r503, r404, r500, net, other]) {
    assert.equal(r.ok, false);
    assert.equal(r.at, "10:42:18 UTC");
  }
});

test("display helpers", () => {
  assert.equal(shortMint("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"), "7xKX…gAsU");
  assert.equal(shortMint("short"), "short");
  assert.equal(usd("270.1158"), "$270.12");
  assert.equal(usd("-12.5", { sign: true }), "−$12.50");
  assert.equal(usd("0.0042"), "$0.0042"); // a sub-cent cost never reads $0.00
  assert.equal(usd("0"), "$0.00");
  assert.equal(usd(null), "—");
  assert.equal(progressPct(63, 100), 63);
  assert.equal(progressPct(150, 100), 100);
  assert.equal(progressPct(0, 0), 100);
  assert.equal(goalPct("0.0805"), 8.05);
  assert.equal(goalPct("1.5"), 100);
  assert.equal(goalPct(null), null);
  assert.equal(reasonLabel(REJECT_REASONS, "lp_not_secured"), "Liquidity not locked or burned");
  assert.equal(reasonLabel(REJECT_REASONS, "toString"), "Other (toString)");
  assert.equal(reasonLabel(REJECT_REASONS, null), "—");
});
