// Chat photos before upload: shrink and re-encode in the page, and the words
// for a failed upload. Pure (the decoder and canvas are passed in), so tests
// can load this file directly.

export const MAX_PHOTOS = 4;
/** Long side sent to Core; Core shrinks further to 1600 px and re-encodes. */
export const MAX_SIDE = 2048;
export const JPEG_QUALITY = 0.88;
export const UNSUPPORTED = "This photo format isn't supported; export it as JPEG.";
export const CORE_TOO_OLD = "Your Home Node needs Jarvis Core v0.0.83 to receive photos.";
/** Larger files are refused before decoding (a phone photo is far smaller). */
export const MAX_INPUT_BYTES = 30 * 1024 * 1024;
export const TOO_LARGE = "This photo is too large.";

/** `width`×`height` scaled down (never up) to fit `max` on the long side. */
export function fitWithin(width: number, height: number, max = MAX_SIDE): { width: number; height: number } {
  const scale = Math.min(1, max / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

interface Canvas {
  width: number;
  height: number;
  getContext(kind: "2d"): Pick<CanvasRenderingContext2D, "fillStyle" | "fillRect" | "drawImage"> | null;
  toBlob(done: (blob: Blob | null) => void, type: string, quality: number): void;
}
export interface DownscaleDeps {
  decode: (file: Blob) => Promise<ImageBitmap>;
  canvas: () => Canvas;
}

const browser: DownscaleDeps = {
  // The EXIF orientation is applied here, so the JPEG we send is upright.
  decode: (file) => createImageBitmap(file, { imageOrientation: "from-image" }),
  canvas: () => document.createElement("canvas") as unknown as Canvas,
};

/** The photo as a JPEG of at most MAX_SIDE px. Transparency becomes white.
 *  Anything the webview cannot decode (HEIC on Linux/Windows) is refused. */
export async function downscale(file: Blob, deps: DownscaleDeps = browser): Promise<Blob> {
  // ponytail: a byte cap only; a small file claiming huge dimensions still decodes in full
  // (owner-picked files only). Decode with resize options if that ever matters.
  if (file.size > MAX_INPUT_BYTES) throw new Error(TOO_LARGE);
  let bitmap: ImageBitmap;
  try {
    bitmap = await deps.decode(file);
  } catch {
    throw new Error(UNSUPPORTED);
  }
  try {
    const size = fitWithin(bitmap.width, bitmap.height);
    const canvas = deps.canvas();
    canvas.width = size.width;
    canvas.height = size.height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error(UNSUPPORTED);
    context.fillStyle = "#fff";
    context.fillRect(0, 0, size.width, size.height);
    context.drawImage(bitmap, 0, 0, size.width, size.height);
    const blob = await new Promise<Blob | null>((done) => canvas.toBlob(done, "image/jpeg", JPEG_QUALITY));
    if (!blob) throw new Error(UNSUPPORTED);
    return blob;
  } finally {
    bitmap.close();
  }
}

/** What to tell the owner when Core refuses an upload with `status`. */
export function uploadFailure(status: number): string {
  switch (status) {
    case 404: return CORE_TOO_OLD;
    case 400: case 415: return UNSUPPORTED;
    case 413: return TOO_LARGE;
    case 429: return "Too many photos for now; try again later.";
    case 503: return "Photos are unavailable on your Home Node right now.";
    case 507: return "Photo storage on your Home Node is full.";
    default: return "The photo could not be uploaded.";
  }
}

/** Photo ids the messages carry that the conversation no longer has
 *  (deleted or expired): shown as placeholders without a download. */
export function missingPhotos(messages: { attachments?: string[] }[], photos: { id: string }[]): Set<string> {
  const live = new Set(photos.map((photo) => photo.id));
  return new Set(messages.flatMap((m) => m.attachments ?? []).filter((id) => !live.has(id)));
}

/** How long a photo stays, for the lightbox. Without metadata (a photo just
 *  sent) the general rule. */
export function expiryHint(meta: { saved: boolean; expires_at: string | null } | undefined): string {
  if (meta?.saved) return "Kept until you delete it.";
  const at = meta?.expires_at ? new Date(meta.expires_at) : null;
  if (!at || Number.isNaN(at.getTime())) return "Photos are kept 90 days unless you keep them.";
  return `Removed on ${at.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" })} unless you keep it.`;
}
