// Dev-only fixtures for the trading screens (see DevDataToggle). Loaded with a
// dynamic import behind import.meta.env.DEV, so production builds contain none
// of this. Demo is plausible data; worst is the realistic worst case: long
// names, zero and huge amounts, negative P&L, missing optional fields.
import type { Bar, BarRange, HistoryRange, InstrumentBars, Portfolio, PortfolioHistory, Position } from "./trading";
import type { FixtureSet } from "./tradingApi";

const fresh = () => new Date(Date.now() - 60_000).toISOString();
const old = () => new Date(Date.now() - 3 * 3_600_000).toISOString();
const meta = (set: FixtureSet) => ({ source: "IBKR", delay_minutes: set === "worst" ? undefined : 15, as_of: set === "stale" ? old() : fresh() });

const pos = (p: Partial<Position> & Pick<Position, "conid" | "symbol" | "quantity" | "market_value">): Position =>
  ({ asset_class: "STK", currency: "EUR", avg_cost: "0", last_price: "0", ...p });

const DEMO: Position[] = [
  pos({ conid: 1001, symbol: "WRLD", name: "World equity ETF, accumulating", asset_class: "ETF", exchange: "AEB", quantity: "128", avg_cost: "92.70", last_price: "106.07", market_value: "13577.00", day_pnl: "81.20", day_change_pct: "0.60", unrealized_pnl: "1712.40", weight_pct: "57.0" }),
  pos({ conid: 1002, symbol: "EMRG", name: "Emerging markets ETF", asset_class: "ETF", exchange: "AEB", quantity: "40", avg_cost: "45.55", last_price: "47.65", market_value: "1906.00", day_pnl: "3.80", day_change_pct: "0.20", unrealized_pnl: "84.00", weight_pct: "8.0" }),
  pos({ conid: 265598, symbol: "ACME", name: "ACME Corp", exchange: "NASDAQ", currency: "USD", quantity: "12", avg_cost: "182.10", last_price: "176.40", market_value: "1960.00", day_pnl: "-35.90", day_change_pct: "-1.80", unrealized_pnl: "-63.10", weight_pct: "8.2" }),
  pos({ conid: 1004, symbol: "KRNL", name: "Kernel Systems", exchange: "NYSE", currency: "USD", quantity: "25", avg_cost: "57.40", last_price: "71.30", market_value: "1644.00", day_pnl: "17.90", day_change_pct: "1.10", unrealized_pnl: "310.00", weight_pct: "6.9" }),
  pos({ conid: 1005, symbol: "NOVA", name: "Nova Health", exchange: "AEB", quantity: "30", avg_cost: "46.13", last_price: "51.00", market_value: "1530.00", day_pnl: "-4.60", day_change_pct: "-0.30", unrealized_pnl: "146.00", weight_pct: "6.4" }),
];

const WORST: Position[] = [
  pos({ conid: 9001, symbol: "WRLD-ACC-EUR-HEDGED", name: "Vanguard FTSE All-World High Dividend Yield UCITS ETF (USD) Accumulating, EUR Hedged — Xetra listing", asset_class: "ETF", exchange: "IBIS2", quantity: "1234567.891", avg_cost: "99.123456", last_price: "104.120000", market_value: "128456789012.34", day_pnl: "770700000.12", day_change_pct: "0.60", unrealized_pnl: "12345678901.23", weight_pct: "99.98" }),
  pos({ conid: 265598, symbol: "ACME", currency: "USD", quantity: "12", market_value: "1960.00" }),
  pos({ conid: 9003, symbol: "X", name: "Ørsted A/S — Dansk Vindkraft Aktieselskab (Københavns Fondsbørs)", currency: "DKK", quantity: "0.0001", avg_cost: "1000.00", last_price: "0.10", market_value: "0.01", day_pnl: "-9999.99", day_change_pct: "-99.90", unrealized_pnl: "-9999.99", weight_pct: "0.00" }),
  pos({ conid: 9004, symbol: "7203", name: "トヨタ自動車株式会社", exchange: "TSEJ", currency: "JPY", quantity: "100", avg_cost: "2850", last_price: "2850", market_value: "0.00", day_pnl: "0.00", day_change_pct: "0.00", unrealized_pnl: "0.00", weight_pct: "0.00" }),
  pos({ conid: 9005, symbol: "BRK B", name: "Berkshire Hathaway Inc. Class B", asset_class: "STK", exchange: "NYSE", currency: "USD", quantity: "-3", avg_cost: "412.50", last_price: "468.20", market_value: "-1404.60", day_pnl: "-12.30", unrealized_pnl: "-167.10" }),
];

