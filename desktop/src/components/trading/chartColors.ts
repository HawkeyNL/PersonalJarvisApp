// Chart.js paints on a <canvas>, which cannot read CSS custom properties, so
// the trading chart reads its colours from tokens.css at draw time and draws
// again when the theme changes. Each value goes through a 1x1 canvas, which
// turns oklch() into plain rgba() in every WebView.

/** Chart role -> design token. */
export const CHART_TOKENS = {
  accent: "--chart-accent",
  up: "--positive-solid",
  down: "--negative-solid",
  ma: "--chart-ma",
  text: "--text-secondary",
  grid: "--separator",
  strong: "--border-strong",
  vol: "--border",
  tipBg: "--bg-raised",
  tipText: "--text-primary",
  tipBorder: "--border",
} as const;

/** Opacity of the area under the line: low enough that volume bars show through. */
export const FILL_ALPHA = 0.1;

export type ChartColors = Record<keyof typeof CHART_TOKENS | "fill", string>;

type Probe = Pick<CanvasRenderingContext2D, "fillStyle" | "clearRect" | "fillRect" | "getImageData">;

/** A CSS colour as rgba(), optionally at another opacity. Returns the value
 *  unchanged when there is no probe or the probe cannot parse it. */
export function toRgba(probe: Probe | null, value: string, alpha?: number): string {
  if (!probe || !value) return value;
  const sentinel = "#010203";
  probe.fillStyle = sentinel;
  probe.fillStyle = value;
  if (probe.fillStyle === sentinel) return value;
  probe.clearRect(0, 0, 1, 1);
  probe.fillRect(0, 0, 1, 1);
  const [r, g, b, a] = probe.getImageData(0, 0, 1, 1).data;
  return `rgba(${r}, ${g}, ${b}, ${alpha ?? Number((a / 255).toFixed(3))})`;
}

/** Every chart colour, from a token reader (getComputedStyle in the app). */
export function readChartColors(token: (name: string) => string, probe: Probe | null): ChartColors {
  const out = {} as ChartColors;
  for (const [role, name] of Object.entries(CHART_TOKENS) as [keyof typeof CHART_TOKENS, string][]) {
    out[role] = toRgba(probe, token(name).trim());
  }
  out.fill = toRgba(probe, token(CHART_TOKENS.accent).trim(), FILL_ALPHA);
  return out;
}

let probe: Probe | null | undefined;

/** The chart colours of the theme on <html> right now. */
export function themeChartColors(): ChartColors {
  if (probe === undefined) probe = document.createElement("canvas").getContext("2d", { willReadFrequently: true });
  const style = getComputedStyle(document.documentElement);
  return readChartColors((name) => style.getPropertyValue(name), probe);
}
