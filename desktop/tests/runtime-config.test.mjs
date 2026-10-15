import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  AUTOMATIC_UPDATE_DELAY_MS,
  shouldScheduleAutomaticUpdateCheck,
  updateErrorMessage,
  updateSessionActive,
} from "../src/updatePolicy.js";

test("update errors show the native typed cause instead of a blanket 'unreachable'", () => {
  assert.equal(updateErrorMessage("Home Node sent an invalid update capability", "fallback"), "Home Node sent an invalid update capability");
  assert.equal(updateErrorMessage("Home Node did not respond in time", "fallback"), "Home Node did not respond in time");
  for (const odd of [undefined, null, "", "   ", new Error("x"), { message: "x" }, "x".repeat(201)]) {
    assert.equal(updateErrorMessage(odd, "fallback"), "fallback");
  }
});

test("automatic update restart waits for reply, speech, mic, draft and voice checks", () => {
  const idle = { reply: false, console: false, speech: "idle", voiceCheck: false };
  assert.equal(updateSessionActive(idle), false);
  assert.equal(updateSessionActive({ ...idle, speech: "failed" }), false);
  assert.equal(updateSessionActive({ ...idle, speech: "speaking" }), true);
  assert.equal(updateSessionActive({ ...idle, reply: true }), true);
  assert.equal(updateSessionActive({ ...idle, console: true }), true);
  assert.equal(updateSessionActive({ ...idle, voiceCheck: true }), true);
});

test("automatic update check runs once only after authentication", () => {
  assert.equal(shouldScheduleAutomaticUpdateCheck(false, true, false), false);
  assert.equal(shouldScheduleAutomaticUpdateCheck(true, false, false), false);
  assert.equal(shouldScheduleAutomaticUpdateCheck(true, true, false), true);
  assert.equal(shouldScheduleAutomaticUpdateCheck(true, true, true), false);
  assert.ok(AUTOMATIC_UPDATE_DELAY_MS >= 1000);
});

test("desktop API has no build-time or implicit localhost endpoint", async () => {
  const source = await readFile(new URL("../src/api.ts", import.meta.url), "utf8");
  assert.doesNotMatch(source, /VITE_JARVIS_API_BASE/);
  assert.doesNotMatch(source, /localhost:8080/);
  assert.match(source, /homeNodeOrigin\(\)/);
});

test("production capabilities exclude development loopback HTTP", async () => {
  const production = await readFile(new URL("../src-tauri/capabilities/default.json", import.meta.url), "utf8");
  const development = await readFile(new URL("../src-tauri/capabilities/development.json", import.meta.url), "utf8");
  assert.doesNotMatch(production, /localhost|127\.0\.0\.1|http:\/\//);
  assert.match(development, /http:\/\/localhost:8080/);
});

test("desktop webview ships a strict CSP without remote sources", async () => {
  const config = JSON.parse(await readFile(new URL("../src-tauri/tauri.conf.json", import.meta.url), "utf8"));
  const csp = config.app.security.csp;
  assert.equal(typeof csp, "string");
  const directives = Object.fromEntries(csp.split(";").map((d) => d.trim().split(/\s+/)).map(([k, ...v]) => [k, v]));
  assert.deepEqual(directives["default-src"], ["'self'"]);
  assert.deepEqual(directives["img-src"], ["'self'", "data:", "blob:"]);
  assert.deepEqual(directives["connect-src"], ["'self'", "ipc:", "http://ipc.localhost"]);
  assert.deepEqual(directives["object-src"], ["'none'"]);
  for (const sources of Object.values(directives)) {
    assert.ok(!sources.some((s) => s === "*" || s === "https:" || s === "http:" || s === "'unsafe-eval'" || s === "'unsafe-inline'"), csp);
  }
});

test("session bearer is never exposed through the desktop JavaScript API", async () => {
  const api = await readFile(new URL("../src/api.ts", import.meta.url), "utf8");
  const auth = await readFile(new URL("../src/auth.ts", import.meta.url), "utf8");
  const native = await readFile(new URL("../src-tauri/src/lib.rs", import.meta.url), "utf8");
  assert.doesNotMatch(api, /Authorization|Bearer/);
  assert.doesNotMatch(auth, /auth_session|auth_save|token:\s*string/);
  assert.doesNotMatch(native, /generate_handler![\s\S]*auth_session/);
  assert.match(native, /async fn auth_login\(/);
  assert.match(native, /async fn auth_request/);
});

test("no exposed command signs a caller-supplied login challenge", async () => {
  const native = await readFile(new URL("../src-tauri/src/lib.rs", import.meta.url), "utf8");
  const handlers = native.match(/generate_handler!\[([\s\S]*?)\]/)?.[1];
  assert.ok(handlers);
  assert.match(handlers, /\bauth_login,/);
  assert.doesNotMatch(handlers, /auth_sign_login|auth_complete_login/);
  const auth = await readFile(new URL("../src/auth.ts", import.meta.url), "utf8");
  assert.doesNotMatch(auth, /\/v1\/auth\/challenge|auth_sign_login|auth_complete_login/);
});

test("approvals are signed natively by id, never from webview-supplied data", async () => {
  const native = await readFile(new URL("../src-tauri/src/lib.rs", import.meta.url), "utf8");
  const handlers = native.match(/generate_handler!\[([\s\S]*?)\]/)?.[1];
  assert.ok(handlers);
  for (const command of ["auth_approve_pairing", "auth_approve_unlock", "auth_approve_agent"]) {
    assert.match(handlers, new RegExp(`approvals::${command},`));
  }
  assert.deepEqual(handlers.match(/auth_sign_\w+/g), ["auth_sign_account_approval"]);
  const approvals = await readFile(new URL("../src-tauri/src/approvals.rs", import.meta.url), "utf8");
  const commands = approvals.match(/async fn auth_approve_\w+\([^)]*\)/g)
    ?.map((command) => command.replace(/\s+/g, " ").replace("( ", "(").replace(/,? \)$/, ")"));
  // Only ids cross IPC; unlock also takes the owner's number pick, never signed bytes.
  assert.deepEqual(commands, [
    "async fn auth_approve_pairing(app: AppHandle, request_id: String)",
    "async fn auth_approve_unlock(app: AppHandle, request_id: String, choice: u8)",
    "async fn auth_approve_agent(app: AppHandle, pending_id: String)",
  ]);
  assert.match(approvals, /async fn auth_unlock_choices\(\s*app: AppHandle,\s*request_id: String,?\s*\)/);
  assert.match(native, /fn biometric_unlock\(allow_password: bool\)/);
  for (const file of ["pairingApprovals.ts", "unlockApprovals.ts", "agentApprovals.ts"]) {
    const web = await readFile(new URL(`../src/${file}`, import.meta.url), "utf8");
    assert.doesNotMatch(web, /auth_sign_|\{ signature|\/approve\b/, file);
  }
});

test("webview listens to the native update status event and reports session activity", async () => {
  const web = await readFile(new URL("../src/updates.ts", import.meta.url), "utf8");
  const native = await readFile(new URL("../src-tauri/src/app_updates.rs", import.meta.url), "utf8");
  const event = native.match(/STATUS_EVENT: &str = "([^"]+)"/)?.[1];
  assert.ok(event);
  assert.match(web, new RegExp(`listen<NativeUpdateStatus>\\("${event}"`));
  assert.match(web, /invoke\("app_update_set_session_active", \{ active \}\)/);
  assert.match(native, /fn app_update_set_session_active\(\s*app: AppHandle,\s*active: bool,/);
});
