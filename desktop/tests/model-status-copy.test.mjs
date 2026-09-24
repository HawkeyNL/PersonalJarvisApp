import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

test("System uses the owner model policy, not the credential-based resource list", () => {
  const system = readFileSync(new URL("../src/views/Status.vue", import.meta.url), "utf8");
  const models = readFileSync(new URL("../src/components/ModelControls.vue", import.meta.url), "utf8");
  assert.doesNotMatch(system, /Beschikbare AI-resources|reg\.brains|reg\.models/);
  assert.match(system, /section === 'Modellen'.*ModelControls/s);
  assert.match(models, /"Toegestaan"/);
  assert.match(models, /"Geblokkeerd"/);
  assert.match(models, /niet dat het nu een aanvraag verwerkt/);
});
