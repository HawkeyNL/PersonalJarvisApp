import assert from "node:assert/strict";
import test from "node:test";

import {
  STALE_AFTER_MS, ageText, changePercent, chartNumber, dataState, decimalSign, failureState, formatDecimal, formatMoney,
  LIMITS, changeRatio, formatPercent, formatPrice, movingAverage, notConnectedText, onTarget, parseConid, readBars, readHistory,
  readPortfolio, signTone, sourceLabel,
} from "../src/trading.ts";
import { barsFixture, historyFixture, portfolioFixture } from "../src/tradingFixtures.ts";

test("decimal strings format exactly, without floats", () => {
  assert.equal(formatDecimal("1234567.891"), "1,234,567.891");
  assert.equal(formatDecimal("0.1", { places: 2 }), "0.10");
  // 0.1 + 0.2 style float errors cannot happen: the digits are rounded as text.
  assert.equal(formatDecimal("1.005", { places: 2 }), "1.01");
  assert.equal(formatDecimal("9999999999999999.995", { places: 2 }), "10,000,000,000,000,000.00");
  assert.equal(formatDecimal("-0.004", { places: 2 }), "0.00");
  assert.equal(formatDecimal("12.50", { sign: true }), "+12.50");
  assert.equal(formatDecimal("-3", { sign: true }), "−3");
  assert.equal(formatDecimal("0.00010000", { maxPlaces: 8 }), "0.0001");
  assert.equal(formatDecimal("007"), "7");
  for (const bad of [undefined, null, "", "1e5", "1,000", "NaN", 12.5, " -", "--1"]) {
    assert.equal(formatDecimal(bad), "—", String(bad));
  }
});

test("money follows the currency and keeps its sign", () => {
  assert.equal(formatMoney("23820", "EUR"), "€23,820.00");
  assert.equal(formatMoney("-63.1", "EUR", { sign: true }), "−€63.10");
  assert.equal(formatMoney("101.6", "EUR", { sign: true }), "+€101.60");
  assert.equal(formatMoney("0.00", "EUR", { sign: true }), "€0.00");
  assert.equal(formatMoney("176.4", "USD"), "$176.40");
  assert.equal(formatMoney("2850.4", "JPY"), "¥2,850");
  assert.equal(formatMoney("0.000000412", "EUR", { places: 9 }), "€0.000000412");
  assert.equal(formatMoney("128456789012.34", "EUR"), "€128,456,789,012.34");
  assert.equal(formatMoney("10", "CHF"), "10.00 CHF");
  assert.equal(formatMoney("10", "not-a-code"), "10.00 not-a-code");
  assert.equal(formatMoney("10", ""), "10.00");
  assert.equal(formatMoney(undefined, "EUR"), "—");
  assert.equal(formatPrice("0.00041200", "USD"), "$0.000412");
  assert.equal(formatPrice("176.40", "USD"), "$176.40");
  assert.equal(formatPrice("2850.00", "JPY"), "¥2,850");
});

test("percentages, signs and chart numbers", () => {
  assert.equal(formatPercent("0.43"), "+0.43%");
  assert.equal(formatPercent("-99.9"), "−99.90%");
  assert.equal(formatPercent(undefined), "—");
  assert.deepEqual(["-1", "0.000", "3", "x"].map(decimalSign), [-1, 0, 1, null]);
  assert.deepEqual(["-1", "0", "2", undefined].map(signTone), ["neg", "flat", "pos", "flat"]);
  assert.equal(chartNumber("176.40"), 176.4);
  assert.equal(chartNumber("abc"), null);
  assert.equal(changePercent("100", "104.8"), "+4.80%");
  assert.equal(changePercent("0", "1"), "—");
  assert.equal(changeRatio("100", "95"), "-5.00");
  assert.equal(signTone(changeRatio("100", "95")), "neg");
  assert.equal(changeRatio(undefined, "1"), null);
  // A non-string currency never throws.
  for (const currency of [undefined, null, 42, {}]) assert.equal(formatMoney("1", currency), "1.00");
});

test("moving average waits for n bars and skips gaps", () => {
  assert.deepEqual(movingAverage([1, 2, 3, 4], 2), [null, 1.5, 2.5, 3.5]);
  assert.deepEqual(movingAverage([1, null, 3, 5], 2), [null, null, null, 4]);
  assert.deepEqual(movingAverage([1, 2], 50), [null, null]);
});

