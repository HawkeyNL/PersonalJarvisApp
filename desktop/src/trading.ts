// Trading read API contract, the decimal-string formatters and the screen-state
// selection for the trading screens. Pure: no Vue or Tauri imports, so tests
// load this file directly.
//
// Core serves these routes from its IBKR broker (OAuth, read-only):
//   GET /v1/trading/portfolio                           -> Portfolio
//   GET /v1/trading/portfolio/history?range=<HistoryRange> -> PortfolioHistory
//   GET /v1/trading/instruments/{conid}/bars?range=<BarRange> -> InstrumentBars
// Money, prices and quantities are decimal strings ("-1234.5600"), never JSON
// numbers: a JS float cannot hold every amount exactly. They become numbers
// only to draw a chart (chartNumber).

import type { Tone } from "./hubModel";

/** Exact decimal as a string: optional "-", digits, optional "." + digits. */
export type Decimal = string;

/** Where the numbers come from and how old they are. On every response. */
export type Freshness = {
  /** Display name of the data source, e.g. "IBKR". */
  source: string;
  /** Price delay of the market-data subscription; 0 is real time. Absent:
   *  unknown, shown as "delay unknown", never as real time. */
  delay_minutes?: number;
  /** ISO 8601 time of Core's last successful read from the broker. */
  as_of: string;
};

export type Position = {
  /** IBKR contract id; the key for /instruments/{conid}/bars. */
  conid: number;
  symbol: string;
  name?: string;
  /** IBKR asset class, e.g. "STK", "ETF", "FUT", "CASH". */
  asset_class: string;
  exchange?: string;
  /** Currency of the instrument's prices (last_price, avg_cost). */
  currency: string;
  quantity: Decimal;
  avg_cost: Decimal;
  last_price: Decimal;
  /** The *_value and *_pnl fields are in Portfolio.base_currency. */
  market_value: Decimal;
  day_pnl?: Decimal;
  day_change_pct?: Decimal;
  unrealized_pnl?: Decimal;
  /** Share of net liquidation, 0..100. */
  weight_pct?: Decimal;
};

/** One allocation bucket against the owner's target, both 0..100. */
export type AllocationTarget = { label: string; current_pct: Decimal; target_pct: Decimal };

export type MonthlyDeposit = { amount: Decimal; day_of_month: number; next_date: string };

export type Dividend = { conid: number; symbol: string; ex_date: string; pay_date?: string; amount?: Decimal; currency: string };

/** A position Core left out because it is not in the base currency. */
export type SkippedPosition = { symbol: string; currency?: string };

export type Portfolio = Freshness & {
  base_currency: string;
  net_liquidation: Decimal;
  cash: Decimal;
  day_pnl?: Decimal;
  day_change_pct?: Decimal;
  total_pnl?: Decimal;
  total_return_pct?: Decimal;
  dividends_12m?: Decimal;
  positions: Position[];
  /** Absent: the owner has not set targets. */
  allocation?: AllocationTarget[];
  monthly_deposit?: MonthlyDeposit;
  upcoming_dividends?: Dividend[];
  /** Both absent when Core showed every position; at most LIMITS.skipped of
   *  skipped_count are named. Older Cores never send them. */
  skipped_count?: number;
  skipped_positions?: SkippedPosition[];
};

export const HISTORY_RANGES = ["1M", "3M", "1Y", "ALL"] as const;
export type HistoryRange = (typeof HISTORY_RANGES)[number];

export type PortfolioHistory = Freshness & {
  range: HistoryRange;
  base_currency: string;
  /** Oldest first. */
  points: { t: string; value: Decimal }[];
};

export const BAR_RANGES = ["1D", "1W", "1M", "3M", "1Y", "5Y"] as const;
export type BarRange = (typeof BAR_RANGES)[number];

export type Bar = { t: string; o: Decimal; h: Decimal; l: Decimal; c: Decimal; v?: Decimal };

