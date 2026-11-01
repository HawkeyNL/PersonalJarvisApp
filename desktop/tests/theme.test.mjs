import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {currentTheme, initTheme, resolveTheme, setTheme, themeCards} from "../src/theme.ts";

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

test("theme cards: one checked card, and System previews and names what the OS picks", () => {
  for (const dark of [false, true]) {
    const cards = themeCards("system", dark);
    assert.deepEqual(cards.map(c => c.id), ["light", "dark", "system"]);
    assert.deepEqual(cards.filter(c => c.checked).map(c => c.id), ["system"]);
    assert.deepEqual(cards.map(c => c.preview), ["light", "dark", dark ? "dark" : "light"]);
    assert.equal(cards[2].label, `System (now ${dark ? "dark" : "light"})`);
  }
  assert.deepEqual(themeCards("dark", false).filter(c => c.checked).map(c => c.id), ["dark"]);
});

test("theme cards are native radios in one labelled group, wired to setTheme", () => {
  const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");
  const cards = read("components/settings/ThemeCards.vue");
  assert.match(cards, /role="radiogroup" aria-labelledby="theme-heading"/);
  assert.match(cards, /<input class="sr-only" type="radio" name="theme" :value="card\.id" :checked="card\.checked"/);
  assert.match(cards, /\.choice:has\(input:focus-visible\) \{ outline: 2px solid var\(--focus-ring\)/);
  const settings = read("views/Settings.vue");
  assert.match(settings, /<ThemeCards [^>]*:model-value="theme" @update:model-value="pickTheme"/);
  assert.match(settings, /function pickTheme\(t: ThemeChoice\) \{[^}]*setTheme\(t\);/);
});

test("theme card previews use the exact light and dark token values", () => {
  const read = path => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");
  const vars = block => Object.fromEntries([...block.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(([, k, v]) => [k, v.trim()]));
  const block = (css, selector) => css.slice(css.indexOf(`${selector} {`)).split("}")[0];
  const tokens = read("tokens.css");
  const cards = read("components/settings/ThemeCards.vue");
  for (const [theme, selector] of [["light", ":root"], ["dark", ':root[data-theme="dark"]']]) {
    const preview = vars(block(cards, `.preview.${theme}`));
    const source = vars(block(tokens, selector));
    assert.ok(Object.keys(preview).length >= 8, theme);
    for (const [name, value] of Object.entries(preview)) assert.equal(value, source[name], `${theme} ${name}`);
  }
});
