import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { CHART_TOKENS, FILL_ALPHA, readChartColors, toRgba } from "../src/components/trading/chartColors.ts";

// A stand-in for a 1x1 2D canvas: it "parses" the colours in KNOWN (keeping
// the old fillStyle for anything else, like a real canvas) and paints them.
const KNOWN = { "oklch(0.52 0.17 258)": [28, 101, 200, 255], "oklch(0 0 0 / 0.5)": [0, 0, 0, 128], "#010203": [1, 2, 3, 255] };
function fakeProbe() {
  let style = "#000000";
  let pixel = [0, 0, 0, 0];
  return {
    get fillStyle() { return style; },
    set fillStyle(v) { if (v in KNOWN) style = v; },
    clearRect() { pixel = [0, 0, 0, 0]; },
    fillRect() { pixel = KNOWN[style]; },
    getImageData() { return { data: pixel }; },
  };
}

test("a token colour becomes rgba, at its own or a given opacity", () => {
  const probe = fakeProbe();
  assert.equal(toRgba(probe, "oklch(0.52 0.17 258)"), "rgba(28, 101, 200, 1)");
  assert.equal(toRgba(probe, "oklch(0.52 0.17 258)", 0.1), "rgba(28, 101, 200, 0.1)");
  assert.equal(toRgba(probe, "oklch(0 0 0 / 0.5)"), "rgba(0, 0, 0, 0.502)");
});

test("a colour the probe cannot parse, or no probe, passes through unchanged", () => {
  assert.equal(toRgba(fakeProbe(), "color(display-p3 1 0 0)"), "color(display-p3 1 0 0)");
  assert.equal(toRgba(null, "oklch(0.52 0.17 258)"), "oklch(0.52 0.17 258)");
  assert.equal(toRgba(fakeProbe(), ""), "");
  // A translucent fill that cannot be converted is left out, never drawn opaque.
  assert.equal(toRgba(fakeProbe(), "color(display-p3 1 0 0)", 0.1), "transparent");
  assert.equal(toRgba(null, "oklch(0.52 0.17 258)", 0.1), "transparent");
});

test("every chart role reads its token, and the fill is the translucent chart accent", () => {
  const asked = [];
  const colors = readChartColors((name) => { asked.push(name); return ` value-of(${name}) `; }, null);
  for (const [role, name] of Object.entries(CHART_TOKENS)) assert.equal(colors[role], `value-of(${name})`, role);
  assert.ok(asked.includes("--chart-accent"));
  assert.equal(colors.fill, "transparent");

  const painted = readChartColors((name) => (name === "--chart-accent" ? "oklch(0.52 0.17 258)" : ""), fakeProbe());
  assert.equal(painted.accent, "rgba(28, 101, 200, 1)");
  assert.equal(painted.fill, `rgba(28, 101, 200, ${FILL_ALPHA})`);
});

test("the chart colours redraw from the theme: no hard-coded copies, every token exists in both themes", () => {
  const chart = readFileSync(new URL("../src/components/trading/TradingChart.vue", import.meta.url), "utf8");
  assert.doesNotMatch(chart, /#[0-9a-f]{3,8}\b|\brgba?\(/i);
  assert.match(chart, /themeChartColors\(\)/);
  assert.match(chart, /MutationObserver\(render\)[\s\S]*attributeFilter: \["data-theme"\]/);

  const tokens = readFileSync(new URL("../src/tokens.css", import.meta.url), "utf8");
  const light = tokens.slice(tokens.indexOf(":root {"), tokens.indexOf(":root[data-theme=\"dark\"]"));
  const dark = tokens.slice(tokens.indexOf(":root[data-theme=\"dark\"]"));
  for (const name of Object.values(CHART_TOKENS)) {
    assert.match(light, new RegExp(`${name}:`), `light ${name}`);
    assert.match(dark, new RegExp(`${name}:`), `dark ${name}`);
  }
});
