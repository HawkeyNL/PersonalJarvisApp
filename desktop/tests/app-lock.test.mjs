import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { askedIn, replyIsHere } from "../src/chatList.ts";
import { acceptsShortcuts, composerFocus } from "../src/lockPolicy.ts";

const read = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");
const AUTH = ["checking", "in", "uit", "wachten", "fout"];

test("global shortcuts are ignored while locked, on every shell", () => {
  for (const mode of ["app", "signin", "setup"]) assert.equal(acceptsShortcuts(mode, true), false, mode);
  assert.equal(acceptsShortcuts("app", false), true);
  assert.equal(acceptsShortcuts("signin", false), false);
  assert.equal(acceptsShortcuts("setup", false), false);
});

test("no queued composer focus survives a lock", () => {
  for (const auth of AUTH) {
    for (const chatReady of [true, false]) {
      assert.equal(composerFocus({ locked: true, auth, chatReady }), "drop", `${auth} ${chatReady}`);
    }
  }
});

test("a focus request made while sign-in fails or waits is dropped, not kept for later", () => {
  for (const auth of ["uit", "wachten", "fout"]) {
    assert.equal(composerFocus({ locked: false, auth, chatReady: false }), "drop", auth);
  }
  assert.equal(composerFocus({ locked: false, auth: "checking", chatReady: false }), "wait");
  assert.equal(composerFocus({ locked: false, auth: "in", chatReady: false }), "wait");
  assert.equal(composerFocus({ locked: false, auth: "in", chatReady: true }), "focus");
});

test("App.vue: the whole app is inert while locked; only the lock screen is outside it", () => {
  const app = read("App.vue");
  const template = app.slice(app.indexOf("<template>"), app.lastIndexOf("</template>"));
  const open = template.indexOf('<div class="app" :inert="locked">');
  assert.ok(open > 0, "the app root is inert while locked");
  const close = template.lastIndexOf("</div>");
  const inside = template.slice(open, close);
  for (const part of ["<RouterView", "<AppSidebar", "<UnlockApprovals", "<PairingApprovals", 'class="skip"']) {
    assert.ok(inside.includes(part), part);
  }
  assert.ok(template.slice(close).includes('<AppLock v-if="locked" />'), "the lock screen is outside the inert app");
  assert.equal(template.match(/<AppLock/g).length, 1);
  // The keydown handler asks the lock first.
  assert.match(app, /if \(!acceptsShortcuts\(mode\.value, locked\.value\)\) return;/);
});

test("the lock screen is a labelled modal dialog that takes focus", () => {
  const lock = read("components/AppLock.vue");
  assert.match(lock, /role="dialog" aria-modal="true" aria-labelledby="lock-title" tabindex="-1"/);
  assert.match(lock, /onMounted\(\(\) => dialog\.value\?\.focus\(\)\)/);
});

test("behind the lock no message is sent and the mic stays off", () => {
  assert.match(read("assistant.ts"), /if \(!t \|\| thinking\.value \|\| locked\.value\) return;/);
  assert.match(read("voicewake.ts"), /if \(verifying \|\| locked\.value\) return;/);
  const chat = read("components/JarvisConsole.vue");
  assert.match(chat, /if \(!locked\.value && !mic\.listening\.value\) mic\.toggle\(\);/);
  assert.match(chat, /watch\(\(\) => locked\.value && mic\.listening\.value, \(both\) => \{ if \(both\) mic\.stop\(\); \}/);
  // Home drops a pending request through the same decision.
  assert.match(read("views/Home.vue"), /composerFocus\(\{ locked: isLocked, auth: state, chatReady: !!chat \}\)/);
});

test("a reply is announced only in the conversation it was asked in", () => {
  const sent = [{ id: 1, role: "user" }, { id: 2, role: "jarvis" }, { id: 3, role: "user" }];
  // Asked in a known conversation.
  const inA = askedIn("a", sent);
  assert.deepEqual(inA, { conversation: "a", message: 3 });
  assert.equal(replyIsHere(inA, "a", []), true);
  assert.equal(replyIsHere(inA, "b", sent), false);
  // A new conversation: Core's id keeps the asking message on screen.
  const fresh = askedIn(null, [{ id: 7, role: "user" }]);
  assert.equal(replyIsHere(fresh, "new-id", [{ id: 7 }, { id: 8 }]), true);
  assert.equal(replyIsHere(fresh, null, [{ id: 7 }, { id: 8 }]), true);
  // Switching to another thread before Core assigns the id replaces the messages.
  assert.equal(replyIsHere(fresh, "other", [{ id: 20 }, { id: 21 }]), false);
  assert.equal(replyIsHere(fresh, null, []), false);
  // Nothing asked: nothing to announce.
  assert.equal(replyIsHere(askedIn(null, []), null, []), false);
});
