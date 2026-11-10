// Memecoin PAPER strategies and Claude's decisions: the read contract, the
// response guards and the disable call's outcome. Pure: no Vue or Tauri
// imports, so tests load this file directly.
//
// Core serves (owner session):
//   GET  /v1/trading/meme/strategies                 -> { strategies: Strategy[] }
//   GET  /v1/trading/meme/strategies/{id}            -> StrategyDetail (raw shape below)
//   POST /v1/trading/meme/strategies/{id}/disable    -> { strategy_id, enabled: false }
//   GET  /v1/trading/meme/decisions?limit=           -> { decisions: Decision[] }
// Enabling needs a device signature and runs natively (meme_enable_strategy);
// the webview proxy refuses the enable routes.
import { REJECT_REASONS, isBool, isCount, isDec, isList, isMint, isObj, isText, isTime, nullable, type Obj } from "./memecoin.ts";
import type { Decimal } from "./trading.ts";

export type Family = "a" | "b" | "c";

export type Performance = {
  trades: number;
  net_usd: Decimal;
  costs_usd: Decimal;
  expectancy_usd: Decimal;
  win_rate_bps: number;
  max_drawdown_usd: Decimal;
  profit_factor: Decimal | null;
};

export type Strategy = {
  id: string;
  family: Family | null;
  active_version: number;
  enabled: boolean;
  /** Only a device signature enables it again. */
  disabled_by_owner: boolean;
  changed_at: string;
  changed_by: string;
  performance: Performance;
};

/** JSON values as compact text; "—" when the field is absent. */
export type DiffRow = { field: string; from: string; to: string };

export type Backtest =
  | { status: "unbacktested" | "failed"; mints: number; points: number }
  | {
    status: "ok"; mints: number; points: number; trades: number; net_usd: Decimal; expectancy_usd: Decimal;
    win_rate_bps: number; max_drawdown_usd: Decimal; profit_factor: Decimal | null;
  };

export type StrategyVersion = {
  version: number;
  parent_version: number | null;
  author: "seed" | "claude";
  created_at: string;
  /** Parameter fields as compact JSON text, without the identity fields. */
  config: [string, string][] | null;
  /** Core's per-field diff to the parent; null without a parent. */
  diff: DiffRow[] | null;
  /** Written by Claude; plain text, display only. */
  rationale: string | null;
  backtest: Backtest | null;
};

export type StrategyGate = {
  at: string;
  closed_trades: number;
  min_closed_trades: number;
  duration_days: number;
  min_duration_days: number;
  trading_days: number;
  min_trading_days: number;
  net_result_usd: Decimal;
  net_expectancy_usd: Decimal;
};

export type StrategyDetail = Strategy & {
  active_config_sha256: string | null;
  versions: StrategyVersion[];
  gate: StrategyGate | null;
};

export type Decision = {
  key: string;
  at: string;
  call_id: string;
  kind: string;
  strategy_version: number | null;
  mint: string | null;
  size_bps: number | null;
  reason_code: string | null;
  /** Written by Claude; plain text, display only. */
  rationale: string | null;
  model: string | null;
};

/** Core lists at most 6 strategies and 20 versions each. */
export const STRATEGY_LIMITS = { strategies: 20, versions: 20, diff: 64, config: 8192, value: 2048, rationale: 600, decision: 300 } as const;

// ---- guards -----------------------------------------------------------------
// Fail closed: anything that is not Core's shape is an error state.

/** Core's strategy id rule; also used for the route and the native call. */
export const isStrategyId = (x: unknown): x is string => typeof x === "string" && /^[a-z0-9-]{1,32}$/.test(x);
const isCode = (x: unknown, max = 32) => typeof x === "string" && x.length <= max && /^[a-z][a-z0-9_]*$/.test(x);
const isVersion = (x: unknown) => isCount(x) && (x as number) >= 1 && (x as number) <= 0xffffffff;
/** Display text from Claude: bounded. Shown through `plain`, which drops
 *  control and format characters (bidi overrides, zero-width, tag
 *  characters) so it reads as what it holds. Rendered as text, never as
 *  HTML. A character Core lets through never fails the whole record, which
 *  would also hide the Disable button. */
const isPlain = (x: unknown, max: number) => typeof x === "string" && [...x].length <= max;
const plain = (x: unknown) =>
  (typeof x === "string" ? x.replace(/[\p{Cc}\u2028\u2029]/gu, " ").replace(/\p{Cf}/gu, "") : "") || null;
