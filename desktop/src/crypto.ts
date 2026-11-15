// Read-only crypto wallets: Core's contract (docs/CRYPTO_WALLETS.md in the
// Core repository), response guards, the add form's checks and the fixed
// owner-facing wording. Pure: no Vue or Tauri imports, so tests load this
// file directly. The Android and iOS apps mirror the strings here.
//
//   GET    /v1/trading/crypto                -> Overview
//   POST   /v1/trading/crypto/wallets        {kind, address, label?, chains?}
//   DELETE /v1/trading/crypto/wallets/{id}
//   POST   /v1/trading/crypto/refresh        202, or 429 {retry_after_secs}
//   GET    /v1/trading/total                 -> PortfolioTotal
//
// Amounts and EUR values are decimal strings, shown through trading.ts's
// formatters, never as floats. Addresses, labels and Core text are plain
// text. Anything that looks like key material is refused here, before a
// request, with Core's fixed message; the input is never kept or echoed.
import { validText } from "./roadmaps.ts";
import type { Tone } from "./hubModel.ts";

export const CHAINS = ["bitcoin", "ethereum", "base", "arbitrum", "optimism", "polygon", "solana"] as const;
export type Chain = (typeof CHAINS)[number];
export const EVM_CHAINS = ["ethereum", "base", "arbitrum", "optimism", "polygon"] as const satisfies readonly Chain[];
export const CHAIN_LABEL: Record<Chain, string> = {
  bitcoin: "Bitcoin", ethereum: "Ethereum", base: "Base", arbitrum: "Arbitrum", optimism: "Optimism", polygon: "Polygon", solana: "Solana",
};
export const KINDS = ["bitcoin", "evm", "solana"] as const;
export type Kind = (typeof KINDS)[number];
export const KIND_LABEL: Record<Kind, string> = { bitcoin: "Bitcoin", evm: "EVM", solana: "Solana" };

export type Status = "ok" | "stale" | "error" | "pending" | "unknown";
export const STATUS_LABEL: Record<Status, string> = { ok: "OK", stale: "Stale", error: "Error", pending: "Pending", unknown: "Unknown" };
/** The trading screens' tones (hubModel TONE_BADGE gives the badge class). */
export const STATUS_TONE: Record<Status, Tone> = { ok: "ok", stale: "warn", error: "error", pending: "idle", unknown: "idle" };

export const LIMITS = { wallets: 50, balances: 100, assets: 50, label: 40, address: 128, otherTokens: 1_000_000, maxRetrySecs: 3600 } as const;

export type Balance = { chain: Chain | null; symbol: string; amount: string; price_eur: string | null; value_eur: string | null };
export type Wallet = {
  id: string; kind: string; chains: Chain[]; address: string; label: string | null; status: Status; error: string | null;
  last_refresh_at: string | null; other_tokens: number; value_eur: string; balances: Balance[];
};
export type AssetRow = { symbol: string; amount: string; price_eur: string | null; value_eur: string | null; weight_pct: string | null };
/** `hidden`: wallets this build could not read (left out, still in Core's total). */
export type Overview = {
  as_of: string; total_eur: string; chains_available: Chain[]; wallets: Wallet[]; assets: AssetRow[]; hidden: number;
};
export type TotalPart = { source: string; value: string; as_of: string };
export type PortfolioTotal = { as_of: string; base_currency: string; total: string; parts: TotalPart[]; partial: boolean };

// ---- response guards ----------------------------------------------------------

type Obj = Record<string, unknown>;
const isObj = (x: unknown): x is Obj => typeof x === "object" && x !== null && !Array.isArray(x);
const str = (x: unknown, max: number): x is string => typeof x === "string" && x.length <= max;
/** A decimal string as Core writes it: at most 20 integer digits. */
const DECIMAL = /^-?\d{1,20}(\.\d{1,28})?$/;
const dec = (x: unknown): x is string => typeof x === "string" && DECIMAL.test(x);
const optDec = (x: unknown) => (dec(x) ? x : null);
const time = (x: unknown): x is string => str(x, 64) && !Number.isNaN(Date.parse(x));
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isId = (x: unknown): x is string => typeof x === "string" && UUID.test(x);
const CODE = /^[a-z0-9_]{1,64}$/;
const SYMBOL = /^[A-Z0-9]{1,12}$/;
const ADDRESS = /^[0-9A-Za-z]{20,128}$/;
const chain = (x: unknown): Chain | null => CHAINS.find((c) => c === x) ?? null;
const chainList = (x: unknown): Chain[] =>
  (Array.isArray(x) ? x.slice(0, CHAINS.length * 2) : []).map(chain).filter((c, i, all): c is Chain => c !== null && all.indexOf(c) === i);

