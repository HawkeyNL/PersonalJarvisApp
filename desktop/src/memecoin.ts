// Memecoin PAPER trader: the read contract, response guards, status selection
// and the pause call's outcome. Pure: no Vue or Tauri imports, so tests load
// this file directly. Formatters and decimal rules come from trading.ts.
//
// Core serves (owner session, like every /v1 route):
//   GET  /v1/trading/meme/overview          -> MemeOverview (raw shape below)
//   GET  /v1/trading/meme/candidates?limit= -> { candidates: Candidate[] }
//   GET  /v1/trading/meme/trades?limit=     -> { trades: ClosedTrade[] }
//   POST /v1/trading/meme/pause             -> { paused: true }
// It is a simulation: no wallet, no signer, no execution path. The pause only
// stops new paper entries; exits keep running. Resuming is configuration-only
// on the Home Node, so this app has no resume action.
import { STALE_AFTER_MS, chartNumber, decimalSign, formatMoney, type Decimal } from "./trading.ts";

export type OpenPosition = {
  mint: string;
  strategy_version: number;
  tokens: Decimal;
  cost_usd: Decimal;
  /** Executable value along the pool curve; null without a fresh mark. */
  value_usd: Decimal | null;
  opened_at: string;
  exit_failures: number;
  /** Exits stopped after repeated failures; the position keeps its slot. */
  stuck: boolean;
};

export type Gate = {
  closed_trades: number;
  min_closed_trades: number;
  duration_days: number;
  min_duration_days: number;
  trading_days: number;
  min_trading_days: number;
  net_result_usd: Decimal;
  net_expectancy_usd: Decimal;
  enough_trades: boolean;
  enough_time: boolean;
  enough_days: boolean;
  positive_expectancy: boolean;
  passed: boolean;
};

export type Goal = {
  multiple_target: Decimal;
  /** All null until there is progress to report. */
  multiple: Decimal | null;
  fraction_to_goal: Decimal | null;
  remaining_usd: Decimal | null;
  reached: boolean | null;
};

/** The trader's report; Core sends it only once a tick has produced one. */
export type MemeReport = {
  strategy_version: number;
  as_of: string;
  stake_usd: Decimal;
  equity_usd: Decimal;
  pnl: { realised_usd: Decimal; unrealised_usd: Decimal; failed_tx_fees_usd: Decimal; ai_cost_usd: Decimal; net_usd: Decimal };
  open_positions: OpenPosition[];
  gate: Gate;
  goal: Goal;
};

export type MemeOverview = {
  enabled: boolean;
  running: boolean;
  paused: boolean;
  paused_at: string | null;
  /** A fixed reason from Core, never error detail. */
  last_error: string | null;
  report: MemeReport | null;
};

export type Candidate = {
  mint: string;
  observed_at: string;
  strategy_version: number;
  verdict: "accept" | "reject";
  reason: string | null;
  price_usd: Decimal | null;
  liquidity_usd: Decimal | null;
};

export type ClosedTrade = {
  mint: string;
  strategy_version: number;
  tokens: Decimal;
  cost_usd: Decimal;
  opened_at: string;
  closed_at: string;
  usd_out: Decimal;
  exit_fees_usd: Decimal;
  net_pnl_usd: Decimal;
  exit_reason: string;
};

/** Rows asked for per list; Core allows 1..100. */
export const LIST_LIMIT = 50;
export const MEME_LIMITS = { positions: 100, list: 100, text: 200, mint: 64 } as const;

// ---- response guards ----------------------------------------------------
// Fail closed: anything that is not exactly Core's shape, or claims a mode
// other than "paper", is an error state, never a partly drawn screen.

type Obj = Record<string, unknown>;
const isObj = (x: unknown): x is Obj => typeof x === "object" && x !== null && !Array.isArray(x);
const isBool = (x: unknown): x is boolean => typeof x === "boolean";
const isText = (x: unknown, max: number = MEME_LIMITS.text): x is string => typeof x === "string" && x.length <= max;
const isTime = (x: unknown) => isText(x, 64) && !Number.isNaN(Date.parse(x));
const isCount = (x: unknown) => Number.isSafeInteger(x) && (x as number) >= 0;
const isDec = (x: unknown) => isText(x, 64) && decimalSign(x) !== null;
const isMint = (x: unknown) => isText(x, MEME_LIMITS.mint) && /^[1-9A-HJ-NP-Za-km-z]+$/.test(x);
const nullable = (x: unknown, check: (x: unknown) => boolean) => x === null || x === undefined || check(x);
const isList = (x: unknown, max: number, each: (item: unknown) => boolean): x is unknown[] =>
  Array.isArray(x) && x.length <= max && x.every(each);