const isJson = (x: unknown, max: number) => {
  try {
    return (JSON.stringify(x) ?? "").length <= max;
  } catch {
    return false;
  }
};
const json = (x: unknown) => (x === undefined ? "—" : JSON.stringify(x));

const isPerformance = (p: unknown) =>
  isObj(p) && isCount(p.trades) && isCount(p.win_rate_bps) && (p.win_rate_bps as number) <= 10_000
  && [p.net_usd, p.costs_usd, p.expectancy_usd, p.max_drawdown_usd].every(isDec) && nullable(p.profit_factor, isDec);

const isStrategy = (s: unknown) =>
  isObj(s) && isStrategyId(s.id) && nullable(s.family, (f) => f === "a" || f === "b" || f === "c")
  && isVersion(s.active_version) && isBool(s.enabled) && isBool(s.disabled_by_owner)
  && isTime(s.changed_at) && isText(s.changed_by, 64) && isPerformance(s.performance);

function strategy(s: Obj): Strategy {
  const p = s.performance as Obj;
  return {
    id: s.id as string, family: (s.family as Family | undefined) ?? null, active_version: s.active_version as number,
    enabled: s.enabled as boolean, disabled_by_owner: s.disabled_by_owner as boolean,
    changed_at: s.changed_at as string, changed_by: s.changed_by as string,
    performance: { ...(p as Performance), profit_factor: (p.profit_factor as string | undefined) ?? null },
  };
}

export function readStrategies(x: unknown): Strategy[] | null {
  if (!isObj(x) || !isList(x.strategies, STRATEGY_LIMITS.strategies, isStrategy)) return null;
  return (x.strategies as Obj[]).map(strategy);
}

const isBacktest = (b: unknown) =>
  isObj(b) && isCount(b.mints) && isCount(b.points)
  && (b.status === "unbacktested" || b.status === "failed"
    || (b.status === "ok" && isCount(b.trades) && isCount(b.win_rate_bps)
      && [b.net_usd, b.expectancy_usd, b.max_drawdown_usd].every(isDec) && nullable(b.profit_factor, isDec)));

const isDiffRow = (d: unknown) =>
  isObj(d) && isText(d.field, 64) && isJson(d.from, STRATEGY_LIMITS.value) && isJson(d.to, STRATEGY_LIMITS.value);

const isStrategyVersion = (v: unknown) =>
  isObj(v) && isVersion(v.version) && nullable(v.parent_version, isVersion)
  && (v.author === "seed" || v.author === "claude") && isTime(v.created_at)
  && nullable(v.config, (c) => isObj(c) && isJson(c, STRATEGY_LIMITS.config))
  && nullable(v.diff, (d) => isList(d, STRATEGY_LIMITS.diff, isDiffRow))
  && nullable(v.rationale, (r) => isPlain(r, STRATEGY_LIMITS.rationale))
  && nullable(v.backtest, isBacktest);

const isGate = (g: unknown) =>
  isObj(g) && isTime(g.at)
  && ["closed_trades", "min_closed_trades", "duration_days", "min_duration_days", "trading_days", "min_trading_days"].every((k) => isCount(g[k]))
  && isDec(g.net_result_usd) && isDec(g.net_expectancy_usd);

/** Identity fields are shown in the version header, not as parameters. */
const IDENTITY = new Set(["id", "version", "parent", "family"]);

function version(v: Obj): StrategyVersion {
  const config = isObj(v.config) ? Object.entries(v.config).filter(([k]) => !IDENTITY.has(k)).map(([k, value]): [string, string] => [k, json(value)]) : null;
  return {
    version: v.version as number, parent_version: (v.parent_version as number | undefined) ?? null,
    author: v.author as "seed" | "claude", created_at: v.created_at as string, config,
    diff: Array.isArray(v.diff) ? (v.diff as Obj[]).map((d) => ({ field: d.field as string, from: json(d.from), to: json(d.to) })) : null,
    rationale: plain(v.rationale),
    backtest: isObj(v.backtest) ? ({ ...v.backtest, profit_factor: v.backtest.profit_factor ?? null } as Backtest) : null,
  };
}

