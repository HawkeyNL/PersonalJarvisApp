// Memecoin paper trader API (contract, guards and states in memecoin.ts) and
// the loader its page uses. Calls go through the native auth_request command
// like every other owner endpoint; Vue never sees the session bearer.
import { ref, watch, type Ref } from "vue";
import { getJsonAuth, postJsonAuth } from "./api";
import { currentAuthStatus } from "./auth";
import { LIST_LIMIT, memeFailure, type MemeFailure } from "./memecoin";
import { devDataSet, type FixtureSet } from "./tradingApi";

async function signedIn(): Promise<void> {
  const auth = await currentAuthStatus();
  if (!auth.authenticated) throw { name: "ApiError", status: 401 };
}

export const memeApi = {
  overview: () => getJsonAuth<unknown>("/v1/trading/meme/overview"),
  candidates: () => getJsonAuth<unknown>(`/v1/trading/meme/candidates?limit=${LIST_LIMIT}`),
  trades: () => getJsonAuth<unknown>(`/v1/trading/meme/trades?limit=${LIST_LIMIT}`),
  /** Tightening only: stops new paper entries. There is no resume route. */
  pause: async () => {
    // Dev data: "offline" and "worst" preview the failures, the rest succeed.
    if (import.meta.env.DEV && devDataSet.value !== "live") {
      if (devDataSet.value === "offline") throw { name: "NetworkError" };
      if (devDataSet.value === "worst") throw { name: "ApiError", status: 503 };
      return { paused: true };
    }
    await signedIn();
    return postJsonAuth<unknown>("/v1/trading/meme/pause", {});
  },
};

export type MemeRead<T> = { kind: "loading" } | { kind: "failed"; failure: MemeFailure } | { kind: "ok"; data: T };

type Fixtures = typeof import("./memecoinFixtures");

/** Load one memecoin read, again whenever the dev data set changes. Only the
 *  newest request may set the state. */
export function useMemeRead<T>(opts: {
  live: () => Promise<unknown>;
  fixture: (f: Fixtures, set: FixtureSet) => unknown;
  /** Response guard from memecoin.ts; null = invalid. */
  read: (x: unknown) => T | null;
}): { state: Ref<MemeRead<T>>; reload: () => Promise<void> } {
  const state = ref({ kind: "loading" }) as Ref<MemeRead<T>>;
  let seq = 0;

  async function fetchRaw(): Promise<unknown> {
    const set = devDataSet.value;
    if (import.meta.env.DEV && set !== "live") {
      if (set === "loading") return new Promise(() => {}); // held until the switch moves
      if (set === "offline") throw { name: "ApiError", status: 501 };
      return opts.fixture(await import("./memecoinFixtures"), set);
    }
    await signedIn();
    return opts.live();
  }

  async function reload(): Promise<void> {
    const id = ++seq;
    // A reload keeps what is on screen; only the first load shows "loading".
    if (state.value.kind !== "ok") state.value = { kind: "loading" };
    let next: MemeRead<T>;
    try {
      const data = opts.read(await fetchRaw());
      next = data ? { kind: "ok", data } : { kind: "failed", failure: "failed" };
    } catch (error) {
      next = { kind: "failed", failure: memeFailure(error) };
    }
    if (id === seq) state.value = next;
  }

  watch(devDataSet, () => {
    state.value = { kind: "loading" };
    void reload();
  }, { immediate: true });
  return { state, reload };
}
