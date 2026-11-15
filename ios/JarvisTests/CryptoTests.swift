import XCTest
@testable import Jarvis

/// Mirrors desktop/tests/crypto.test.mjs and Core's wallet.rs vectors. Neutral test data
/// only: the keys are Core's throwaway test vectors, never funded or used.
final class CryptoTests: XCTestCase {
    private let w1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c71"

    private func json(_ object: Any) -> Data { try! JSONSerialization.data(withJSONObject: object) }

    private func wallet(_ overrides: [String: Any] = [:]) -> [String: Any] {
        ([
            "id": w1, "kind": "evm", "chains": ["ethereum", "base"], "address": "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "label": "Cold", "status": "ok", "last_refresh_at": "2026-11-15T10:00:00Z", "value_eur": "3000.5",
            "balances": [
                ["chain": "ethereum", "asset": "eth", "symbol": "ETH", "amount": "1.000000000000000001", "price_eur": "3000.5",
                 "value_eur": "3000.5"],
                ["chain": "base", "asset": "usdc", "symbol": "USDC", "amount": "12.5"],
            ] as [[String: Any]],
        ] as [String: Any]).merging(overrides) { $1 }
    }

    private func overview(_ overrides: [String: Any] = [:], wallets: [[String: Any]]? = nil) -> [String: Any] {
        ([
            "source": "crypto", "as_of": "2026-11-15T10:00:00Z", "base_currency": "EUR", "total_eur": "3000.5",
            "chains_available": ["bitcoin", "ethereum", "base", "solana", "zksync"], "wallets": wallets ?? [wallet()],
            "assets": [["asset": "eth", "symbol": "ETH", "amount": "1", "price_eur": "3000.5", "value_eur": "3000.5", "weight_pct": "100"],
                       ["asset": "usdc", "symbol": "USDC", "amount": "12.5"]] as [[String: Any]],
        ] as [String: Any]).merging(overrides) { $1 }
    }

    private func read(_ object: [String: Any]) -> CryptoOverview? { try? JSONDecoder().decode(CryptoOverview.self, from: json(object)) }

    private func total(_ object: [String: Any]) -> PortfolioTotal? {
        guard let value = try? JSONDecoder().decode(PortfolioTotal.self, from: json(object)), Crypto.valid(value) else { return nil }
        return value
    }

    // MARK: Readers

    func testTheOverviewReadsCoresShapeAsSent() throws {
        let value = try XCTUnwrap(read(overview()))
        XCTAssertEqual(value.chains_available, [.bitcoin, .ethereum, .base, .solana], "an unknown chain is left out")
        XCTAssertEqual(value.kinds, [.bitcoin, .evm, .solana])
        XCTAssertEqual(value.hidden, 0)
        let w = value.wallets[0]
        XCTAssertEqual(w.name, "Cold")
        XCTAssertEqual(w.status, .ok)
        XCTAssertEqual(w.chains, [.ethereum, .base])
        XCTAssertEqual(w.other_tokens, 0)
        XCTAssertEqual(Trading.formatDecimal(w.balances[0].amount), "1.000000000000000001", "amounts show as Core sends, no float rounding")
        XCTAssertEqual(Trading.formatMoney(w.value_eur, "EUR"), "€3,000.50")
        XCTAssertNil(w.balances[1].value_eur, "an unpriced balance has no value and is shown as not counted")
        XCTAssertEqual(Crypto.noPrice, "no EUR price, not counted")
        XCTAssertEqual(value.assets[0].weight_pct, "100")
        XCTAssertEqual(Crypto.shortAddress(w.address), "0x5aAe…eAed")
        XCTAssertEqual(Crypto.shortAddress("abc"), "abc")
        // Without a label the kind names the wallet; a pending one was not refreshed yet.
        let pending = try XCTUnwrap(read(overview(wallets: [wallet(["label": NSNull(), "status": "pending", "last_refresh_at": NSNull(),
                                                                    "balances": [] as [Any], "value_eur": "0"])])))
        XCTAssertEqual(pending.wallets[0].name, "EVM")
        XCTAssertEqual(Crypto.refreshedText(pending.wallets[0]), "Not refreshed yet")
        let now = try XCTUnwrap(parseCoreDate("2026-11-15T10:04:00Z"))
        XCTAssertEqual(Crypto.refreshedText(w, now: now), "Last refreshed 4m ago")
        // A Solana wallet's uncounted tokens.
        let solana = try XCTUnwrap(read(overview(wallets: [wallet(["kind": "solana", "chains": ["solana"], "other_tokens": 3])])))
        XCTAssertEqual(solana.wallets[0].other_tokens, 3)
        XCTAssertEqual(Crypto.otherTokensText(3), "3 other tokens not counted")
        XCTAssertEqual(Crypto.otherTokensText(1), "1 other token not counted")
    }