function readBalance(x: unknown): Balance | null {
  if (!isObj(x) || !(typeof x.symbol === "string" && SYMBOL.test(x.symbol)) || !dec(x.amount)) return null;
  return { chain: chain(x.chain), symbol: x.symbol, amount: x.amount, price_eur: optDec(x.price_eur), value_eur: optDec(x.value_eur) };
}

function readWallet(x: unknown): Wallet | null {
  if (!isObj(x) || !isId(x.id) || !(typeof x.kind === "string" && CODE.test(x.kind))) return null;
  if (!(typeof x.address === "string" && ADDRESS.test(x.address)) || !dec(x.value_eur)) return null;
  if (x.label !== undefined && !str(x.label, 200)) return null;
  if (!Array.isArray(x.balances) || x.balances.length > LIMITS.balances) return null;
  const balances = x.balances.map(readBalance);
  if (balances.includes(null)) return null;
  const other = x.other_tokens;
  return {
    id: x.id, kind: x.kind, chains: chainList(x.chains), address: x.address, label: (x.label as string | undefined) || null,
    // A status this build does not know shows as Unknown, never as OK.
    status: (["ok", "stale", "error", "pending"] as const).find((s) => s === x.status) ?? "unknown",
    error: typeof x.error === "string" && CODE.test(x.error) ? x.error : x.error === undefined ? null : "unknown",
    last_refresh_at: time(x.last_refresh_at) ? x.last_refresh_at : null,
    other_tokens: Number.isSafeInteger(other) && (other as number) > 0 && (other as number) <= LIMITS.otherTokens ? (other as number) : 0,
    value_eur: x.value_eur, balances: balances as Balance[],
  };
}

function readAsset(x: unknown): AssetRow | null {
  if (!isObj(x) || !(typeof x.symbol === "string" && SYMBOL.test(x.symbol)) || !dec(x.amount)) return null;
  return { symbol: x.symbol, amount: x.amount, price_eur: optDec(x.price_eur), value_eur: optDec(x.value_eur), weight_pct: optDec(x.weight_pct) };
}

/** GET /v1/trading/crypto. A wallet this build cannot read is left out and
 *  counted; anything else unexpected fails the whole read (null). */
export function readOverview(x: unknown): Overview | null {
  if (!isObj(x) || !time(x.as_of) || x.base_currency !== "EUR" || !dec(x.total_eur)) return null;
  if (!Array.isArray(x.wallets) || x.wallets.length > LIMITS.wallets || !Array.isArray(x.assets) || x.assets.length > LIMITS.assets) return null;
  if (!Array.isArray(x.chains_available)) return null;
  const wallets = x.wallets.map(readWallet).filter((w): w is Wallet => w !== null);
  const assets = x.assets.map(readAsset);
  if (assets.includes(null)) return null;
  return {
    as_of: x.as_of, total_eur: x.total_eur, chains_available: chainList(x.chains_available), wallets,
    assets: assets as AssetRow[], hidden: x.wallets.length - wallets.length,
  };
}

/** GET /v1/trading/total: at most a few parts, each with its own time. */
export function readTotal(x: unknown): PortfolioTotal | null {
  if (!isObj(x) || !time(x.as_of) || !(typeof x.base_currency === "string" && /^[A-Z]{3}$/.test(x.base_currency))) return null;
  if (!dec(x.total) || typeof x.partial !== "boolean" || !Array.isArray(x.parts) || x.parts.length > 8) return null;
  const parts: TotalPart[] = [];
  for (const p of x.parts) {
    if (!isObj(p) || !str(p.source, 32) || !p.source || !dec(p.value) || !time(p.as_of)) return null;
    parts.push({ source: p.source, value: p.value, as_of: p.as_of });
  }
  return { as_of: x.as_of, base_currency: x.base_currency, total: x.total, parts, partial: x.partial };
}

/** "IBKR", "Crypto"; another source Core adds later shows as Core names it. */
export const partLabel = (source: string) => (source === "crypto" ? "Crypto" : source);
export const PARTIAL_TEXT = "Partial: a part that is set up has no value right now and is left out.";
export const TOTAL_FAILED_TEXT = "Crypto wallets are not in this value: Core could not give the total just now.";

