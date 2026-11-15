import CryptoKit
import Foundation

// Read-only crypto wallets (/v1/trading/crypto, docs/CRYPTO_WALLETS.md in the Core
// repository) and the portfolio total (/v1/trading/total), as in desktop crypto.ts. The
// owner adds public addresses only: anything that looks like key material is refused here
// before it is sent, with Core's own fixed message, and is never echoed, logged or kept.
// Amounts are Core's decimal strings, shown as sent; Core's text is shown as plain text.

enum CryptoChain: String, CaseIterable {
    case bitcoin, ethereum, base, arbitrum, optimism, polygon, solana

    var label: String {
        switch self {
        case .bitcoin: "Bitcoin"
        case .ethereum: "Ethereum"
        case .base: "Base"
        case .arbitrum: "Arbitrum"
        case .optimism: "Optimism"
        case .polygon: "Polygon"
        case .solana: "Solana"
        }
    }

    /// The chains one EVM address covers.
    static let evm: [CryptoChain] = [.ethereum, .base, .arbitrum, .optimism, .polygon]
}

enum CryptoWalletKind: String, Codable, CaseIterable {
    case bitcoin, evm, solana

    var label: String {
        switch self {
        case .bitcoin: "Bitcoin"
        case .evm: "EVM"
        case .solana: "Solana"
        }
    }

    /// The chains a wallet of this kind can be on.
    var chains: [CryptoChain] {
        switch self {
        case .bitcoin: [.bitcoin]
        case .evm: CryptoChain.evm
        case .solana: [.solana]
        }
    }
}

/// A wallet's status; `unknown` for one this app does not know, never shown as OK.
enum CryptoWalletStatus: String {
    case ok, stale, error, pending, unknown

    var label: String {
        switch self {
        case .ok: "OK"
        case .stale: "Stale"
        case .error: "Error"
        case .pending: "Pending"
        case .unknown: "Unknown"
        }
    }

    var tone: Tone {
        switch self {
        case .ok: .ok
        case .stale: .warn
        case .error: .error
        case .pending, .unknown: .idle
        }
    }
}

struct CryptoBalance {
    /// Nil for a chain this app does not know: shown as "Other".
    let chain: CryptoChain?
    let symbol: String
    let amount: String
    let price_eur: String?
    let value_eur: String?
}

struct CryptoWallet: Identifiable {
    let id: String
    let kind: String
    let chains: [CryptoChain]
    let address: String
    let label: String?
    let status: CryptoWalletStatus
    /// A code; "unknown" for one that is not a code.
    let error: String?
    let last_refresh_at: String?
    /// 0 when Core sent none, or nothing usable.
    let other_tokens: Int
    let value_eur: String
    let balances: [CryptoBalance]

    var uuid: UUID? { UUID(uuidString: id) }
    /// Its label, or its kind.
    var name: String { label ?? CryptoWalletKind(rawValue: kind)?.label ?? "Wallet" }
}

struct CryptoAsset {
    let symbol: String
    let amount: String
    let price_eur: String?
    let value_eur: String?
    let weight_pct: String?
}

/// GET /v1/trading/crypto. A wallet this app cannot read is left out and counted
/// (`hidden`, still in Core's total); anything else unexpected fails the whole read.
struct CryptoOverview: Decodable {
    let as_of: String
    let total_eur: String
    let chains_available: [CryptoChain]
    let wallets: [CryptoWallet]
    let assets: [CryptoAsset]
    let hidden: Int

    /// The kinds a new wallet can be: those with at least one chain Core has on.
    var kinds: [CryptoWalletKind] { CryptoWalletKind.allCases.filter { kind in kind.chains.contains { chains_available.contains($0) } } }

