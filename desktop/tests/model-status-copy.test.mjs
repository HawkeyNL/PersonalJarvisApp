import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

test("System Health uses the owner model policy, not the credential-based resource list", () => {
  const system = readFileSync(new URL("../src/views/Health.vue", import.meta.url), "utf8");
  const models = readFileSync(new URL("../src/components/ModelControls.vue", import.meta.url), "utf8");
  assert.doesNotMatch(system, /Beschikbare AI-resources|Available AI resources|reg\.brains|reg\.models/);
  assert.match(system, /current\.id === 'models'.*<ModelControls/s);
  assert.match(models, /"Allowed"/);
  assert.match(models, /"Blocked"/);
  assert.match(models, /not that it is handling a request right now/);
});
