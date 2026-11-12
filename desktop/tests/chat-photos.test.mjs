import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fitWithin, downscale, imageFiles, uploadFailure, missingPhotos, expiryHint, UNSUPPORTED, CORE_TOO_OLD, MAX_SIDE, JPEG_QUALITY, MAX_INPUT_BYTES, TOO_LARGE, UNREADABLE, runRefusal, tooOldToSend, SEND_WITHIN_MS } from "../src/chatPhotos.ts";
import { runRequest } from "../src/realtimeProjection.ts";

test("photos shrink to 2048 px on the long side and never grow", () => {
  assert.deepEqual(fitWithin(4032, 3024), { width: 2048, height: 1536 });
  assert.deepEqual(fitWithin(3024, 4032), { width: 1536, height: 2048 });
  assert.deepEqual(fitWithin(800, 600), { width: 800, height: 600 });
  assert.deepEqual(fitWithin(100000, 1), { width: 2048, height: 1 });
  assert.equal(MAX_SIDE, 2048);
});

function fakes({ width = 4000, height = 2000, decodeFails = false, blob = new Blob(["jpeg"]) } = {}) {
  const calls = { closed: 0, drawn: null, filled: null, encoded: null, decodedWith: null };
  const context = {
    fillStyle: "",
    fillRect: (...args) => { calls.filled = { style: context.fillStyle, args }; },
    drawImage: (_image, ...args) => { calls.drawn = args; },
  };
  const canvas = {
    width: 0, height: 0,
    getContext: () => context,
    toBlob: (done, type, quality) => { calls.encoded = { type, quality, width: canvas.width, height: canvas.height }; done(blob); },
  };
  return {
    calls,
    deps: {
      decode: async (file) => {
        calls.decodedWith = file;
        if (decodeFails) throw new Error("HEIC");
        return { width, height, close: () => calls.closed++ };
      },
      canvas: () => canvas,
    },
  };
}

test("downscale draws onto white and re-encodes as JPEG 0.88", async () => {
  const { calls, deps } = fakes();
  const input = new Blob(["png"]);
  const out = await downscale(input, deps);
  assert.equal(await out.text(), "jpeg");
  assert.equal(calls.decodedWith, input);
  assert.deepEqual(calls.encoded, { type: "image/jpeg", quality: JPEG_QUALITY, width: 2048, height: 1024 });
  assert.deepEqual(calls.drawn, [0, 0, 2048, 1024]);
  assert.equal(calls.filled.style, "#fff");
  assert.equal(calls.closed, 1);
});

test("an undecodable photo or a failed encode says to export as JPEG", async () => {
  await assert.rejects(downscale(new Blob(["heic"]), fakes({ decodeFails: true }).deps), { message: UNSUPPORTED });
  const { calls, deps } = fakes({ blob: null });
  await assert.rejects(downscale(new Blob(["x"]), deps), { message: UNSUPPORTED });
  assert.equal(calls.closed, 1);
  // Huge files are refused before any decode.
  const huge = fakes();
  await assert.rejects(downscale({ size: MAX_INPUT_BYTES + 1 }, huge.deps), { message: TOO_LARGE });
  assert.equal(huge.calls.decodedWith, null);
});

test("upload refusals have their own words; 404 means an older Core", () => {
  assert.equal(uploadFailure(404), CORE_TOO_OLD);
  assert.equal(CORE_TOO_OLD, "Your Home Node needs Jarvis Core v0.0.83 to receive photos.");
  assert.equal(uploadFailure(415), UNSUPPORTED);
  // A 400 is any unreadable body, not only a format: no "export as JPEG".
  assert.equal(uploadFailure(400), UNREADABLE);
  assert.equal(UNREADABLE, "This photo could not be read.");
  assert.equal(uploadFailure(503), "Photos are busy or unavailable on your Home Node; try again.");
  for (const status of [413, 429, 503, 507, 500]) assert.ok(uploadFailure(status).length > 0);
  assert.notEqual(uploadFailure(507), uploadFailure(500));
});