const isPosition = (p: unknown) =>
  isObj(p) && isMint(p.mint) && isCount(p.strategy_version) && isDec(p.tokens) && isDec(p.cost_usd)
  && nullable(p.value_usd, isDec) && isTime(p.opened_at) && isCount(p.exit_failures) && isBool(p.stuck);

const isGate = (g: unknown) =>
  isObj(g)
  && ["closed_trades", "min_closed_trades", "duration_days", "min_duration_days", "trading_days", "min_trading_days"].every((k) => isCount(g[k]))
  && isDec(g.net_result_usd) && isDec(g.net_expectancy_usd)
  && ["enough_trades", "enough_time", "enough_days", "positive_expectancy", "passed"].every((k) => isBool(g[k]));

const isGoal = (g: unknown) =>
  isObj(g) && isDec(g.multiple_target) && nullable(g.multiple, isDec) && nullable(g.fraction_to_goal, isDec)
  && nullable(g.remaining_usd, isDec) && nullable(g.reached, isBool);

const isPnl = (p: unknown) =>
  isObj(p) && [p.realised_usd, p.unrealised_usd, p.failed_tx_fees_usd, p.ai_cost_usd, p.net_usd].every(isDec);

export function readOverview(x: unknown): MemeOverview | null {
  if (!isObj(x) || x.mode !== "paper") return null;
  if (!isBool(x.enabled) || !isBool(x.running) || !isBool(x.paused)) return null;
  if (!nullable(x.paused_at, isTime) || !nullable(x.last_error, isText)) return null;
  const status = {
    enabled: x.enabled, running: x.running, paused: x.paused,
    paused_at: (x.paused_at as string | undefined) ?? null, last_error: (x.last_error as string | undefined) ?? null,
  };
  // Core adds the report fields together, or none of them.
  if (x.as_of === undefined && x.strategy_version === undefined) return { ...status, report: null };
  if (!isCount(x.strategy_version) || !isTime(x.as_of) || !isDec(x.stake_usd) || !isDec(x.equity_usd)) return null;
  if (!isPnl(x.pnl) || !isList(x.open_positions, MEME_LIMITS.positions, isPosition) || !isGate(x.gate) || !isGoal(x.goal)) return null;
  const goal = x.goal as Obj;
  return {
    ...status,
    report: {
      strategy_version: x.strategy_version as number, as_of: x.as_of as string,
      stake_usd: x.stake_usd as string, equity_usd: x.equity_usd as string,
      pnl: x.pnl as MemeReport["pnl"],
      open_positions: (x.open_positions as OpenPosition[]).map((p) => ({ ...p, value_usd: p.value_usd ?? null })),
      gate: x.gate as Gate,
      goal: {
        multiple_target: goal.multiple_target as string,
        multiple: (goal.multiple as string | undefined) ?? null,
        fraction_to_goal: (goal.fraction_to_goal as string | undefined) ?? null,
        remaining_usd: (goal.remaining_usd as string | undefined) ?? null,
        reached: (goal.reached as boolean | undefined) ?? null,
      },
    },
  };
}

const isCandidate = (c: unknown) =>
  isObj(c) && isMint(c.mint) && isTime(c.observed_at) && isCount(c.strategy_version)
  && (c.verdict === "accept" ? nullable(c.reason, () => false) : c.verdict === "reject" && isText(c.reason, 64))
  && nullable(c.price_usd, isDec) && nullable(c.liquidity_usd, isDec);

export function readCandidates(x: unknown): Candidate[] | null {
  if (!isObj(x) || !isList(x.candidates, MEME_LIMITS.list, isCandidate)) return null;
  return (x.candidates as Candidate[]).map((c) => ({ ...c, reason: c.reason ?? null, price_usd: c.price_usd ?? null, liquidity_usd: c.liquidity_usd ?? null }));
}