    func testUnknownStatusesCodesAndChainsNeverShowAsKnown() throws {
        let value = try XCTUnwrap(read(overview(wallets: [wallet([
            "status": "syncing", "error": "Upstream said <b>no</b>", "kind": "cosmos", "label": NSNull(), "other_tokens": -4,
            "last_refresh_at": "yesterday", "chains": ["zksync", "base", "base"],
            "balances": [["chain": "zksync", "asset": "x", "symbol": "ZK", "amount": "1"]],
        ])])))
        let w = value.wallets[0]
        XCTAssertEqual(w.status, .unknown)
        XCTAssertEqual(w.status.label, "Unknown")
        XCTAssertEqual(w.status.tone, .idle)
        XCTAssertEqual(w.error, "unknown")
        XCTAssertEqual(w.name, "Wallet")
        XCTAssertEqual(w.other_tokens, 0)
        XCTAssertNil(w.last_refresh_at)
        XCTAssertEqual(w.chains, [.base])
        XCTAssertNil(w.balances[0].chain, "shown as Other")
        XCTAssertEqual(Crypto.walletErrorText("unknown"), "Core reported an error this app does not know yet. The last good balances still count.")
        XCTAssertEqual(Crypto.walletErrorText("rate_limited"), "A provider's rate limit was hit. Core tries again later. The last good balances still count.")
    }

    func testAWalletThisAppCannotReadIsLeftOutAndCounted() throws {
        let broken: [[String: Any]] = [
            wallet(["id": "../x"]), wallet(["value_eur": "1,5"]), wallet(["address": "0x5aAe <script>"]), wallet(["kind": "Big Kind"]),
            wallet(["label": String(repeating: "a", count: 201)]),
            wallet(["balances": [["chain": "base", "asset": "eth", "symbol": "ETH", "amount": "NaN"]]]),
            wallet(["balances": [["chain": "base", "asset": "eth", "symbol": "eth", "amount": "1"]]]),
        ]
        let value = try XCTUnwrap(read(overview(wallets: broken + [wallet()])))
        XCTAssertEqual(value.wallets.count, 1)
        XCTAssertEqual(value.hidden, broken.count)
        XCTAssertEqual(Crypto.hiddenText(1), "1 wallet this app cannot show yet. Update the app to see it.")
        XCTAssertEqual(Crypto.hiddenText(2), "2 wallets this app cannot show yet. Update the app to see them.")
    }

    func testAMalformedOverviewFailsClosed() {
        let broken: [[String: Any]] = [
            overview(["base_currency": "USD"]),
            overview(["total_eur": "1e5"]),
            overview(["total_eur": 3000.5]),
            overview(["total_eur": "123456789012345678901"]),
            overview(["as_of": "soon"]),
            overview(["chains_available": "bitcoin"]),
            overview(["assets": [["symbol": "ETH", "amount": "lots"]]]),
            overview(wallets: Array(repeating: wallet(), count: 51)),
        ]
        for object in broken { XCTAssertNil(read(object), "\(object)") }
        for text in ["null", "[]", #"{"wallets":[]}"#] {
            XCTAssertNil(try? JSONDecoder().decode(CryptoOverview.self, from: Data(text.utf8)), text)
        }
    }

