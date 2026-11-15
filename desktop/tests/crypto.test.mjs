import assert from "node:assert/strict";
import test from "node:test";

import {
  ATTRIBUTION, CHAINS_TEXT, DELETE_CONFIRM, HINT, INVALID_ADDRESS_TEXT, KEY_MATERIAL_TEXT, LABEL_TEXT, NEEDS_CORE, NO_PRICE, OFF_DETAIL, OFF_KEY,
  OFF_TITLE, PARTIAL_TEXT, STATUS_LABEL, STATUS_TONE, TESTNET_TEXT, XPUB_TEXT, addFailureText, addressIssue, cooldownText, draftIssue, failureOf,
  failureText, hiddenText, isCryptoUpdated, isId, isKeyMaterial, isKeyMaterialRefusal, kindsAvailable, newWallet, otherTokensText, partLabel,
  readOverview, readTotal, removeFailureText, retryAfterSecs, shortAddress, totalFailed, walletErrorText, walletName,
} from "../src/crypto.ts";

// Neutral test data only: public example addresses from the specs, and
// throwaway key material from Core's own tests (never funded or used).
const W1 = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
const W2 = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c";
const EVM = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";
const SOL = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
const apiError = (status, body) => ({ name: "ApiError", status, body });

const evmWallet = {
  id: W1, kind: "evm", chains: ["ethereum", "base"], address: EVM, label: "Cold", status: "ok",
  last_refresh_at: "2026-11-15T09:00:00Z", value_eur: "1234.56",
  balances: [
    { chain: "ethereum", asset: "eth", symbol: "ETH", amount: "0.123456789012345678", price_eur: "2500.5", value_eur: "308.71" },
    { chain: "base", asset: "usdc", symbol: "USDC", amount: "925.85" },
  ],
};
const solWallet = {
  id: W2, kind: "solana", chains: ["solana"], address: SOL, status: "error", error: "rate_limited", other_tokens: 3,
  value_eur: "0", balances: [],
};
const overview = {
  source: "crypto", as_of: "2026-11-15T09:00:00Z", base_currency: "EUR", total_eur: "1234.56",
  chains_available: ["bitcoin", "ethereum", "base", "solana"],
  wallets: [evmWallet, solWallet],
  assets: [
    { asset: "eth", symbol: "ETH", amount: "0.123456789012345678", price_eur: "2500.5", value_eur: "308.71", weight_pct: "25" },
    { asset: "usdc", symbol: "USDC", amount: "925.85" },
  ],
};

test("the overview reads Core's wallets, amounts as exact strings", () => {
  const o = readOverview(overview);
  assert.equal(o.hidden, 0);
  assert.deepEqual(o.chains_available, ["bitcoin", "ethereum", "base", "solana"]);
  const [e, s] = o.wallets;
  assert.equal(e.balances[0].amount, "0.123456789012345678", "never through a float");
  assert.equal(e.balances[1].value_eur, null, "unpriced: shown, not counted");
  assert.equal(walletName(e), "Cold");
  assert.equal(walletName(s), "Solana");
  assert.equal(s.error, "rate_limited");
  assert.equal(s.other_tokens, 3);
  assert.equal(s.last_refresh_at, null);
  assert.equal(o.assets[1].price_eur, null);
  assert.deepEqual(kindsAvailable(o.chains_available), ["bitcoin", "evm", "solana"]);
  assert.deepEqual(kindsAvailable(["base"]), ["evm"]);
  assert.deepEqual(kindsAvailable([]), []);
});

test("unknown statuses, chains and codes never read as fine", () => {
  const [w] = readOverview({
    ...overview, chains_available: ["ethereum", "dogechain"],
    wallets: [{ ...evmWallet, status: "syncing", error: "Provider said <b>x</b>", chains: ["ethereum", "zksync", "ethereum"] }],
  }).wallets;
  assert.equal(w.status, "unknown");
  assert.equal(STATUS_LABEL[w.status], "Unknown");
  assert.equal(STATUS_TONE[w.status], "idle");
  assert.equal(w.error, "unknown", "Core text is never shown");
  assert.match(walletErrorText(w.error), /^Core reported an error this app does not know yet\./);
  assert.deepEqual(w.chains, ["ethereum"]);
  assert.deepEqual(STATUS_TONE, { ok: "ok", stale: "warn", error: "error", pending: "idle", unknown: "idle" });
  assert.deepEqual(STATUS_LABEL, { ok: "OK", stale: "Stale", error: "Error", pending: "Pending", unknown: "Unknown" });
  assert.match(walletErrorText("toString"), /does not know yet/);
});