/** The kinds the add form offers: those with a chain Core has on. */
export function kindsAvailable(chains: readonly Chain[]): Kind[] {
  return KINDS.filter((k) => (k === "evm" ? EVM_CHAINS.some((c) => chains.includes(c)) : chains.includes(k)));
}

// ---- wording ------------------------------------------------------------------

export const TITLE = "Crypto";
export const SUBTITLE = "Read-only balances of your self-custody wallets, in EUR.";
export const HINT = "Add self-custody addresses only, not exchange deposit addresses. Don't also enter these coins as manual holdings.";
export const ATTRIBUTION = "Prices by CoinGecko";
export const NO_PRICE = "no EUR price, not counted";
export const DELETE_CONFIRM = "Remove this wallet? Its balances leave the total.";
export const OFF_TITLE = "Crypto wallets are off on your Home Node.";
export const OFF_KEY = "JARVIS_CRYPTO_WALLETS_ENABLED";
export const OFF_DETAIL =
  `To turn them on, allow the provider hosts in your Home Node's egress rules (see "Egress hosts" in docs/CRYPTO_WALLETS.md in the Core repository), set ${OFF_KEY}=true in Core's environment and restart Core.`;
export const NEEDS_CORE = "Update Core to v0.0.90 or later.";

export const otherTokensText = (n: number) => `${n} other ${n === 1 ? "token" : "tokens"} not counted`;
export const hiddenText = (n: number) =>
  `${n === 1 ? "1 wallet" : `${n} wallets`} this app cannot show yet. Update the app to see ${n === 1 ? "it" : "them"}.`;

/** "0x1234…abcd": the start and end an owner compares; short ones whole. */
export const shortAddress = (a: string) => (a.length <= 12 ? a : `${a.slice(0, 6)}…${a.slice(-4)}`);

/** The wallet's name: its label, or its kind. */
export const walletName = (w: Pick<Wallet, "label" | "kind">) => w.label ?? (KIND_LABEL[w.kind as Kind] ?? "Wallet");

const ERROR_TEXT: Record<string, string> = {
  unavailable: "A provider could not be reached or answered with an error, or the chain is off on Core.",
  rate_limited: "A provider's rate limit was hit. Core tries again later.",
  invalid_response: "A provider sent an answer Core does not accept.",
  too_many_tokens: "This Solana wallet has more token accounts than Core reads.",
};
const own = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);
/** The owner-facing text of a wallet's error code; never the code's own words. */
export const walletErrorText = (code: string) =>
  `${own(ERROR_TEXT, code) ? ERROR_TEXT[code] : "Core reported an error this app does not know yet."} The last good balances still count.`;

// ---- add form -------------------------------------------------------------------

export const KEY_MATERIAL_TEXT =
  "This looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only.";
export const XPUB_TEXT = "Extended public keys (xpub) are not supported; add single addresses.";
export const TESTNET_TEXT = "Only mainnet addresses are supported.";
export const INVALID_ADDRESS_TEXT: Record<Kind, string> = {
  bitcoin: "Not a valid Bitcoin address.",
  evm: "Not a valid EVM address: 0x followed by 40 hex digits.",
  solana: "Not a valid Solana address.",
};
export const CHAINS_TEXT = "Pick at least one chain.";
export const LABEL_TEXT = `The label can be 1 to ${LIMITS.label} characters on one line.`;

const B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

/** Base58 decoding (Bitcoin alphabet), as Core's bs58: the byte length and
 *  the first byte, or null for text outside the alphabet. The bytes may be a
 *  private key, so they are wiped before returning. */
function base58(text: string): { length: number; first: number } | null {
  const bytes: number[] = [];
  for (const c of text) {
    let carry = B58.indexOf(c);
    if (carry < 0) return null;
    for (let i = 0; i < bytes.length; i++) {
      carry += bytes[i] * 58;
      bytes[i] = carry & 0xff;
      carry >>= 8;
    }
    while (carry) {
      bytes.push(carry & 0xff);
      carry >>= 8;
    }
  }
  let zeros = 0;
  while (zeros < text.length && text[zeros] === "1") zeros++;
  const length = bytes.length + zeros;
  const first = zeros ? 0 : (bytes[bytes.length - 1] ?? 0);
  bytes.fill(0);
  return { length, first };
}

