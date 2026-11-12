// Chat photos in the page: the composer's pending photos (chips) and a cache
// of shown photos as blob URLs. Bytes come and go only through the native
// photo_upload / photo_get commands, so the bearer never reaches the page.
import { ref, watch } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { chatSession } from "./chatSession";
import { currentId } from "./conversations";
import { ApiError, deleteAuth, patchJsonAuth } from "./api";
import { downscale, uploadFailure, missingPhotos, tooOldToSend, MAX_PHOTOS, TOO_OLD_TO_SEND } from "./chatPhotos";
import type { PhotoMeta } from "./assistant";

export interface Chip {
  key: number;
  /** Blob URL of the shrunk photo, once made. */
  url: string;
  state: "preparing" | "uploading" | "ready" | "failed";
  id?: string;
  error?: string;
  /** The shrunk photo, so a refused message can show it again. */
  blob?: Blob;
  /** When the upload finished (ms since epoch). */
  at?: number;
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
  Object.assign(preparing, { url, blob, state: "uploading" });
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
  if (id) Object.assign(uploaded, { id, state: "ready", at: Date.now() });
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

/** The uploaded photos' ids, in order. */
export const chipIds = () => chips.value.flatMap((c) => (c.state === "ready" && c.id ? [c.id] : []));

/** Photos Core would no longer attach (uploaded over 23 h ago) fail in place. */
export function expireStaleChips(now = Date.now()): void {
  for (const chip of chips.value) {
    if (chip.state === "ready" && tooOldToSend(chip.at, now)) Object.assign(chip, { state: "failed", error: TOO_OLD_TO_SEND });
  }
}

/** Hand the uploaded photos to a sent message: their previews move into the
 *  cache so the message shows them without a download. Returns them, for
 *  restoreChips if Core refuses the message. */
export function takeChips(): Chip[] {
  const taken: Chip[] = [];
  for (const chip of chips.value) {
    if (chip.state === "ready" && chip.id) {
      taken.push({ ...chip });
      cache.set(chip.id, Promise.resolve(chip.url));
    } else if (chip.url) URL.revokeObjectURL(chip.url);
  }
  chips.value = [];
  chipNotice.value = "";
  return taken;
}

/** Put photos of a refused message back in the composer, ahead of any added
 *  since. Each takes its preview back from the cache under a fresh URL. */
export function restoreChips(taken: Chip[]): void {
  const room = Math.max(MAX_PHOTOS - chips.value.length, 0);
  // No room left (photos added since): the rest are deleted on the Home Node.
  for (const chip of taken.slice(room)) forget(chip.id!);
  if (taken.length > room) chipNotice.value = `Up to ${MAX_PHOTOS} photos per message.`;
  const back = taken.slice(0, room).map((chip) => {
    const old = cache.get(chip.id!);
    cache.delete(chip.id!);
    void old?.then((value) => value && URL.revokeObjectURL(value), () => {});
    return { ...chip, url: chip.blob ? URL.createObjectURL(chip.blob) : "" };
  });
  chips.value = [...back, ...chips.value];
}

// Shown photos by id: a blob URL, or null once Core says it is gone.
// ponytail: unbounded within one conversation (<= 1 MiB per photo, freed on a switch);
// an LRU would revoke URLs still on screen, so cap it only if long photo threads appear.
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

/** The open conversation's photos that still exist (from its last read). */
export const photoMeta = ref(new Map<string, PhotoMeta>());
/** Photos known to be gone: shown as "Photo removed". */
export const removedPhotos = ref(new Set<string>());

/** After reading a conversation: its live photos, and the ones its messages
 *  still name but that were deleted or expired. */
export function setConversationPhotos(messages: { attachments?: string[] }[], photos: PhotoMeta[]): void {
  photoMeta.value = new Map(photos.map((photo) => [photo.id, photo]));
  removedPhotos.value = missingPhotos(messages, photos);
}

const gone = (error: unknown) => error instanceof ApiError && error.status === 404;

/** Keep (or stop keeping) a photo past its 90 days. A photo Core no longer
 *  has (deleted elsewhere or expired) is marked removed instead. */
export async function keepPhoto(id: string, saved: boolean): Promise<void> {
  let meta: PhotoMeta;
  try {
    meta = await patchJsonAuth<PhotoMeta>(photoPath(id), { saved });
  } catch (error) {
    if (!gone(error)) throw error;
    return markRemoved(id);
  }
  if (meta?.id === id && typeof meta.saved === "boolean") photoMeta.value = new Map(photoMeta.value).set(id, meta);
}

/** Delete a photo for good; its messages show a placeholder. */
export async function deletePhoto(id: string): Promise<void> {
  try {
    await deleteAuth(photoPath(id));
  } catch (error) {
    // Already gone counts as deleted.
    if (!gone(error)) throw error;
  }
  markRemoved(id);
}

/** Forget a photo Core no longer has: its messages show "Photo removed". */
function markRemoved(id: string): void {
  const meta = new Map(photoMeta.value);
  meta.delete(id);
  photoMeta.value = meta;
  removedPhotos.value = new Set(removedPhotos.value).add(id);
  const url = cache.get(id);
  cache.set(id, Promise.resolve(null));
  void url?.then((value) => value && URL.revokeObjectURL(value), () => {});
}

/** Revoke every cached blob URL. */
export function clearPhotoCache(): void {
  for (const url of cache.values()) void url.then((value) => value && URL.revokeObjectURL(value), () => {});
  cache.clear();
}

// Leaving a conversation frees its photos. Its first message (no id yet ->
// an id) keeps them: they are the photos just sent.
watch(currentId, (_, before) => {
  if (before === null) return;
  clearPhotoCache();
  photoMeta.value = new Map();
  removedPhotos.value = new Set();
});
chatSession.onReset(() => {
  clearPhotoCache();
  photoMeta.value = new Map();
  removedPhotos.value = new Set();
  for (const chip of chips.value) if (chip.url) URL.revokeObjectURL(chip.url);
  chips.value = [];
  chipNotice.value = "";
});
