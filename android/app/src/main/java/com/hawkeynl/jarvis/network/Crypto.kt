package com.hawkeynl.jarvis.network

import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Read-only crypto wallets (desktop src/crypto.ts): Core's contract (docs/CRYPTO_WALLETS.md in
// the Core repository), the response guards, the add form's checks and the fixed owner-facing
// wording. Pure, so tests load it directly.
//
//   GET    /v1/trading/crypto                -> CryptoOverview (503 while the feature is off)
//   POST   /v1/trading/crypto/wallets        {kind, address, label?, chains?}
//   DELETE /v1/trading/crypto/wallets/{id}
//   POST   /v1/trading/crypto/refresh        202, or 429 {retry_after_secs}
//   GET    /v1/trading/total                 -> PortfolioTotal
// Amounts and EUR values are decimal strings, shown through Trading.kt's formatters, never as
// floats. Addresses, labels and Core text are plain text. Anything that looks like key
// material is refused here, before a request, with Core's fixed message; the input is never
// kept or echoed.

enum class CryptoChain(val wire: String, val label: String) {
    BITCOIN("bitcoin", "Bitcoin"), ETHEREUM("ethereum", "Ethereum"), BASE("base", "Base"), ARBITRUM("arbitrum", "Arbitrum"),
    OPTIMISM("optimism", "Optimism"), POLYGON("polygon", "Polygon"), SOLANA("solana", "Solana"),
}

/** The chains one EVM address can cover, in Core's order. */
val EVM_CHAINS = listOf(CryptoChain.ETHEREUM, CryptoChain.BASE, CryptoChain.ARBITRUM, CryptoChain.OPTIMISM, CryptoChain.POLYGON)

enum class WalletKind(val wire: String, val label: String) { BITCOIN("bitcoin", "Bitcoin"), EVM("evm", "EVM"), SOLANA("solana", "Solana") }

/** The kinds the add form offers: those with a chain Core has on. */
fun kindsAvailable(chains: List<CryptoChain>): List<WalletKind> = WalletKind.entries.filter { kind ->
    when (kind) {
        WalletKind.BITCOIN -> CryptoChain.BITCOIN in chains
        WalletKind.EVM -> EVM_CHAINS.any { it in chains }
        WalletKind.SOLANA -> CryptoChain.SOLANA in chains
    }
}

/** [UNKNOWN]: a status this build does not know; it shows as such, never as OK. */
enum class WalletStatus(val wire: String, val label: String) {
    OK("ok", "OK"), STALE("stale", "Stale"), ERROR("error", "Error"), PENDING("pending", "Pending"), UNKNOWN("unknown", "Unknown"),
}

/** [chain] null: a chain this build does not know. */
data class WalletBalance(val chain: CryptoChain?, val symbol: String, val amount: String, val priceEur: String?, val valueEur: String?)

/** [kind] null: a kind this build does not know. [error]: Core's code, or "unknown" when it
 *  sent one this build cannot read. */
data class Wallet(
    val id: String, val kind: WalletKind?, val chains: List<CryptoChain>, val address: String, val label: String?,
    val status: WalletStatus, val error: String?, val lastRefreshAt: String?, val otherTokens: Long, val valueEur: String,
    val balances: List<WalletBalance>,
)

data class CryptoAsset(val symbol: String, val amount: String, val priceEur: String?, val valueEur: String?, val weightPct: String?)

/** [hidden]: wallets this build could not read (left out, still in Core's total). */
data class CryptoOverview(
    val asOf: String, val totalEur: String, val chainsAvailable: List<CryptoChain>, val wallets: List<Wallet>, val assets: List<CryptoAsset>,
    val hidden: Int = 0,
)

data class TotalPart(val source: String, val value: String, val asOf: String)
data class PortfolioTotal(val asOf: String, val baseCurrency: String, val total: String, val parts: List<TotalPart>, val partial: Boolean)

object CryptoLimits {
    const val WALLETS = 50
    const val BALANCES = 100
    const val ASSETS = 50
    const val LABEL = 40
    const val OTHER_TOKENS = 1_000_000L
    const val MAX_RETRY_SECS = 3600L
}