    func testTheTotalReadsItsPartsAndPartialNote() throws {
        let value = try XCTUnwrap(total([
            "source": "Jarvis", "as_of": "2026-11-15T10:00:00Z", "base_currency": "EUR", "total": "4000.5",
            "parts": [["source": "IBKR", "value": "1000", "as_of": "2026-11-15T10:00:00Z"],
                      ["source": "crypto", "value": "3000.5", "as_of": "2026-11-15T09:00:00Z"]],
            "partial": true,
        ]))
        XCTAssertTrue(value.shown)
        XCTAssertEqual(value.parts.map { Crypto.partLabel($0.source) }, ["IBKR", "Crypto"])
        XCTAssertEqual(Crypto.partLabel("bank"), "bank", "a later source shows as Core names it")
        XCTAssertEqual(Crypto.partialText, "Partial: a part that is set up has no value right now and is left out.")
        let none = try XCTUnwrap(total(["source": "Jarvis", "as_of": "2026-11-15T10:00:00Z", "base_currency": "EUR", "total": "0",
                                        "parts": [] as [Any], "partial": false]))
        XCTAssertFalse(none.shown, "nothing set up: the desk shows IBKR's own state")
        let at = "2026-11-15T10:00:00Z"
        for bad: [String: Any] in [
            ["as_of": "x", "base_currency": "EUR", "total": "1", "parts": [] as [Any], "partial": false],
            ["as_of": at, "base_currency": "euro", "total": "1", "parts": [] as [Any], "partial": false],
            ["as_of": at, "base_currency": "EUR", "total": "1.2.3", "parts": [] as [Any], "partial": false],
            ["as_of": at, "base_currency": "EUR", "total": "1", "parts": [["source": "", "value": "1", "as_of": at]], "partial": false],
            ["as_of": at, "base_currency": "EUR", "total": "1", "parts": [["source": "IBKR", "value": "x", "as_of": at]], "partial": false],
            ["as_of": at, "base_currency": "EUR", "total": "1", "parts": [] as [Any], "partial": "no"],
        ] {
            XCTAssertNil(total(bad), "\(bad)")
        }
    }

    // MARK: Key material: refused here before sending, never echoed

    private let wifCompressed = "L2fo4Kvj1k79zBLw3RTRacgsVgWPLNQMQbGNNc8LnsLnDt662SF7"
    private let solanaSecret = "Ri71QgUgULUbKeM5426JRND3BB68jDLaVn2bdxwTxWse67F9HvGgEGxR7Gp4m5vE6eDnBd2QiX72cSQvTffKwuv"

    private var keyMaterial: [String] {
        let mnemonic12 = String(repeating: "abandon ", count: 11) + "about"
        let hex64 = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"
        let solanaArray = "[" + Array(repeating: "174", count: 64).joined(separator: ",") + "]"
        return [
            mnemonic12, String(repeating: "abandon ", count: 23) + "art", mnemonic12.replacingOccurrences(of: " ", with: "\n"),
            mnemonic12.replacingOccurrences(of: " ", with: ","), mnemonic12.replacingOccurrences(of: " ", with: "-"),
            wifCompressed, "5KFbZ6ALEBaeHdRc4dz6rir2AELThVnmE8oMFvUF7SpT8npAgbu", "cRJuYTqc3oV92biruZreVwveD4D6oGZvBRu7TCJ2DWbZBWVtkMPi",
            "\"\(wifCompressed)\"",
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
            "zprvAWgYBBk7JR8Gj", "tprv8ZgxMBicQKsPd", hex64, "0x\(hex64)", hex64.uppercased(), hex64 + hex64, solanaSecret,
            solanaArray, "  " + solanaArray.replacingOccurrences(of: ",", with: ", "),
            "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEE\n-----END EC PRIVATE KEY-----",
        ]
    }

    func testKeyMaterialIsRefusedFirstWithCoresMessageAndNeverEchoed() {
        for input in keyMaterial {
            XCTAssertTrue(Crypto.isKeyMaterial(input), input)
            for kind in CryptoWalletKind.allCases {
                for (address, label) in [(input, ""), ("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", input)] {
                    guard case let .failure(issue) = Crypto.newWallet(kind: kind, address: address, label: label, chains: CryptoChain.evm) else {
                        return XCTFail("sent: \(kind) \(input)")
                    }
                    XCTAssertTrue(issue.key)
                    XCTAssertEqual(issue.field, address == input ? .address : .label, "the form empties that field")
                    XCTAssertEqual(issue.message,
                                   "This looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only.")
                    for word in input.split(whereSeparator: { !$0.isLetter && !$0.isNumber }) where word.count >= 6 {
                        XCTAssertFalse(issue.message.contains(word), "echoes \(word)")
                    }
                }
            }
        }
    }

    func testPublicAddressesAreNotKeyMaterial() {
        for address in [
            "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy",
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "So11111111111111111111111111111111111111112",
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
            "eleven words are not a phrase so this text passes fine",
        ] {
            XCTAssertFalse(Crypto.isKeyMaterial(address), address)
        }
    }

