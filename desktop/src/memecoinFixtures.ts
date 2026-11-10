// Dev-only fixtures for the memecoin paper page (see DevDataToggle), in the raw
// shape Core sends so the response guards run on them. Loaded with a dynamic
// import behind import.meta.env.DEV, so production builds contain none of
// this. Worst is the realistic worst case: stuck exits, a stopped report,
// zero and huge amounts, unknown reason codes, full lists.
import type { FixtureSet } from "./tradingApi";

const ago = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
// Plausible base58 mints (not real tokens).
const MINTS = [
  "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
  "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263",
  "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R",
  "HeLp6NuQkmYB4pYWo2zYs22mESHXPQYzXbB8n4V98jwC",
  "9n4nbM75f5Ui33ZbPYXn59EwSgE8CGsHtAeTH5YFeJ9E",
];

const position = (i: number, extra: Record<string, unknown> = {}) => ({
  mint: MINTS[i % MINTS.length], strategy_version: 3, tokens: "184211.775", cost_usd: "25", value_usd: "27.31",
  opened_at: ago(90 + i * 40), exit_failures: 0, stuck: false, ...extra,
});

const gate = (closed: number, days: number, tradingDays: number, net: string, perTrade: string) => ({
  closed_trades: closed, min_closed_trades: 100, duration_days: days, min_duration_days: 28,
  trading_days: tradingDays, min_trading_days: 7, net_result_usd: net, net_expectancy_usd: perTrade,
  enough_trades: closed >= 100, enough_time: days >= 28, enough_days: tradingDays >= 7,
  positive_expectancy: !perTrade.startsWith("-") && perTrade !== "0", passed: false,
});

function report(set: FixtureSet) {
  if (set === "empty") {
    return {
      strategy_version: 1, as_of: ago(0.5), stake_usd: "250", equity_usd: "250",
      pnl: { realised_usd: "0", unrealised_usd: "0", failed_tx_fees_usd: "0", ai_cost_usd: "0", net_usd: "0" },
      open_positions: [], gate: gate(0, 0, 0, "0", "0"),
      goal: { multiple_target: "2", multiple: "1", fraction_to_goal: "0", remaining_usd: "250", reached: false },
    };
  }
  if (set === "worst") {
    return {
      strategy_version: 4294967295, as_of: ago(26 * 60), stake_usd: "1000000", equity_usd: "0.000001",
      pnl: { realised_usd: "-998765.4321", unrealised_usd: "-1234.5678", failed_tx_fees_usd: "12345.678901", ai_cost_usd: "0.0042", net_usd: "-1012345.6789" },
      open_positions: [
        position(0, { stuck: true, exit_failures: 10, value_usd: null, tokens: "999999999999.123456789" }),
        position(1, { strategy_version: 2, exit_failures: 7, value_usd: "0", cost_usd: "100000" }),
        position(2, { stuck: true, exit_failures: 10, value_usd: null }),
        ...Array.from({ length: 7 }, (_, i) => position(i + 3, { value_usd: i % 2 ? "0.00000004" : "99999.99" })),
      ],
      gate: { ...gate(99, 27, 6, "-1012345.6789", "-10225.7139"), passed: false },
      goal: { multiple_target: "20", multiple: null, fraction_to_goal: null, remaining_usd: null, reached: null },
    };
  }
  return {
    strategy_version: 3, as_of: set === "stale" ? ago(3 * 60) : ago(0.5), stake_usd: "250", equity_usd: "270.1158",
    pnl: { realised_usd: "18.4", unrealised_usd: "2.31", failed_tx_fees_usd: "0.38", ai_cost_usd: "0.2142", net_usd: "20.1158" },
    open_positions: [position(0), position(1, { value_usd: "22.04", opened_at: ago(12) })],
    gate: gate(63, 19, 14, "20.1158", "0.3193"),
    goal: { multiple_target: "2", multiple: "1.0805", fraction_to_goal: "0.0805", remaining_usd: "229.8842", reached: false },
  };
}

export function overviewFixture(set: FixtureSet): unknown {
  const base = {
    mode: "paper", enabled: true, running: true, paused: false, paused_at: null, last_error: null,
    resume: "configuration only (JARVIS_MEMECOIN_PAPER_RESUME_PAUSED_AT)",
  };
  if (set === "disabled") return { ...base, enabled: false, running: false, agent: null };
  const body = { ...base, agent: agent(set), ...report(set) };
  if (set === "paused") return { ...body, paused: true, paused_at: ago(42) };
  if (set === "worst") {
    return { ...body, last_error: "paper ledger no longer replays; entries and reports stopped" };
  }
  return body;
}

const REASONS = ["lp_not_secured", "liquidity_too_low", "pool_too_young", "holder_concentration_too_high", "sell_impact_too_high"];