test("a wallet the app cannot read is left out and counted; a broken overview fails", () => {
  for (const bad of [
    { ...evmWallet, id: "../x" },
    { ...evmWallet, address: "0x12 34" },
    { ...evmWallet, value_eur: 12.5 },
    { ...evmWallet, value_eur: "1e5" },
    { ...evmWallet, balances: [{ symbol: "ETH", amount: 0.1 }] },
    { ...evmWallet, balances: [{ symbol: "<script>", amount: "1" }] },
    { ...evmWallet, balances: Array(101).fill({ symbol: "ETH", amount: "1" }) },
    { ...evmWallet, label: "x".repeat(201) },
    "junk",
  ]) {
    const o = readOverview({ ...overview, wallets: [bad, solWallet] });
    assert.equal(o.wallets.length, 1, JSON.stringify(bad).slice(0, 60));
    assert.equal(o.hidden, 1);
  }
  assert.equal(hiddenText(1), "1 wallet this app cannot show yet. Update the app to see it.");
  assert.equal(hiddenText(2), "2 wallets this app cannot show yet. Update the app to see them.");
  for (const broken of [
    null, [], { ...overview, base_currency: "USD" }, { ...overview, total_eur: 1234.56 }, { ...overview, total_eur: "123456789012345678901" },
    { ...overview, as_of: "soon" }, { ...overview, wallets: Array(51).fill(evmWallet) }, { ...overview, assets: [{ symbol: "ETH" }] },
    { ...overview, chains_available: "all" },
  ]) {
    assert.equal(readOverview(broken), null, JSON.stringify(broken)?.slice(0, 60));
  }
});

test("the portfolio total keeps each part's time and the partial flag", () => {
  const t = readTotal({
    source: "Jarvis", as_of: "2026-11-15T08:00:00Z", base_currency: "EUR", total: "25000.10", partial: true,
    parts: [{ source: "IBKR", value: "23765.54", as_of: "2026-11-15T08:00:00Z" }, { source: "crypto", value: "1234.56", as_of: "2026-11-15T09:00:00Z" }],
  });
  assert.equal(t.total, "25000.10");
  assert.equal(t.partial, true);
  assert.deepEqual(t.parts.map((p) => partLabel(p.source)), ["IBKR", "Crypto"]);
  assert.equal(PARTIAL_TEXT, "Partial: a part that is set up has no value right now and is left out.");
  const base = { source: "Jarvis", as_of: "2026-11-15T08:00:00Z", base_currency: "EUR", total: "0", parts: [], partial: false };
  assert.deepEqual(readTotal(base).parts, []);
  assert.equal(readTotal({ ...base, total: "-12.5" }).total, "-12.5", "a margin account can be negative");
  for (const broken of [
    { ...base, partial: "no" }, { ...base, total: 3 }, { ...base, parts: [{ source: "IBKR", value: "1" }] },
    { ...base, parts: Array(9).fill({ source: "IBKR", value: "1", as_of: base.as_of }) }, { ...base, base_currency: "euro" }, "x",
  ]) {
    assert.equal(readTotal(broken), null, JSON.stringify(broken).slice(0, 60));
  }
});

// From Core's wallet.rs tests: every one is refused before any request.
const WIF_COMPRESSED = "L2fo4Kvj1k79zBLw3RTRacgsVgWPLNQMQbGNNc8LnsLnDt662SF7";
const SOLANA_SECRET = "Ri71QgUgULUbKeM5426JRND3BB68jDLaVn2bdxwTxWse67F9HvGgEGxR7Gp4m5vE6eDnBd2QiX72cSQvTffKwuv";
const HEX64 = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
const mnemonic12 = `${"abandon ".repeat(11)}about`;
const solanaArray = `[${Array(64).fill("174").join(",")}]`;
const KEY_MATERIAL = [
  mnemonic12, `${"abandon ".repeat(23)}art`, mnemonic12.replaceAll(" ", "\n"), mnemonic12.replaceAll(" ", ","), mnemonic12.replaceAll(" ", "-"),
  WIF_COMPRESSED, "5KFbZ6ALEBaeHdRc4dz6rir2AELThVnmE8oMFvUF7SpT8npAgbu", "cRJuYTqc3oV92biruZreVwveD4D6oGZvBRu7TCJ2DWbZBWVtkMPi",
  `"${WIF_COMPRESSED}"`,
  "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
  "zprvAWgYBBk7JR8Gj", "tprv8ZgxMBicQKsPd", HEX64, `0x${HEX64}`, HEX64.toUpperCase(), HEX64.repeat(2), SOLANA_SECRET,
  solanaArray, `  ${solanaArray.replaceAll(",", ", ")}`, "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEE\n-----END EC PRIVATE KEY-----",
];

