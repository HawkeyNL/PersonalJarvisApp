// Accent colour theming. Green is the Jarvis default; the user can switch the
// accent from Settings. The choice is persisted and re-applied on launch.
// The preset names are unchanged from the previous palette, so a saved choice
// keeps working and maps onto the new hue of the same name.
export type Accent = "green" | "cyan" | "amber" | "violet";

/** [accent, light companion] per preset. */
export const PRESETS: Record<Accent, [string, string]> = {
  green: ["#2ee6a2", "#9bffd6"],
  cyan: ["#38bdf8", "#a5e3fc"],
  amber: ["#fbbf24", "#fde08a"],
  violet: ["#a78bfa", "#d6cbfd"],
};

export const ACCENTS = Object.keys(PRESETS) as Accent[];

const KEY = "jarvis.accent";

export function currentAccent(): Accent {
  const v = localStorage.getItem(KEY) as Accent | null;
  return v && v in PRESETS ? v : "green";
}

/** "#2ee6a2" -> "46, 230, 162", for `rgba(var(--accent-rgb), .3)`. */
function rgbTriplet(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
}

export function applyAccent(a: Accent): void {
  const [c1, c2] = PRESETS[a] ?? PRESETS.green;
  const root = document.documentElement;
  root.style.setProperty("--accent", c1);
  root.style.setProperty("--accent-2", c2);
  root.style.setProperty("--accent-rgb", rgbTriplet(c1));
  localStorage.setItem(KEY, a);
}

/** Apply the saved accent on app start. */
export function initAccent(): void {
  applyAccent(currentAccent());
}

/** Parse the live `--accent-2` value into [r,g,b] for canvas drawing. */
export function accentRgb(): [number, number, number] {
  const raw = getComputedStyle(document.documentElement)
    .getPropertyValue("--accent-2")
    .trim();
  const m = /^#?([0-9a-f]{6})$/i.exec(raw);
  if (!m) return [155, 255, 214];
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
