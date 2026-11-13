import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { alertText, createSession, nextAlert, titleWithCount } from "../src/approvalAlerts.ts";

const pairing = (id, device_name = "Pixel") => ({ kind: "pairing", id, device_name });
const unlock = (id) => ({ kind: "unlock", id, device_name: "iPad" });

test("only a request not seen before raises an alert, newest first", () => {
  let state = nextAlert(new Set(), [pairing("a")]);
  assert.equal(state.alert?.id, "a");
  state = nextAlert(state.seen, [pairing("a")]);
  assert.equal(state.alert, null);
  state = nextAlert(state.seen, [pairing("a"), pairing("b"), unlock("c")]);
  assert.deepEqual(state.alert, unlock("c"));
  // The same id in the other list is a different request.
  state = nextAlert(state.seen, [pairing("a"), pairing("b"), unlock("c"), unlock("a")]);
  assert.deepEqual(state.alert, unlock("a"));
  // Only current requests are remembered, so the set stays bounded.
  state = nextAlert(state.seen, []);
  assert.equal(state.alert, null);
  assert.equal(state.seen.size, 0);
});

test("alert text bounds and strips the candidate-supplied name", () => {
  assert.equal(alertText(pairing("a", "Pixel 9")), "New device wants to pair: Pixel 9 (unverified)");
  assert.equal(alertText(pairing("a", "evil‮gnp.exe\u0007")), "New device wants to pair: evilgnp.exe (unverified)");
  assert.equal(alertText(pairing("a", "x".repeat(80))), `New device wants to pair: ${"x".repeat(40)}… (unverified)`);
  assert.equal(alertText(pairing("a", "")), "New device wants to pair: unknown (unverified)");
  // Unlock requests name no device in the banner.
  assert.equal(alertText(unlock("b")), "A device asks to unlock");
});

test("window title carries the waiting count and drops it when none wait", () => {
  assert.equal(titleWithCount("Jarvis", 1), "(1) Jarvis");
  assert.equal(titleWithCount("(1) Jarvis", 3), "(3) Jarvis");
  assert.equal(titleWithCount("(3) Jarvis", 0), "Jarvis");
  assert.equal(titleWithCount("Jarvis", 0), "Jarvis");
});

test("a read from before a sign-out is dropped and raises no banner", () => {
  const session = createSession();
  const token = session.token();
  let seen = nextAlert(new Set(), [pairing("a")]).seen;
  // Sign-out: the lists are emptied, then the old read answers.
  session.end();
  let list = [];
  if (session.isCurrent(token)) list = [pairing("a")];
  assert.deepEqual(list, []);
  const state = nextAlert(seen, list);
  assert.equal(state.alert, null);
  assert.equal(state.seen.size, 0);
  // A read started in the new session counts.
  assert.equal(session.isCurrent(session.token()), true);
});

test("leaving the app clears both approval lists and entering reads at once", () => {
  const app = readFileSync(new URL("../src/App.vue", import.meta.url), "utf8");
  const watcher = app.slice(app.indexOf("watch(currentShellMode"), app.indexOf("});", app.indexOf("watch(currentShellMode")));
  for (const call of ["clearUnlocks()", "clearPairing()", "refreshPending()", "restartPairingPolling()"]) assert.ok(watcher.includes(call), call);
});