test("run requests carry photos only when there are some", () => {
  const history = [{ role: "jarvis", text: "Hi" }, { role: "user", text: "" }];
  const plain = runRequest("r", null, history);
  assert.deepEqual(plain, { request_id: "r", conversation_id: null, messages: [{ role: "assistant", content: "Hi" }, { role: "user", content: "" }] });
  assert.ok(!("attachments" in plain) && !("mode" in plain));
  assert.deepEqual(runRequest("r", "c", history, undefined, ["p1", "p2"]).attachments, ["p1", "p2"]);
  assert.equal(runRequest("r", "c", history, "research").mode, "research");
});

test("photos a message names but the conversation lacks are shown as removed", () => {
  const messages = [{ attachments: ["a", "b"] }, {}, { attachments: ["c"] }];
  assert.deepEqual([...missingPhotos(messages, [{ id: "a" }, { id: "c" }])], ["b"]);
  assert.deepEqual([...missingPhotos(messages, [])], ["a", "b", "c"]);
  assert.equal(missingPhotos([{}], []).size, 0);
});

test("the lightbox says how long a photo stays", () => {
  assert.equal(expiryHint({ saved: true, expires_at: null }), "Kept until you delete it.");
  assert.equal(expiryHint(undefined), "Photos are kept 90 days unless you keep them.");
  assert.equal(expiryHint({ saved: false, expires_at: "nonsense" }), "Photos are kept 90 days unless you keep them.");
  assert.match(expiryHint({ saved: false, expires_at: "2027-02-10T12:00:00Z" }), /^Removed on .*2027.* unless you keep it\.$/);
  // Kept but not sent yet: Core still removes it 24 h after the upload.
  assert.match(expiryHint({ saved: true, expires_at: "2027-02-10T12:00:00Z" }), /^Removed on .*2027\.$/);
});

test("only a 400 or 404 on submit is a definite refusal", () => {
  assert.match(runRefusal(400), /Nothing was sent\.$/);
  assert.match(runRefusal(404), /Nothing was sent\.$/);
  for (const status of [undefined, 0, 401, 409, 500, 503]) assert.equal(runRefusal(status), null, String(status));
});

test("a photo older than 23 hours is not sent", () => {
  const now = Date.UTC(2026, 10, 12, 12);
  assert.equal(SEND_WITHIN_MS, 23 * 3600 * 1000);
  assert.equal(tooOldToSend(now - SEND_WITHIN_MS, now), false);
  assert.equal(tooOldToSend(now - SEND_WITHIN_MS - 1, now), true);
  assert.equal(tooOldToSend(undefined, now), false);
});

const read = (path) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");

test("a refused message comes back to the composer, photos included", () => {
  assert.match(read("assistant.ts"), /if\(refusal\) \{\n\s+pending\.delete\(requestId\);\n\s+messages\.value=messages\.value\.filter\(m=>m\.id!==optimistic\.id\);/);
  const chat = read("components/JarvisConsole.vue");
  assert.match(chat, /function onSend\(\) \{\n  expireStaleChips\(\);/);
  assert.match(chat, /if \(!refused\) return;\n    if \(!text\.value\.trim\(\)\) text\.value = draft;\n    restoreChips\(taken\);/);
});

test("keeping a photo Core no longer has marks it removed", () => {
  assert.match(read("photos.ts"), /if \(!gone\(error\)\) throw error;\n    return markRemoved\(id\);/);
  assert.match(read("components/JarvisConsole.vue"), /if \(removedPhotos\.value\.has\(id\)\) lightboxEl\.value\?\.close\(\);/);
});

test("only images from a paste or a drop are attached", () => {
  const file = (type) => ({ type });
  assert.deepEqual(imageFiles([file("image/png"), file("text/plain"), file("image/jpeg"), file("")]), [file("image/png"), file("image/jpeg")]);
  assert.deepEqual(imageFiles(null), []);
});

test("photos dropped on the composer are attached; the window never opens a dropped file", () => {
  const conf = JSON.parse(readFileSync(new URL("../src-tauri/tauri.conf.json", import.meta.url), "utf8"));
  assert.equal(conf.app.windows[0].dragDropEnabled, false);
  assert.match(read("components/JarvisConsole.vue"), /<form class="composer card"[^>]*@dragover="onDragOver"[^>]*@drop="onDrop">/);
  assert.match(read("main.ts"), /for \(const kind of \["dragover", "drop"\]\)/);
});