    private enum Keys: String, CodingKey { case as_of, base_currency, total_eur, chains_available, wallets, assets }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        let wallets = try c.decode([Lenient<RawWallet>].self, forKey: .wallets)
        let assets = try c.decode([RawAsset].self, forKey: .assets)
        as_of = try c.decode(String.self, forKey: .as_of)
        total_eur = try c.decode(String.self, forKey: .total_eur)
        guard try c.decode(String.self, forKey: .base_currency) == "EUR", Crypto.isTime(as_of), Crypto.isDecimal(total_eur),
              wallets.count <= Crypto.maxListed, assets.count <= 50 else { throw Crypto.invalid }
        chains_available = Crypto.chains(try c.decode([Lenient<String>].self, forKey: .chains_available).compactMap(\.value))
        self.wallets = wallets.compactMap { $0.value?.wallet }
        self.assets = try assets.map { try $0.asset() }
        hidden = wallets.count - self.wallets.count
    }
}

/// IBKR plus the crypto wallets (GET /v1/trading/total).
struct PortfolioTotal: Decodable {
    struct Part: Decodable { let source: String; let value: String; let as_of: String }

    let as_of: String
    let base_currency: String
    let total: String
    let parts: [Part]
    let partial: Bool

    /// The card shows once a part is set up; otherwise IBKR's own state says why.
    var shown: Bool { !parts.isEmpty || partial }
}

/// The add form's body; `chains` only for an EVM wallet, no label when empty.
struct NewCryptoWallet: Encodable, Equatable {
    let kind: CryptoWalletKind
    let address: String
    let chains: [String]?
    let label: String?
}

/// Core refused a refresh within its cooldown: try again after `seconds`.
struct CryptoCooldown: Error, Equatable { let seconds: Int }

/// Why an address does not fit its kind, as Core's wallet.rs decides.
enum CryptoAddressIssue: Error, Equatable { case keyMaterial, extendedPublicKey, testnet, invalid }

/// The first rule the add form breaks. `key`: the field holds key material; the form
/// empties it.
struct CryptoDraftIssue: Error, Equatable {
    enum Field { case address, label, chains }
    let field: Field
    let message: String
    var key = false
}

private struct RawBalance: Decodable {
    let chain: Lenient<String>?
    let symbol: String
    let amount: String
    let price_eur: Lenient<String>?
    let value_eur: Lenient<String>?
}

private struct RawWallet: Decodable {
    let id: String
    let kind: String
    let chains: [Lenient<String>]?
    let address: String
    let label: Lenient<String>?
    let status: Lenient<String>?
    let error: Lenient<String>?
    let last_refresh_at: Lenient<String>?
    let other_tokens: Lenient<Int>?
    let value_eur: String
    let balances: [RawBalance]

    /// Nil when it breaks the contract.
    var wallet: CryptoWallet? {
        guard Crypto.isId(id), Jobs.isCode(kind), Crypto.isAddress(address), Crypto.isDecimal(value_eur),
              label?.value.map({ $0.count <= 200 }) ?? (label == nil), balances.count <= 100,
              balances.allSatisfy({ Crypto.isSymbol($0.symbol) && Crypto.isDecimal($0.amount) }) else { return nil }
        let code = error.map { $0.value.flatMap { Jobs.isCode($0) ? $0 : nil } ?? "unknown" }
        let other = other_tokens?.value ?? 0
        return CryptoWallet(
            id: id, kind: kind, chains: Crypto.chains((chains ?? []).prefix(14).compactMap(\.value)), address: address,
            label: label?.value.flatMap { $0.isEmpty ? nil : $0 },
            status: status?.value.flatMap(CryptoWalletStatus.init(rawValue:)) ?? .unknown,
            error: code, last_refresh_at: last_refresh_at?.value.flatMap { Crypto.isTime($0) ? $0 : nil },
            other_tokens: (1...Crypto.maxOtherTokens).contains(other) ? other : 0, value_eur: value_eur,
            balances: balances.map { b in
                CryptoBalance(chain: b.chain?.value.flatMap(CryptoChain.init(rawValue:)), symbol: b.symbol, amount: b.amount,
                              price_eur: Crypto.optionalDecimal(b.price_eur), value_eur: Crypto.optionalDecimal(b.value_eur))
            })
    }
}

