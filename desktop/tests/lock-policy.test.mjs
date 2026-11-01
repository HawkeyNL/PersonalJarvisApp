import assert from "node:assert/strict";
import test from "node:test";

import { isSetUp, lockModeFor, shouldLockOnLaunch } from "../src/lockPolicy.ts";

test("each desktop gets its lock mode; Windows never uses Windows Hello", () => {
  assert.equal(lockModeFor("macos"), "biometric");
  assert.equal(lockModeFor("windows"), "password");
  assert.equal(lockModeFor("linux"), "off");
  assert.equal(lockModeFor("unknown"), "off");
});

test("a saved Home Node address alone is not set up", () => {
  // Fresh install, or one with only leftover metadata from an older install.
  assert.equal(isSetUp({ device_id: null, authenticated: false, has_key: false }), false);
  // Key created for a pairing request that was never approved.
  assert.equal(isSetUp({ device_id: null, authenticated: false, has_key: true }), false);
  // Leftover device id whose key is gone cannot sign in.
  assert.equal(isSetUp({ device_id: "d1", authenticated: false, has_key: false }), false);
  assert.equal(isSetUp({ device_id: "d1", authenticated: false, has_key: true }), true);
  assert.equal(isSetUp({ device_id: "d1", authenticated: true, has_key: true }), true);
});

test("a fresh install goes to first run instead of the lock screen", () => {
  for (const mode of ["biometric", "password"]) {
    assert.equal(shouldLockOnLaunch({ mode, enabled: true, alreadyUnlocked: false, setUp: false }), false, mode);
  }
});

test("a set-up device locks at launch", () => {
  const base = { enabled: true, alreadyUnlocked: false, setUp: true };
  assert.equal(shouldLockOnLaunch({ ...base, mode: "biometric" }), true);
  assert.equal(shouldLockOnLaunch({ ...base, mode: "password" }), true);
  assert.equal(shouldLockOnLaunch({ ...base, mode: "off" }), false);
  // Already unlocked in this window session (a reload) stays unlocked.
  assert.equal(shouldLockOnLaunch({ ...base, mode: "biometric", alreadyUnlocked: true }), false);
  assert.equal(shouldLockOnLaunch({ ...base, mode: "password", alreadyUnlocked: true }), false);
});

test("only the macOS lock can be switched off", () => {
  const base = { enabled: false, alreadyUnlocked: false, setUp: true };
  assert.equal(shouldLockOnLaunch({ ...base, mode: "biometric" }), false);
  assert.equal(shouldLockOnLaunch({ ...base, mode: "password" }), true);
});
