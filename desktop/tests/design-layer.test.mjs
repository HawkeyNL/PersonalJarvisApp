import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");
const sheets = { "styles.css": read("styles.css"), "components.css": read("components.css") };
const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, "");

// Top-level rules only; nested @media blocks are flattened by matching innermost { }.
function rules(css) {
  return [...stripComments(css).matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, sel, body]) => ({
    selectors: sel.split(",").map((s) => s.trim()),
    body,
  }));
}

test("desktop no longer ships or references Exo 2 or Rajdhani", () => {
  for (const [name, css] of Object.entries(sheets)) assert.doesNotMatch(css, /exo|rajdhani/i, name);
  const fonts = readdirSync(new URL("../src/assets/fonts/", import.meta.url));
  assert.ok(fonts.includes("OFL.txt"));
  assert.deepEqual(fonts.filter((f) => /exo|rajdhani/i.test(f)), []);
});

test("bare button rules stay neutral: no global fill", () => {
  for (const [name, css] of Object.entries(sheets)) {
    for (const { selectors, body } of rules(css)) {
      if (!selectors.some((s) => /^button(:[\w-]+)*$/.test(s))) continue;
      const bg = body.match(/background(?:-color)?\s*:\s*([^;]+)/)?.[1].trim();
      assert.ok(!bg || /^(transparent|none)$/.test(bg), `${name}: ${selectors.join(", ")} sets background ${bg}`);
      assert.doesNotMatch(body, /--accent-solid|--text-on-accent/, `${name}: ${selectors.join(", ")}`);
    }
  }
});

test("component layer uses token variables, not colour literals", () => {
  const css = stripComments(sheets["components.css"]);
  assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b|\b(rgba?|hsla?|oklch|oklab|lab|lch)\(/i);
});
