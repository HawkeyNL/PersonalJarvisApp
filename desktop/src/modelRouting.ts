// Pure helpers for the owner routing editor. No Vue or Tauri imports, so
// behavioral tests can load this file directly. The native command validates
// again and builds the signed bytes; this only keeps the editor honest.

export const TIERS = ["cheap", "default", "hard"] as const;
export type Tier = (typeof TIERS)[number];
export type RouteEntry = { provider: string; model: string };
export type TierRoute = { chain: RouteEntry[]; metered_after_subscription: boolean };
export type Routing = { version: number; paid_api: "allowed" | "off"; tiers: Partial<Record<Tier, TierRoute>> };
export type RoutingPolicy = {
  models: { provider: string; model: string; enabled: boolean }[];
  routing?: Routing | null;
  routing_sha256?: string | null;
  routing_unavailable_reason?: string | null;
  routing_mutation?: string;
};

/** Providers a routed chain may name (Core `ROUTING_PROVIDERS`). */
export const ROUTING_PROVIDERS = [
  "anthropic-api", "openai-api", "deepseek-api", "xai-api", "zai-api",
  "ollama", "ollama-cloud", "huggingface", "claude-cli", "codex-cli",
];
export const MAX_CHAIN = 9;

export const isSubscription = (provider: string) => provider === "claude-cli" || provider === "codex-cli";
/** Classified by provider id only, like Core's validator. */
export const isMetered = (provider: string) => !["ollama", "claude-cli", "codex-cli"].includes(provider);

/** "editable", "readonly" (Core cannot apply a signed route now) or
 *  "unsupported" (Core predates signed routing). */
export function routingMode(policy: RoutingPolicy): "editable" | "readonly" | "unsupported" {
  if (policy.routing_mutation === undefined) return "unsupported";
  return policy.routing_mutation === "device-signed-model-route-v1" && !!policy.routing_sha256 ? "editable" : "readonly";
}

/** A copy in Core's canonical field order, with defaults filled in. */
export function canonicalRouting(routing: Routing | null | undefined): Routing {
  const tiers: Partial<Record<Tier, TierRoute>> = {};
  for (const tier of TIERS) {
    const route = routing?.tiers?.[tier];
    if (route) tiers[tier] = {
      chain: route.chain.map(({ provider, model }) => ({ provider, model })),
      metered_after_subscription: !!route.metered_after_subscription,
    };
  }
  return { version: routing?.version ?? 1, paid_api: routing?.paid_api === "off" ? "off" : "allowed", tiers };
}

export const sameRouting = (a: Routing, b: Routing) => JSON.stringify(canonicalRouting(a)) === JSON.stringify(canonicalRouting(b));

/** True when a metered entry follows a subscription entry in `chain`. */
export function meteredAfterSubscription(chain: RouteEntry[]): boolean {
  let afterSubscription = false;
  for (const { provider } of chain) {
    if (afterSubscription && isMetered(provider)) return true;
    afterSubscription ||= isSubscription(provider);
  }
  return false;
}

const key = (e: RouteEntry) => `${e.provider}\u0000${e.model}`;

/** First reason Core would refuse this document, or null. Mirrors Core's
 *  `ModelRouting::validate` plus the "every pair is discovered" check. */
export function routingIssue(routing: Routing, discovered: RouteEntry[]): string | null {
  if (routing.version !== 1) return "Unsupported routing version";
  const known = new Set(discovered.map(key));
  for (const tier of TIERS) {
    const route = routing.tiers[tier];
    if (!route) continue;
    if (route.chain.length < 1 || route.chain.length > MAX_CHAIN) return `${tier}: a routed tier needs 1 to ${MAX_CHAIN} models`;
    const seen = new Set<string>();
    for (const entry of route.chain) {
      if (!ROUTING_PROVIDERS.includes(entry.provider)) return `${tier}: unknown provider ${entry.provider}`;
      const chars = [...entry.model];
      if (!chars.length || chars.length > 256 || /\p{Cc}/u.test(entry.model)) return `${tier}: invalid model name`;
      if (seen.has(key(entry))) return `${tier}: ${entry.provider}/${entry.model} is listed twice`;
      seen.add(key(entry));
      if (!known.has(key(entry))) return `${tier}: ${entry.provider}/${entry.model} is not discovered on the Home Node`;
    }
    if (meteredAfterSubscription(route.chain) && !route.metered_after_subscription) {
      return `${tier}: a paid API after a subscription needs explicit approval`;
    }
  }
  return null;
}

/** Discovered routable pairs not yet in `chain`, enabled first. */
export function candidates<T extends RouteEntry & { enabled: boolean }>(models: T[], chain: RouteEntry[]): T[] {
  const used = new Set(chain.map(key));
  return models
    .filter((m) => ROUTING_PROVIDERS.includes(m.provider) && !used.has(key(m)))
    .sort((a, b) => Number(b.enabled) - Number(a.enabled) || a.provider.localeCompare(b.provider) || a.model.localeCompare(b.model));
}

/** `chain` with entry `index` moved by `delta`; unchanged when out of range. */
export function moveEntry(chain: RouteEntry[], index: number, delta: number): RouteEntry[] {
  const target = index + delta;
  if (index < 0 || index >= chain.length || target < 0 || target >= chain.length) return chain;
  const next = [...chain];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

const FAIL_CLOSED = " Until it is fixed, Core uses the built-in order without paid APIs.";
const REASONS: Record<string, string> = {
  routing_invalid: "The routing file on the Home Node is invalid." + FAIL_CLOSED,
  routing_unsafe: "The routing file on the Home Node has unsafe ownership or permissions." + FAIL_CLOSED,
  routing_unreadable: "Core cannot read the routing file." + FAIL_CLOSED,
  routing_too_large: "The routing file on the Home Node is too large." + FAIL_CLOSED,
  routing_unavailable: "Core cannot use the routing file." + FAIL_CLOSED,
  routing_activation_unverified: "A signed routing change could not be verified. Paid APIs stay off until the owner verifies routing and restarts Core.",
  routing_reload_required: "The routing file on disk differs from what Core runs; restart Core to apply it.",
};
/** Owner-facing copy for `routing_unavailable_reason`; unknown codes stay visible. */
export function routingReason(code: string | null | undefined): string | null {
  if (!code) return null;
  return Object.prototype.hasOwnProperty.call(REASONS, code) ? REASONS[code] : `Routing unavailable (${code}).${FAIL_CLOSED}`;
}
