// Memecoin paper trader API (contract, guards and states in memecoin.ts) and
// the loader its page uses. Calls go through the native auth_request command
// like every other owner endpoint; Vue never sees the session bearer.
import { onUnmounted, ref, watch, type Ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getJsonAuth, postJsonAuth } from "./api";
import { currentAuthStatus } from "./auth";
import {
  LIST_LIMIT, appendOlder, memeFailure, prependCandidates, readCandidatePage, settleRead, type CandidatePage, type MemeFailure,
  type MemeRead,
} from "./memecoin";
import { memeHint, type EnableReview, type MemeHint } from "./memeStrategies";
import { devDataSet, type FixtureSet } from "./tradingApi";

async function signedIn(): Promise<void> {
  const auth = await currentAuthStatus();
  if (!auth.authenticated) throw { name: "ApiError", status: 401 };
}

export const memeApi = {
  overview: () => getJsonAuth<unknown>("/v1/trading/meme/overview"),
  /** `before` is a cursor Core sent (checked with isCursor). */
  candidates: (before?: string) => getJsonAuth<unknown>(
    `/v1/trading/meme/candidates?limit=${LIST_LIMIT}${before ? `&before=${encodeURIComponent(before)}` : ""}`,
  ),
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
  strategies: () => getJsonAuth<unknown>("/v1/trading/meme/strategies"),
  /** `id` is checked with isStrategyId before it reaches a path. */
  strategy: (id: string) => getJsonAuth<unknown>(`/v1/trading/meme/strategies/${id}`),
  decisions: () => getJsonAuth<unknown>(`/v1/trading/meme/decisions?limit=${LIST_LIMIT}`),
  /** Immediate and unsigned: it only stops this strategy's entries. */
  disable: async (id: string) => {
    if (import.meta.env.DEV && devDataSet.value !== "live") {
      if (devDataSet.value === "offline") throw { name: "NetworkError" };
      if (devDataSet.value === "worst") throw { name: "ApiError", status: 503 };
      return { strategy_id: id, enabled: false };
    }
    await signedIn();
    return postJsonAuth<unknown>(`/v1/trading/meme/strategies/${id}/disable`, {});
  },
  /** macOS only: native code fetches the challenge, checks it is for this
   *  strategy, version and parameter hash, asks for Touch ID, signs and
   *  posts. The webview never sees the challenge or the signature. Rejects
   *  with a message. */
  enable: async ({ id, version, config_sha256 }: EnableReview) => {
    if (import.meta.env.DEV && devDataSet.value !== "live") {
      if (devDataSet.value === "offline") throw "Home Node is unreachable";
      return;
    }
    await signedIn();
    await invoke("meme_enable_strategy", { strategyId: id, version, configSha256: config_sha256 });
  },
};

/** Call `on` for each memecoin realtime hint while the calling component is
 *  mounted. The native socket delivers the events; reloads stay REST. */
export function onMemeHint(on: (hint: MemeHint) => void): void {
  let stop: (() => void) | undefined;
  let gone = false;
  // Promise.resolve first: outside Tauri, listen throws instead of rejecting.
  Promise.resolve().then(() => listen<unknown>("jarvis-realtime", (event) => {
    const hint = memeHint(event.payload);
    if (hint && !gone) on(hint);
  })).then((unlisten) => (gone ? unlisten() : (stop = unlisten)), () => {});
  onUnmounted(() => {
    gone = true;
    stop?.();
  });
}

export type { MemeRead } from "./memecoin";

type Fixtures = typeof import("./memecoinFixtures");

async function fetchMeme(live: () => Promise<unknown>, fixture: (f: Fixtures, set: FixtureSet) => unknown): Promise<unknown> {
  const set = devDataSet.value;
  if (import.meta.env.DEV && set !== "live") {
    if (set === "loading") return new Promise(() => {}); // held until the switch moves
    if (set === "offline") throw { name: "ApiError", status: 501 };
    return fixture(await import("./memecoinFixtures"), set);
  }
  await signedIn();
  return live();
}

/** Load one memecoin read, again whenever the dev data set changes. Only the
 *  newest request may set the state. `refresh` is the background variant: it
 *  keeps what is shown, merges with `merge` when given, and a failure only
 *  sets `quietFailure`. */
export function useMemeRead<T>(opts: {
  live: () => Promise<unknown>;
  fixture: (f: Fixtures, set: FixtureSet) => unknown;
  /** Response guard from memecoin.ts; null = invalid. */
  read: (x: unknown) => T | null;
  merge?: (have: T, fresh: T) => T;
}): { state: Ref<MemeRead<T>>; quietFailure: Ref<MemeFailure | null>; reload: () => Promise<void>; refresh: () => Promise<void> } {
  const state = ref({ kind: "loading" }) as Ref<MemeRead<T>>;
  const quietFailure = ref<MemeFailure | null>(null);
  let seq = 0;
  let settled = 0; // the newest request that finished

  async function run(background: boolean): Promise<void> {
    // A background refresh never replaces a reload that is still running.
    if (background && settled !== seq) return;
    const id = ++seq;
    // A reload keeps what is on screen; only the first load shows "loading".
    if (!background && state.value.kind !== "ok") state.value = { kind: "loading" };
    let next: MemeRead<T>;
    try {
      const data = opts.read(await fetchMeme(opts.live, opts.fixture));
      next = data ? { kind: "ok", data } : { kind: "failed", failure: "failed" };
    } catch (error) {
      next = { kind: "failed", failure: memeFailure(error) };
    }
    if (id !== seq) return;
    settled = id;
    const out = settleRead(state.value, next, background, opts.merge);
    state.value = out.state;
    quietFailure.value = out.quiet;
  }

  const reload = () => run(false);
  watch(devDataSet, () => {
    quietFailure.value = null;
    state.value = { kind: "loading" };
    void reload();
  }, { immediate: true });
  return { state, quietFailure, reload, refresh: () => run(true) };
}

/** Candidate decisions with "Load older" paging. `reload` starts again from
 *  the first page; `refresh` merges a fresh first page on top. */
export function useCandidates() {
  const read = useMemeRead<CandidatePage>({
    live: () => memeApi.candidates(), fixture: (f, set) => f.candidatesFixture(set), read: readCandidatePage,
    merge: prependCandidates,
  });
  const { state } = read;
  const older = ref<{ loading: boolean; failure: MemeFailure | null }>({ loading: false, failure: null });
  let gen = 0; // bumped by every reset; a page from an older generation is dropped

  function reset(): void {
    gen++;
    older.value = { loading: false, failure: null };
  }
  watch(devDataSet, reset);

  async function reload(): Promise<void> {
    reset();
    await read.reload();
  }

  async function loadOlder(): Promise<void> {
    const s = state.value;
    const before = s.kind === "ok" ? s.data.next : null;
    if (!before || older.value.loading) return;
    const id = gen;
    older.value = { loading: true, failure: null };
    let page: CandidatePage | null = null;
    let failure: MemeFailure | null = null;
    try {
      page = readCandidatePage(await fetchMeme(() => memeApi.candidates(before), (f, set) => f.candidatesFixture(set, before)));
      if (!page) failure = "failed";
    } catch (error) {
      failure = memeFailure(error);
    }
    if (id !== gen) return;
    older.value = { loading: false, failure };
    if (page) state.value = appendOlder(state.value, before, page);
  }

  return { state, quietFailure: read.quietFailure, older, reload, refresh: read.refresh, loadOlder };
}
