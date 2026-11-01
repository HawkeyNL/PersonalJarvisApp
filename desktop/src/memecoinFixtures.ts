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
  if (set === "disabled") return { ...base, enabled: false, running: false };
  const body = { ...base, ...report(set) };
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