test("key material is refused before sending, with Core's fixed message and no echo", () => {
  for (const input of KEY_MATERIAL) {
    assert.ok(isKeyMaterial(input), input.slice(0, 12));
    for (const kind of ["bitcoin", "evm", "solana"]) {
      for (const field of ["address", "label"]) {
        const draft = { kind, address: field === "address" ? input : EVM, label: field === "label" ? input : "", chains: ["ethereum"] };
        const issue = draftIssue(draft);
        assert.deepEqual(issue, { field, message: KEY_MATERIAL_TEXT, key: true });
        assert.equal(newWallet(draft), null, "nothing to send");
        for (const word of input.split(/[^0-9A-Za-z]/)) assert.ok(word.length < 6 || !issue.message.includes(word), "echoes input");
      }
    }
  }
  assert.equal(KEY_MATERIAL_TEXT, "This looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only.");
});

test("a long label is checked for key material before its length", () => {
  // The label input lets long pastes through (maxlength 160), so a phrase or
  // key never gets cut to a fragment that passes both checks.
  for (const label of [`Cold ${mnemonic12}`, `Cold ${HEX64}`]) {
    assert.ok(label.length > 40);
    assert.deepEqual(draftIssue({ kind: "evm", address: EVM, label, chains: ["base"] }), { field: "label", message: KEY_MATERIAL_TEXT, key: true });
  }
});

test("words split on every Rust whitespace, U+0085 too", () => {
  assert.ok(isKeyMaterial(mnemonic12.replaceAll(" ", "\u0085")));
  assert.ok(isKeyMaterial(`\u0085[\u0085${Array(64).fill("174").join(",")}]`));
  assert.ok(isKeyMaterial(mnemonic12.replaceAll(" ", "\u00a0")));
});

test("public addresses and plain words are not key material", () => {
  for (const text of [
    "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
    "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", EVM, SOL, "So11111111111111111111111111111111111111112",
    "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
    "eleven words are not a phrase so this text passes fine", "xprvJChwnKLytsecV5vbr8jyFKQLBPGBxRn9YZFNkif", "Cold storage",
  ]) {
    assert.equal(isKeyMaterial(text), false, text);
  }
});

test("each chain's address is checked for its shape", () => {
  for (const ok of [
    "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3",
    "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
    "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", " 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa\n",
  ]) assert.equal(addressIssue("bitcoin", ok), null, ok);
  assert.equal(addressIssue("bitcoin", "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9g"), XPUB_TEXT);
  for (const testnet of ["tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7", "mrS8eVKXguwufwvsVe9GtgGb7fif9UQeAu", "2N9hLwkSqr1cPQAPxbrGVUjxyjD11G2e1he"]) {
    assert.equal(addressIssue("bitcoin", testnet), TESTNET_TEXT, testnet);
  }
  for (const bad of ["", EVM, SOL, "bc1rw5uspcuh", "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN0", "bc1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"]) {
    assert.equal(addressIssue("bitcoin", bad), INVALID_ADDRESS_TEXT.bitcoin, bad);
  }
  for (const ok of [EVM, "0xde709f2102306220921060314715629080e2fb77", "0x52908400098527886E0F7030069857D2E4169EE7"]) assert.equal(addressIssue("evm", ok), null);
  for (const bad of ["5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0X5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", `${EVM}00`, "0xgaaeb6053f3e94c9b9a09f33669435e7ef1beaed"]) {
    assert.equal(addressIssue("evm", bad), INVALID_ADDRESS_TEXT.evm, bad);
  }
  for (const ok of [SOL, "11111111111111111111111111111111", "So11111111111111111111111111111111111111112"]) assert.equal(addressIssue("solana", ok), null, ok);
  for (const bad of ["So1111111111111111111111111111111111111111", EVM, "0OIl1111111111111111111111111111"]) {
    assert.equal(addressIssue("solana", bad), INVALID_ADDRESS_TEXT.solana, bad);
  }
});

test("the add body sends only Core's fields", () => {
  assert.deepEqual(newWallet({ kind: "evm", address: ` ${EVM} `, label: "  Cold ", chains: ["polygon", "ethereum"] }), {
    kind: "evm", address: EVM, label: "Cold", chains: ["ethereum", "polygon"],
  });
  assert.deepEqual(newWallet({ kind: "solana", address: SOL, label: " ", chains: ["ethereum"] }), { kind: "solana", address: SOL });
  assert.deepEqual(draftIssue({ kind: "evm", address: EVM, label: "", chains: [] }), { field: "chains", message: CHAINS_TEXT });
  assert.deepEqual(draftIssue({ kind: "evm", address: EVM, label: "x".repeat(41), chains: ["base"] }), { field: "label", message: LABEL_TEXT });
  assert.deepEqual(draftIssue({ kind: "evm", address: EVM, label: "a​b", chains: ["base"] }), { field: "label", message: LABEL_TEXT });
  assert.equal(draftIssue({ kind: "evm", address: EVM, label: "x".repeat(40), chains: ["base"] }), null);
});