// ---- response guards ------------------------------------------------------------

/** A decimal string as Core writes it: at most 20 integer digits. */
private val DECIMAL = Regex("^-?[0-9]{1,20}(\\.[0-9]{1,28})?$")
private val CODE = Regex("^[a-z0-9_]{1,64}$")
private val SYMBOL = Regex("^[A-Z0-9]{1,12}$")
private val ADDRESS = Regex("^[0-9A-Za-z]{20,128}$")
private val CURRENCY = Regex("^[A-Z]{3}$")

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.walletDec(key: String): String = text(key)?.takeIf { DECIMAL.matches(it) } ?: throw IllegalArgumentException()
private fun JsonObject.walletOptDec(key: String): String? = text(key)?.takeIf { DECIMAL.matches(it) }
private fun validTime(value: String?): String? = value?.takeIf { it.length <= 64 && isoMillis(it) != null }
private fun JsonObject.walletTime(key: String): String = validTime(text(key)) ?: throw IllegalArgumentException()

private fun chainOf(x: JsonElement?): CryptoChain? =
    (x as? JsonPrimitive)?.takeIf { it.isString }?.content.let { wire -> CryptoChain.entries.firstOrNull { it.wire == wire } }

/** Known chains only, each once. */
private fun chainList(x: JsonElement?): List<CryptoChain> =
    (x as? JsonArray).orEmpty().take(CryptoChain.entries.size * 2).mapNotNull(::chainOf).distinct()

private fun readBalance(x: JsonElement): WalletBalance {
    val o = x.obj()
    val symbol = o.text("symbol")?.takeIf { SYMBOL.matches(it) } ?: throw IllegalArgumentException()
    return WalletBalance(chainOf(o["chain"]), symbol, o.walletDec("amount"), o.walletOptDec("price_eur"), o.walletOptDec("value_eur"))
}

