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
  assert.ok(views.length >= 15, views.join(", "));
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

test("node pages: a section list and one card, no orb stage", () => {
  const node = read("components/jv/NodePage.vue");
  assert.doesNotMatch(node, /stage|wires|sats|useFitScale|accent-rgb/);
  assert.match(node, /<nav class="np-sections"/);
  assert.match(node, /:aria-current="item\.id === selected \? 'true' : undefined"/);
  for (const view of ["Agents.vue", "Integrations.vue", "Health.vue"]) assert.match(read(`views/${view}`), /<NodePage /, view);
  // Memory has no store to browse: a plain empty state, no section list.
  const memory = read("views/Memory.vue");
  assert.doesNotMatch(memory, /NodePage/);
  assert.match(memory, /class="card empty"/);
});

test("the IBKR link on Integrations uses the compact trading status", () => {
  assert.match(read("views/Integrations.vue"), /<TradingStatus [^>]*:state="ibkr"[^>]*\bcompact\b/);
});

test("account administration leaves revoking to the Devices page", () => {
  const admin = read("components/AccountAdministration.vue");
  assert.doesNotMatch(admin, /revokeDevice|listDevices/);
  assert.match(admin, /<RouterLink to="\/devices">Devices<\/RouterLink>/);
});

test("the page title row sits on the text baseline", () => {
  assert.match(read("components/PageShell.vue"), /\.title \{ display: flex; align-items: baseline;/);
});

test("onboarding is a step card; setup and sign-in logic stay as they were", () => {
  const home = read("views/Home.vue");
  assert.match(home, /const STEPS = \["Connect", "Sign in", "Confirm"\] as const;/);
  assert.match(home, /:aria-current="i === step \? 'step' : undefined"/);
  assert.match(home, /<form v-if="step === 0" class="step" @submit\.prevent="saveHomeNode">/);
  assert.match(home, /<form v-else-if="step === 1" class="step" @submit\.prevent="submitAccount">/);
});
