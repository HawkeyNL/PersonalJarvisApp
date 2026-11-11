// Scheduled job calls (contract and guards in jobs.ts). They go through the
// native auth_request command like every other owner endpoint; Vue never sees
// the session bearer. Answers are raw: the pages pass them through the guards.
import { onMounted, onUnmounted } from "vue";
import { listen } from "@tauri-apps/api/event";
import { getJsonAuth, patchJsonAuth, postJsonAuth } from "./api";
import { runFinished, type JobPatch, type NewJob, type RunFinished } from "./jobs";

const base = (id: string) => `/v1/jobs/${encodeURIComponent(id)}`;

export const jobApi = {
  list: () => getJsonAuth<unknown>("/v1/jobs"),
  create: (body: NewJob) => postJsonAuth<unknown>("/v1/jobs", body),
  update: (id: string, revision: number, patch: JobPatch) => patchJsonAuth<unknown>(`${base(id)}?expected_revision=${revision}`, patch),
  /** 202 when queued; 429 within an hour of the last owner run. */
  runNow: (id: string) => postJsonAuth<unknown>(`${base(id)}/run`, {}),
  runs: (id: string) => getJsonAuth<unknown>(`${base(id)}/runs?limit=20`),
  run: (runId: string) => getJsonAuth<unknown>(`/v1/jobs/runs/${encodeURIComponent(runId)}`),
};

/** Reload when the window regains focus or the realtime socket (re)connects,
 *  and pass each `jobs.run_finished` hint to `finished`. */
export function useJobRefetch(reload: () => void, finished: (hint: RunFinished) => void): void {
  let unlisten: (() => void) | undefined;
  let gone = false;
  const onFocus = () => reload();
  onMounted(() => {
    window.addEventListener("focus", onFocus);
    // Promise.resolve first: outside Tauri, listen throws instead of rejecting.
    Promise.resolve().then(() => listen<{ type?: unknown }>("jarvis-realtime", (event) => {
      if (event.payload?.type === "connection.ready") return reload();
      const hint = runFinished(event.payload);
      if (hint) finished(hint);
    })).then((stop) => (gone ? stop() : (unlisten = stop)), () => { /* browser preview: focus only */ });
  });
  onUnmounted(() => {
    gone = true;
    window.removeEventListener("focus", onFocus);
    unlisten?.();
  });
}