test("off, an older Core and refusals get fixed wording", () => {
  assert.equal(failureOf(apiError(503, { error: "crypto wallets are not enabled" })), "off");
  assert.equal(failureOf(apiError(503)), "failed");
  assert.equal(failureOf(apiError(404)), "no-api");
  assert.equal(failureOf(apiError(405)), "no-api");
  assert.equal(failureOf(apiError(401)), "signin");
  assert.equal(failureOf({ name: "NetworkError" }), "unreachable");
  assert.equal(failureText("off"), "Crypto wallets are off on your Home Node.");
  assert.equal(failureText("no-api"), "Update Core to v0.0.90 or later.");
  assert.equal(NEEDS_CORE, "Update Core to v0.0.90 or later.");
  assert.equal(OFF_TITLE, "Crypto wallets are off on your Home Node.");
  assert.ok(OFF_DETAIL.includes(`${OFF_KEY}=true`) && OFF_DETAIL.includes("docs/CRYPTO_WALLETS.md") && OFF_DETAIL.includes("Egress hosts"));
  const keyRefusal = apiError(400, { error: "this looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only" });
  assert.equal(addFailureText(keyRefusal), KEY_MATERIAL_TEXT);
  assert.equal(isKeyMaterialRefusal(keyRefusal), true);
  assert.equal(isKeyMaterialRefusal(apiError(400, { error: "wallet limit reached" })), false);
  assert.equal(addFailureText(apiError(409, { error: "wallet limit reached" })), "You have 20 wallets, the most Core keeps. Remove one to add another.");
  assert.equal(addFailureText(apiError(409, { error: "this wallet was already added" })), "This wallet was already added.");
  assert.equal(addFailureText(apiError(400, { error: "something new" })), "Core refused this wallet. Check the fields and try again.");
  assert.equal(addFailureText(apiError(400, { error: "toString" })), "Core refused this wallet. Check the fields and try again.");
  assert.equal(addFailureText(apiError(503, { error: "crypto wallets are not enabled" })), `Not added. ${OFF_TITLE}`);
  assert.equal(removeFailureText(apiError(404)), null, "already gone");
  assert.match(removeFailureText(apiError(500)), /^Not removed\./);
});

test("the desk falls back to IBKR quietly only on an older Core", () => {
  assert.equal(totalFailed(apiError(404)), false);
  assert.equal(totalFailed(apiError(405)), false);
  for (const error of [apiError(500), apiError(503), apiError(401), { name: "NetworkError" }, new Error("x"), null]) {
    assert.equal(totalFailed(error), true, JSON.stringify(error));
  }
});

test("a refused refresh waits for Core's cooldown", () => {
  assert.equal(retryAfterSecs(apiError(429, { error: "x", retry_after_secs: 42 })), 42);
  assert.equal(retryAfterSecs(apiError(429, { retry_after_secs: "42" })), 60, "odd value: Core's minute");
  assert.equal(retryAfterSecs(apiError(429)), 60);
  assert.equal(retryAfterSecs(apiError(429, { retry_after_secs: 10 ** 9 })), 3600, "bounded");
  assert.equal(retryAfterSecs(apiError(429, { retry_after_secs: 0 })), 60);
  assert.equal(retryAfterSecs(apiError(503)), null);
  assert.equal(cooldownText(42), "Core refreshes at most once a minute. Try again in 42 s.");
});

test("display helpers and fixed strings", () => {
  assert.equal(shortAddress(EVM), "0x5aAe…eAed");
  assert.equal(shortAddress("0x1234567890abcdef1234567890abcdef1234abcd"), "0x1234…abcd");
  assert.equal(shortAddress("short"), "short");
  assert.equal(otherTokensText(1), "1 other token not counted");
  assert.equal(otherTokensText(4), "4 other tokens not counted");
  assert.equal(NO_PRICE, "no EUR price, not counted");
  assert.equal(ATTRIBUTION, "Prices by CoinGecko");
  assert.equal(DELETE_CONFIRM, "Remove this wallet? Its balances leave the total.");
  assert.equal(HINT, "Add self-custody addresses only, not exchange deposit addresses. Don't also enter these coins as manual holdings.");
  assert.equal(isId(W1), true);
  assert.equal(isId(`${W1}/x`), false);
  assert.equal(isCryptoUpdated({ type: "trading.crypto.updated", payload: { as_of: "2026-11-15T09:00:00Z" } }), true);
  assert.equal(isCryptoUpdated({ type: "trading.meme.decision" }), false);
});