const isTrade = (t: unknown) =>
  isObj(t) && isMint(t.mint) && isCount(t.strategy_version) && isTime(t.opened_at) && isTime(t.closed_at)
  && [t.tokens, t.cost_usd, t.usd_out, t.exit_fees_usd, t.net_pnl_usd].every(isDec) && isText(t.exit_reason, 64);

export function readTrades(x: unknown): ClosedTrade[] | null {
  if (!isObj(x) || !isList(x.trades, MEME_LIMITS.list, isTrade)) return null;
  return x.trades as ClosedTrade[];
}

// ---- status -------------------------------------------------------------

export type MemeStatusKind = "disabled" | "stopped" | "paused" | "halted" | "starting" | "running";
export type MemeStatus = { kind: MemeStatusKind; label: string; tone: "neutral" | "acc" | "neg"; detail: string };

/** One honest headline for the trader, most restrictive first: off, not
 *  running, paused, last tick stopped, no report yet, running. */
export function memeStatus(o: MemeOverview): MemeStatus {
  if (!o.enabled) {
    return {
      kind: "disabled", label: "Off", tone: "neutral",
      detail: "Paper trading is off, which is the default. Only the owner can turn it on, in Core's configuration on the Home Node; this app cannot.",
    };
  }
  if (!o.running) {
    return { kind: "stopped", label: "Not running", tone: "neg", detail: o.last_error ? `${o.last_error}.` : "Paper trading is turned on, but the trader is not running. The Core log says why." };
  }
  if (o.paused) {
    return {
      kind: "paused", label: "Paused", tone: "neg",
      // A failing tick also stops exits, so do not claim they run.
      detail: o.last_error
        ? `No new paper entries. The last tick also stopped: ${o.last_error}. Open paper positions may not be valued or exited until it recovers; the Core log has the details.`
        : "No new paper entries. Open paper positions are still valued and exited.",
    };
  }
  if (o.last_error) {
    return { kind: "halted", label: "Tick stopped", tone: "neg", detail: `The last tick stopped: ${o.last_error}. Core tries again every minute; the Core log has the details.` };
  }
  if (!o.report) return { kind: "starting", label: "Starting", tone: "neutral", detail: "The trader is running but has not reported yet. A tick runs every minute." };
  return { kind: "running", label: "Running · paper", tone: "acc", detail: "Simulating on read-only market data. Exits and entries run every minute." };
}

/** How old the report is when that is past the stale limit; else null. */
export function reportAge(o: MemeOverview, now: number): number | null {
  if (!o.report) return null;
  const at = Date.parse(o.report.as_of);
  const age = Number.isNaN(at) ? Infinity : now - at;
  return age > STALE_AFTER_MS ? age : null;
}

export const stuckCount = (o: MemeOverview) => o.report?.open_positions.filter((p) => p.stuck).length ?? 0;

export const RESUME_TEXT =
  "Resuming is configuration-only, on the Home Node: copy the paused-at time into JARVIS_MEMECOIN_PAPER_RESUME_PAUSED_AT in core.env and restart Core. This app cannot resume.";

// ---- read failures ------------------------------------------------------

export type MemeFailure = "no-api" | "unreachable" | "signin" | "unavailable" | "failed";

/** Classify a failed read (ApiError / NetworkError from api.ts, by shape). */
export function memeFailure(error: unknown): MemeFailure {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "NetworkError") return "unreachable";
  if (e?.name === "ApiError") {
    if (e.status === 404 || e.status === 405 || e.status === 501) return "no-api";
    if (e.status === 401) return "signin";
    if (e.status === 503) return "unavailable";
  }
  return "failed";
}

export function failureText(f: MemeFailure): string {
  switch (f) {
    case "no-api": return "This Core has no memecoin paper trader yet. Update Core on your Home Node.";
    case "unreachable": return "No answer from your Home Node. Check the connection.";
    case "signin": return "Your session is not valid. Sign in again on the Core page.";
    case "unavailable": return "Core could not read the paper trader's data just now. Try again in a minute.";
    case "failed": return "Core returned an answer this app does not understand, or an error. Try again later.";
  }
}

// ---- pause --------------------------------------------------------------

export type PauseOutcome = { ok: true; at: string } | { ok: false; at: string; message: string; status?: number };

const clock = (d: Date) => `${d.toISOString().slice(11, 19)} UTC`;

