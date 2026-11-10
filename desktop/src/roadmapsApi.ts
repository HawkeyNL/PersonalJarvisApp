// Roadmap calls (contract and guards in roadmaps.ts). They go through the
// native auth_request command like every other owner endpoint; Vue never sees
// the session bearer. Answers are raw: the pages pass them through the guards.
import { onMounted, onUnmounted } from "vue";
import { listen } from "@tauri-apps/api/event";
import { deleteAuth, getJsonAuth, patchJsonAuth, postJsonAuth } from "./api";
import { roadmapChange, type ItemPatch, type NewItem, type RoadmapChange } from "./roadmaps";

const base = (id: string) => `/v1/roadmaps/${encodeURIComponent(id)}`;
const rev = (revision: number) => `?expected_revision=${revision}`;

export const roadmapApi = {
  list: () => getJsonAuth<unknown>("/v1/roadmaps"),
  create: (body: { title: string; description?: string; phases?: string[] }) => postJsonAuth<unknown>("/v1/roadmaps", body),
  get: (id: string) => getJsonAuth<unknown>(base(id)),
  update: (id: string, revision: number, patch: { title?: string; description?: string | null; phases?: string[] }) =>
    patchJsonAuth<unknown>(`${base(id)}${rev(revision)}`, patch),
  /** Archives; Core deletes nothing. */
  archive: (id: string, revision: number) => deleteAuth(`${base(id)}${rev(revision)}`),
  createItem: (id: string, revision: number, item: NewItem) => postJsonAuth<unknown>(`${base(id)}/items${rev(revision)}`, item),
  updateItem: (id: string, revision: number, itemId: string, patch: ItemPatch) =>
    patchJsonAuth<unknown>(`${base(id)}/items/${encodeURIComponent(itemId)}${rev(revision)}`, patch),
  deleteItem: (id: string, revision: number, itemId: string) =>
    deleteAuth(`${base(id)}/items/${encodeURIComponent(itemId)}${rev(revision)}`),
  history: (id: string) => getJsonAuth<unknown>(`${base(id)}/history?limit=50`),
  proposals: (id: string) => getJsonAuth<unknown>(`${base(id)}/proposals`),
  accept: (id: string, proposalId: string) => postJsonAuth<unknown>(`${base(id)}/proposals/${encodeURIComponent(proposalId)}/accept`, {}),
  reject: (id: string, proposalId: string) => postJsonAuth<unknown>(`${base(id)}/proposals/${encodeURIComponent(proposalId)}/reject`, {}),
};

/** Reload when the window regains focus or the realtime socket (re)connects,
 *  and pass each `roadmap.changed` hint to `changed`. Proposals send no
 *  event, so focus still matters. */
export function useRoadmapRefetch(reload: () => void, changed: (change: RoadmapChange) => void): void {
  let unlisten: (() => void) | undefined;
  let gone = false;
  const onFocus = () => reload();
  onMounted(() => {
    window.addEventListener("focus", onFocus);
    // Promise.resolve first: outside Tauri, listen throws instead of rejecting.
    Promise.resolve().then(() => listen<{ type?: unknown }>("jarvis-realtime", (event) => {
      if (event.payload?.type === "connection.ready") return reload();
      const change = roadmapChange(event.payload);
      if (change) changed(change);
    })).then((stop) => (gone ? stop() : (unlisten = stop)), () => { /* browser preview: focus only */ });
  });
  onUnmounted(() => {
    gone = true;
    window.removeEventListener("focus", onFocus);
    unlisten?.();
  });
}