test("labels name the source and the delay", () => {
  assert.equal(sourceLabel({ source: "IBKR", delay_minutes: 15 }), "IBKR · delayed 15 min");
  assert.equal(sourceLabel({ source: "IBKR", delay_minutes: 0 }), "IBKR · real time");
  // Missing or nonsense delay is never shown as real time.
  for (const delay_minutes of [undefined, null, -1, NaN, Infinity, "15"]) {
    assert.equal(sourceLabel({ source: "IBKR", delay_minutes }), "IBKR · delay unknown", String(delay_minutes));
  }
  assert.equal(ageText(3 * 3_600_000), "3 h");
  assert.equal(ageText(Infinity), "an unknown time");
});

test("failed reads: not connected for 404/405/501/503 and no answer, else an error", () => {
  const api = (status) => ({ name: "ApiError", status });
  assert.deepEqual(failureState(api(404)), { kind: "not-connected", reason: "no-api" });
  assert.deepEqual(failureState(api(405)), { kind: "not-connected", reason: "no-api" });
  assert.deepEqual(failureState(api(501)), { kind: "not-connected", reason: "no-api" });
  assert.deepEqual(failureState(api(503)), { kind: "not-connected", reason: "gateway" });
  assert.deepEqual(failureState({ name: "NetworkError" }), { kind: "not-connected", reason: "unreachable" });
  assert.deepEqual(failureState(api(401)), { kind: "error", reason: "signin" });
  assert.deepEqual(failureState(api(500)), { kind: "error", reason: "failed" });
  assert.deepEqual(failureState(new Error("boom")), { kind: "error", reason: "failed" });
  // Every not-connected reason tells the owner what to do.
  for (const reason of ["no-api", "gateway", "unreachable"]) assert.ok(notConnectedText(reason).detail.length > 40, reason);
  assert.match(notConnectedText("no-api").title, /Not connected to IBKR yet/);
});

test("successful reads: empty, data or stale", () => {
  const now = Date.parse("2026-11-01T12:00:00Z");
  const at = (ms) => ({ source: "IBKR", delay_minutes: 15, as_of: new Date(now - ms).toISOString(), items: [1] });
  const isEmpty = (d) => d.items.length === 0;
  assert.equal(dataState(at(60_000), isEmpty, now).kind, "data");
  assert.equal(dataState({ ...at(0), items: [] }, isEmpty, now).kind, "empty");
  // Stale wins over empty, and keeps the emptiness for the screen.
  assert.deepEqual(dataState({ ...at(STALE_AFTER_MS + 1), items: [] }, isEmpty, now), { kind: "stale", data: { ...at(STALE_AFTER_MS + 1), items: [] }, ageMs: STALE_AFTER_MS + 1, empty: true });
  const stale = dataState(at(STALE_AFTER_MS + 1), isEmpty, now);
  assert.equal(stale.kind, "stale");
  assert.equal(stale.ageMs, STALE_AFTER_MS + 1);
  assert.equal(dataState({ ...at(0), as_of: "garbage" }, isEmpty, now).kind, "stale");
});

test("allocation counts buckets within 2 points of target", () => {
  assert.equal(onTarget([
    { label: "a", current_pct: "57", target_pct: "60" },
    { label: "b", current_pct: "9", target_pct: "10" },
    { label: "c", current_pct: "x", target_pct: "10" },
  ]), 1);
});

test("worst-case fixtures format without NaN, undefined or floats leaking", () => {
  const p = portfolioFixture("worst");
  const shown = [
    formatMoney(p.net_liquidation, p.base_currency), formatMoney(p.day_pnl, p.base_currency, { sign: true }),
    formatMoney(p.dividends_12m, p.base_currency),
    ...p.positions.flatMap((x) => [formatMoney(x.market_value, p.base_currency), formatMoney(x.unrealized_pnl, p.base_currency), formatDecimal(x.quantity, { maxPlaces: 8 }), formatPercent(x.day_change_pct)]),
  ];
  for (const text of shown) assert.doesNotMatch(text, /NaN|undefined|e\+|null/, text);
  assert.equal(formatMoney(p.dividends_12m, p.base_currency), "—"); // missing field
  assert.equal(portfolioFixture("empty").positions.length, 0);
  assert.equal(historyFixture("empty", "3M").points.length, 0);
  const bars = barsFixture("worst", 265598, "1M");
  assert.ok(bars.bars.length > 0 && bars.bars.some((b) => b.v === undefined));
  assert.ok(Date.now() - Date.parse(portfolioFixture("stale").as_of) > STALE_AFTER_MS);
});