/** One strategy; null also when Core answers for another id. */
export function readStrategy(x: unknown, id: string): StrategyDetail | null {
  if (!isStrategy(x) || (x as Obj).id !== id) return null;
  const o = x as Obj;
  if (!nullable(o.active_config_sha256, (h) => typeof h === "string" && /^[0-9a-f]{64}$/.test(h))) return null;
  if (!isList(o.versions, STRATEGY_LIMITS.versions, isStrategyVersion) || !nullable(o.gate, isGate)) return null;
  return {
    ...strategy(o),
    active_config_sha256: (o.active_config_sha256 as string | undefined) ?? null,
    versions: (o.versions as Obj[]).map(version),
    gate: (o.gate as StrategyGate | undefined) ?? null,
  };
}

const isDecision = (d: unknown) =>
  isObj(d) && nullable(d.id, (i) => isText(i, 128) || isCount(i)) && isTime(d.at)
  && typeof d.call_id === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(d.call_id) && isCode(d.kind)
  && nullable(d.strategy_version, isVersion) && nullable(d.mint, isMint)
  && nullable(d.size_bps, (n) => isCount(n) && (n as number) >= 1 && (n as number) <= 10_000)
  && nullable(d.reason_code, isCode) && nullable(d.rationale, (r) => isPlain(r, STRATEGY_LIMITS.decision))
  && nullable(d.model, (m) => typeof m === "string" && /^[A-Za-z0-9_.:-]{1,64}$/.test(m))
  && nullable(d.input_tokens, isCount) && nullable(d.output_tokens, isCount);

export function readDecisions(x: unknown): Decision[] | null {
  if (!isObj(x) || !isList(x.decisions, 100, isDecision)) return null;
  return (x.decisions as Obj[]).map((d, i) => ({
    key: `${d.id ?? ""}-${d.at}-${i}`, at: d.at as string, call_id: d.call_id as string, kind: d.kind as string,
    strategy_version: (d.strategy_version as number | undefined) ?? null, mint: (d.mint as string | undefined) ?? null,
    size_bps: (d.size_bps as number | undefined) ?? null, reason_code: (d.reason_code as string | undefined) ?? null,
    rationale: plain(d.rationale), model: (d.model as string | undefined) ?? null,
  }));
}

// ---- display ------------------------------------------------------------------

export const FAMILY_LABEL: Record<Family, string> = {
  a: "A · survivor momentum",
  b: "B · base reclaim",
  c: "C · established trend",
};

/** Strategy state, most restrictive first. */
export function strategyState(s: Strategy): { label: string; tone: "neutral" | "acc" | "neg" } {
  if (s.enabled) return { label: "Enabled", tone: "acc" };
  return s.disabled_by_owner ? { label: "Disabled by you", tone: "neg" } : { label: "Disabled", tone: "neutral" };
}

/** Basis points as a percentage: 4250 -> "42.5%". */
export const bpsPct = (bps: number) => `${Number((bps / 100).toFixed(2))}%`;

const KINDS: Record<string, string> = {
  enter: "Enter", exit: "Exit", hold: "Hold", skip: "Skipped", propose: "Proposal",
  worker_unavailable: "Worker busy", invalid_output: "Invalid reply",
};

const REASONS: Record<string, string> = {
  ...REJECT_REASONS,
  accepted: "Accepted",
  strategy_limit: "Strategy limit reached",
  strategy_disabled: "Strategy disabled",
  strategy_changed: "Strategy changed",
  no_slot: "No free slot",
  no_price: "No price",
  apply_deadline: "Too late to apply",
  book_unavailable: "Paper ledger unavailable",
  plan_limit: "Plan limit",
  malformed_reply: "Malformed reply",
  out_of_bounds: "Outside the bounds",
  loosening: "Would loosen the filters",
  parent_mismatch: "Wrong parent version",
  family_changed: "Family changed",
  unchanged: "No change",
  version_cap: "Daily version limit",
  strategy_cap: "Strategy limit",
};

/** A decision row for display. `skip` / `call_started` only marks that a call
 *  began (it counts toward the budget), so it reads as "Claude called" and is
 *  de-emphasised. Unknown kinds and codes stay visible as their code. */
