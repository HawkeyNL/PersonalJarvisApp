// Trading read API (contract and states in trading.ts) and the loader the
// trading screens share. Calls go through the native auth_request command like
// every other owner endpoint; Vue never sees the session bearer.
import { ref, watch, type Ref } from "vue";
import { getJsonAuth } from "./api";
import { currentAuthStatus } from "./auth";
import {
  BAR_RANGES, HISTORY_RANGES, dataState, failureState,
  type BarRange, type Freshness, type HistoryRange, type InstrumentBars, type Portfolio, type PortfolioHistory, type TradingState,
} from "./trading";

/** A conid from a route or a response: a positive safe integer, else null. */
export function parseConid(value: unknown): number | null {
  const n = typeof value === "string" && /^\d{1,12}$/.test(value) ? Number(value) : value;
  return typeof n === "number" && Number.isSafeInteger(n) && n > 0 ? n : null;
}

export const tradingApi = {
  portfolio: () => getJsonAuth<Portfolio>("/v1/trading/portfolio"),
  history: (range: HistoryRange) => {
    if (!HISTORY_RANGES.includes(range)) throw new Error("invalid range");
    return getJsonAuth<PortfolioHistory>(`/v1/trading/portfolio/history?range=${range}`);
  },
  bars: (conid: number, range: BarRange) => {
    if (parseConid(conid) === null || !BAR_RANGES.includes(range)) throw new Error("invalid instrument request");
    return getJsonAuth<InstrumentBars>(`/v1/trading/instruments/${conid}/bars?range=${range}`);
  },
};

// ---- dev-only data switch (DevDataToggle) -------------------------------

export const DATA_SETS = ["live", "demo", "worst", "empty", "stale", "offline", "loading"] as const;
export type DataSet = (typeof DATA_SETS)[number];
export type FixtureSet = Exclude<DataSet, "live">;

const DEV_KEY = "jarvis.dev.tradingData";
function savedSet(): DataSet {
  try {
    const saved = sessionStorage.getItem(DEV_KEY);
    return DATA_SETS.find((set) => set === saved) ?? "live";
  } catch {
    return "live";
  }
}
/** Which data the trading screens show in a dev build; always "live" in production. */
export const devDataSet = ref<DataSet>(import.meta.env.DEV ? savedSet() : "live");
export function setDevDataSet(set: DataSet): void {
  if (!import.meta.env.DEV) return;
  devDataSet.value = set;
  try {
    sessionStorage.setItem(DEV_KEY, set);
  } catch {
    /* private mode: the choice lasts until reload */
  }
}

type Fixtures = typeof import("./tradingFixtures");

/** Shape check at the boundary: an older or broken Core must not crash a screen. */
const fresh = (x: unknown): x is Freshness =>
  typeof x === "object" && x !== null && typeof (x as Freshness).as_of === "string" && typeof (x as Freshness).source === "string";
export const isPortfolio = (x: unknown): x is Portfolio =>
  fresh(x) && Array.isArray((x as Portfolio).positions) && (x as Portfolio).positions.every((p) => parseConid(p?.conid) !== null);
export const isHistory = (x: unknown): x is PortfolioHistory => fresh(x) && Array.isArray((x as PortfolioHistory).points);
export const isBars = (x: unknown): x is InstrumentBars => fresh(x) && Array.isArray((x as InstrumentBars).bars);

/** Load one trading read into a TradingState, again whenever `key` or the dev
 *  data set changes. Only the newest request may set the state. */
export function useTradingRead<T extends Freshness>(opts: {
  live: () => Promise<unknown>;
  fixture: (f: Fixtures, set: FixtureSet) => unknown;
  valid: (x: unknown) => x is T;
  isEmpty: (data: T) => boolean;
  key?: () => unknown;
}): { state: Ref<TradingState<T>>; reload: () => Promise<void> } {
  const state = ref({ kind: "loading" }) as Ref<TradingState<T>>;
  let seq = 0;

  async function fetchRaw(): Promise<unknown> {
    const set = devDataSet.value;
    if (import.meta.env.DEV && set !== "live") {
      if (set === "loading") return new Promise(() => {}); // held until the switch moves
      if (set === "offline") throw { name: "ApiError", status: 501 };
      return opts.fixture(await import("./tradingFixtures"), set);
    }
    const auth = await currentAuthStatus();
    if (!auth.authenticated) throw { name: "ApiError", status: 401 };
    return opts.live();
  }

  async function reload(): Promise<void> {
    const id = ++seq;
    state.value = { kind: "loading" };
    let next: TradingState<T>;
    try {
      const raw = await fetchRaw();
      next = opts.valid(raw) ? dataState(raw, opts.isEmpty, Date.now()) : { kind: "error", reason: "failed" };
    } catch (error) {
      next = failureState(error);
    }
    if (id === seq) state.value = next;
  }

  watch([devDataSet, () => opts.key?.()], () => void reload(), { immediate: true });
  return { state, reload };
}
