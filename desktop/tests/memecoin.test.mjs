import assert from "node:assert/strict";
import test from "node:test";

import {
  AUTO_REFRESH_MS, CANDIDATE_CAP, MEME_LIMITS, appendCandidates, appendOlder, autoRefresher, candidatesCapped, isCursor,
  prependCandidates, readCandidatePage, settleRead, updatedText, failureText, goalPct, memeFailure, memeStatus, progressPct, readCandidates, readOverview, readTrades,
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

// ---- candidate paging and auto-refresh -----------------------------------

const row = (i, extra = {}) => ({ ...candidatesFixture("demo").candidates[0], observed_at: new Date(Date.UTC(2026, 0, 1) - i * 60_000).toISOString(), ...extra });
const pageOf = (from, n, next = null) => ({ rows: Array.from({ length: n }, (_, k) => row(from + k)), next });

test("candidate page reader takes a valid next_before and drops a bad one", () => {
  const body = { candidates: [row(0)] };
  assert.equal(readCandidatePage({ ...body, next_before: "MjAyNi0xMS0yNlQx:Mw.~_-" }).next, "MjAyNi0xMS0yNlQx:Mw.~_-");
  assert.equal(readCandidatePage(body).next, null); // older Core: no paging
  for (const bad of [null, 7, "", "a b", "a/b", "a?b=c", "a&before=x", "é", "x".repeat(129), ["x"], { x: 1 }]) {
    assert.equal(readCandidatePage({ ...body, next_before: bad }).next, null, JSON.stringify(bad));
    assert.equal(isCursor(bad), false);
  }
  assert.ok(isCursor("x".repeat(128)));
  assert.equal(readCandidatePage({ next_before: "abc" }), null);
});

test("dev fixture pages: demo has one older page, worst pages to the cap", () => {
  const first = readCandidatePage(candidatesFixture("demo"));
  assert.ok(first.next);
  const second = readCandidatePage(candidatesFixture("demo", first.next));
  assert.equal(second.next, null);
  const both = appendCandidates(first, second);
  assert.equal(both.rows.length, first.rows.length + second.rows.length - 1); // one repeated row
  assert.equal(readCandidatePage(candidatesFixture("empty")).next, null);
  let worst = readCandidatePage(candidatesFixture("worst"));
  for (let i = 0; i < 10 && worst.next; i++) worst = appendCandidates(worst, readCandidatePage(candidatesFixture("worst", worst.next)));
  assert.ok(candidatesCapped(worst));
});

test("older pages append without duplicates and keep the new cursor", () => {
  const have = pageOf(0, 3, "c1");
  const out = appendCandidates(have, { rows: [row(2), row(3), row(3), row(4)], next: "c2" });
  assert.deepEqual(out.rows.map((c) => c.observed_at), [0, 1, 2, 3, 4].map((i) => row(i).observed_at));
  assert.equal(out.next, "c2");
  // Same mint and time but another strategy version is another decision.
  assert.equal(appendCandidates(have, { rows: [row(2, { strategy_version: 4 })], next: null }).rows.length, 4);
  assert.equal(appendCandidates(have, pageOf(3, 1)).next, null); // last page: no button
});

test("rows stop at the cap and paging ends there", () => {
  let p = pageOf(0, 100, "c");
  for (let i = 1; i < 6; i++) p = appendCandidates(p, pageOf(i * 100, 100, "c"));
  assert.equal(p.rows.length, CANDIDATE_CAP);
  assert.equal(p.next, null);
  assert.ok(candidatesCapped(p));
  assert.equal(appendCandidates(pageOf(0, 450, "c"), pageOf(450, 100, "c")).rows.length, CANDIDATE_CAP);
});

test("a late older page is appended only while its cursor is still shown", () => {
  const ok = { kind: "ok", data: pageOf(0, 2, "c1") };
  assert.equal(appendOlder(ok, "c1", pageOf(2, 2)).data.rows.length, 4);
  assert.equal(appendOlder(ok, "c0", pageOf(2, 2)), ok); // reset or refresh moved on
  assert.deepEqual(appendOlder({ kind: "loading" }, "c1", pageOf(2, 2)), { kind: "loading" });
});

test("a failed refresh keeps the rows and reports quietly", () => {
  const ok = { kind: "ok", data: pageOf(0, 3, "c1") };
  const failed = { kind: "failed", failure: "unreachable" };
  assert.deepEqual(settleRead(ok, failed, true, prependCandidates), { state: ok, quiet: "unreachable" });
  assert.deepEqual(settleRead(ok, failed, false), { state: failed, quiet: null }); // a manual reload shows the error
  assert.deepEqual(settleRead({ kind: "loading" }, failed, true), { state: failed, quiet: null });
  const fresh = { kind: "ok", data: pageOf(0, 1) };
  assert.equal(settleRead(ok, fresh, false, prependCandidates).state, fresh); // manual reload: first page again
});

test("a background refresh prepends new rows and keeps older pages and their cursor", () => {
  const have = pageOf(0, 120, "old-cursor"); // first page plus older pages
  const merged = prependCandidates(have, { rows: [row(-2), row(-1), row(0), row(1)], next: "first-page-cursor" });
  assert.equal(merged.rows.length, 122);
  assert.equal(merged.rows[0].observed_at, row(-2).observed_at);
  assert.equal(merged.next, "old-cursor");
  // No overlap with more pages behind it: a gap, so start over from that page.
  const gap = { rows: [row(-10)], next: "n" };
  assert.equal(prependCandidates(have, gap), gap);
  assert.equal(prependCandidates(pageOf(0, 0), gap), gap); // nothing shown yet
  // Capped: nothing is added any more.
  const capped = pageOf(0, CANDIDATE_CAP);
  assert.equal(prependCandidates(capped, { rows: [row(-1)], next: null }), capped);
  const filled = prependCandidates(pageOf(0, 499, "c"), { rows: [row(-2), row(-1), row(0)], next: "x" });
  assert.equal(filled.rows.length, CANDIDATE_CAP);
  assert.equal(filled.next, null);
});

function fakeTimers() {
  const t = { live: new Set(), set: (f) => { const id = { f }; t.live.add(id); return id; }, clear: (id) => t.live.delete(id) };
  t.fire = () => [...t.live].forEach((id) => id.f());
  return t;
}

test("auto-refresh runs only while active and restarts with one refresh", async () => {
  const timers = fakeTimers();
  let calls = 0;
  const auto = autoRefresher(async () => { calls++; }, AUTO_REFRESH_MS, timers);
  auto.setActive(true); // first activation: the page has just loaded
  assert.equal(calls, 0);
  assert.equal(timers.live.size, 1);
  timers.fire();
  await Promise.resolve();
  assert.equal(calls, 1);
  auto.setActive(false); // hidden, locked or left
  assert.equal(timers.live.size, 0);
  timers.fire();
  assert.equal(calls, 1);
  auto.setActive(true); // visible again: refresh once, then the timer
  await Promise.resolve();
  assert.equal(calls, 2);
  assert.equal(timers.live.size, 1);
  auto.setActive(true); // no second timer
  assert.equal(timers.live.size, 1);
});

test("auto-refresh never overlaps and survives a failing refresh", async () => {
  const timers = fakeTimers();
  let calls = 0;
  let finish;
  const auto = autoRefresher(() => { calls++; return new Promise((_, reject) => (finish = reject)); }, AUTO_REFRESH_MS, timers);
  auto.setActive(true);
  timers.fire();
  timers.fire(); // still in flight
  assert.equal(calls, 1);
  finish(new Error("offline"));
  await new Promise((r) => setTimeout(r, 0));
  timers.fire();
  assert.equal(calls, 2);
});

test("updated label is HH:MM in local time", () => {
  assert.equal(updatedText(new Date(2026, 10, 26, 9, 5)), "Updated 09:05");
  assert.equal(updatedText(new Date(2026, 10, 26, 23, 59)), "Updated 23:59");
});
