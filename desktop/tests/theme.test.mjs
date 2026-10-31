import test from "node:test";
import assert from "node:assert/strict";
import {currentTheme, initTheme, resolveTheme, setTheme} from "../src/theme.ts";

function fakeEnv({dark = false, saved = null} = {}) {
  const store = new Map(saved ? [["jarvis.theme", saved]] : []);
  const listeners = [];
  const classes = new Set();
  const root = {dataset: {}, offsetHeight: 0, classList: {add: c => classes.add(c), remove: c => classes.delete(c)}};
  const mql = {get matches() { return env.dark; }, addEventListener: (_, fn) => listeners.push(fn)};
  const env = {
    dark,
    classes,
    localStorage: {getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k), has: k => store.has(k)},
    matchMedia: () => mql,
    document: {documentElement: root},
    requestAnimationFrame: fn => fn(),
    osSwitch(next) { env.dark = next; listeners.forEach(fn => fn()); },
  };
  return env;
}
const theme = env => env.document.documentElement.dataset.theme;

test("resolveTheme maps system onto the OS preference", () => {
  assert.equal(resolveTheme("system", true), "dark");
  assert.equal(resolveTheme("system", false), "light");
  assert.equal(resolveTheme("light", true), "light");
  assert.equal(resolveTheme("dark", false), "dark");
});

test("default is system and unknown saved values fall back to it", () => {
  assert.equal(currentTheme(fakeEnv()), "system");
  assert.equal(currentTheme(fakeEnv({saved: "green"})), "system");
  const env = fakeEnv({dark: true});
  env.localStorage.setItem("jarvis.accent", "green");
  initTheme(env);
  assert.equal(theme(env), "dark");
  assert.equal(env.localStorage.has("jarvis.accent"), false);
});

test("system follows live OS switches; an explicit choice does not", () => {
  const env = fakeEnv();
  initTheme(env);
  assert.equal(theme(env), "light");
  env.osSwitch(true);
  assert.equal(theme(env), "dark");
  setTheme("light", env);
  env.osSwitch(true);
  assert.equal(theme(env), "light");
  setTheme("system", env);
  assert.equal(theme(env), "dark");
});

test("choice persists across launches and transitions are restored after the swap", () => {
  const first = fakeEnv();
  initTheme(first);
  setTheme("dark", first);
  assert.equal(first.localStorage.getItem("jarvis.theme"), "dark");
  assert.equal(first.classes.has("no-tx"), false);
  const next = fakeEnv({saved: first.localStorage.getItem("jarvis.theme")});
  initTheme(next);
  assert.equal(theme(next), "dark");
});
