package com.hawkeynl.jarvis.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors desktop tests/crypto.test.mjs and Core's wallet.rs vectors. Neutral test data only:
// the keys below are Core's random throwaway test values, never funded or used.
class CryptoTest {
    private val w1 = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"
    private val evm = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val sol = "So11111111111111111111111111111111111111112"
    private val wallet = """{"id":"$w1","kind":"evm","chains":["ethereum","base"],"address":"$evm","label":"Cold","status":"ok",
        "last_refresh_at":"2026-11-15T10:00:00Z","value_eur":"2400.5","balances":[
        {"chain":"ethereum","asset":"eth","symbol":"ETH","amount":"1.000000000000000001","price_eur":"2400.5","value_eur":"2400.5"},
        {"chain":"base","asset":"usdc","symbol":"USDC","amount":"12"}]}"""
    private val overview = """{"source":"crypto","as_of":"2026-11-15T10:00:00Z","base_currency":"EUR","total_eur":"2400.5",
        "chains_available":["bitcoin","ethereum","base","solana","later_chain"],"wallets":[$wallet],
        "assets":[{"asset":"eth","symbol":"ETH","amount":"1.000000000000000001","price_eur":"2400.5","value_eur":"2400.5","weight_pct":"100"},
        {"asset":"usdc","symbol":"USDC","amount":"12"}]}"""

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun withField(base: String, field: String, value: String): String = JsonObject(json(base).jsonObject + (field to json(value))).toString()

    @Test fun `the overview reads Core's shape and keeps amounts as sent`() {
        val o = readCryptoOverview(json(overview))!!
        assertEquals("2400.5", o.totalEur)
        assertEquals("an unknown chain is never offered", listOf(CryptoChain.BITCOIN, CryptoChain.ETHEREUM, CryptoChain.BASE, CryptoChain.SOLANA), o.chainsAvailable)
        val w = o.wallets.single()
        assertEquals(WalletKind.EVM, w.kind)
        assertEquals(WalletStatus.OK, w.status)
        assertEquals("1.000000000000000001", w.balances[0].amount)
        assertEquals("1.000000000000000001", formatDecimal(w.balances[0].amount))
        assertNull(w.balances[1].priceEur)
        assertEquals("100", o.assets[0].weightPct)
        assertEquals(listOf(WalletKind.BITCOIN, WalletKind.EVM, WalletKind.SOLANA), kindsAvailable(o.chainsAvailable))
        assertEquals(listOf(WalletKind.EVM), kindsAvailable(listOf(CryptoChain.POLYGON)))
        // Optional fields are absent; a pending wallet with no balances reads.
        val pending = """{"id":"$w1","kind":"solana","chains":["solana"],"address":"$sol","status":"pending","value_eur":"0","balances":[],"other_tokens":3}"""
        val p = readCryptoOverview(json(withField(overview, "wallets", "[$pending]")))!!.wallets.single()
        assertEquals(3L, p.otherTokens)
        assertNull(p.label)
        assertNull(p.lastRefreshAt)
    }

