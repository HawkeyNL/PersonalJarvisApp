// Trading read API contract, the decimal-string formatters and the screen-state
// selection for the trading screens. Pure: no Vue or Tauri imports, so tests
// load this file directly.
//
// Core serves these routes once the IBKR broker lands:
//   GET /v1/trading/portfolio                           -> Portfolio
//   GET /v1/trading/portfolio/history?range=<HistoryRange> -> PortfolioHistory
//   GET /v1/trading/instruments/{conid}/bars?range=<BarRange> -> InstrumentBars
// Money, prices and quantities are decimal strings ("-1234.5600"), never JSON
// numbers: a JS float cannot hold every amount exactly. They become numbers
// only to draw a chart (chartNumber).

/** Exact decimal as a string: optional "-", digits, optional "." + digits. */
export type Decimal = string;

/** Where the numbers come from and how old they are. On every response. */
export type Freshness = {
  /** Display name of the data source, e.g. "IBKR". */
  source: string;
  /** Price delay of the market-data subscription; 0 is real time. */
  delay_minutes: number;
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

/** Money in its currency: "€23,820.00", "+$3.23", "−€63.10". Fraction digits
 *  follow the currency (JPY 0, EUR 2) unless `places` is given. */
export function formatMoney(value: unknown, currency: string, { sign = false, places }: { sign?: boolean; places?: number } = {}): string {
  const raw = parse(value);
  if (!raw) return DASH;
  let symbol = currency;
  let digits = 2;
  let after = !CURRENCY.test(currency); // not an ISO code: show it as text after the amount
  if (CURRENCY.test(currency)) {
    try {
      const nf = new Intl.NumberFormat("en", { style: "currency", currency, currencyDisplay: "narrowSymbol" });
      const parts = nf.formatToParts(1);
      symbol = parts.find((part) => part.type === "currency")?.value ?? currency;
      after = parts.findIndex((part) => part.type === "currency") > parts.findIndex((part) => part.type === "integer");
      digits = nf.resolvedOptions().maximumFractionDigits ?? 2;
    } catch {
      /* unknown code: show it as text */
    }
  }
  const p = round(raw, places ?? digits);
  const body = group(p.int) + (p.frac ? `.${p.frac}` : "");
  const prefix = isZero(p) ? "" : p.neg ? "−" : sign ? "+" : "";
  const spaced = symbol.length > 1 && /^[A-Z]+$/.test(symbol);
  return after || spaced ? `${prefix}${body} ${symbol}`.trimEnd() : `${prefix}${symbol}${body}`;
}

/** A price: sub-cent prices keep their digits (up to 8), others follow the
 *  currency, so a $0.0004 token never shows as "$0.00". */
export function formatPrice(value: unknown, currency: string): string {
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

/** "IBKR · delayed 15 min" or "IBKR · real time". */
export function sourceLabel(meta: Pick<Freshness, "source" | "delay_minutes">): string {
  const delay = meta.delay_minutes > 0 ? `delayed ${meta.delay_minutes} min` : "real time";
  return `${meta.source || "Unknown source"} · ${delay}`;
}

// ---- screen state -------------------------------------------------------

/** Core read is older than this: show it, but say it is stale. */
export const STALE_AFTER_MS = 10 * 60_000;

/** Why there is no trading data at all.
 *  no-api: Core has no trading read API yet (404/405/501).
 *  gateway: Core has it but cannot reach the IBKR gateway (503).
 *  unreachable: no answer from the Home Node. */
export type NotConnected = "no-api" | "gateway" | "unreachable";

export type TradingState<T> =
  | { kind: "loading" }
  | { kind: "not-connected"; reason: NotConnected }
  | { kind: "error"; reason: "signin" | "failed" }
  | { kind: "empty"; data: T }
  | { kind: "data"; data: T }
  | { kind: "stale"; data: T; ageMs: number };

type Failure = Extract<TradingState<never>, { kind: "not-connected" | "error" }>;

/** Classify a failed read (ApiError / NetworkError from api.ts, by shape). */
export function failureState(error: unknown): Failure {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "NetworkError") return { kind: "not-connected", reason: "unreachable" };
  if (e?.name === "ApiError") {
    if (e.status === 404 || e.status === 405 || e.status === 501) return { kind: "not-connected", reason: "no-api" };
    if (e.status === 503) return { kind: "not-connected", reason: "gateway" };
    if (e.status === 401) return { kind: "error", reason: "signin" };
  }
  return { kind: "error", reason: "failed" };
}

/** State for a successful read: empty when `isEmpty`, stale when Core's read
 *  is old or its time is unreadable. */
export function dataState<T extends Freshness>(data: T, isEmpty: (data: T) => boolean, now: number): TradingState<T> {
  if (isEmpty(data)) return { kind: "empty", data };
  const at = Date.parse(data.as_of);
  const ageMs = Number.isNaN(at) ? Infinity : now - at;
  return ageMs > STALE_AFTER_MS ? { kind: "stale", data, ageMs } : { kind: "data", data };
}

/** What the owner sees, and must do, when there is no trading data. */
export function notConnectedText(reason: NotConnected): { title: string; detail: string } {
  switch (reason) {
    case "no-api":
      return {
        title: "Not connected to IBKR yet",
        detail: "This Core has no trading data yet; it comes with the IBKR broker connection. Update Core, then start the IBKR Client Portal Gateway on your Home Node and log in. Nothing is shown until then.",
      };
    case "gateway":
      return {
        title: "IBKR gateway not connected",
        detail: "Core cannot reach the IBKR Client Portal Gateway. Start it on your Home Node and log in with your IBKR account.",
      };
    case "unreachable":
      return { title: "Home Node unreachable", detail: "No answer from your Home Node. Check the connection; trading data comes from Core." };
  }
}

export function errorText(reason: "signin" | "failed"): string {
  return reason === "signin" ? "Sign in again on the Core page." : "Core returned an error. Try again later.";
}

/** "10:42" today, "5 Nov, 10:42" otherwise. */
export function asOfText(iso: string, now = Date.now()): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "an unknown time";
  const today = new Date(now).toDateString() === d.toDateString();
  return d.toLocaleString("en", today ? { hour: "2-digit", minute: "2-digit" } : { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

/** "+4.80%" change from the first to the last value; "—" when unknown. */
export function changePercent(first: unknown, last: unknown): string {
  const a = chartNumber(first);
  const b = chartNumber(last);
  return a && b !== null ? formatPercent(((b / a - 1) * 100).toFixed(2)) : DASH;
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
