import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { displayHint, groupFingerprint, keyFingerprint, PairingFingerprintMismatch, verifiedFingerprint } from "../src/pairingFingerprint.ts";

const key = Buffer.from(Array.from({ length: 32 }, (_, i) => i + 32));
const keyHex = key.toString("hex");
const expected = createHash("sha256").update(key).digest("hex");

test("fingerprint is lowercase hex SHA-256 of the raw 32-byte key", async () => {
  assert.equal(await keyFingerprint(keyHex), expected);
  assert.equal(await keyFingerprint(keyHex.toUpperCase()), expected);
  for (const bad of ["", "zz".repeat(32), keyHex.slice(2), `${keyHex}00`]) {
    await assert.rejects(keyFingerprint(bad));
  }
});

test("approver fingerprint is computed locally and refuses any server disagreement", async () => {
  const request = { candidate_public_key: keyHex, fingerprint: expected.slice(0, 16) };
  // Older Core: no fingerprint_full, the legacy prefix still has to match.
  assert.equal(await verifiedFingerprint(request), expected);
  assert.equal(await verifiedFingerprint({ ...request, fingerprint_full: null }), expected);
  assert.equal(await verifiedFingerprint({ ...request, fingerprint_full: expected }), expected);
  const other = createHash("sha256").update(Buffer.alloc(32, 8)).digest("hex");
  await assert.rejects(verifiedFingerprint({ ...request, fingerprint_full: other }), PairingFingerprintMismatch);
  await assert.rejects(verifiedFingerprint({ ...request, fingerprint: other.slice(0, 16) }), PairingFingerprintMismatch);
  await assert.rejects(verifiedFingerprint({ ...request, fingerprint_full: expected.toUpperCase() }), PairingFingerprintMismatch);
});

test("fingerprint groups and candidate hints are bounded plain text", () => {
  assert.equal(groupFingerprint("0123456789abcdef01"), "0123 4567 89ab cdef 01");
  assert.equal(displayHint(null), "unknown");
  assert.equal(displayHint(undefined), "unknown");
  assert.equal(displayHint("Pixel‮ 9\u0007"), "Pixel 9");
  assert.equal(displayHint("x".repeat(70)), `${"x".repeat(64)}…`);
  assert.equal(displayHint("<b>x</b>"), "<b>x</b>");
});
