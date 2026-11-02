import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import {createMemoryHistory, createRouter} from "vue-router";

import {PAGES, buildRoutes} from "../src/navigation.ts";
import {latestWins, shellGuard, shellMode} from "../src/shellMode.ts";

const VIEWS = ["home", "agents", "integrations", "health", "memory", "trading", "desk", "stocks", "stock", "positions", "memecoin", "tasks", "context", "settings", "soon"];
const stub = name => ({name, render: () => null});

function guardedRouter(read) {
  const router = createRouter({history: createMemoryHistory(), routes: buildRoutes(Object.fromEntries(VIEWS.map(v => [v, stub(v)])))});
  router.beforeEach(shellGuard(read));
  return router;
}
async function landsOn(read, path) {
  const router = guardedRouter(read);
  await router.push(path);
  return router.currentRoute.value.path;
}

test("the app shell needs a configured Home Node and a signed-in session", () => {
  assert.equal(shellMode({configured: false, authenticated: false}), "setup");
  // A leftover session without a Home Node is still setup.
  assert.equal(shellMode({configured: false, authenticated: true}), "setup");
  assert.equal(shellMode({configured: true, authenticated: false}), "signin");
  assert.equal(shellMode({configured: true, authenticated: true}), "app");
});

test("unknown or loading state fails closed", () => {
  assert.equal(shellMode(null), "setup");
  assert.equal(shellMode({configured: null, authenticated: true}), "setup");
  assert.equal(shellMode({configured: true, authenticated: null}), "signin");
});

test("before sign-in every app route leads to the setup and sign-in screen", async () => {
  const states = [
    {configured: false, authenticated: false},
    {configured: true, authenticated: false},
    {configured: null, authenticated: null},
  ];
  for (const state of states) {
    for (const page of PAGES) {
      const path = page.path.replace(":conid(\\d+)", "265598");
      assert.equal(await landsOn(async () => state, path), "/", `${JSON.stringify(state)} ${path}`);
    }
    // Old paths, unknown paths and deep links with a query go there too.
    for (const path of ["/status", "/nope", "/settings?section=Updates"]) {
      assert.equal(await landsOn(async () => state, path), "/", path);
    }
  }
});

test("an unreadable session counts as signed out", async () => {
  assert.equal(await landsOn(async () => { throw new Error("ipc down"); }, "/settings"), "/");
});

test("a signed-in session reaches every app route", async () => {
  const signedIn = async () => ({configured: true, authenticated: true});
  assert.equal(await landsOn(signedIn, "/settings"), "/settings");
  assert.equal(await landsOn(signedIn, "/trading/stocks/265598"), "/trading/stocks/265598");
  assert.equal(await landsOn(signedIn, "/status"), "/health");
});

test("the setup screen itself never waits on the session read", async () => {
  let reads = 0;
  assert.equal(await landsOn(async () => { reads++; return null; }, "/"), "/");
  assert.equal(reads, 0);
});

test("a session read that resolves after sign-out cannot sign the shell back in", () => {
  let value = null;
  const writes = latestWins(v => { value = v; });
  const slowRead = writes.begin(); // started while still signed in
  const signOut = writes.begin();
  writes.apply(signOut, false);
  writes.apply(slowRead, true); // resolves late
  assert.equal(value, false);
  const freshRead = writes.begin();
  writes.apply(freshRead, true);
  assert.equal(value, true);
});

test("the shell renders navigation only in app mode and the router uses the guard", async () => {
  const app = await readFile(new URL("../src/App.vue", import.meta.url), "utf8");
  assert.match(app, /<AppSidebar v-if="mode === 'app'" \/>/);
  assert.match(app, /if \(mode\.value !== "app"\) return;/);
  // The launch lock decides first; leaving the app closes the chat and goes back.
  const lock = await readFile(new URL("../src/lock.ts", import.meta.url), "utf8");
  assert.match(lock, /lockDecided\.value \? currentShellMode\.value : "signin"/);
  assert.match(app, /previous === "app"/);
  // Core status and the update link live in the sidebar, which only app mode renders.
  const sidebar = await readFile(new URL("../src/components/AppSidebar.vue", import.meta.url), "utf8");
  assert.match(sidebar, /<RouterLink v-if="updateLabel" v-slot="\{ href, navigate \}" to="\/settings" custom>/);
  // Home is the screen shown before sign-in: it has no Settings link.
  const home = await readFile(new URL("../src/views/Home.vue", import.meta.url), "utf8");
  assert.doesNotMatch(home, /to="'?\/settings/);
  const router = await readFile(new URL("../src/router/index.ts", import.meta.url), "utf8");
  assert.match(router, /router\.beforeEach\(shellGuard\(/);
});