export function candidatesFixture(set: FixtureSet): unknown {
  if (set === "empty" || set === "disabled") return { candidates: [] };
  const n = set === "worst" ? 100 : 8;
  return {
    candidates: Array.from({ length: n }, (_, i) => {
      const accept = set !== "worst" && i === 3;
      const reason = set === "worst" && i === 1 ? "a_reason_this_app_does_not_know_yet" : REASONS[i % REASONS.length];
      return {
        mint: MINTS[i % MINTS.length], observed_at: ago(4 + i * 17), strategy_version: 3,
        verdict: accept ? "accept" : "reject", reason: accept ? null : reason,
        price_usd: set === "worst" && i % 3 === 0 ? null : i % 2 ? "0.00004127" : "0.0213",
        liquidity_usd: set === "worst" && i === 0 ? "123456789012.5" : i % 2 ? "8120.5" : "41250",
      };
    }),
  };
}

export function tradesFixture(set: FixtureSet): unknown {
  if (set === "empty" || set === "disabled") return { trades: [] };
  const n = set === "worst" ? 100 : 5;
  const EXITS = ["take_profit", "stop_loss", "time_stop", "liquidity_drop"];
  return {
    trades: Array.from({ length: n }, (_, i) => {
      const net = set === "worst" ? (i % 2 ? "-99999.987654" : "0") : ["12.71", "-4.92", "0.83", "-2.4", "9.1"][i % 5];
      return {
        mint: MINTS[(i + 2) % MINTS.length], strategy_version: 3, tokens: "120544.1", cost_usd: "25",
        opened_at: ago(300 + i * 240), closed_at: ago(200 + i * 240), usd_out: "37.87", exit_fees_usd: set === "worst" ? "0.000031" : "0.16",
        net_pnl_usd: net, exit_reason: set === "worst" && i === 0 ? "manual_override_future" : EXITS[i % EXITS.length],
      };
    }),
  };
}

// ---- strategies and Claude's decisions ---------------------------------------

const ahead = (min: number) => new Date(Date.now() + min * 60_000).toISOString();

function agent(set: FixtureSet) {
  if (set === "empty") return null;
  const budget = { calling: false, calls_last_hour: 2, calls_last_day: 17, max_calls_per_hour: 4, max_calls_per_day: 48, last_call_at: ago(9), paused_until: null, backoff_until: null };
  if (set === "worst") return { ...budget, calls_last_hour: 4, calls_last_day: 48, paused_until: ahead(300), backoff_until: ahead(90) };
  return budget;
}

const perf = (trades: number, net: string, extra: Record<string, unknown> = {}) => ({
  trades, net_usd: net, costs_usd: "0.38", expectancy_usd: trades ? (Number(net) / trades).toFixed(4) : "0",
  win_rate_bps: trades ? 4125 : 0, max_drawdown_usd: "6.4", profit_factor: trades ? "1.31" : null, ...extra,
});

const SEEDS = [
  { id: "a-survivor-momentum", family: "a", active_version: 1000004, enabled: true, disabled_by_owner: false, changed_at: ago(600), changed_by: "claude", performance: perf(41, "14.82") },
  { id: "b-base-reclaim", family: "b", active_version: 1000002, enabled: false, disabled_by_owner: true, changed_at: ago(3000), changed_by: "6f1c2a9e-0b7d-4f43-9d58-1a2b3c4d5e6f", performance: perf(17, "-3.91") },
  { id: "c-established-trend", family: "c", active_version: 1000003, enabled: true, disabled_by_owner: false, changed_at: ago(9000), changed_by: "seed", performance: perf(5, "9.2") },
  { id: "claude-1000005", family: "a", active_version: 1000005, enabled: false, disabled_by_owner: false, changed_at: ago(120), changed_by: "claude", performance: perf(0, "0") },
];

export function strategiesFixture(set: FixtureSet): unknown {
  if (set === "empty" || set === "disabled") return { strategies: [] };
  if (set === "worst") {
    return {
      strategies: Array.from({ length: 20 }, (_, i) => ({
        ...SEEDS[i % 4], id: `${"x".repeat(24)}-${String(i).padStart(7, "0")}`, family: i % 5 ? SEEDS[i % 4].family : null,
        active_version: 4294967295 - i, performance: perf(i * 1000, i % 2 ? "-999999.999999" : "0.000001", { max_drawdown_usd: "123456789.12", win_rate_bps: i % 2 ? 0 : 10000 }),
      })),
    };
  }
  return { strategies: SEEDS };
}