export function portfolioFixture(set: FixtureSet): Portfolio {
  const base = { ...meta(set), base_currency: "EUR" };
  if (set === "empty") return { ...base, net_liquidation: "0.00", cash: "0.00", positions: [] };
  if (set === "worst") {
    return {
      ...base,
      net_liquidation: "128456812345.67", cash: "-2500.00", day_pnl: "-1234567.89", day_change_pct: "-0.96", total_pnl: "-98765432.10",
      positions: WORST,
      allocation: [
        { label: "World equity, developed and emerging markets combined, accumulating share class", current_pct: "99.98", target_pct: "60" },
        { label: "Single stocks", current_pct: "0.02", target_pct: "35" },
        { label: "Cash", current_pct: "0", target_pct: "5" },
      ],
      upcoming_dividends: [{ conid: 9001, symbol: "WRLD-ACC-EUR-HEDGED", ex_date: "2026-11-05", currency: "EUR" }],
    };
  }
  return {
    ...base,
    net_liquidation: "23820.00", cash: "1203.00", day_pnl: "101.60", day_change_pct: "0.43", total_pnl: "2410.00", total_return_pct: "11.30", dividends_12m: "312.00",
    positions: DEMO,
    allocation: [
      { label: "World ETF", current_pct: "57", target_pct: "60" },
      { label: "Emerging markets", current_pct: "8", target_pct: "10" },
      { label: "Single stocks", current_pct: "30", target_pct: "25" },
      { label: "Cash", current_pct: "5", target_pct: "5" },
    ],
    monthly_deposit: { amount: "500.00", day_of_month: 1, next_date: "2026-12-01" },
    upcoming_dividends: [
      { conid: 1001, symbol: "WRLD", ex_date: "2026-11-05", currency: "EUR" },
      { conid: 1004, symbol: "KRNL", ex_date: "2026-11-12", pay_date: "2026-11-20", amount: "14.20", currency: "USD" },
    ],
  };
}

// Deterministic random walk, so a range always draws the same line.
function walk(n: number, seed: number, start: number, drift: number): number[] {
  let x = seed;
  let v = start;
  return Array.from({ length: n }, () => {
    x = (x * 9301 + 49297) % 233280;
    v *= 1 + drift + (x / 233280 - 0.5) * 0.03;
    return v;
  });
}

const STEP: Record<BarRange | HistoryRange, [number, number]> = {
  "1D": [78, 5 * 60_000], "1W": [35, 3_600_000 * 4], "1M": [22, 86_400_000], "3M": [64, 86_400_000],
  "1Y": [252, 86_400_000], "5Y": [260, 7 * 86_400_000], ALL: [140, 7 * 86_400_000],
};
const times = (range: BarRange | HistoryRange, n: number) => {
  const step = STEP[range][1];
  const end = Date.now();
  return Array.from({ length: n }, (_, i) => new Date(end - (n - 1 - i) * step).toISOString());
};

export function historyFixture(set: FixtureSet, range: HistoryRange): PortfolioHistory {
  const n = set === "empty" ? 0 : STEP[range][0];
  const values = walk(n, 11, set === "worst" ? 1.2e11 : 21000, set === "worst" ? -0.004 : 0.0012);
  const t = times(range, n);
  return { ...meta(set), range, base_currency: "EUR", points: values.map((v, i) => ({ t: t[i], value: v.toFixed(2) })) };
}

export function barsFixture(set: FixtureSet, conid: number, range: BarRange): InstrumentBars {
  const held = [...DEMO, ...WORST].find((p) => p.conid === conid);
  const n = set === "empty" ? 0 : STEP[range][0];
  const closes = walk(n, conid % 97, set === "worst" ? 0.0004 : 180, set === "worst" ? -0.01 : 0.0004);
  const t = times(range, n);
  const bars: Bar[] = closes.map((c, i) => {
    const o = i ? closes[i - 1] : c;
    const places = set === "worst" ? 8 : 2;
    return { t: t[i], o: o.toFixed(places), h: (Math.max(o, c) * 1.006).toFixed(places), l: (Math.min(o, c) * 0.994).toFixed(places), c: c.toFixed(places), v: set === "worst" && i % 3 ? undefined : String(Math.round(800_000 + (i * 7919) % 400_000)) };
  });
  return {
    ...meta(set), conid, range, bars,
    symbol: held?.symbol ?? String(conid), name: set === "worst" ? undefined : held?.name, exchange: held?.exchange, currency: held?.currency ?? "USD",
    notes: set === "demo" && n ? [
      { t: t[Math.floor(n * 0.6)], kind: "fact", text: "Q3 revenue +12% year on year; earnings per share $1.02 against $1.10 expected.", sources: ["10-Q, 24 Oct"] },
      { t: t[Math.floor(n * 0.6)], kind: "calculation", text: "ACME is 8.2% of your portfolio. Your limit per single stock is 10%." },
      { t: t[n - 1], kind: "inference", text: "The drop looks sector-wide rather than specific to ACME. Uncertain: 2 of 3 sources point that way.", sources: ["Sector index", "Press release"] },
    ] : undefined,
  };
}
