import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { APPROVER_PLATFORMS, deviceRows, platformLabel, revokeFailureText } from "../src/devices.ts";

const NOW = Date.parse("2026-11-01T12:00:00Z");
const linked = (hours) => Math.floor((NOW - hours * 3_600_000) / 1000);
const mac = { id: "m1", name: "MacBook", platform: "macos", status: "active", created_at: linked(3) };
const phone = { id: "p1", name: "iPhone", platform: "ios", status: "active", created_at: linked(30) };
const win = { id: "w1", name: "Desktop", platform: "windows", status: "active" };

test("rows map name, platform, linked time, status and this device", () => {
  const [m, p, w] = deviceRows([mac, phone, win], "m1", true, NOW);
  assert.deepEqual(
    [m.name, m.platform, m.icon, m.linked, m.status, m.isThis],
    ["MacBook", "macOS", "monitor", "3h ago", "Active", true],
  );
  assert.deepEqual([p.platform, p.icon, p.linked, p.isThis], ["iOS", "phone", "1d ago", false]);
  assert.equal(w.linked, "");
  assert.equal(platformLabel("plan9"), "plan9");
  assert.equal(deviceRows([{ ...win, status: "revoked" }], null, true, NOW)[0].status, "revoked");
});

test("only a Mac with a known own id revokes, never itself or an inactive device", () => {
  const rows = (thisId, signer, list = [mac, phone, win]) =>
    Object.fromEntries(deviceRows(list, thisId, signer, NOW).map((r) => [r.id, r.canRevoke]));
  assert.deepEqual(rows("m1", true), { m1: false, p1: true, w1: true });
  assert.deepEqual(rows("w1", false), { m1: false, p1: false, w1: false });
  assert.deepEqual(rows(null, true), { m1: false, p1: false, w1: false });
  assert.equal(rows("m1", true, [mac, phone, { ...win, status: "revoked" }]).w1, false);
});

test("last-approver guard mirrors Core: the only active iOS, Android or macOS device", () => {
  assert.deepEqual(APPROVER_PLATFORMS, ["ios", "android", "macos"]);
  const [m, w] = deviceRows([mac, win], "w1", true, NOW);
  assert.deepEqual([m.lastApprover, m.canRevoke, w.lastApprover], [true, false, false]);
  const both = deviceRows([mac, phone, win], "m1", true, NOW);
  assert.deepEqual(both.map((r) => r.lastApprover), [false, false, false]);
  // A revoked approver does not count as the second one.
  const [, lone] = deviceRows([{ ...mac, status: "revoked" }, phone], "x", true, NOW);
  assert.equal(lone.lastApprover, true);
});

test("a 409 from Core explains the last approver; other failures stay generic", () => {
  assert.match(revokeFailureText({ name: "ApiError", status: 409 }), /last approver/);
  assert.match(revokeFailureText(new Error("x")), /Touch ID/);
  assert.match(revokeFailureText(null), /Touch ID/);
});

test("the Devices view keeps the existing calls and a named revoke dialog", () => {
  const view = readFileSync(new URL("../src/views/Context.vue", import.meta.url), "utf8");
  assert.match(view, /loadOptional<\{ devices: Device\[\] \}>\("\/v1\/devices"\)/);
  assert.match(view, /await revokeDevice\(device\.id\)/);
  assert.match(view, /<dialog ref="dialog" class="dialog" aria-labelledby="revoke-title"/);
  assert.match(view, /<h2 id="revoke-title">Revoke \{\{ confirming\.name \}\}\?<\/h2>/);
  assert.match(view, /class="btn btn-secondary" autofocus/);
  assert.doesNotMatch(view, /NodePage/);
});