const solanaAddress = (t: string) => t.length >= 32 && t.length <= 44 && base58(t)?.length === 32;
const PRIVATE_PREFIXES = ["xprv", "yprv", "zprv", "tprv", "uprv", "vprv", "Yprv", "Zprv"];
const PUBLIC_PREFIXES = ["xpub", "ypub", "zpub", "tpub", "upub", "vpub", "Ypub", "Zpub"];

function keyToken(token: string): boolean {
  const hex = token.startsWith("0x") || token.startsWith("0X") ? token.slice(2) : token;
  if (PRIVATE_PREFIXES.some((p) => token.startsWith(p)) && !solanaAddress(token)) return true;
  if ((hex.length === 64 || hex.length === 128) && /^[0-9a-fA-F]+$/.test(hex)) return true;
  if (token.length === 51 || token.length === 52) {
    const b = base58(token);
    if (b && (b.length === 37 || b.length === 38) && (b.first === 0x80 || b.first === 0xef)) return true;
  }
  return token.length >= 64 && token.length <= 88 && base58(token)?.length === 64;
}

/** Core's is_key_material (crates/market-data/src/wallet.rs): a phrase of 12+
 *  words, a PEM block, a JSON byte array, or a token that is an extended
 *  private key, 64 or 128 hex digits, a WIF key or base58 of 64 bytes. */