private struct RawAsset: Decodable {
    let symbol: String
    let amount: String
    let price_eur: Lenient<String>?
    let value_eur: Lenient<String>?
    let weight_pct: Lenient<String>?

    func asset() throws -> CryptoAsset {
        guard Crypto.isSymbol(symbol), Crypto.isDecimal(amount) else { throw Crypto.invalid }
        return CryptoAsset(symbol: symbol, amount: amount, price_eur: Crypto.optionalDecimal(price_eur),
                           value_eur: Crypto.optionalDecimal(value_eur), weight_pct: Crypto.optionalDecimal(weight_pct))
    }
}

enum Crypto {
    static let path = "/v1/trading/crypto"
    static let totalPath = "/v1/trading/total"
    static func walletPath(_ id: UUID) -> String { "\(path)/wallets/\(id.uuidString.lowercased())" }

    static let maxWallets = 20
    /// Entries read from one answer, readable or not.
    static let maxListed = 50
    static let maxLabel = 40
    static let maxOtherTokens = 1_000_000
    static let maxRetrySecs = 3600
    /// Core's minimum gap between refreshes; used when a 429 does not say.
    static let defaultCooldown = 60

    // MARK: Wording (desktop crypto.ts)

    static let title = "Crypto"
    static let subtitle = "Read-only balances of your self-custody wallets, in EUR."
    static let hint = "Add self-custody addresses only, not exchange deposit addresses. Don't also enter these coins as manual holdings."
    static let attribution = "Prices by CoinGecko"
    static let noPrice = "no EUR price, not counted"
    static let removeConfirm = "Remove this wallet? Its balances leave the total."
    static let offTitle = "Crypto wallets are off on your Home Node."
    static let offKey = "JARVIS_CRYPTO_WALLETS_ENABLED"
    static let offDetail =
        "To turn them on, allow the provider hosts in your Home Node's egress rules (see \"Egress hosts\" in docs/CRYPTO_WALLETS.md in the Core repository), set \(offKey)=true in Core's environment and restart Core."
    static let needsCore = "Update Core to v0.0.90 or later."
    static let partialText = "Partial: a part that is set up has no value right now and is left out."
    static let totalFailedText = "Crypto wallets are not in this value: Core could not give the total just now."
    static let keyMaterialText =
        "This looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only."
    static let xpubText = "Extended public keys (xpub) are not supported; add single addresses."
    static let testnetText = "Only mainnet addresses are supported."
    static let chainsText = "Pick at least one chain."
    static let labelText = "The label can be 1 to 40 characters on one line."
    /// Phones reload on pull-to-refresh, not on Core's realtime hint.
    static let refreshAsked = "Refreshing. Pull down in a moment to see the new balances."
    static let added = "Added. Core reads its balances in a moment."
    static let removed = "Removed."

    static func invalidAddressText(_ kind: CryptoWalletKind) -> String {
        switch kind {
        case .bitcoin: "Not a valid Bitcoin address."
        case .evm: "Not a valid EVM address: 0x followed by 40 hex digits."
        case .solana: "Not a valid Solana address."
        }
    }

    /// "IBKR", "Crypto"; another source Core adds later shows as Core names it.
    static func partLabel(_ source: String) -> String { source == "crypto" ? "Crypto" : source }

    static func otherTokensText(_ count: Int) -> String { "\(count) other \(count == 1 ? "token" : "tokens") not counted" }

    static func hiddenText(_ count: Int) -> String {
        "\(count == 1 ? "1 wallet" : "\(count) wallets") this app cannot show yet. Update the app to see \(count == 1 ? "it" : "them")."
    }

    /// "0x1234…abcd": the start and end an owner compares; short ones whole.
    static func shortAddress(_ address: String) -> String {
        address.count <= 12 ? address : "\(address.prefix(6))…\(address.suffix(4))"
    }

    static func refreshedText(_ wallet: CryptoWallet, now: Date = Date()) -> String {
        guard let at = wallet.last_refresh_at else { return "Not refreshed yet" }
        let ago = relativeTime(at, now: now)
        return "Last refreshed \(ago.isEmpty ? "at an unknown time" : ago)"
    }