    @Test fun `unknown values show as unknown, never as OK`() {
        val w = readCryptoOverview(json(withField(overview, "wallets", "[${withField(withField(withField(withField(wallet, "status", "\"frozen\""),
            "kind", "\"tron\""), "chains", "[\"base\",\"later\",\"base\"]"), "error", "\"Upstream <b>text</b>\"")}]")))!!.wallets.single()
        assertEquals(WalletStatus.UNKNOWN, w.status)
        assertEquals("Unknown", w.status.label)
        assertNull(w.kind)
        assertEquals("Cold", walletName(w))
        assertEquals("Wallet", walletName(w.copy(label = null)))
        assertEquals(listOf(CryptoChain.BASE), w.chains)
        assertEquals("unknown", w.error)
        assertEquals("Core reported an error this app does not know yet. The last good balances still count.", walletErrorText(w.error!!))
        assertEquals("EVM", walletName(readCryptoOverview(json(overview))!!.wallets.single().copy(label = null)))
        val odd = readCryptoOverview(json(withField(overview, "wallets", "[${withField(withField(wallet, "other_tokens", "\"3\""), "last_refresh_at", "\"yesterday\"")}]")))!!.wallets.single()
        assertEquals(0L, odd.otherTokens)
        assertNull(odd.lastRefreshAt)
    }

    @Test fun `a wallet that does not read is hidden and counted, a broken overview fails`() {
        for ((field, value) in listOf(
            "id" to "\"../x\"", "kind" to "\"Not A Code\"", "address" to "\"0x12 34\"", "address" to "\"short\"",
            "value_eur" to "2400.5", "value_eur" to "\"1e5\"", "value_eur" to "\"${"9".repeat(21)}\"", "label" to "5",
            "balances" to "[{\"symbol\":\"eth\",\"amount\":\"1\"}]", "balances" to "{}",
        )) {
            val o = readCryptoOverview(json(withField(overview, "wallets", "[$wallet,${withField(wallet, field, value)}]")))!!
            assertEquals("$field $value", 1, o.wallets.size)
            assertEquals("$field $value", 1, o.hidden)
        }
        assertEquals("1 wallet this app cannot show yet. Update the app to see it.", hiddenText(1))
        assertEquals("2 wallets this app cannot show yet. Update the app to see them.", hiddenText(2))
        for (broken in listOf(
            "null", "[]", withField(overview, "base_currency", "\"USD\""), withField(overview, "total_eur", "1"),
            withField(overview, "as_of", "\"soon\""), withField(overview, "wallets", "{}"), withField(overview, "chains_available", "null"),
            withField(overview, "assets", "[{\"symbol\":\"ETH\"}]"), withField(overview, "wallets", "[${List(51) { wallet }.joinToString(",")}]"),
        )) assertNull(broken.take(80), readCryptoOverview(json(broken)))
    }

    @Test fun `the total reads its parts and partial flag`() {
        val read = readPortfolioTotal(json("""{"source":"Jarvis","as_of":"2026-11-15T09:00:00Z","base_currency":"EUR","total":"12400.5",
            "parts":[{"source":"IBKR","value":"10000","as_of":"2026-11-15T09:00:00Z"},{"source":"crypto","value":"2400.5","as_of":"2026-11-15T10:00:00Z"}],"partial":true}"""))!!
        assertEquals("12400.5", read.total)
        assertEquals(listOf("IBKR", "Crypto"), read.parts.map { partLabel(it.source) })
        assertTrue(read.partial)
        for (bad in listOf(
            """{"as_of":"2026-11-15T09:00:00Z","base_currency":"EUR","total":"1","parts":[]}""",
            """{"as_of":"2026-11-15T09:00:00Z","base_currency":"EUR","total":"1","parts":[],"partial":"no"}""",
            """{"as_of":"2026-11-15T09:00:00Z","base_currency":"EUR","total":"1","parts":[{"source":"","value":"1","as_of":"2026-11-15T09:00:00Z"}],"partial":false}""",
        )) assertNull(bad, readPortfolioTotal(json(bad)))
        // The Desk: the card once a part is set up; IBKR's value alone for an older Core.
        assertEquals(read, deskTotal(ApiResult.Success(read)).shown)
        val none = read.copy(parts = emptyList(), partial = false)
        assertNull(deskTotal(ApiResult.Success(none)).shown)
        assertEquals(none, deskTotal(ApiResult.Success(none)).total)
        for (status in listOf(404, 405)) assertEquals(DeskTotal(null, false), deskTotal(ApiResult.HttpError(status, null)))
        assertEquals(DeskTotal(null, true), deskTotal(ApiResult.HttpError(500, null)))
        assertEquals(DeskTotal(null, true), deskTotal(ApiResult.InvalidResponse("x")))
        assertEquals("Partial: a part that is set up has no value right now and is left out.", PARTIAL_TEXT)
    }

    private val keyMaterial: List<String> = run {
        val mnemonic12 = "abandon ".repeat(11) + "about"
        val hex64 = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"
        val wif = "L2fo4Kvj1k79zBLw3RTRacgsVgWPLNQMQbGNNc8LnsLnDt662SF7"
        val array = "[" + List(64) { "174" }.joinToString(",") + "]"
        listOf(
            mnemonic12, "abandon ".repeat(23) + "art", mnemonic12.replace(' ', '\n'), mnemonic12.replace(' ', ','), mnemonic12.replace(' ', '-'),
            wif, "5KFbZ6ALEBaeHdRc4dz6rir2AELThVnmE8oMFvUF7SpT8npAgbu", "cRJuYTqc3oV92biruZreVwveD4D6oGZvBRu7TCJ2DWbZBWVtkMPi", "\"$wif\"",
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
            "zprvAWgYBBk7JR8Gj", "tprv8ZgxMBicQKsPd", hex64, "0x$hex64", hex64.uppercase(), hex64.repeat(2),
            "Ri71QgUgULUbKeM5426JRND3BB68jDLaVn2bdxwTxWse67F9HvGgEGxR7Gp4m5vE6eDnBd2QiX72cSQvTffKwuv",
            array, "  " + array.replace(",", ", "), "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEE\n-----END EC PRIVATE KEY-----",
        )
    }

    @Test fun `key material is refused first, with Core's fixed message, never echoed`() {
        for (input in keyMaterial) {
            assertTrue(input, isKeyMaterial(input))
            for (kind in WalletKind.entries) {
                val issue = draftIssue(kind, input, EVM_CHAINS, "")!!
                assertEquals("$kind $input", DraftIssue(DraftField.ADDRESS, KEY_MATERIAL_TEXT, key = true), issue)
                for (word in input.split(Regex("[^A-Za-z0-9]+"))) assertTrue(issue.message, word.length < 6 || word !in issue.message)
                assertNull(newWallet(kind, input, EVM_CHAINS, ""))
            }
            // A key in the label is refused as well.
            assertEquals(DraftIssue(DraftField.LABEL, KEY_MATERIAL_TEXT, key = true), draftIssue(WalletKind.EVM, evm, EVM_CHAINS, input))
        }
        assertEquals(
            "This looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only.",
            KEY_MATERIAL_TEXT,
        )
    }

    @Test fun `public addresses are not key material`() {
        for (address in listOf(
            "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", evm,
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", sol,
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
            "eleven words are not a phrase so this text passes fine",
        )) assertFalse(address, isKeyMaterial(address))
    }

    @Test fun `each chain checks its own address format`() {
        for (ok in listOf("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", " bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0 ")) {
            assertNull(ok, addressIssue(WalletKind.BITCOIN, ok))
        }
        assertEquals(XPUB_TEXT, addressIssue(WalletKind.BITCOIN, "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"))
        assertEquals(TESTNET_TEXT, addressIssue(WalletKind.BITCOIN, "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertEquals(TESTNET_TEXT, addressIssue(WalletKind.BITCOIN, "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"))
        for (bad in listOf("", "bc1Qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t", evm, sol, "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN0")) {
            assertEquals(bad, "Not a valid Bitcoin address.", addressIssue(WalletKind.BITCOIN, bad))
        }
        assertNull(draftIssue(WalletKind.EVM, evm, listOf(CryptoChain.BASE), ""))
        assertNull(draftIssue(WalletKind.EVM, evm.lowercase(), listOf(CryptoChain.BASE), "Cold storage"))
        for (bad in listOf("5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAe", "0xZaAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", sol)) {
            assertEquals(bad, "Not a valid EVM address: 0x followed by 40 hex digits.", addressIssue(WalletKind.EVM, bad))
        }
        assertEquals(DraftIssue(DraftField.CHAINS, CHAINS_TEXT), draftIssue(WalletKind.EVM, evm, emptyList(), ""))
        assertNull(draftIssue(WalletKind.SOLANA, sol, emptyList(), ""))
        for (bad in listOf(evm, "So1111111111111111111111111111111111111111O", "short")) assertEquals(bad, "Not a valid Solana address.", addressIssue(WalletKind.SOLANA, bad))
        assertEquals(DraftIssue(DraftField.LABEL, LABEL_TEXT), draftIssue(WalletKind.SOLANA, sol, emptyList(), "x".repeat(41)))
        assertEquals("The label can be 1 to 40 characters on one line.", LABEL_TEXT)
        assertNull(draftIssue(WalletKind.SOLANA, sol, emptyList(), "x".repeat(40)))
    }

    @Test fun `the create body carries chains only for EVM, in Core's order`() {
        assertEquals(json("""{"kind":"evm","address":"$evm","label":"Cold","chains":["ethereum","polygon"]}"""),
            newWallet(WalletKind.EVM, " $evm ", listOf(CryptoChain.POLYGON, CryptoChain.ETHEREUM), " Cold "))
        assertEquals(json("""{"kind":"solana","address":"$sol"}"""), newWallet(WalletKind.SOLANA, sol, EVM_CHAINS, "  "))
    }

    @Test fun `display helpers and fixed wording`() {
        assertEquals("0x5aAe…eAed", shortAddress(evm))
        assertEquals("short", shortAddress("short"))
        assertEquals("1 other token not counted", otherTokensText(1))
        assertEquals("3 other tokens not counted", otherTokensText(3))
        assertEquals("A provider's rate limit was hit. Core tries again later. The last good balances still count.", walletErrorText("rate_limited"))
        assertEquals("Add self-custody addresses only, not exchange deposit addresses. Don't also enter these coins as manual holdings.", CRYPTO_HINT)
        assertEquals("Prices by CoinGecko", CRYPTO_ATTRIBUTION)
        assertEquals("Remove this wallet? Its balances leave the total.", DELETE_CONFIRM)
        assertEquals("no EUR price, not counted", NO_PRICE)
    }

    @Test fun `503 off, 404 and 405 an older Core, refusals in the app's words`() {
        assertEquals(CryptoFailure.OFF, cryptoFailureOf(ApiResult.HttpError(503, CRYPTO_OFF_MESSAGE)))
        assertEquals("another 503 is not the feature being off", CryptoFailure.FAILED, cryptoFailureOf(ApiResult.HttpError(503, null)))
        assertEquals("Crypto wallets are off on your Home Node.", cryptoFailureText(CryptoFailure.OFF))
        assertTrue(CRYPTO_OFF_DETAIL.contains("JARVIS_CRYPTO_WALLETS_ENABLED=true") && CRYPTO_OFF_DETAIL.contains("docs/CRYPTO_WALLETS.md"))
        for (status in listOf(404, 405)) assertEquals(CryptoFailure.NO_API, cryptoFailureOf(ApiResult.HttpError(status, null)))
        assertEquals("Update Core to v0.0.90 or later.", cryptoFailureText(CryptoFailure.NO_API))
        assertEquals(CryptoFailure.SIGNIN, cryptoFailureOf(ApiResult.Unauthorized))
        assertEquals(CryptoFailure.FAILED, cryptoFailureOf(ApiResult.InvalidResponse("x")))
        assertEquals("This wallet was already added.", addWalletFailureText(ApiResult.HttpError(409, "this wallet was already added")))
        assertEquals("You have 20 wallets, the most Core keeps. Remove one to add another.", addWalletFailureText(ApiResult.HttpError(409, "wallet limit reached")))
        assertEquals("Core refused this wallet. Check the fields and try again.", addWalletFailureText(ApiResult.HttpError(400, "<b>new</b>")))
        val keyRefusal = ApiResult.HttpError(400, "this looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only")
        assertEquals(KEY_MATERIAL_TEXT, addWalletFailureText(keyRefusal))
        assertTrue(isKeyMaterialRefusal(keyRefusal))
        assertFalse(isKeyMaterialRefusal(ApiResult.HttpError(400, "only mainnet addresses are supported")))
        assertEquals(WALLET_NOT_SENT, addWalletFailureText(ApiResult.InvalidResponse(INVALID_WALLET)))
        assertEquals(NOT_CONFIRMED, addWalletFailureText(ApiResult.InvalidResponse("x")))
        assertEquals("Not added. Update Core to v0.0.90 or later.", addWalletFailureText(ApiResult.HttpError(404, null)))
        assertNull("already gone", removeWalletFailureText(ApiResult.HttpError(404, null)))
        assertEquals("Not removed. ${cryptoFailureText(CryptoFailure.FAILED)}", removeWalletFailureText(ApiResult.HttpError(500, "internal error")))
    }

    @Test fun `the refresh cooldown reads Core's retry_after_secs within bounds`() {
        assertEquals(42L, retryAfterSecs(json("""{"error":"x","retry_after_secs":42}""")))
        assertEquals(3600L, retryAfterSecs(json("""{"retry_after_secs":99999}""")))
        for (bad in listOf("""{"retry_after_secs":0}""", """{"retry_after_secs":"42"}""", """{"retry_after_secs":1.5}""", """{}""", "null")) {
            assertEquals(bad, 60L, retryAfterSecs(json(bad)))
        }
        assertEquals(60L, retryAfterSecs(null))
        assertEquals("Core refreshes at most once a minute. Try again in 42 s.", cooldownText(42))
    }
}