private fun readWallet(x: JsonElement): Wallet {
    val o = x.obj()
    val id = o.text("id")?.takeIf(::isJobId) ?: throw IllegalArgumentException()
    val kind = o.text("kind")?.takeIf { CODE.matches(it) } ?: throw IllegalArgumentException()
    val address = o.text("address")?.takeIf { ADDRESS.matches(it) } ?: throw IllegalArgumentException()
    val label = if (o["label"] == null) null else o.text("label")?.takeIf { it.length <= 200 } ?: throw IllegalArgumentException()
    val balances = o.array("balances", CryptoLimits.BALANCES).map(::readBalance)
    val error = if (o["error"] == null) null else o.text("error")?.takeIf { CODE.matches(it) } ?: "unknown"
    val other = (o["other_tokens"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        ?.takeIf { it in 1..CryptoLimits.OTHER_TOKENS } ?: 0L
    val status = o.text("status")
    return Wallet(
        id, WalletKind.entries.firstOrNull { it.wire == kind }, chainList(o["chains"]), address, label?.ifEmpty { null },
        WalletStatus.entries.firstOrNull { it != WalletStatus.UNKNOWN && it.wire == status } ?: WalletStatus.UNKNOWN,
        error, validTime(o.text("last_refresh_at")), other, o.walletDec("value_eur"), balances,
    )
}

/** GET /v1/trading/crypto. A wallet this build cannot read is left out and counted; anything
 *  else unexpected fails the whole read (null). */
fun readCryptoOverview(x: JsonElement): CryptoOverview? = guarded {
    val o = x.obj()
    require(o.text("base_currency") == "EUR" && o["chains_available"] is JsonArray)
    val wallets = o.array("wallets", CryptoLimits.WALLETS).map { guarded { readWallet(it) } }
    val assets = o.array("assets", CryptoLimits.ASSETS).map {
        val a = it.obj()
        CryptoAsset(
            a.text("symbol")?.takeIf { s -> SYMBOL.matches(s) } ?: throw IllegalArgumentException(), a.walletDec("amount"),
            a.walletOptDec("price_eur"), a.walletOptDec("value_eur"), a.walletOptDec("weight_pct"),
        )
    }
    CryptoOverview(o.walletTime("as_of"), o.walletDec("total_eur"), chainList(o["chains_available"]), wallets.filterNotNull(), assets, wallets.count { it == null })
}

/** GET /v1/trading/total: at most a few parts, each with its own time. */
fun readPortfolioTotal(x: JsonElement): PortfolioTotal? = guarded {
    val o = x.obj()
    val currency = o.text("base_currency")?.takeIf { CURRENCY.matches(it) } ?: throw IllegalArgumentException()
    val parts = o.array("parts", 8).map {
        val p = it.obj()
        val source = p.text("source")?.takeIf { s -> s.isNotEmpty() && s.length <= 32 } ?: throw IllegalArgumentException()
        TotalPart(source, p.walletDec("value"), p.walletTime("as_of"))
    }
    PortfolioTotal(o.walletTime("as_of"), currency, o.walletDec("total"), parts, o.bool("partial"))
}

/** "IBKR", "Crypto"; another source Core adds later shows as Core names it. */
fun partLabel(source: String): String = if (source == "crypto") "Crypto" else source
const val PARTIAL_TEXT = "Partial: a part that is set up has no value right now and is left out."
const val TOTAL_FAILED_TEXT = "Crypto wallets are not in this value: Core could not give the total just now."

/** The Desk's total: [total] when Core gave it (IBKR's value is then labelled "IBKR value"),
 *  else IBKR's value is the total; [failed] when Core could not give it (not an older Core's
 *  404 or 405). The total card shows once a part is set up ([shown]). */
data class DeskTotal(val total: PortfolioTotal?, val failed: Boolean) {
    val shown: PortfolioTotal? get() = total?.takeIf { it.parts.isNotEmpty() || it.partial }
}

fun deskTotal(result: ApiResult<PortfolioTotal>): DeskTotal = when {
    result is ApiResult.Success -> DeskTotal(result.value, false)
    result is ApiResult.HttpError && (result.status == 404 || result.status == 405) -> DeskTotal(null, false)
    else -> DeskTotal(null, true)
}

// ---- wording ------------------------------------------------------------------

const val CRYPTO_SUBTITLE = "Read-only balances of your self-custody wallets, in EUR."
const val CRYPTO_HINT = "Add self-custody addresses only, not exchange deposit addresses. Don't also enter these coins as manual holdings."
const val CRYPTO_ATTRIBUTION = "Prices by CoinGecko"
const val NO_PRICE = "no EUR price, not counted"
const val DELETE_CONFIRM = "Remove this wallet? Its balances leave the total."
const val CRYPTO_OFF = "Crypto wallets are off on your Home Node."
const val CRYPTO_OFF_KEY = "JARVIS_CRYPTO_WALLETS_ENABLED"
const val CRYPTO_OFF_DETAIL =
    "To turn them on, allow the provider hosts in your Home Node's egress rules (see \"Egress hosts\" in docs/CRYPTO_WALLETS.md in the Core repository), set $CRYPTO_OFF_KEY=true in Core's environment and restart Core."
const val CRYPTO_NEEDS_CORE = "Update Core to v0.0.90 or later."
const val NO_CHAINS = "No chain is on in Core, so no wallet can be added."

fun otherTokensText(n: Long): String = "$n other ${if (n == 1L) "token" else "tokens"} not counted"
fun hiddenText(n: Int): String =
    "${if (n == 1) "1 wallet" else "$n wallets"} this app cannot show yet. Update the app to see ${if (n == 1) "it" else "them"}."

/** "0x1234…abcd": the start and end an owner compares; short ones whole. */
fun shortAddress(address: String): String = if (address.length <= 12) address else "${address.take(6)}…${address.takeLast(4)}"

/** The wallet's name: its label, or its kind. */
fun walletName(w: Wallet): String = w.label ?: w.kind?.label ?: "Wallet"

private val WALLET_ERROR_TEXT = mapOf(
    "unavailable" to "A provider could not be reached or answered with an error, or the chain is off on Core.",
    "rate_limited" to "A provider's rate limit was hit. Core tries again later.",
    "invalid_response" to "A provider sent an answer Core does not accept.",
    "too_many_tokens" to "This Solana wallet has more token accounts than Core reads.",
)

/** The owner-facing text of a wallet's error code; never the code's own words. */
fun walletErrorText(code: String): String =
    "${WALLET_ERROR_TEXT[code] ?: "Core reported an error this app does not know yet."} The last good balances still count."

// ---- add form -------------------------------------------------------------------

const val KEY_MATERIAL_TEXT =
    "This looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only."
const val XPUB_TEXT = "Extended public keys (xpub) are not supported; add single addresses."
const val TESTNET_TEXT = "Only mainnet addresses are supported."
fun invalidAddressText(kind: WalletKind): String = when (kind) {
    WalletKind.BITCOIN -> "Not a valid Bitcoin address."
    WalletKind.EVM -> "Not a valid EVM address: 0x followed by 40 hex digits."
    WalletKind.SOLANA -> "Not a valid Solana address."
}
const val CHAINS_TEXT = "Pick at least one chain."
const val LABEL_TEXT = "The label can be 1 to ${CryptoLimits.LABEL} characters on one line."

private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// ponytail: BigInteger copies cannot be wiped; the input lives only in the form and is
// cleared on refusal. Wipe by hand if this ever holds key material for longer.
/** Base58 (Bitcoin alphabet) as Core's bs58: the decoded bytes, or null for text outside it. */
internal fun base58(text: String): ByteArray? {
    var n = BigInteger.ZERO
    for (c in text) {
        val digit = BASE58.indexOf(c).takeIf { it >= 0 } ?: return null
        n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
    }
    val body = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
    return ByteArray(text.takeWhile { it == '1' }.length) + body
}

private fun isSolanaAddress(text: String): Boolean = text.length in 32..44 && base58(text)?.size == 32
private val PRIVATE_PREFIXES = listOf("xprv", "yprv", "zprv", "tprv", "uprv", "vprv", "Yprv", "Zprv")
private val PUBLIC_PREFIXES = listOf("xpub", "ypub", "zpub", "tpub", "upub", "vpub", "Ypub", "Zpub")
private val WORD_BREAK = Regex("[\\p{IsWhite_Space},;\\-]")
private val NOT_ALNUM = Regex("[^0-9A-Za-z]")
private val JSON_BYTES = Regex("^\\p{IsWhite_Space}*\\[\\p{IsWhite_Space}*[0-9]")
private val HEX = Regex("^[0-9a-fA-F]+$")

private fun isKeyToken(token: String): Boolean {
    val hex = if (token.startsWith("0x") || token.startsWith("0X")) token.drop(2) else token
    if (PRIVATE_PREFIXES.any { token.startsWith(it) } && !isSolanaAddress(token)) return true
    if ((hex.length == 64 || hex.length == 128) && HEX.matches(hex)) return true
    if (token.length == 51 || token.length == 52) {
        val b = base58(token)
        if (b != null && (b.size == 37 || b.size == 38) && (b[0] == 0x80.toByte() || b[0] == 0xef.toByte())) return true
    }
    return token.length in 64..88 && base58(token)?.size == 64
}

/** Core's is_key_material (crates/market-data/src/wallet.rs): a phrase of 12+ words, a PEM
 *  block, a JSON byte array, or a token that is an extended private key, 64 or 128 hex
 *  digits, a WIF key or base58 of 64 bytes. */
fun isKeyMaterial(input: String): Boolean =
    "-----BEGIN" in input ||
        input.split(WORD_BREAK).count { it.isNotEmpty() } >= 12 ||
        JSON_BYTES.containsMatchIn(input) ||
        input.split(NOT_ALNUM).any(::isKeyToken)

private val BECH32 = Regex("^bc1([02-9ac-hj-np-z]{39}|[02-9ac-hj-np-z]{59})$")
private val LEGACY = Regex("^[13][1-9A-HJ-NP-Za-km-z]{25,34}$")
private val TESTNET_LEGACY = Regex("^[mn2][1-9A-HJ-NP-Za-km-z]{25,34}$")
private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

/** Why the address does not fit the kind, or null. Shape only: Core checks the checksum
 *  again (400). Key material is checked before this. */
fun addressIssue(kind: WalletKind, input: String): String? {
    val a = input.trim()
    return when (kind) {
        WalletKind.BITCOIN -> {
            val lower = a.lowercase()
            when {
                PUBLIC_PREFIXES.any { a.startsWith(it) } -> XPUB_TEXT
                lower.startsWith("tb1") || lower.startsWith("bcrt1") || TESTNET_LEGACY.matches(a) -> TESTNET_TEXT
                ((a == lower || a == a.uppercase()) && BECH32.matches(lower)) || LEGACY.matches(a) -> null
                else -> invalidAddressText(kind)
            }
        }
        WalletKind.EVM -> if (EVM_ADDRESS.matches(a)) null else invalidAddressText(kind)
        WalletKind.SOLANA -> if (isSolanaAddress(a)) null else invalidAddressText(kind)
    }
}

enum class DraftField { ADDRESS, LABEL, CHAINS }

/** [key]: the field holds key material; the form clears it. */
data class DraftIssue(val field: DraftField, val message: String, val key: Boolean = false)

/** The first rule the typed wallet breaks, key material first, or null. */
fun draftIssue(kind: WalletKind, address: String, chains: List<CryptoChain>, label: String): DraftIssue? {
    if (isKeyMaterial(address)) return DraftIssue(DraftField.ADDRESS, KEY_MATERIAL_TEXT, key = true)
    if (isKeyMaterial(label)) return DraftIssue(DraftField.LABEL, KEY_MATERIAL_TEXT, key = true)
    addressIssue(kind, address)?.let { return DraftIssue(DraftField.ADDRESS, it) }
    if (kind == WalletKind.EVM && EVM_CHAINS.none { it in chains }) return DraftIssue(DraftField.CHAINS, CHAINS_TEXT)
    if (label.isNotBlank() && !validText(label.trim(), CryptoLimits.LABEL)) return DraftIssue(DraftField.LABEL, LABEL_TEXT)
    return null
}

/** The create body (only Core's fields; chains only for EVM, in Core's order); null while
 *  the typed wallet breaks a rule. */
fun newWallet(kind: WalletKind, address: String, chains: List<CryptoChain>, label: String): JsonObject? {
    if (draftIssue(kind, address, chains, label) != null) return null
    return buildJsonObject {
        put("kind", kind.wire)
        put("address", address.trim())
        label.trim().takeIf { it.isNotEmpty() }?.let { put("label", it) }
        if (kind == WalletKind.EVM) putJsonArray("chains") { EVM_CHAINS.filter { it in chains }.forEach { add(JsonPrimitive(it.wire)) } }
    }
}

// ---- failures ----------------------------------------------------------------

/** The InvalidResponse message of a call this app refused before sending (a wallet the form
 *  refuses, or an id that is not a UUID): nothing reached Core. */
const val INVALID_WALLET = "Invalid wallet"
const val WALLET_NOT_SENT = "Not sent: this app refused the wallet. Check it and try again."
private fun notSent(result: ApiResult<*>) = result is ApiResult.InvalidResponse && result.message == INVALID_WALLET

/** Core's message when the feature is off (503). */
const val CRYPTO_OFF_MESSAGE = "crypto wallets are not enabled"

enum class CryptoFailure { OFF, NO_API, UNREACHABLE, SIGNIN, FAILED }

/** A failed read or write: 503 "crypto wallets are not enabled" is off; an older Core without
 *  the routes answers 404 or 405. Crypto calls carry Core's `error` text in HttpError.message. */
fun cryptoFailureOf(result: ApiResult<*>): CryptoFailure = when (result) {
    is ApiResult.Unreachable -> CryptoFailure.UNREACHABLE
    ApiResult.Unauthorized -> CryptoFailure.SIGNIN
    is ApiResult.HttpError -> when {
        result.status == 401 -> CryptoFailure.SIGNIN
        result.status == 404 || result.status == 405 || result.status == 501 -> CryptoFailure.NO_API
        result.status == 503 && result.message == CRYPTO_OFF_MESSAGE -> CryptoFailure.OFF
        else -> CryptoFailure.FAILED
    }
    else -> CryptoFailure.FAILED
}

fun cryptoFailureText(failure: CryptoFailure): String = when (failure) {
    CryptoFailure.OFF -> CRYPTO_OFF
    CryptoFailure.NO_API -> CRYPTO_NEEDS_CORE
    CryptoFailure.UNREACHABLE -> "No answer from your Home Node. Check the connection."
    CryptoFailure.SIGNIN -> "Your session is not valid. Sign this device in again."
    CryptoFailure.FAILED -> "Core returned an answer this app does not understand, or an error. Try again later."
}

/** Core's fixed refusals (400, 409), in the app's words. */
private val REFUSAL_TEXT = mapOf(
    "this looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only" to KEY_MATERIAL_TEXT,
    "extended public keys (xpub) are not supported; add single addresses" to XPUB_TEXT,
    "only mainnet addresses are supported" to TESTNET_TEXT,
    "not a valid address for this wallet kind" to "Not a valid address for this chain. Check it for typos.",
    "choose one or more EVM chains, each once" to CHAINS_TEXT,
    "label must be 1 to 40 visible characters" to LABEL_TEXT,
    "a chosen chain is not available on this Core" to "A chosen chain is not available on your Home Node.",
    "this wallet was already added" to "This wallet was already added.",
    "wallet limit reached" to "You have 20 wallets, the most Core keeps. Remove one to add another.",
)

/** The notice after a refused add. */
fun addWalletFailureText(result: ApiResult<*>): String = when {
    result is ApiResult.HttpError && (result.status == 400 || result.status == 409) -> REFUSAL_TEXT[result.message]
        ?: if (result.status == 409) "Core refused this: the wallet was already added, or you have 20 wallets."
        else "Core refused this wallet. Check the fields and try again."
    result is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the wallet was added; reloaded to check."
    notSent(result) -> WALLET_NOT_SENT
    // A 2xx whose answer is oversized or unreadable: the wallet may have been added.
    result is ApiResult.InvalidResponse -> NOT_CONFIRMED
    else -> "Not added. ${cryptoFailureText(cryptoFailureOf(result))}"
}

/** Core refused the body as key material: the form forgets what was typed. */
fun isKeyMaterialRefusal(result: ApiResult<*>): Boolean =
    result is ApiResult.HttpError && result.status == 400 && REFUSAL_TEXT[result.message] == KEY_MATERIAL_TEXT

/** The notice after a failed remove; null when the wallet is already gone. */
fun removeWalletFailureText(result: ApiResult<*>): String? = when {
    result is ApiResult.HttpError && result.status == 404 -> null
    result is ApiResult.Unreachable -> "No answer from your Home Node. It is unknown whether the wallet was removed; reloaded to check."
    notSent(result) -> WALLET_NOT_SENT
    result is ApiResult.InvalidResponse -> NOT_CONFIRMED
    else -> "Not removed. ${cryptoFailureText(cryptoFailureOf(result))}"
}

/** Core's answer to a refresh: queued, or asked less than a minute ago (429). */
sealed interface CryptoRefresh {
    data object Queued : CryptoRefresh
    data class Cooldown(val seconds: Long) : CryptoRefresh
}

/** Seconds until Core takes another refresh, from a 429's body; a missing or odd value waits
 *  Core's minute. */
fun retryAfterSecs(body: JsonElement?): Long =
    ((body as? JsonObject)?.get("retry_after_secs") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        ?.takeIf { it >= 1 }?.coerceAtMost(CryptoLimits.MAX_RETRY_SECS) ?: 60L

fun cooldownText(secs: Long): String = "Core refreshes at most once a minute. Try again in $secs s."
const val REFRESH_ASKED = "Refreshing. The page updates when Core is done."
const val WALLET_ADDED = "Added. Core reads its balances in a moment."
const val WALLET_REMOVED = "Removed."
