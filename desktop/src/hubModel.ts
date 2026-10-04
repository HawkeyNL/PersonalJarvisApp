// Pure presentation helpers for the redesigned shell (hub, node pages).
// No Vue or Tauri imports, so behavioral tests can load this file directly.

/** Status colour of a dot/label. Every tone is always paired with text. */
export type Tone = "ok" | "idle" | "warn" | "error";

/** What the orb shows. */
export type Mood = "idle" | "listening" | "thinking";

/** Result of an optional Core read. "unsupported" = the route does not exist
 *  on this Core (older version); it is shown as "Requires newer Core". */
export type Availability<T> =
  | { state: "loading" }
  | { state: "ok"; value: T }
  | { state: "unsupported" }
  | { state: "error" };

/** Classify a failed read: 404/405 from an ApiError means an older Core. */
export function availabilityFromError(error: unknown): { state: "unsupported" } | { state: "error" } {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "ApiError" && (e.status === 404 || e.status === 405)) return { state: "unsupported" };
  return { state: "error" };
}

/** Animation-duration multiplier per mood (export: x1 / x0.7 / x0.4). */
export function moodFactor(mood: Mood): number {
  return mood === "thinking" ? 0.4 : mood === "listening" ? 0.7 : 1;
}