/** fact = from a source; calculation = computed by code; inference = an AI estimate. */
export type NoteKind = "fact" | "calculation" | "inference";
export type InstrumentNote = { t: string; kind: NoteKind; text: string; sources?: string[] };

export type InstrumentBars = Freshness & {
  conid: number;
  symbol: string;
  name?: string;
  exchange?: string;
  currency: string;
  range: BarRange;
  /** Oldest first. */
  bars: Bar[];
  notes?: InstrumentNote[];
};

export const NOTE_LABEL: Record<NoteKind, string> = { fact: "Fact", calculation: "Calculation", inference: "AI inference" };

// ---- decimal strings ----------------------------------------------------

const DECIMAL = /^(-)?(\d+)(?:\.(\d+))?$/;
const DASH = "—";

type Parsed = { neg: boolean; int: string; frac: string };

function parse(value: unknown): Parsed | null {
  if (typeof value !== "string") return null;
  const m = DECIMAL.exec(value.trim());
  return m ? { neg: !!m[1], int: m[2].replace(/^0+(?=\d)/, ""), frac: m[3] ?? "" } : null;
}

/** Round half away from zero to `places` fraction digits, on the digits. */
function round({ neg, int, frac }: Parsed, places: number): Parsed {
  if (frac.length <= places) return { neg, int, frac: frac.padEnd(places, "0") };
  const digits = (int + frac.slice(0, places)).split("").map(Number);
  if (Number(frac[places]) >= 5) {
    let i = digits.length - 1;
    while (i >= 0 && digits[i] === 9) digits[i--] = 0;
    if (i < 0) digits.unshift(1);
    else digits[i]++;
  }
  const all = digits.join("");
  const cut = all.length - places;
  return { neg, int: all.slice(0, cut) || "0", frac: all.slice(cut) };
}

const isZero = (p: Parsed) => /^0*$/.test(p.int + p.frac);
const group = (int: string) => int.replace(/\B(?=(\d{3})+(?!\d))/g, ",");

/** -1, 0 or 1 for a decimal string; null when it is not one. */
export function decimalSign(value: unknown): -1 | 0 | 1 | null {
  const p = parse(value);
  return p ? (isZero(p) ? 0 : p.neg ? -1 : 1) : null;
}

type DecimalFormat = {
  /** Fixed fraction digits (rounded). */
  places?: number;
  /** Keep up to this many digits, dropping trailing zeros (quantities). */
  maxPlaces?: number;
  /** "+" on positive values (changes, P&L). */
  sign?: boolean;
};

/** Format a decimal string with "," grouping. Not a decimal: "—". */
export function formatDecimal(value: unknown, { places, maxPlaces, sign = false }: DecimalFormat = {}): string {
  const raw = parse(value);
  if (!raw) return DASH;
  let p = round(raw, places ?? maxPlaces ?? raw.frac.length);
  if (places === undefined && maxPlaces !== undefined) p = { ...p, frac: p.frac.replace(/0+$/, "") };
  const body = group(p.int) + (p.frac ? `.${p.frac}` : "");
  if (isZero(p)) return body;
  return (p.neg ? "−" : sign ? "+" : "") + body;
}

/** Signed percentage: "+0.43%", "−99.90%". */
export function formatPercent(value: unknown, { sign = true, places = 2 } = {}): string {
  const text = formatDecimal(value, { places, sign });
  return text === DASH ? DASH : `${text}%`;
}

const CURRENCY = /^[A-Z]{3}$/;

type CurrencyStyle = { symbol: string; after: boolean; digits: number };
const currencyStyles = new Map<string, CurrencyStyle>();

