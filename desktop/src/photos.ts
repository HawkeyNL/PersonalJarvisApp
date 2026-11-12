// Chat photos in the page: the composer's pending photos (chips) and a cache
// of shown photos as blob URLs. Bytes come and go only through the native
// photo_upload / photo_get commands, so the bearer never reaches the page.
import { ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { chatSession } from "./chatSession";
import { currentId } from "./conversations";
import { deleteAuth } from "./api";
import { downscale, uploadFailure, MAX_PHOTOS } from "./chatPhotos";

export interface Chip {
  key: number;
  /** Blob URL of the shrunk photo, once made. */
  url: string;
  state: "preparing" | "uploading" | "ready" | "failed";
  id?: string;
  error?: string;
}

export const chips = ref<Chip[]>([]);
/** Why a photo was not added (the limit); cleared by the next attach. */
export const chipNotice = ref("");
let keys = 0;

const photoPath = (id: string) => `/v1/photos/${encodeURIComponent(id)}`;
const forget = (id: string) => void deleteAuth(photoPath(id)).catch(() => {});

/** Add picked or pasted files, up to MAX_PHOTOS in the composer. */
export function attach(files: Iterable<File>): void {
  chipNotice.value = "";
  for (const file of files) {
    if (chips.value.length >= MAX_PHOTOS) {
      chipNotice.value = `Up to ${MAX_PHOTOS} photos per message.`;
      break;
    }
    const key = keys++;
    chips.value.push({ key, url: "", state: "preparing" });
    void prepare(key, file);
  }
}

async function prepare(key: number, file: File): Promise<void> {
  const epoch = chatSession.capture();
  // The reactive chip, while it is still in the composer.
  const chip = () => (chatSession.current(epoch) ? chips.value.find((c) => c.key === key) : undefined);
  let blob: Blob;
  try {
    blob = await downscale(file);
  } catch (error) {
    const current = chip();
    if (current) Object.assign(current, { state: "failed", error: (error as Error).message });
    return;
  }
  const url = URL.createObjectURL(blob);
  const preparing = chip();
  if (!preparing) return URL.revokeObjectURL(url);
  Object.assign(preparing, { url, state: "uploading" });
  let status = 0;
  let id: string | undefined;
  try {
    const bytes = new Uint8Array(await blob.arrayBuffer());
    const reply = await invoke<{ status: number; body: { id?: unknown } | null }>("photo_upload", bytes);
    status = reply.status;
    if (status >= 200 && status < 300 && typeof reply.body?.id === "string") id = reply.body.id;
  } catch { /* Unreachable or refused natively: no id. */ }
  const uploaded = chip();
  if (!uploaded) {
    // Removed (✕) while uploading: delete what Core kept. After a sign-out the
    // photo stays pending and Core removes it after 24 hours.
    if (id && chatSession.current(epoch)) forget(id);
    return;
  }
  if (id) Object.assign(uploaded, { id, state: "ready" });
  else Object.assign(uploaded, { state: "failed", error: status ? uploadFailure(status) : "Could not reach your Home Node." });
}

/** ✕ on a chip: drop it, and delete it on the Home Node once uploaded. */
export function removeChip(key: number): void {
  const chip = chips.value.find((c) => c.key === key);
  if (!chip) return;
  chips.value = chips.value.filter((c) => c.key !== key);
  if (chip.id) {
    forget(chip.id);
    cache.delete(chip.id);
  }
  if (chip.url) URL.revokeObjectURL(chip.url);
  chipNotice.value = "";
}

export const uploading = () => chips.value.some((c) => c.state === "preparing" || c.state === "uploading");
export const allReady = () => chips.value.every((c) => c.state === "ready");

/** Hand the uploaded photos to a message: their ids, while their previews
 *  move into the cache so the sent message shows them without a download. */
export function takeChips(): string[] {
  const ids: string[] = [];
  for (const chip of chips.value) {
    if (chip.state === "ready" && chip.id) {
      ids.push(chip.id);
      cache.set(chip.id, Promise.resolve(chip.url));
    } else if (chip.url) URL.revokeObjectURL(chip.url);
  }
  chips.value = [];
  chipNotice.value = "";
  return ids;
}

// Shown photos by id: a blob URL, or null once Core says it is gone.
const cache = new Map<string, Promise<string | null>>();

/** A blob URL for one photo; null when the photo no longer exists. */
export function photoUrl(id: string): Promise<string | null> {
  const hit = cache.get(id);
  if (hit) return hit;
  const loading: Promise<string | null> = invoke<ArrayBuffer>("photo_get", { id }).then(
    (bytes) => {
      const url = URL.createObjectURL(new Blob([bytes], { type: "image/jpeg" }));
      // Cleared (switch, sign-out) while loading: nobody will revoke it later.
      if (cache.get(id) !== loading) {
        URL.revokeObjectURL(url);
        return null;
      }
      return url;
    },
    (error) => {
      if (error === "photo not found") return null;
      // A passing failure is not remembered; the next showing retries.
      if (cache.get(id) === loading) cache.delete(id);
      throw new Error(typeof error === "string" ? error : "photo unavailable");
    },
  );
  cache.set(id, loading);
  return loading;
}

/** Revoke every cached blob URL. */
export function clearPhotoCache(): void {
  for (const url of cache.values()) void url.then((value) => value && URL.revokeObjectURL(value), () => {});
  cache.clear();
}

// Leaving a conversation frees its photos. Its first message (no id yet ->
// an id) keeps them: they are the photos just sent.
watch(currentId, (_, before) => {
  if (before !== null) clearPhotoCache();
});
chatSession.onReset(() => {
  clearPhotoCache();
  for (const chip of chips.value) if (chip.url) URL.revokeObjectURL(chip.url);
  chips.value = [];
  chipNotice.value = "";
});