/** Send the pause and word the outcome for the owner. Every message says
 *  when, what Core answered and whether the pause is in effect, so it can be
 *  matched with Core's audit log (event memecoin.paper.pause). */
export async function sendPause(post: () => Promise<unknown>, now: () => Date = () => new Date()): Promise<PauseOutcome> {
  let answer: unknown;
  try {
    answer = await post();
  } catch (error) {
    const at = clock(now());
    const e = error as { name?: string; status?: number } | null;
    if (e?.name === "NetworkError") {
      return { ok: false, at, message: `No answer from the Home Node at ${at}. It is unknown whether the pause arrived; reload the status before trying again.` };
    }
    if (e?.name === "ApiError" && typeof e.status === "number") {
      if (e.status === 401) return { ok: false, at, message: `Pause refused at ${at} (HTTP 401): your session is not valid, so nothing changed. Sign in again on the Core page, then pause again.` };
      if (e.status === 503) {
        return { ok: false, at, status: 503, message: `Pause not saved at ${at} (HTTP 503): Core stopped new entries, but could not store the pause, so a restart would undo it. Pause again until it is confirmed.` };
      }
      if (e.status === 404 || e.status === 405 || e.status === 501) return { ok: false, at, message: `Pause not available at ${at} (HTTP ${e.status}): this Core has no memecoin pause, so nothing changed.` };
      return { ok: false, at, message: `Pause failed at ${at} (HTTP ${e.status}). The pause is not confirmed; try again.` };
    }
    return { ok: false, at, message: `Pause could not be sent at ${at}. The pause is not confirmed; try again.` };
  }
  const at = clock(now());
  if (isObj(answer) && answer.paused === true) return { ok: true, at };
  return { ok: false, at, message: `Core answered at ${at}, but did not confirm the pause. Reload the status to check it.` };
}

// ---- display --------------------------------------------------------------

/** "7xKX…gAsU": a mint is long and unreadable; the full one goes in a title. */
export const shortMint = (mint: string) => (mint.length > 12 ? `${mint.slice(0, 4)}…${mint.slice(-4)}` : mint);

/** Dollars; amounts below a cent keep their digits so a fee never reads $0.00. */
export function usd(value: unknown, { sign = false } = {}): string {
  const n = chartNumber(value);
  const frac = typeof value === "string" ? (value.split(".")[1] ?? "").replace(/0+$/, "").length : 0;
  const tiny = n !== null && n !== 0 && Math.abs(n) < 0.01;
  return formatMoney(value, "USD", { sign, places: tiny ? Math.min(8, Math.max(2, frac)) : 2 });
}

/** Share of a minimum reached, 0..100, for a meter. */
export const progressPct = (have: number, need: number) => (need <= 0 ? 100 : Math.min(100, Math.max(0, (have / need) * 100)));

/** fraction_to_goal (0..1 as a decimal string) as 0..100; null when unknown. */
export function goalPct(fraction: unknown): number | null {
  const n = chartNumber(fraction);
  return n === null ? null : Math.min(100, Math.max(0, n * 100));
}

export const REJECT_REASONS: Record<string, string> = {
  data_unavailable: "Market data unavailable",
  stale_snapshot: "Market data too old",
  invalid_snapshot: "Market data inconsistent",
  denied: "On the deny list",
  mint_authority_active: "Mint authority still active",
  freeze_authority_active: "Freeze authority still active",
  unknown_extension: "Unknown token extension",
  extension_not_allowed: "Token extension not allowed",
  extension_authority_active: "Extension authority active",
  lp_not_secured: "Liquidity not locked or burned",
  lp_unlocks_too_soon: "Liquidity unlocks too soon",
  liquidity_too_low: "Liquidity too low",
  pool_too_young: "Pool too young",
  holder_concentration_too_high: "Top holders own too much",
  no_sell_route: "No route to sell",
  sell_impact_too_high: "Selling moves the price too much",
};

export const EXIT_REASONS: Record<string, string> = {
  liquidity_drop: "Liquidity dropped",
  stop_loss: "Stop loss",
  take_profit: "Take profit",
  time_stop: "Time stop",
};

/** A known code's label; an unknown one is shown as its code, marked as such. */
export const reasonLabel = (labels: Record<string, string>, code: string | null) =>
  code === null ? "—" : Object.prototype.hasOwnProperty.call(labels, code) ? labels[code] : `Other (${code})`;
