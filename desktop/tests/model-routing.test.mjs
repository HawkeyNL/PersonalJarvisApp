import assert from "node:assert/strict";
import test from "node:test";

import {
  candidates, canonicalRouting, meteredAfterSubscription, moveEntry, routingIssue, routingMode, routingReason, sameRouting,
} from "../src/modelRouting.ts";

const e = (provider, model) => ({ provider, model });
const discovered = [
  e("huggingface", "org/modèl"), e("claude-cli", "claude-haiku-4-5"), e("claude-cli", "claude-opus-5"),
  e("anthropic-api", "claude-opus-5"), e("ollama", "llama3.2"), e("codex-cli", "gpt-6-luna"), e("openai-api", "gpt-6-luna"),
];
const tier = (chain, metered = false) => ({ version: 1, paid_api: "allowed", tiers: { default: { chain, metered_after_subscription: metered } } });

test("routing mode distinguishes old Core, unavailable broker and editable", () => {
  assert.equal(routingMode({ models: [] }), "unsupported");
  assert.equal(routingMode({ models: [], routing_mutation: "unavailable", routing_sha256: "ab" }), "readonly");
  assert.equal(routingMode({ models: [], routing_mutation: "device-signed-model-route-v1", routing_sha256: null }), "readonly");
  assert.equal(routingMode({ models: [], routing_mutation: "device-signed-model-route-v1", routing_sha256: "ab" }), "editable");
});

test("canonical routing follows Core's field order and omits absent tiers", () => {
  const loose = {
    tiers: {
      hard: { metered_after_subscription: true, chain: [{ model: "claude-opus-5", provider: "claude-cli" }, e("anthropic-api", "claude-opus-5")] },
      cheap: { chain: [e("huggingface", "org/modèl"), e("claude-cli", "claude-haiku-4-5")] },
    },
    paid_api: "off", version: 1,
  };
  // The `routing` member of Core's fixed model.routing_set vector.
  assert.equal(
    JSON.stringify(canonicalRouting(loose)),
    '{"version":1,"paid_api":"off","tiers":{"cheap":{"chain":[{"provider":"huggingface","model":"org/modèl"},{"provider":"claude-cli","model":"claude-haiku-4-5"}],"metered_after_subscription":false},"hard":{"chain":[{"provider":"claude-cli","model":"claude-opus-5"},{"provider":"anthropic-api","model":"claude-opus-5"}],"metered_after_subscription":true}}}',
  );
  assert.deepEqual(canonicalRouting(null), { version: 1, paid_api: "allowed", tiers: {} });
  assert.ok(sameRouting(loose, canonicalRouting(loose)));
  assert.ok(!sameRouting(loose, { ...loose, paid_api: "allowed" }));
});

test("validation mirrors Core's routing rules", () => {
  assert.equal(routingIssue({ version: 1, paid_api: "allowed", tiers: {} }, []), null);
  for (const ok of [
    tier([e("claude-cli", "claude-opus-5"), e("anthropic-api", "claude-opus-5")], true),
    tier([e("anthropic-api", "claude-opus-5"), e("claude-cli", "claude-opus-5")]),
    tier([e("claude-cli", "claude-opus-5"), e("ollama", "llama3.2")]),
  ]) assert.equal(routingIssue(ok, discovered), null);
  const many = Array.from({ length: 10 }, (_, i) => e("ollama", `m${i}`));
  assert.equal(routingIssue(tier(many.slice(0, 9)), many), null);
  for (const bad of [
    tier([]),
    tier(many),
    tier([e("jev", "a")]),
    tier([e("Claude-CLI", "claude-opus-5")]),
    tier([e("ollama", "")]),
    tier([e("ollama", "m".repeat(257))]),
    tier([e("ollama", "a\nb")]),
    tier([e("ollama", "llama3.2"), e("ollama", "llama3.2")]),
    tier([e("claude-cli", "claude-opus-5"), e("anthropic-api", "claude-opus-5")]),
    tier([e("codex-cli", "gpt-6-luna"), e("openai-api", "gpt-6-luna")]),
    tier([e("claude-cli", "claude-opus-5"), e("ollama", "llama3.2"), e("huggingface", "org/modèl")]),
    tier([e("ollama", "not-discovered")]),
    { version: 2, paid_api: "allowed", tiers: {} },
  ]) assert.notEqual(routingIssue(bad, [...discovered, ...many]), null, JSON.stringify(bad));
});

test("metered-after-subscription detection ignores local entries", () => {
  assert.ok(!meteredAfterSubscription([e("anthropic-api", "a"), e("claude-cli", "a")]));
  assert.ok(!meteredAfterSubscription([e("claude-cli", "a"), e("ollama", "b")]));
  assert.ok(meteredAfterSubscription([e("claude-cli", "a"), e("ollama", "b"), e("zai-api", "c")]));
});

test("chain editing helpers keep order and offer unused routable pairs, enabled first", () => {
  const chain = [e("a", "1"), e("b", "2"), e("c", "3")];
  assert.deepEqual(moveEntry(chain, 2, -1).map((x) => x.provider), ["a", "c", "b"]);
  assert.equal(moveEntry(chain, 0, -1), chain);
  assert.equal(moveEntry(chain, 2, 1), chain);
  const models = [
    { ...e("ollama", "z"), enabled: false }, { ...e("claude-cli", "claude-opus-5"), enabled: true },
    { ...e("jev", "x"), enabled: true }, { ...e("anthropic-api", "claude-opus-5"), enabled: false },
  ];
  assert.deepEqual(candidates(models, [e("claude-cli", "claude-opus-5")]).map((m) => m.provider), ["anthropic-api", "ollama"]);
  assert.deepEqual(candidates(models, []).map((m) => m.provider), ["claude-cli", "anthropic-api", "ollama"]);
});

test("unavailable reasons explain the fail-closed state without hiding unknown codes", () => {
  assert.equal(routingReason(null), null);
  assert.match(routingReason("routing_invalid"), /without paid APIs/);
  assert.match(routingReason("routing_activation_unverified"), /Paid APIs stay off/);
  assert.doesNotMatch(routingReason("routing_reload_required"), /without paid APIs/);
  assert.match(routingReason("routing_new_code"), /routing_new_code/);
});
