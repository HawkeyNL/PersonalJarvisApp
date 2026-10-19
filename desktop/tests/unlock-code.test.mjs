import test from "node:test";
import assert from "node:assert/strict";
import { unlockCode } from "../src/unlockCode.ts";

const NONCE = Array.from({ length: 32 }, (_, i) => i.toString(16).padStart(2, "0")).join("");

test("unlock code matches the client-core golden vectors", async () => {
  assert.equal(await unlockCode("00112233-4455-6677-8899-aabbccddeeff", NONCE), "89");
  assert.equal(await unlockCode("ffeeddcc-bbaa-9988-7766-554433221100", "0b".repeat(32)), "00");
  assert.equal(await unlockCode("00112233-4455-6677-8899-AABBCCDDEEFF", NONCE.toUpperCase()), "89");
});

test("unlock code refuses malformed input", async () => {
  for (const [id, nonce] of [
    ["00112233-4455-6677-8899-aabbccddeeff", NONCE.slice(2)],
    ["00112233-4455-6677-8899-aabbccddeef", NONCE],
    ["00112233445566778899aabbccddeeff", NONCE],
    ["00112233-4455-6677-8899-aabbccddeeff", "zz".repeat(32)],
  ]) {
    await assert.rejects(unlockCode(id, nonce));
  }
});