    func testACombiningMarkHidesNoSeparatorOrMarker() {
        let mark = "\u{0301}"
        XCTAssertTrue(Crypto.isKeyMaterial((1...12).map { "w\($0)" }.joined(separator: ",\(mark)")))
        XCTAssertTrue(Crypto.isKeyMaterial("[1\u{20E3},2]"))
        XCTAssertTrue(Crypto.isKeyMaterial("-----BEGIN\(mark) KEY"))
    }

    // MARK: Per-chain format checks

    func testBitcoinAddresses() {
        let btc = { (text: String) in Crypto.parseAddress(.bitcoin, text) }
        for (text, stored) in [
            ("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"),
            ("bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3", "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"),
            ("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"),
            ("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"),
            ("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"),
            (" 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa\n", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"),
        ] {
            XCTAssertEqual(btc(text), .success(stored), text)
        }
        for text in ["tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7",
                     "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c",
                     "mrS8eVKXguwufwvsVe9GtgGb7fif9UQeAu", "2N9hLwkSqr1cPQAPxbrGVUjxyjD11G2e1he"] {
            XCTAssertEqual(btc(text), .failure(.testnet), text)
        }
        for text in ["bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5", "BC13W508D6QEJXTDG4Y5R3ZARVARY0C5XW7KN40WF2", "bc1rw5uspcuh",
                     "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P", "bc1zw508d6qejxtdg4y5r3zarvaryvqyzf3du", "bc1gmk9yu",
                     "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh",
                     "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN3", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", ""] {
            XCTAssertEqual(btc(text), .failure(.invalid), text)
        }
        for xpub in ["xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8",
                     " tpubD6NzVbkrYhZ4X"] {
            XCTAssertEqual(btc(xpub), .failure(.extendedPublicKey))
            XCTAssertEqual(Crypto.parseAddress(.evm, xpub), .failure(.invalid))
            XCTAssertEqual(Crypto.parseAddress(.solana, xpub), .failure(.invalid))
        }
    }

    func testBech32SpecVectors() {
        for (text, bech32m) in [("A12UEL5L", false), ("a12uel5l", false), ("abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw", false),
                                ("?1ezyfcl", false), ("A1LQFN3A", true), ("abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx", true), ("?1v759aa", true)] {
            XCTAssertEqual(Crypto.bech32Decode(text)?.bech32m, bech32m, text)
        }
        for text in ["\u{20}1nwldj5", "\u{7f}1axkwrx", "pzry9x0s0muk", "1pzry9x0s0muk", "x1b4n0q5v", "li1dgmt3", "A1G7SGD8", "10a06t8",
                     "1qzzfhee", "M1VUXWEZ", "16plkw9", "1p2gdwpf"] {
            XCTAssertNil(Crypto.bech32Decode(text), text)
        }
    }

    func testEvmAndSolanaAddresses() {
        XCTAssertEqual(Crypto.parseAddress(.evm, " 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed "), .success("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        for bad in ["5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0X5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAe",
                    "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeg", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"] {
            XCTAssertEqual(Crypto.parseAddress(.evm, bad), .failure(.invalid), bad)
        }
        XCTAssertEqual(Crypto.parseAddress(.solana, "So11111111111111111111111111111111111111112"), .success("So11111111111111111111111111111111111111112"))
        for bad in ["So1111111111111111111111111111111111111111O", "So111", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"] {
            XCTAssertEqual(Crypto.parseAddress(.solana, bad), .failure(.invalid), bad)
        }
    }

    func testTheFormChecksInDesktopsOrderAndSendsOnlyTheKindsFields() throws {
        guard case let .success(evm) = Crypto.newWallet(kind: .evm, address: " 0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", label: "  Cold ",
                                                        chains: [.polygon, .ethereum]) else { return XCTFail() }
        XCTAssertEqual(evm, NewCryptoWallet(kind: .evm, address: "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", chains: ["ethereum", "polygon"], label: "Cold"))
        guard case let .success(btc) = Crypto.newWallet(kind: .bitcoin, address: "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", label: " ",
                                                        chains: CryptoChain.evm) else { return XCTFail() }
        let keys = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(btc)) as? [String: Any]).keys.sorted()
        XCTAssertEqual(keys, ["address", "kind"], "no chains, no empty label")
        let issue = { (kind: CryptoWalletKind, address: String, label: String, chains: [CryptoChain]) -> CryptoDraftIssue? in
            if case let .failure(issue) = Crypto.newWallet(kind: kind, address: address, label: label, chains: chains) { return issue }
            return nil
        }
        let evmAddress = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        XCTAssertEqual(issue(.evm, "0x12", String(repeating: "a", count: 41), []),
                       CryptoDraftIssue(field: .address, message: "Not a valid EVM address: 0x followed by 40 hex digits."))
        XCTAssertEqual(issue(.evm, evmAddress, String(repeating: "a", count: 41), []), CryptoDraftIssue(field: .chains, message: "Pick at least one chain."))
        XCTAssertEqual(issue(.evm, evmAddress, String(repeating: "a", count: 41), [.base]),
                       CryptoDraftIssue(field: .label, message: "The label can be 1 to 40 characters on one line."))
        XCTAssertEqual(issue(.evm, evmAddress, "Two\nlines", [.base])?.field, .label)
        XCTAssertNil(issue(.evm, evmAddress, String(repeating: "a", count: 40), [.base]))
        XCTAssertEqual(issue(.bitcoin, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5", "", [])?.message, "Not a valid Bitcoin address.")
        XCTAssertEqual(issue(.bitcoin, "mrS8eVKXguwufwvsVe9GtgGb7fif9UQeAu", "", [])?.message, "Only mainnet addresses are supported.")
        XCTAssertEqual(issue(.bitcoin, "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8", "", [])?.message,
                       "Extended public keys (xpub) are not supported; add single addresses.")
        XCTAssertEqual(issue(.solana, evmAddress, "", [])?.message, "Not a valid Solana address.")
    }

    // MARK: Failures

    func testOffOldCoreAndFailuresGetFixedWording() {
        XCTAssertEqual(Crypto.failure(Availability<CryptoOverview>.error(.unavailable)), .off)
        XCTAssertEqual(Crypto.failure(Availability<CryptoOverview>.unsupported), .noApi)
        XCTAssertNil(Crypto.failure(Availability<CryptoOverview>.loading))
        XCTAssertEqual(Crypto.offTitle, "Crypto wallets are off on your Home Node.")
        XCTAssertTrue(Crypto.offDetail.contains("JARVIS_CRYPTO_WALLETS_ENABLED=true") && Crypto.offDetail.contains("docs/CRYPTO_WALLETS.md"))
        XCTAssertEqual(Crypto.failureText(.noApi), "Update Core to v0.0.90 or later.")
        // A write's 503 is off only with Core's own text.
        XCTAssertEqual(Crypto.failure(JarvisAPIError.rejected(status: 503, message: "crypto wallets are not enabled")), .off)
        XCTAssertEqual(Crypto.failure(JarvisAPIError.rejected(status: 503, message: "busy")), .failed)
        for status in [404, 405, 501] { XCTAssertEqual(Crypto.failure(JarvisAPIError.rejected(status: status, message: nil)), .noApi) }
        XCTAssertEqual(Crypto.failure(JarvisAPIError.timedOut), .unreachable)
        // Core's known refusals map to the app's words; anything else from Core is never shown.
        XCTAssertEqual(Crypto.addFailureText(JarvisAPIError.rejected(status: 400, message: "only mainnet addresses are supported")),
                       "Only mainnet addresses are supported.")
        XCTAssertEqual(Crypto.addFailureText(JarvisAPIError.rejected(status: 409, message: "wallet limit reached")),
                       "You have 20 wallets, the most Core keeps. Remove one to add another.")
        XCTAssertEqual(Crypto.addFailureText(JarvisAPIError.rejected(status: 400, message: "<b>raw</b>")),
                       "Core refused this wallet. Check the fields and try again.")
        XCTAssertEqual(Crypto.addFailureText(JarvisAPIError.rejected(status: 409, message: "<b>raw</b>")),
                       "Core refused this: the wallet was already added, or you have 20 wallets.")
        XCTAssertEqual(Crypto.addFailureText(JarvisAPIError.rejected(status: 503, message: "crypto wallets are not enabled")),
                       "Not added. Crypto wallets are off on your Home Node.")
        XCTAssertTrue(Crypto.addFailureText(JarvisAPIError.unreachable).contains("unknown whether the wallet was added"))
        let keyRefusal = JarvisAPIError.rejected(
            status: 400, message: "this looks like a private key or recovery phrase; it was not stored. Never enter one: add a public address only")
        XCTAssertTrue(Crypto.isKeyMaterialRefusal(keyRefusal))
        XCTAssertEqual(Crypto.addFailureText(keyRefusal), Crypto.keyMaterialText)
        XCTAssertFalse(Crypto.isKeyMaterialRefusal(JarvisAPIError.rejected(status: 400, message: "only mainnet addresses are supported")))
        XCTAssertTrue(Crypto.isKeyMaterialRefusal(JarvisAPIError.rejected(status: 400, message: "reworded")), "an unknown 400 clears the form")
        XCTAssertNil(Crypto.removeFailureText(JarvisAPIError.rejected(status: 404, message: nil)), "already gone counts as removed")
        XCTAssertEqual(Crypto.removeFailureText(JarvisAPIError.rejected(status: 500, message: "raw")),
                       "Not removed. Core returned an answer this app does not understand, or an error. Try again later.")
    }

    func testARefusedRefreshWaitsForCoresCooldown() {
        XCTAssertEqual(Crypto.retryAfterSecs(CryptoCooldown(seconds: 42)), 42)
        XCTAssertEqual(Crypto.retryAfterSecs(JarvisAPIError.rejected(status: 429, message: nil)), 60, "Core's minute when it does not say")
        XCTAssertNil(Crypto.retryAfterSecs(JarvisAPIError.rejected(status: 503, message: nil)))
        XCTAssertEqual(Crypto.cooldownText(42), "Core refreshes at most once a minute. Try again in 42 s.")
    }
}

/// Crypto writes through the app model against a fixture Core: the lock gate, the paths and
/// Core's cooldown.
@MainActor
final class CryptoWriteModelTests: XCTestCase {
    private func signedIn() -> JarvisAppModel {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CryptoCoreProtocol.self]
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: URLSession(configuration: configuration))
        return JarvisAppModel(signedInChat: ChatService(api: api, tokenLoader: { "fixture-session-not-a-secret" }), api: api, realtime: false)
    }