export function decisionView(d: Decision): { kind: string; reason: string; muted: boolean } {
  if (d.kind === "skip" && d.reason_code === "call_started") return { kind: "Claude called", reason: "", muted: true };
  const has = (map: Record<string, string>, key: string) => Object.prototype.hasOwnProperty.call(map, key);
  return {
    kind: has(KINDS, d.kind) ? KINDS[d.kind] : `Other (${d.kind})`,
    reason: d.reason_code === null ? "" : has(REASONS, d.reason_code) ? REASONS[d.reason_code] : d.reason_code,
    muted: false,
  };
}

/** Which strategy a version belongs to, when it is a strategy's active one;
 *  versions are unique across strategies. */
export function versionOwner(strategies: Strategy[], version: number | null): string | null {
  return version === null ? null : strategies.find((s) => s.active_version === version)?.id ?? null;
}

// ---- realtime -----------------------------------------------------------------

/** What a `trading.meme.*` realtime event asks a page to reload. Only the
 *  type and the ids are read; the reload is the authority, never the event. */
export type MemeHint = { kind: "strategy"; id: string } | { kind: "decision" } | { kind: "reconcile" };

/** `connection.ready` asks for a full reload: hints sent while the socket
 *  was down are not replayed. */
export function memeHint(event: unknown): MemeHint | null {
  if (!isObj(event) || !isObj(event.payload)) return null;
  if (event.type === "connection.ready") return { kind: "reconcile" };
  const p = event.payload;
  if (event.type === "trading.meme.strategy_changed") {
    return isStrategyId(p.strategy_id) && isVersion(p.version) ? { kind: "strategy", id: p.strategy_id } : null;
  }
  if (event.type === "trading.meme.decision") return isText(p.id, 128) ? { kind: "decision" } : null;
  return null;
}

// ---- enable -------------------------------------------------------------------

/** What the owner reviews and Touch ID signs: the strategy, its active
 *  version and the SHA-256 of that version's parameters. Native code refuses
 *  a challenge for anything else. Null when Core sent no hash. */
export type EnableReview = { id: string; version: number; config_sha256: string };

export function enableReview(s: StrategyDetail): EnableReview | null {
  return s.active_config_sha256 === null ? null : { id: s.id, version: s.active_version, config_sha256: s.active_config_sha256 };
}

// ---- disable ------------------------------------------------------------------

export type DisableOutcome = { ok: true; at: string } | { ok: false; at: string; message: string; status?: number };

const clock = (d: Date) => `${d.toISOString().slice(11, 19)} UTC`;

/** Send the disable and word the outcome; every message says when and whether
 *  it holds (audit event memecoin.strategy.disable). */
export async function sendDisable(id: string, post: () => Promise<unknown>, now: () => Date = () => new Date()): Promise<DisableOutcome> {
  let answer: unknown;
  try {
    answer = await post();
  } catch (error) {
    const at = clock(now());
    const e = error as { name?: string; status?: number } | null;
    if (e?.name === "NetworkError") {
      return { ok: false, at, message: `No answer from the Home Node at ${at}. It is unknown whether the disable arrived; reload before trying again.` };
    }
    if (e?.name === "ApiError" && typeof e.status === "number") {
      if (e.status === 401) return { ok: false, at, message: `Disable refused at ${at} (HTTP 401): your session is not valid, so nothing changed. Sign in again on the Core page.` };
      if (e.status === 503) {
        return { ok: false, at, status: 503, message: `Disable not saved at ${at} (HTTP 503): Core stopped this strategy's entries, but a restart would undo it. Disable again until it is confirmed.` };
      }
      if (e.status === 404) return { ok: false, at, message: `Core has no strategy ${id} (HTTP 404), so nothing changed.` };
      if (e.status === 429) return { ok: false, at, message: `Too many strategy changes at ${at} (HTTP 429). Wait a minute, then try again.` };
      return { ok: false, at, message: `Disable failed at ${at} (HTTP ${e.status}). It is not confirmed; try again.` };
    }
    return { ok: false, at, message: `Disable could not be sent at ${at}. It is not confirmed; try again.` };
  }
  const at = clock(now());
  if (isObj(answer) && answer.strategy_id === id && answer.enabled === false) return { ok: true, at };
  return { ok: false, at, message: `Core answered at ${at}, but did not confirm the disable. Reload to check it.` };
}

export const NO_EDGE_TEXT =
  "Paper results are a filter, not proof. Even 100 closed trades cannot prove an edge: memecoin results hang on a few outliers, and paper fills are simulated.";

export const ENABLE_ELSEWHERE = "Enable from your Mac or phone";
