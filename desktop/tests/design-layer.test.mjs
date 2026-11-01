import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
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

const srcDir = fileURLToPath(new URL("../src/", import.meta.url));
const sources = readdirSync(srcDir, { recursive: true }).filter((f) => /\.(css|vue)$/.test(f));

test("desktop no longer ships or references Exo 2 or Rajdhani", () => {
  assert.ok(sources.length > 10);
  for (const f of sources) assert.doesNotMatch(readFileSync(join(srcDir, f), "utf8"), /\b(exo 2|exo2|rajdhani)\b/i, f);
  const fonts = readdirSync(new URL("../src/assets/fonts/", import.meta.url));
  assert.ok(fonts.includes("OFL.txt"));
  assert.deepEqual(fonts.filter((f) => /exo|rajdhani/i.test(f)), []);
});

test("bare button rules stay neutral: no global fill", () => {
  for (const [name, css] of Object.entries(sheets)) {
    for (const { selectors, body } of rules(css)) {
      // Any selector whose subject is a bare button (pseudo-classes allowed): button, button:hover:not(...), .app button, :where(button).
      const bare = (s) => /(^|[\s>+~(])button(:[\w-]+(\([^)]*\))?)*\)?$/.test(s.replace(/^:where\((.*)\)$/, "$1"));
      if (!selectors.some(bare)) continue;
      for (const [, value] of body.matchAll(/background(?:-color|-image)?\s*:\s*([^;]+)/g)) {
        const bg = value.replace(/!important/, "").trim();
        assert.ok(/^(transparent|none)$/.test(bg), `${name}: ${selectors.join(", ")} sets background ${bg}`);
      }
      assert.doesNotMatch(body, /--accent-solid|--text-on-accent/, `${name}: ${selectors.join(", ")}`);
    }
  }
});

test("component layer uses token variables, not colour literals", () => {
  const css = stripComments(sheets["components.css"]);
  assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b|\b(rgba?|hsla?|oklch|oklab|lab|lch)\(/i);
});
