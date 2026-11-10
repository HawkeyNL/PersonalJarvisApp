import assert from "node:assert/strict";
import test from "node:test";

import { coalesce } from "../src/coalesce.ts";

test("a burst of reload hints costs at most one more reload", async () => {
  let runs = 0;
  let release;
  const reload = coalesce(() => { runs++; return new Promise((r) => (release = r)); });
  const first = reload();
  for (let i = 0; i < 5; i++) void reload();
  assert.equal(runs, 1);
  release();
  await new Promise((r) => setTimeout(r));
  assert.equal(runs, 2, "one trailing reload for the burst");
  release();
  await first;
  void reload();
  assert.equal(runs, 3, "idle again: the next hint reloads at once");
  release();
});

test("a failed reload does not wedge later ones", async () => {
  let runs = 0;
  const reload = coalesce(async () => { runs++; throw new Error("offline"); });
  await assert.rejects(reload());
  await assert.rejects(reload());
  assert.equal(runs, 2);
});