test("parseConid accepts positive safe integers only", () => {
  assert.equal(parseConid("265598"), 265598);
  assert.equal(parseConid(265598), 265598);
  for (const bad of ["0", 0, -1, "12a", "1.5", 1.5, "", null, undefined, "9999999999999", Number.MAX_SAFE_INTEGER + 2, {}]) {
    assert.equal(parseConid(bad), null, String(bad));
  }
});

const meta = { source: "IBKR", delay_minutes: 15, as_of: "2026-11-01T12:00:00Z" };
const position = { conid: 1, symbol: "A", asset_class: "STK", currency: "EUR", quantity: "1", avg_cost: "1", last_price: "1", market_value: "1" };
const portfolio = { ...meta, base_currency: "EUR", net_liquidation: "1", cash: "0", positions: [position] };

test("portfolio guard checks required fields and element shapes", () => {
  assert.ok(readPortfolio(portfolio));
  for (const fixture of ["demo", "worst", "empty", "stale"]) assert.ok(readPortfolio(portfolioFixture(fixture)), fixture);
  const bad = [
    null, [], "x", { ...portfolio, base_currency: undefined }, { ...portfolio, net_liquidation: 1 }, { ...portfolio, as_of: undefined },
    { ...portfolio, delay_minutes: -5 }, { ...portfolio, delay_minutes: "15" }, { ...portfolio, positions: "none" },
    { ...portfolio, positions: [{ ...position, symbol: 7 }] }, { ...portfolio, positions: [{ ...position, conid: "abc" }] },
    { ...portfolio, positions: [{ ...position, currency: undefined }] }, { ...portfolio, positions: [null] },
    { ...portfolio, allocation: [{ label: "x", current_pct: 5, target_pct: "5" }] },
    { ...portfolio, upcoming_dividends: [{ conid: 1, symbol: "A", ex_date: "2026-11-05" }] },
    { ...portfolio, monthly_deposit: { amount: "500" } },
    { ...portfolio, positions: Array(LIMITS.positions + 1).fill(position) },
  ];
  for (const x of bad) assert.equal(readPortfolio(x), null, JSON.stringify(x)?.slice(0, 80));
  // Delay may be absent; the label then says so.
  assert.ok(readPortfolio({ ...portfolio, delay_minutes: undefined }));
});

test("bars guard drops bars without finite prices and rejects bad shapes", () => {
  const bar = { t: "2026-11-01T12:00:00Z", o: "1", h: "2", l: "0.5", c: "1.5", v: "100" };
  const base = { ...meta, conid: 1, symbol: "A", currency: "USD", range: "1M", bars: [bar] };
  const read = readBars({ ...base, bars: [bar, { ...bar, o: undefined }, { ...bar, c: "NaN" }, { ...bar, h: 1 }, null, { ...bar, v: "x" }] });
  assert.equal(read.bars.length, 2);
  assert.equal(read.bars[1].v, undefined);
  assert.equal(readBars({ ...base, symbol: undefined }), null);
  assert.equal(readBars({ ...base, currency: 3 }), null);
  assert.equal(readBars({ ...base, bars: Array(LIMITS.bars + 1).fill(bar) }), null);
  assert.equal(readBars({ ...base, notes: [{ t: "x", kind: "rumour", text: "y" }] }), null);
  assert.equal(readBars({ ...base, notes: [{ t: "x", kind: "fact", text: "y", sources: [1] }] }), null);
  assert.equal(readBars({ ...base, notes: Array(LIMITS.notes + 1).fill({ t: "x", kind: "fact", text: "y" }) }), null);
  assert.ok(readBars(barsFixture("demo", 265598, "1Y")).notes.length > 0);
  assert.ok(readBars(barsFixture("worst", 265598, "1M")));
});

test("history guard drops unreadable points and caps the list", () => {
  const base = { ...meta, base_currency: "EUR", range: "3M" };
  assert.equal(readHistory({ ...base, points: [{ t: "a", value: "1" }, { t: "b", value: 2 }, { value: "3" }] }).points.length, 1);
  assert.equal(readHistory({ ...base, points: Array(LIMITS.points + 1).fill({ t: "a", value: "1" }) }), null);
  assert.equal(readHistory({ ...base, base_currency: null, points: [] }), null);
  assert.ok(readHistory(historyFixture("demo", "1Y")));
});