/** Symbol, position and fraction digits per ISO code, cached (Intl is slow to build). */
function currencyStyle(code: string): CurrencyStyle {
  let style = currencyStyles.get(code);
  if (style) return style;
  // Not an ISO code: show it as text after the amount.
  style = { symbol: code, after: true, digits: 2 };
  if (CURRENCY.test(code)) {
    try {
      const nf = new Intl.NumberFormat("en", { style: "currency", currency: code, currencyDisplay: "narrowSymbol" });
      const parts = nf.formatToParts(1);
      style = {
        symbol: parts.find((part) => part.type === "currency")?.value ?? code,
        after: parts.findIndex((part) => part.type === "currency") > parts.findIndex((part) => part.type === "integer"),
        digits: nf.resolvedOptions().maximumFractionDigits ?? 2,
      };
    } catch {
      /* unknown code: keep it as text */
    }
  }
  if (currencyStyles.size < 200) currencyStyles.set(code, style);
  return style;
}

/** Money in its currency: "€23,820.00", "+$3.23", "−€63.10". Fraction digits
 *  follow the currency (JPY 0, EUR 2) unless `places` is given. */
export function formatMoney(value: unknown, currency: unknown, { sign = false, places }: { sign?: boolean; places?: number } = {}): string {
  const raw = parse(value);
  if (!raw) return DASH;
  const { symbol, after, digits } = currencyStyle(typeof currency === "string" ? currency.slice(0, 12) : "");
  const p = round(raw, places ?? digits);
  const body = group(p.int) + (p.frac ? `.${p.frac}` : "");
  const prefix = isZero(p) ? "" : p.neg ? "−" : sign ? "+" : "";
  const spaced = symbol.length > 1 && /^[A-Z]+$/.test(symbol);
  return after || spaced ? `${prefix}${body} ${symbol}`.trimEnd() : `${prefix}${symbol}${body}`;
}

/** A price: sub-cent prices keep their digits (up to 8), others follow the
 *  currency, so a $0.0004 token never shows as "$0.00". */
export function formatPrice(value: unknown, currency: unknown): string {
  const digits = typeof value === "string" ? (value.split(".")[1] ?? "").replace(/0+$/, "").length : 0;
  return formatMoney(value, currency, digits > 2 ? { places: Math.min(8, digits) } : {});
}

/** A decimal string as a number, for chart drawing and bar widths only;
 *  amounts on screen always go through formatMoney/formatDecimal. */
export function chartNumber(value: unknown): number | null {
  if (!parse(value)) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

/** CSS tone for a signed value. */
export function signTone(value: unknown): "pos" | "neg" | "flat" {
  const s = decimalSign(value);
  return s === 1 ? "pos" : s === -1 ? "neg" : "flat";
}

/** Simple moving average of the closes; null until `n` bars exist. */
export function movingAverage(closes: (number | null)[], n: number): (number | null)[] {
  let sum = 0;
  let valid = 0;
  return closes.map((c, i) => {
    if (c !== null) { sum += c; valid++; }
    const drop = closes[i - n];
    if (i >= n && drop !== null && drop !== undefined) { sum -= drop; valid--; }
    return i >= n - 1 && valid === n ? sum / n : null;
  });
}

/** A delay Core actually reported: a finite number of minutes, 0 or more. */
export const knownDelay = (value: unknown): value is number => typeof value === "number" && Number.isFinite(value) && value >= 0;

/** "IBKR · delayed 15 min", "IBKR · real time", or "IBKR · delay unknown"
 *  when Core did not say: never claim real time without data. */
export function sourceLabel(meta: Pick<Freshness, "source" | "delay_minutes">): string {
  const d = meta.delay_minutes;
  const delay = !knownDelay(d) ? "delay unknown" : d > 0 ? `delayed ${Math.round(d)} min` : "real time";
  return `${meta.source || "Unknown source"} · ${delay}`;
}

// ---- screen state -------------------------------------------------------

/** Core read is older than this: show it, but say it is stale. */
export const STALE_AFTER_MS = 10 * 60_000;

/** Why there is no trading data at all.
 *  no-api: Core has no trading read API yet (404/405/501).
 *  not-synced: Core has it, but IBKR is not configured, not synced yet or
 *  unreachable from Core (503).
 *  unreachable: no answer from the Home Node. */
export type NotConnected = "no-api" | "not-synced" | "unreachable";
/** not-held: the instrument is no longer in the portfolio (404 on bars). */
export type TradingError = "signin" | "failed" | "not-held";

export type TradingState<T> =
  | { kind: "loading" }
  | { kind: "not-connected"; reason: NotConnected }
  | { kind: "error"; reason: TradingError }
  | { kind: "empty"; data: T }
  | { kind: "data"; data: T }
  | { kind: "stale"; data: T; ageMs: number; empty: boolean };

type Failure = Extract<TradingState<never>, { kind: "not-connected" | "error" }>;

/** Classify a failed read (ApiError / NetworkError from api.ts, by shape).
 *  `instrument`: the read was /instruments/{conid}/bars, where 404 means the
 *  instrument is not held, not a missing API. */
export function failureState(error: unknown, instrument = false): Failure {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "NetworkError") return { kind: "not-connected", reason: "unreachable" };
  if (e?.name === "ApiError") {
    if (instrument && e.status === 404) return { kind: "error", reason: "not-held" };
    if (e.status === 404 || e.status === 405 || e.status === 501) return { kind: "not-connected", reason: "no-api" };
    if (e.status === 503) return { kind: "not-connected", reason: "not-synced" };
    if (e.status === 401) return { kind: "error", reason: "signin" };
  }
  return { kind: "error", reason: "failed" };
}