export function isKeyMaterial(input: string): boolean {
  return input.includes("-----BEGIN")
    || input.split(/[\s,;-]/u).filter(Boolean).length >= 12
    || /^\s*\[\s*\d/u.test(input)
    || input.split(/[^0-9A-Za-z]/).some(keyToken);
}

/** Why the address does not fit the kind, or null. Shape only: Core checks
 *  the checksum again (400). Key material is checked before this. */
export function addressIssue(kind: Kind, input: string): string | null {
  const a = input.trim();
  if (kind === "bitcoin") {
    if (PUBLIC_PREFIXES.some((p) => a.startsWith(p))) return XPUB_TEXT;
    const lower = a.toLowerCase();
    if (/^(tb1|bcrt1)/.test(lower) || /^[mn2][1-9A-HJ-NP-Za-km-z]{25,34}$/.test(a)) return TESTNET_TEXT;
    const bech32 = (a === lower || a === a.toUpperCase()) && /^bc1[02-9ac-hj-np-z]{39}$|^bc1[02-9ac-hj-np-z]{59}$/.test(lower);
    return bech32 || /^[13][1-9A-HJ-NP-Za-km-z]{25,34}$/.test(a) ? null : INVALID_ADDRESS_TEXT.bitcoin;
  }
  if (kind === "evm") return /^0x[0-9a-fA-F]{40}$/.test(a) ? null : INVALID_ADDRESS_TEXT.evm;
  return solanaAddress(a) ? null : INVALID_ADDRESS_TEXT.solana;
}

export type WalletDraft = { kind: Kind; address: string; label: string; chains: Chain[] };
export type DraftField = "address" | "label" | "chains";
/** `key`: the field holds key material; the form clears it. */
export type DraftIssue = { field: DraftField; message: string; key?: true };

/** The first rule the draft breaks, key material first, or null. */
export function draftIssue(d: WalletDraft): DraftIssue | null {
  if (isKeyMaterial(d.address)) return { field: "address", message: KEY_MATERIAL_TEXT, key: true };
  if (isKeyMaterial(d.label)) return { field: "label", message: KEY_MATERIAL_TEXT, key: true };
  const address = addressIssue(d.kind, d.address);
  if (address) return { field: "address", message: address };
  if (d.kind === "evm" && !d.chains.some((c) => (EVM_CHAINS as readonly Chain[]).includes(c))) return { field: "chains", message: CHAINS_TEXT };
  if (d.label.trim() && !validText(d.label.trim(), LIMITS.label)) return { field: "label", message: LABEL_TEXT };
  return null;
}

export type NewWallet = { kind: Kind; address: string; label?: string; chains?: Chain[] };

/** The create body (only Core's fields; chains only for EVM, in Core's
 *  order); null while the draft breaks a rule. */
export function newWallet(d: WalletDraft): NewWallet | null {
  if (draftIssue(d)) return null;
  const label = d.label.trim();
  return {
    kind: d.kind, address: d.address.trim(), ...(label ? { label } : {}),
    ...(d.kind === "evm" ? { chains: EVM_CHAINS.filter((c) => d.chains.includes(c)) } : {}),
  };
}

// ---- failures ----------------------------------------------------------------

export type Failure = "off" | "no-api" | "unreachable" | "signin" | "failed";
type ErrorShape = { name?: string; status?: number; body?: unknown } | null;
const coreMessage = (e: ErrorShape) => (isObj(e?.body) && str(e.body.error, 300) ? e.body.error : "");

/** A failed read or write: 503 "crypto wallets are not enabled" is off; an
 *  older Core without the routes answers 404 or 405. */
export function failureOf(error: unknown): Failure {
  const e = error as ErrorShape;
  if (e?.name === "NetworkError") return "unreachable";
  if (e?.name === "ApiError") {
    if (e.status === 401) return "signin";
    if (e.status === 404 || e.status === 405 || e.status === 501) return "no-api";
    if (e.status === 503 && coreMessage(e) === "crypto wallets are not enabled") return "off";
  }
  return "failed";
}

export function failureText(f: Failure): string {
  switch (f) {
    case "off": return OFF_TITLE;
    case "no-api": return NEEDS_CORE;
    case "unreachable": return "No answer from your Home Node. Check the connection.";
    case "signin": return "Your session is not valid. Sign in again on the Core page.";
    case "failed": return "Core returned an answer this app does not understand, or an error. Try again later.";
  }
}

/** Core's fixed refusals (400, 409), in the app's words. */
const REFUSAL_TEXT: Record<string, string> = {
  "this looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only": KEY_MATERIAL_TEXT,
  "extended public keys (xpub) are not supported; add single addresses": XPUB_TEXT,
  "only mainnet addresses are supported": TESTNET_TEXT,
  "not a valid address for this wallet kind": "Not a valid address for this chain. Check it for typos.",
  "choose one or more EVM chains, each once": CHAINS_TEXT,
  "label must be 1 to 40 visible characters": LABEL_TEXT,
  "a chosen chain is not available on this Core": "A chosen chain is not available on your Home Node.",
  "this wallet was already added": "This wallet was already added.",
  "wallet limit reached": "You have 20 wallets, the most Core keeps. Remove one to add another.",
};

/** The notice after a refused add. */
export function addFailureText(error: unknown): string {
  const e = error as ErrorShape;
  if (e?.name === "ApiError" && (e.status === 400 || e.status === 409)) {
    const message = coreMessage(e);
    if (own(REFUSAL_TEXT, message)) return REFUSAL_TEXT[message];
    return e.status === 409 ? "Core refused this: the wallet was already added, or you have 20 wallets." : "Core refused this wallet. Check the fields and try again.";
  }
  if (e?.name === "NetworkError") return "No answer from your Home Node. It is unknown whether the wallet was added; reloaded to check.";
  return `Not added. ${failureText(failureOf(error))}`;
}

/** Core refused the body as key material: the form forgets what was typed. */
export function isKeyMaterialRefusal(error: unknown): boolean {
  const e = error as ErrorShape;
  return e?.name === "ApiError" && e.status === 400 && REFUSAL_TEXT[coreMessage(e)] === KEY_MATERIAL_TEXT;
}

/** The notice after a failed remove; null when the wallet is already gone. */
export function removeFailureText(error: unknown): string | null {
  const e = error as ErrorShape;
  if (e?.name === "ApiError" && e.status === 404) return null;
  if (e?.name === "NetworkError") return "No answer from your Home Node. It is unknown whether the wallet was removed; reloaded to check.";
  return `Not removed. ${failureText(failureOf(error))}`;
}

/** Seconds until Core takes another refresh, from a 429; null for any other
 *  failure. A missing or odd value waits Core's minute. */
export function retryAfterSecs(error: unknown): number | null {
  const e = error as ErrorShape;
  if (e?.name !== "ApiError" || e.status !== 429) return null;
  const secs = isObj(e.body) ? e.body.retry_after_secs : undefined;
  return Number.isSafeInteger(secs) && (secs as number) >= 1 ? Math.min(secs as number, LIMITS.maxRetrySecs) : 60;
}

export const cooldownText = (secs: number) => `Core refreshes at most once a minute. Try again in ${secs} s.`;
export const REFRESH_ASKED = "Refreshing. The page updates when Core is done.";
export const ADDED = "Added. Core reads its balances in a moment.";
export const REMOVED = "Removed.";

// ---- realtime ----------------------------------------------------------------

/** A `trading.crypto.updated` hint: reload; Core's read is the authority. */
export const isCryptoUpdated = (event: unknown) => isObj(event) && event.type === "trading.crypto.updated";