    func testALockedAppSendsNoCryptoWrite() async {
        CryptoCoreProtocol.reset(status: 202)
        let model = signedIn()
        model.lockWhenBackgrounded()
        do {
            try await model.refreshCrypto()
            XCTFail("sent while locked")
        } catch {}
        do {
            try await model.addCryptoWallet(NewCryptoWallet(kind: .bitcoin, address: "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", chains: nil, label: nil))
            XCTFail("sent while locked")
        } catch {}
        XCTAssertEqual(CryptoCoreProtocol.requests(), [])
    }

    func testWritesUseTheirPathsAndA429CarriesCoresWait() async throws {
        let model = signedIn()
        CryptoCoreProtocol.reset(status: 201, body: #"{"id":"x"}"#)
        try await model.addCryptoWallet(NewCryptoWallet(kind: .bitcoin, address: "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", chains: nil, label: nil))
        let id = UUID()
        CryptoCoreProtocol.reset(status: 200, body: #"{"deleted":true}"#)
        try await model.deleteCryptoWallet(id)
        XCTAssertEqual(CryptoCoreProtocol.requests(), ["DELETE /v1/trading/crypto/wallets/\(id.uuidString.lowercased())"])
        CryptoCoreProtocol.reset(status: 429, body: #"{"error":"a refresh was asked for less than a minute ago","retry_after_secs":42}"#)
        do {
            try await model.refreshCrypto()
            XCTFail("a 429 is a refusal")
        } catch {
            XCTAssertEqual(error as? CryptoCooldown, CryptoCooldown(seconds: 42))
        }
        XCTAssertEqual(CryptoCoreProtocol.requests(), ["POST /v1/trading/crypto/refresh"])
        // Without a usable wait it stays a plain 429: Core's minute applies.
        CryptoCoreProtocol.reset(status: 429, body: #"{"retry_after_secs":-1}"#)
        do { try await model.refreshCrypto(); XCTFail("a 429 is a refusal") } catch { XCTAssertEqual(Crypto.retryAfterSecs(error), 60) }
        CryptoCoreProtocol.reset(status: 429, body: #"{"retry_after_secs":99999}"#)
        do { try await model.refreshCrypto(); XCTFail("a 429 is a refusal") } catch {
            XCTAssertEqual(Crypto.retryAfterSecs(error), 3600, "capped, as desktop")
        }
    }
}

private final class CryptoCoreProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var log: [String] = []
    private static var status = 202
    private static var body = "{}"

    static func reset(status: Int, body: String = "{}") {
        lock.withLock { log = []; self.status = status; self.body = body }
    }
    static func requests() -> [String] { lock.withLock { log } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, body) = Self.lock.withLock {
            Self.log.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")")
            return (Self.status, Self.body)
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}
