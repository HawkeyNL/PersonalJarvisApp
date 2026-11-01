import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");
const count = (source, pattern) => source.match(pattern)?.length ?? 0;

// Every view the router imports.
const views = [...read("router/index.ts").matchAll(/from "\.\.\/views\/(\w+\.vue)"/g)].map(([, file]) => file);

// h1s a template renders: its own, plus one per page frame (PageShell renders it; NodePage wraps PageShell).
const h1s = (source) => count(source, /<h1[\s>]/g) + count(source, /<(PageShell|NodePage)[\s>]/g);

test("the router imports every view file", () => {
  assert.ok(views.length >= 16, views.join(", "));
});

test("the old HUD chrome is gone", () => {
  assert.equal(existsSync(new URL("../src/components/jv/JvBackdrop.vue", import.meta.url)), false);
  assert.equal(existsSync(new URL("../src/components/jv/JvTopBar.vue", import.meta.url)), false);
  for (const file of [...views.map((v) => `views/${v}`), "App.vue", "components/jv/NodePage.vue"]) {
    assert.doesNotMatch(read(file), /JvBackdrop|JvTopBar/, file);
  }
});

test("the page frames render exactly one h1", () => {
  assert.equal(count(read("components/PageShell.vue"), /<h1[\s>]/g), 1);
  const node = read("components/jv/NodePage.vue");
  assert.equal(count(node, /<h1[\s>]/g), 0);
  assert.equal(count(node, /<PageShell[\s>]/g), 1);
});

test("each routed view renders exactly one h1", () => {
  for (const view of views) assert.equal(h1s(read(`views/${view}`)), 1, view);
});