/** State for a successful read: stale when Core's read is old or its time is
 *  unreadable (checked first: an old "nothing" is not a fresh "nothing"),
 *  else empty when `isEmpty`, else data. */
export function dataState<T extends Freshness>(data: T, isEmpty: (data: T) => boolean, now: number): TradingState<T> {
  const at = Date.parse(data.as_of);
  const ageMs = Number.isNaN(at) ? Infinity : now - at;
  const empty = isEmpty(data);
  if (ageMs > STALE_AFTER_MS) return { kind: "stale", data, ageMs, empty };
  return empty ? { kind: "empty", data } : { kind: "data", data };
}

/** What the owner sees, and must do, when there is no trading data. */
export function notConnectedText(reason: NotConnected): { title: string; detail: string } {
  switch (reason) {
    case "no-api":
      return {
        title: "Not connected to IBKR yet",
        detail: "This Core has no trading data yet; it comes with the IBKR broker connection. Update Core on your Home Node and turn on the IBKR portfolio sync. Nothing is shown until then.",
      };
    case "not-synced":
      return {
        title: "IBKR not connected yet",
        detail: "Core has no IBKR data yet: the IBKR connection is not set up, the first sync has not finished, or Core cannot reach IBKR right now. Check the IBKR broker on your Home Node, then try again.",
      };
    case "unreachable":
      return { title: "Home Node unreachable", detail: "No answer from your Home Node. Check the connection; trading data comes from Core." };
  }
}

export function errorText(reason: TradingError): string {
  if (reason === "not-held") return "This instrument is no longer in your portfolio.";
  return reason === "signin" ? "Sign in again on the Core page." : "Core returned an error. Try again later.";
}

/** Tone and label of the IBKR link, read from GET /v1/trading/portfolio:
 *  a portfolio means connected; 503 means not connected (yet). */
export function ibkrLinkState(state: TradingState<unknown>): [Tone, string] {
  switch (state.kind) {
    case "loading": return ["idle", "Loading…"];
    case "data": case "empty": return ["ok", "Connected"];
    case "stale": return ["warn", "Connected, sync is old"];
    case "not-connected":
      return state.reason === "no-api" ? ["idle", "Requires newer Core"] : state.reason === "not-synced" ? ["warn", "Not connected"] : ["warn", "Unavailable"];
    case "error": return ["warn", "Unavailable"];
  }
}