const config = (id: string, version: number, parent: number | null, takeProfit: number, trailing: number | null) => ({
  id, version, parent, family: "a",
  entry: [{ signal: "return_15m", op: "gt", a: 800 }, { signal: "pool_age_minutes", op: "between", a: 60, b: 720 }],
  position_share_bps: 2500, max_liquidity_share_bps: 100, take_profit_bps: takeProfit, stop_loss_bps: 2000,
  trailing_stop_bps: trailing, liquidity_drop_exit_bps: 3000, max_hold_secs: 21600, entry_slippage_bps: 300,
  exit_slippage_bps: 500, max_entries_per_day: 6, token_cooldown_secs: 86400, filters: {},
});

const RATIONALE = "Survivors that held a new 15-minute high kept running in the last day's observations, while the 1.5x take profit cut most winners early. Raising it and adding a trailing stop keeps the stop loss unchanged.";

export function strategyFixture(set: FixtureSet, id: string): unknown {
  const listed = (strategiesFixture(set) as { strategies: Record<string, unknown>[] }).strategies.find((s) => s.id === id);
  if (!listed) throw { name: "ApiError", status: 404 };
  const top = listed.active_version as number;
  const worst = set === "worst";
  const versions = Array.from({ length: worst ? 20 : Math.min(3, top - 1000000) }, (_, i) => {
    const v = top - i;
    const parent = i === (worst ? 19 : Math.min(3, top - 1000000) - 1) ? null : v - 1;
    return {
      version: v, parent_version: parent, author: parent === null ? "seed" : "claude", created_at: ago(600 + i * 1440),
      config: config(id, v, parent, 5000 + (i === 0 ? 2500 : 0), i === 0 ? 1500 : null),
      diff: parent === null ? null : worst && i === 1
        ? Array.from({ length: 64 }, (_, k) => ({ field: `field_${k}`, from: k, to: { nested: ["x".repeat(200)] } }))
        : [{ field: "take_profit_bps", from: 5000, to: 7500 }, { field: "trailing_stop_bps", from: null, to: 1500 }],
      rationale: parent === null ? null : worst ? "W".repeat(600) : RATIONALE,
      backtest: parent === null ? null : i === 0 || worst
        ? { status: "unbacktested", mints: 3, points: 212 }
        : { status: "ok", mints: 18, points: 4120, trades: 9, net_usd: "4.71", expectancy_usd: "0.5233", win_rate_bps: 4444, max_drawdown_usd: "3.1", profit_factor: "1.42" },
    };
  });
  return {
    ...listed,
    active_config_sha256: "9f2b7c1d4e5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e",
    versions,
    gate: listed.performance && (listed.performance as { trades: number }).trades
      ? { at: ago(30), closed_trades: (listed.performance as { trades: number }).trades, min_closed_trades: 100, duration_days: 12, min_duration_days: 28, trading_days: 9, min_trading_days: 7, net_result_usd: "14.82", net_expectancy_usd: "0.3615" }
      : null,
  };
}

export function decisionsFixture(set: FixtureSet): unknown {
  if (set === "empty" || set === "disabled") return { decisions: [] };
  const rows = [
    { kind: "skip", reason_code: "call_started" },
    { kind: "enter", strategy_version: 1000004, mint: MINTS[0], size_bps: 6000, reason_code: "fresh_high_breakout", rationale: "Strong 15-minute return with deep liquidity and low sell impact.", model: "claude-sonnet-4-5", input_tokens: 2140, output_tokens: 96 },
    { kind: "skip", strategy_version: 1000003, mint: MINTS[1], reason_code: "no_slot", model: "claude-sonnet-4-5" },
    { kind: "skip", reason_code: "call_started" },
    { kind: "hold", rationale: "Nothing clears the bar this cycle.", model: "claude-sonnet-4-5" },
    { kind: "exit", strategy_version: 1000004, mint: MINTS[2], reason_code: "momentum_faded", model: "claude-sonnet-4-5" },
    { kind: "propose", strategy_version: 1000005, reason_code: "accepted", rationale: "New family A variant with a tighter stop.", model: "claude-opus-4-1" },
    { kind: "worker_unavailable", reason_code: "plan_limit" },
  ];
  const n = set === "worst" ? 100 : rows.length;
  return {
    decisions: Array.from({ length: n }, (_, i) => ({
      id: `d${i}`, at: ago(3 + i * 15), call_id: `call-${Math.floor(i / 2)}`, strategy_version: null, mint: null, size_bps: null,
      reason_code: null, rationale: null, model: null, input_tokens: null, output_tokens: null,
      ...rows[i % rows.length],
      ...(set === "worst" && i === 2 ? { kind: "a_kind_this_app_does_not_know", rationale: "R".repeat(300) } : {}),
      ...(set === "worst" && i === 9 ? { kind: "invalid_output", reason_code: "x".repeat(32) } : {}),
    })),
  };
}
