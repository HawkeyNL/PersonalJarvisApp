import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import { dayOf, groupConversations } from "../src/chatList.ts";

// Local times, so the day boundaries match the machine running the tests.
const NOW = new Date(2026, 9, 4, 9, 30).getTime();
const at = (day, hour, minute = 0) => new Date(2026, 9, day, hour, minute).toISOString();
const LIST = [
  { id: "a", title: "Futures morning", updated_at: at(4, 9, 10) },
  { id: "b", title: "Groceries for Saturday", updated_at: at(4, 0, 0) },
  { id: "c", title: "Weekend trip", updated_at: at(3, 23, 59) },
  { id: "d", title: "Stallo VM for MT5", updated_at: at(3, 0, 0) },
  { id: "e", title: "CPI explained", updated_at: at(2, 23, 59) },
  { id: "f", title: "", updated_at: "not a date" },
];
const shape = (groups) => groups.map((g) => [g.label, g.items.map((c) => c.id)]);

test("conversations group by local day, keeping their order", () => {
  assert.deepEqual(shape(groupConversations(LIST, "", NOW)), [
    ["Today", ["a", "b"]],
    ["Yesterday", ["c", "d"]],
    ["Earlier", ["e", "f"]],
  ]);
  assert.deepEqual(groupConversations([], "", NOW), []);
});

test("day of a time: unparseable is Earlier, a clock ahead is Today", () => {
  assert.equal(dayOf("garbage", NOW), "Earlier");
  assert.equal(dayOf(at(5, 8), NOW), "Today");
  assert.equal(dayOf(at(1, 12), NOW), "Earlier");
});

test("search matches titles case-insensitively and drops empty groups", () => {
  assert.deepEqual(shape(groupConversations(LIST, "  WEEKEND ", NOW)), [["Yesterday", ["c"]]]);
  assert.deepEqual(shape(groupConversations(LIST, "or", NOW)), [["Today", ["a", "b"]], ["Yesterday", ["d"]]]);
  assert.deepEqual(groupConversations(LIST, "nothing like this", NOW), []);
});

// --- The orb hub is retired --------------------------------------------------
const src = fileURLToPath(new URL("../src/", import.meta.url));
const read = (path) => readFileSync(join(src, path), "utf8");
const sources = readdirSync(src, { recursive: true }).filter((f) => /\.(ts|vue)$/.test(f));

test("the hub components and the conversations page are gone and nothing imports them", () => {
  for (const file of ["components/jv/JvModuleCard.vue", "components/jv/JvCommandBar.vue", "views/Conversations.vue"]) {
    assert.equal(existsSync(join(src, file)), false, file);
  }
  for (const file of sources) {
    assert.doesNotMatch(read(file), /JvModuleCard|JvCommandBar|views\/Conversations\.vue/, file);
  }
});

test("the home has no orb, hub glow or taglines", () => {
  const home = read("views/Home.vue");
  assert.doesNotMatch(home, /JvOrb|useFitScale|rgba\(var\(--accent-rgb\)|tagline|IDEAS COMPOUND/i);
  assert.doesNotMatch(read("components/JarvisConsole.vue"), /rgba\(var\(--accent-rgb\)|text-shadow/);
});

test("the message log is not live; a status line announces replies", () => {
  const chat = read("components/JarvisConsole.vue");
  assert.match(chat, /role="log" aria-live="off"/);
  assert.match(chat, /role="status">\{\{ announcement \}\}/);
  assert.match(chat, /aria-keyshortcuts="Control\+K Meta\+K"/);
});