/** "10:42" today, "5 Nov, 10:42" otherwise. */
export function asOfText(iso: string, now = Date.now()): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "an unknown time";
  const today = new Date(now).toDateString() === d.toDateString();
  return d.toLocaleString("en", today ? { hour: "2-digit", minute: "2-digit" } : { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

/** Percent change from the first to the last value as a decimal string
 *  ("4.80"), for signTone and formatPercent; null when unknown. */
export function changeRatio(first: unknown, last: unknown): string | null {
  const a = chartNumber(first);
  const b = chartNumber(last);
  if (!a || b === null) return null;
  const pct = ((b / a - 1) * 100).toFixed(2);
  return parse(pct) ? pct : null;
}

/** "+4.80%" change from the first to the last value; "—" when unknown. */
export function changePercent(first: unknown, last: unknown): string {
  return formatPercent(changeRatio(first, last));
}

/** "3 min", "2 h", "4 days" for a stale banner. */
export function ageText(ms: number): string {
  if (!Number.isFinite(ms)) return "an unknown time";
  const min = Math.max(1, Math.round(ms / 60_000));
  if (min < 60) return `${min} min`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} h`;
  return `${Math.round(h / 24)} days`;
}

/** Owner's chosen allocation buckets that are within 2 points of target. */
export function onTarget(allocation: AllocationTarget[]): number {
  return allocation.filter((a) => {
    const now = chartNumber(a.current_pct);
    const goal = chartNumber(a.target_pct);
    return now !== null && goal !== null && Math.abs(now - goal) < 2;
  }).length;
}

// ---- response guards ----------------------------------------------------
// Core's answer is checked before any screen sees it: an older or broken Core
// yields an error state, never a crash. Lists are capped; a bar or history
// point without finite prices is dropped rather than drawn as 0.

export const LIMITS = { positions: 1000, bars: 5000, points: 5000, notes: 50, sources: 20, allocation: 50, dividends: 200, skipped: 50 } as const;

type Obj = Record<string, unknown>;
const isObj = (x: unknown): x is Obj => typeof x === "object" && x !== null && !Array.isArray(x);
const isStr = (x: unknown): x is string => typeof x === "string";
const optStr = (x: unknown) => x === undefined || x === null || isStr(x);
const isList = (x: unknown, max: number): x is unknown[] => Array.isArray(x) && x.length <= max;
const optList = (x: unknown, max: number, each: (item: unknown) => boolean) =>
  x === undefined || x === null || (isList(x, max) && x.every(each));
const NOTE_KINDS: readonly unknown[] = ["fact", "calculation", "inference"];

/** A conid from a route or a response: a positive safe integer, else null. */
export function parseConid(value: unknown): number | null {
  const n = typeof value === "string" && /^\d{1,12}$/.test(value) ? Number(value) : value;
  return typeof n === "number" && Number.isSafeInteger(n) && n > 0 ? n : null;
}

/** source and as_of are required; delay_minutes is absent or a known delay. */
export const isFresh = (x: unknown): x is Freshness & Obj =>
  isObj(x) && isStr(x.source) && isStr(x.as_of) && (x.delay_minutes === undefined || knownDelay(x.delay_minutes));

const isPosition = (p: unknown) =>
  isObj(p) && parseConid(p.conid) !== null && isStr(p.symbol) && isStr(p.asset_class) && isStr(p.currency)
  && isStr(p.quantity) && isStr(p.avg_cost) && isStr(p.last_price) && isStr(p.market_value)
  && optStr(p.name) && optStr(p.exchange) && optStr(p.day_pnl) && optStr(p.day_change_pct) && optStr(p.unrealized_pnl) && optStr(p.weight_pct);
const isAllocation = (a: unknown) => isObj(a) && isStr(a.label) && isStr(a.current_pct) && isStr(a.target_pct);
const isDividend = (d: unknown) =>
  isObj(d) && parseConid(d.conid) !== null && isStr(d.symbol) && isStr(d.ex_date) && isStr(d.currency) && optStr(d.pay_date) && optStr(d.amount);
const isDeposit = (m: unknown) =>
  m === undefined || m === null || (isObj(m) && isStr(m.amount) && isStr(m.next_date) && Number.isInteger(m.day_of_month));
const isSkipped = (s: unknown) =>
  isObj(s) && isStr(s.symbol) && s.symbol.length >= 1 && s.symbol.length <= 32
  && (s.currency === undefined || (isStr(s.currency) && /^[A-Z]{3}$/.test(s.currency)));
/** Both absent, or a count of 1..positions limit naming 1..min(count, 50). */
const isSkippedSet = (count: unknown, list: unknown) =>
  (count === undefined && list === undefined)
  || (Number.isSafeInteger(count) && (count as number) >= 1 && (count as number) <= LIMITS.positions
    && isList(list, Math.min(count as number, LIMITS.skipped)) && list.length >= 1 && list.every(isSkipped));
const isNote = (n: unknown) =>
  isObj(n) && isStr(n.t) && NOTE_KINDS.includes(n.kind) && isStr(n.text) && optList(n.sources, LIMITS.sources, isStr);

export function readPortfolio(x: unknown): Portfolio | null {
  if (!isFresh(x) || !isStr(x.base_currency) || !isStr(x.net_liquidation) || !isStr(x.cash)) return null;
  if (![x.day_pnl, x.day_change_pct, x.total_pnl, x.total_return_pct, x.dividends_12m].every(optStr)) return null;
  if (!isList(x.positions, LIMITS.positions) || !x.positions.every(isPosition)) return null;
  if (!optList(x.allocation, LIMITS.allocation, isAllocation) || !optList(x.upcoming_dividends, LIMITS.dividends, isDividend)) return null;
  if (!isSkippedSet(x.skipped_count, x.skipped_positions)) return null;
  return isDeposit(x.monthly_deposit) ? (x as unknown as Portfolio) : null;
}

/** "2 positions not shown (not in EUR): AAPL (USD), X (no currency)", or
 *  null when Core showed every position. */
export function skippedText(p: Portfolio): string | null {
  const count = p.skipped_count ?? 0;
  if (count === 0) return null;
  const named = (p.skipped_positions ?? []).map((s) => `${s.symbol} (${s.currency ?? "no currency"})`);
  const more = count - named.length;
  const what = count === 1 ? "1 position" : `${count} positions`;
  return `${what} not shown (not in ${p.base_currency}): ${named.join(", ")}${more > 0 ? ` and ${more} more` : ""}`;
}

export function readHistory(x: unknown): PortfolioHistory | null {
  if (!isFresh(x) || !isStr(x.base_currency) || !isList(x.points, LIMITS.points)) return null;
  const points = x.points.filter((p): p is { t: string; value: string } => isObj(p) && isStr(p.t) && chartNumber(p.value) !== null);
  return { ...(x as unknown as PortfolioHistory), points };
}

export function readBars(x: unknown): InstrumentBars | null {
  if (!isFresh(x) || parseConid(x.conid) === null || !isStr(x.symbol) || !isStr(x.currency)) return null;
  if (!optStr(x.name) || !optStr(x.exchange) || !isList(x.bars, LIMITS.bars) || !optList(x.notes, LIMITS.notes, isNote)) return null;
  const bars = x.bars
    .filter((b): b is Bar => isObj(b) && isStr(b.t) && [b.o, b.h, b.l, b.c].every((v) => chartNumber(v) !== null))
    .map((b) => (b.v === undefined || chartNumber(b.v) !== null ? b : { ...b, v: undefined }));
  return { ...(x as unknown as InstrumentBars), bars };
}