    private static let errorTexts = [
        "unavailable": "A provider could not be reached or answered with an error, or the chain is off on Core.",
        "rate_limited": "A provider's rate limit was hit. Core tries again later.",
        "invalid_response": "A provider sent an answer Core does not accept.",
        "too_many_tokens": "This Solana wallet has more token accounts than Core reads.",
    ]

    /// The owner-facing text of a wallet's error code; never the code's own words.
    static func walletErrorText(_ code: String) -> String {
        "\(errorTexts[code] ?? "Core reported an error this app does not know yet.") The last good balances still count."
    }

    static func cooldownText(_ seconds: Int) -> String { "Core refreshes at most once a minute. Try again in \(seconds) s." }

    // MARK: Response checks: anything unexpected reads as failed (fail closed).

    static let invalid = JarvisAPIError.invalidResponse

    /// A decimal string as Core writes it: at most 20 integer digits.
    static func isDecimal(_ text: String) -> Bool { text.wholeMatch(of: #/-?[0-9]{1,20}(\.[0-9]{1,28})?/#) != nil }
    static func optionalDecimal(_ text: Lenient<String>?) -> String? { text?.value.flatMap { isDecimal($0) ? $0 : nil } }
    static func isSymbol(_ text: String) -> Bool { text.wholeMatch(of: #/[A-Z0-9]{1,12}/#) != nil }
    static func isAddress(_ text: String) -> Bool { text.wholeMatch(of: #/[0-9A-Za-z]{20,128}/#) != nil }
    static func isTime(_ text: String) -> Bool { text.count <= 64 && parseCoreDate(text) != nil }
    static func isId(_ text: String) -> Bool {
        text.wholeMatch(of: #/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/#) != nil
    }

    /// Known chains, each once, in the order sent.
    static func chains(_ names: [String]) -> [CryptoChain] {
        var out: [CryptoChain] = []
        for chain in names.prefix(14).compactMap(CryptoChain.init(rawValue:)) where !out.contains(chain) { out.append(chain) }
        return out
    }

    static func valid(_ t: PortfolioTotal) -> Bool {
        isTime(t.as_of) && t.base_currency.wholeMatch(of: #/[A-Z]{3}/#) != nil && isDecimal(t.total) && t.parts.count <= 8
            && t.parts.allSatisfy { (1...32).contains($0.source.count) && isDecimal($0.value) && isTime($0.as_of) }
    }

    // MARK: Failures

    enum Failure: Equatable { case off, noApi, unreachable, signin, failed }

    /// A failed write: 503 "crypto wallets are not enabled" is off; an older Core without
    /// the routes answers 404 or 405.
    static func failure(_ error: Error) -> Failure {
        if Jobs.isNetwork(error) { return .unreachable }
        switch error as? JarvisAPIError {
        case .unauthorized?: return .signin
        case let .rejected(status, message)?:
            if [404, 405, 501].contains(status) { return .noApi }
            return status == 503 && message == "crypto wallets are not enabled" ? .off : .failed
        default: return .failed
        }
    }

    /// A failed read of the page. `coreRead` keeps no message: on these routes only the
    /// feature switch answers 503.
    static func failure(_ read: Availability<CryptoOverview>) -> Failure? {
        switch read {
        case .unsupported: .noApi
        case .error(.unavailable): .off
        case .error(.network): .unreachable
        case .error(.signin): .signin
        case .error: .failed
        case .loading, .ok: nil
        }
    }

    static func failureText(_ failure: Failure) -> String {
        switch failure {
        case .off: offTitle
        case .noApi: needsCore
        case .unreachable: "No answer from your Home Node. Check the connection."
        case .signin: "Your session is not valid. Sign in again from Settings."
        case .failed: "Core returned an answer this app does not understand, or an error. Try again later."
        }
    }

    /// Core's fixed refusals (400, 409), in the app's words.
    private static let refusalTexts = [
        "this looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only": keyMaterialText,
        "extended public keys (xpub) are not supported; add single addresses": xpubText,
        "only mainnet addresses are supported": testnetText,
        "not a valid address for this wallet kind": "Not a valid address for this chain. Check it for typos.",
        "choose one or more EVM chains, each once": chainsText,
        "label must be 1 to 40 visible characters": labelText,
        "a chosen chain is not available on this Core": "A chosen chain is not available on your Home Node.",
        "this wallet was already added": "This wallet was already added.",
        "wallet limit reached": "You have 20 wallets, the most Core keeps. Remove one to add another.",
    ]

    /// The notice after a refused add.
    static func addFailureText(_ error: Error) -> String {
        if case let .rejected(status, message)? = error as? JarvisAPIError, status == 400 || status == 409 {
            if let text = message.flatMap({ refusalTexts[$0] }) { return text }
            return status == 409 ? "Core refused this: the wallet was already added, or you have 20 wallets."
                : "Core refused this wallet. Check the fields and try again."
        }
        if Jobs.isNetwork(error) { return "No answer from your Home Node. It is unknown whether the wallet was added; reloaded to check." }
        return "Not added. \(failureText(failure(error)))"
    }

    /// Core refused the body as key material: the form forgets what was typed.
    static func isKeyMaterialRefusal(_ error: Error) -> Bool {
        guard case let .rejected(400, message)? = error as? JarvisAPIError else { return false }
        return message.flatMap { refusalTexts[$0] } == keyMaterialText
    }

    /// The notice after a failed remove; nil when the wallet is already gone.
    static func removeFailureText(_ error: Error) -> String? {
        if Jobs.status(error) == 404 { return nil }
        if Jobs.isNetwork(error) { return "No answer from your Home Node. It is unknown whether the wallet was removed; reloaded to check." }
        return "Not removed. \(failureText(failure(error)))"
    }

    /// Seconds until Core takes another refresh, from a 429; nil for any other failure.
    static func retryAfterSecs(_ error: Error) -> Int? {
        if let cooldown = error as? CryptoCooldown { return min(max(cooldown.seconds, 1), maxRetrySecs) }
        return Jobs.status(error) == 429 ? defaultCooldown : nil
    }

    // MARK: Add form: checked here before anything is sent

    /// The body for the form, or the first rule it breaks: key material first (address,
    /// then label), then the address, the chains and the label. The refusal never repeats
    /// the input.
    static func newWallet(kind: CryptoWalletKind, address: String, label: String, chains: [CryptoChain]) -> Result<NewCryptoWallet, CryptoDraftIssue> {
        if isKeyMaterial(address) { return .failure(CryptoDraftIssue(field: .address, message: keyMaterialText, key: true)) }
        if isKeyMaterial(label) { return .failure(CryptoDraftIssue(field: .label, message: keyMaterialText, key: true)) }
        if let issue = addressIssue(kind, address) { return .failure(CryptoDraftIssue(field: .address, message: issue)) }
        let evm = CryptoChain.evm.filter { chains.contains($0) }
        if kind == .evm && evm.isEmpty { return .failure(CryptoDraftIssue(field: .chains, message: chainsText)) }
        let label = label.trimmingCharacters(in: .whitespacesAndNewlines)
        if !label.isEmpty && !Roadmaps.validText(label, max: maxLabel) { return .failure(CryptoDraftIssue(field: .label, message: labelText)) }
        return .success(NewCryptoWallet(kind: kind, address: address.trimmingCharacters(in: .whitespacesAndNewlines),
                                        chains: kind == .evm ? evm.map(\.rawValue) : nil, label: label.isEmpty ? nil : label))
    }

    /// Why the address does not fit the kind, or nil. Core checks it again (and an EVM
    /// address's mixed-case checksum only there).
    static func addressIssue(_ kind: CryptoWalletKind, _ input: String) -> String? {
        switch parseAddress(kind, input) {
        case .success: nil
        case .failure(.keyMaterial): keyMaterialText
        case .failure(.extendedPublicKey): xpubText
        case .failure(.testnet): testnetText
        case .failure(.invalid): invalidAddressText(kind)
        }
    }

    /// Core's `is_key_material` (crates/market-data/src/wallet.rs): a PEM block, 12 or more
    /// words, a JSON byte array, or a token that is an extended private key, 32 or 64 bytes
    /// of hex, a WIF key or a base58 64-byte Solana secret.
    static func isKeyMaterial(_ input: String) -> Bool {
        if input.contains("-----BEGIN") { return true }
        let words = input.split { $0.isWhitespace || $0 == "," || $0 == ";" || $0 == "-" }
        if words.count >= 12 { return true }
        let start = input.drop { $0.isWhitespace }
        if start.first == "[", start.dropFirst().drop(while: \.isWhitespace).first.map({ $0.isASCII && $0.isNumber }) == true { return true }
        return input.unicodeScalars.split { !($0.isASCII && CharacterSet.alphanumerics.contains($0)) }
            .contains { isKeyToken(String(String.UnicodeScalarView($0))) }
    }

    private static let privatePrefixes = ["xprv", "yprv", "zprv", "tprv", "uprv", "vprv", "Yprv", "Zprv"]
    private static let publicPrefixes = ["xpub", "ypub", "zpub", "tpub", "upub", "vpub", "Ypub", "Zpub"]

    private static func isKeyToken(_ token: String) -> Bool {
        let hex = token.hasPrefix("0x") || token.hasPrefix("0X") ? String(token.dropFirst(2)) : token
        if privatePrefixes.contains(where: { token.hasPrefix($0) }), !isSolana(token) { return true }
        if [64, 128].contains(hex.utf8.count), hex.utf8.allSatisfy(isHexDigit) { return true }
        if [51, 52].contains(token.utf8.count), let bytes = base58(token), [37, 38].contains(bytes.count), [0x80, 0xef].contains(bytes[0]) {
            return true
        }
        return (64...88).contains(token.utf8.count) && base58(token)?.count == 64
    }

    private static func isHexDigit(_ byte: UInt8) -> Bool {
        (48...57).contains(byte) || (65...70).contains(byte) || (97...102).contains(byte)
    }

    /// Core's per-kind check, so a typo is caught before sending; Core checks again (and an
    /// EVM address's mixed-case checksum only there).
    static func parseAddress(_ kind: CryptoWalletKind, _ input: String) -> Result<String, CryptoAddressIssue> {
        if isKeyMaterial(input) { return .failure(.keyMaterial) }
        let address = input.trimmingCharacters(in: .whitespacesAndNewlines)
        switch kind {
        case .bitcoin: return bitcoin(address)
        case .evm:
            guard address.hasPrefix("0x"), address.utf8.count == 42, address.utf8.dropFirst(2).allSatisfy(isHexDigit) else { return .failure(.invalid) }
            return .success(address)
        case .solana: return isSolana(address) ? .success(address) : .failure(.invalid)
        }
    }

    private static func isSolana(_ address: String) -> Bool { (32...44).contains(address.utf8.count) && base58(address)?.count == 32 }

    private static func bitcoin(_ address: String) -> Result<String, CryptoAddressIssue> {
        if publicPrefixes.contains(where: { address.hasPrefix($0) }) { return .failure(.extendedPublicKey) }
        let lower = address.lowercased()
        return ["bc1", "tb1", "bcrt1"].contains(where: { lower.hasPrefix($0) }) ? segwit(address) : base58check(address)
    }

    private static func segwit(_ address: String) -> Result<String, CryptoAddressIssue> {
        guard let decoded = bech32Decode(address), let version = decoded.data.first,
              let program = fiveToEight(decoded.data.dropFirst()) else { return .failure(.invalid) }
        let valid = (version == 0 && !decoded.bech32m && [20, 32].contains(program.count))
            || (version == 1 && decoded.bech32m && program.count == 32)
        switch decoded.hrp {
        case "bc" where valid: return .success(address.lowercased())
        case "tb" where valid, "bcrt" where valid: return .failure(.testnet)
        default: return .failure(.invalid)
        }
    }

    private static let bech32Charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l".utf8)

    /// BIP-173/350: the human-readable part, the data without checksum, and whether it is bech32m.
    static func bech32Decode(_ text: String) -> (hrp: String, data: [UInt8], bech32m: Bool)? {
        let bytes = Array(text.utf8)
        guard bytes.count <= 90, bytes.allSatisfy({ (33...126).contains($0) }) else { return nil }
        if bytes.contains(where: { (97...122).contains($0) }) && bytes.contains(where: { (65...90).contains($0) }) { return nil }
        let lower = Array(text.lowercased().utf8)
        guard let one = lower.lastIndex(of: UInt8(ascii: "1")), one > 0, lower.count - one - 1 >= 6 else { return nil }
        let hrp = Array(lower[..<one])
        var data: [UInt8] = []
        for byte in lower[(one + 1)...] {
            guard let value = bech32Charset.firstIndex(of: byte) else { return nil }
            data.append(UInt8(value))
        }
        let variant: Bool
        switch bech32Polymod(hrp, data) {
        case 1: variant = false
        case 0x2bc8_30a3: variant = true
        default: return nil
        }
        return (String(decoding: hrp, as: UTF8.self), Array(data.dropLast(6)), variant)
    }

    private static func bech32Polymod(_ hrp: [UInt8], _ data: [UInt8]) -> UInt32 {
        let generator: [UInt32] = [0x3b6a_57b2, 0x2650_8e6d, 0x1ea1_19fa, 0x3d42_33dd, 0x2a14_62b3]
        var checksum: UInt32 = 1
        let high: [UInt8] = hrp.map { $0 >> 5 }
        let low: [UInt8] = hrp.map { $0 & 31 }
        for value in high + [0] + low + data {
            let top = checksum >> 25
            checksum = ((checksum & 0x1ff_ffff) << 5) ^ UInt32(value)
            for (bit, g) in generator.enumerated() where (top >> UInt32(bit)) & 1 == 1 { checksum ^= g }
        }
        return checksum
    }

    private static func fiveToEight(_ data: ArraySlice<UInt8>) -> [UInt8]? {
        var (acc, bits, out): (UInt32, UInt32, [UInt8]) = (0, 0, [])
        for value in data {
            acc = ((acc << 5) | UInt32(value)) & 0xfff
            bits += 5
            if bits >= 8 {
                bits -= 8
                out.append(UInt8(truncatingIfNeeded: acc >> bits))
            }
        }
        return bits < 5 && acc & ((1 << bits) - 1) == 0 ? out : nil
    }

    private static func base58check(_ address: String) -> Result<String, CryptoAddressIssue> {
        guard (26...35).contains(address.utf8.count), let bytes = base58(address), bytes.count == 25 else { return .failure(.invalid) }
        let check = Array(SHA256.hash(data: Data(SHA256.hash(data: Data(bytes[..<21])))).prefix(4))
        guard check == Array(bytes[21...]) else { return .failure(.invalid) }
        switch bytes[0] {
        case 0x00, 0x05: return .success(address)
        case 0x6f, 0xc4: return .failure(.testnet)
        default: return .failure(.invalid)
        }
    }

    private static let base58Alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".utf8)

    /// Bitcoin-alphabet base58; nil on a character outside it.
    static func base58(_ text: String) -> [UInt8]? {
        var bytes: [UInt8] = []
        for char in text.utf8 {
            guard let digit = base58Alphabet.firstIndex(of: char) else { return nil }
            var carry = digit
            for index in bytes.indices {
                carry += Int(bytes[index]) * 58
                bytes[index] = UInt8(carry & 0xff)
                carry >>= 8
            }
            while carry > 0 {
                bytes.append(UInt8(carry & 0xff))
                carry >>= 8
            }
        }
        let zeros = text.utf8.prefix { $0 == UInt8(ascii: "1") }.count
        return [UInt8](repeating: 0, count: zeros) + bytes.reversed()
    }
}
