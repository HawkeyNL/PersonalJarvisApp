import test from "node:test";
import assert from "node:assert/strict";
import { speechStatusLabel, nextSpeechStatus } from "../src/speechStatus.ts";

test("speech status accepts fixed values, never arbitrary native error content", () => {
  assert.match(speechStatusLabel("unavailable"), /Geen ondersteunde lokale/);
  assert.match(speechStatusLabel("failed"), /chat blijft beschikbaar/);
  assert.match(speechStatusLabel("queue_full"), /wachtrij vol/);
  assert.equal(speechStatusLabel("idle"), "");
  assert.equal(speechStatusLabel({ error: "fixture sensitive detail" }), null);
  assert.equal(speechStatusLabel("fixture sensitive detail"), null);
});

test("stop acknowledgement cannot immediately hide a queue failure", () => {
  const failed = nextSpeechStatus("", "queue_full");
  assert.equal(nextSpeechStatus(failed, "idle"), failed);
  assert.equal(nextSpeechStatus(failed, { error: "untrusted" }), failed);
  const nextRun = nextSpeechStatus(failed, "speaking");
  assert.equal(nextRun, speechStatusLabel("speaking"));
  assert.equal(nextSpeechStatus(nextRun, "idle"), "");
});
