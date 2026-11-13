import XCTest
@testable import Jarvis

/// Mirrors desktop/tests/trading.test.mjs. Neutral test data only.
final class TradingTests: XCTestCase {
    private func json(_ object: Any) -> Data { try! JSONSerialization.data(withJSONObject: object) }

    private let meta: [String: Any] = ["source": "IBKR", "delay_minutes": 15, "as_of": "2026-11-01T12:00:00Z"]
    private let position: [String: Any] = [
        "conid": 1, "symbol": "A", "asset_class": "STK", "currency": "EUR", "quantity": "1", "avg_cost": "1", "last_price": "1",
        "market_value": "1",
    ]

    private func portfolio(_ overrides: [String: Any] = [:]) -> [String: Any] {
        meta.merging(["base_currency": "EUR", "net_liquidation": "1", "cash": "0", "positions": [position]]) { $1 }.merging(overrides) { $1 }
    }

    private func read(_ object: [String: Any]) -> Portfolio? {
        guard let value = try? JSONDecoder().decode(Portfolio.self, from: json(object)), Trading.valid(value) else { return nil }
        return value
    }

    func testDecimalStringsFormatExactlyWithoutFloats() {
        XCTAssertEqual(Trading.formatDecimal("1234567.891"), "1,234,567.891")
        XCTAssertEqual(Trading.formatDecimal("0.1", places: 2), "0.10")
        XCTAssertEqual(Trading.formatDecimal("1.005", places: 2), "1.01")
        XCTAssertEqual(Trading.formatDecimal("9999999999999999.995", places: 2), "10,000,000,000,000,000.00")
        XCTAssertEqual(Trading.formatDecimal("-0.004", places: 2), "0.00")
        XCTAssertEqual(Trading.formatDecimal("12.50", sign: true), "+12.50")
        XCTAssertEqual(Trading.formatDecimal("-3", sign: true), "−3")
        XCTAssertEqual(Trading.formatDecimal("0.00010000", maxPlaces: 8), "0.0001")
        XCTAssertEqual(Trading.formatDecimal("007"), "7")
        for bad in [nil, "", "1e5", "1,000", "NaN", " -", "--1"] { XCTAssertEqual(Trading.formatDecimal(bad), "—", bad ?? "nil") }
    }

    func testMoneyFollowsTheCurrencyAndKeepsItsSign() {
        XCTAssertEqual(Trading.formatMoney("23820", "EUR"), "€23,820.00")
        XCTAssertEqual(Trading.formatMoney("-63.1", "EUR", sign: true), "−€63.10")
        XCTAssertEqual(Trading.formatMoney("101.6", "EUR", sign: true), "+€101.60")
        XCTAssertEqual(Trading.formatMoney("0.00", "EUR", sign: true), "€0.00")
        XCTAssertEqual(Trading.formatMoney("176.4", "USD"), "$176.40")
        XCTAssertEqual(Trading.formatMoney("2850.4", "JPY"), "¥2,850")
        XCTAssertEqual(Trading.formatMoney("0.000000412", "EUR", places: 9), "€0.000000412")
        XCTAssertEqual(Trading.formatMoney("10", "CHF"), "10.00 CHF")
        XCTAssertEqual(Trading.formatMoney("10", "not-a-code"), "10.00 not-a-code")
        XCTAssertEqual(Trading.formatMoney("10", ""), "10.00")
        XCTAssertEqual(Trading.formatMoney("10", nil), "10.00")
        XCTAssertEqual(Trading.formatMoney(nil, "EUR"), "—")
        XCTAssertEqual(Trading.formatPrice("0.00041200", "USD"), "$0.000412")
        XCTAssertEqual(Trading.formatPrice("176.40", "USD"), "$176.40")
        XCTAssertEqual(Trading.formatPrice("2850.00", "JPY"), "¥2,850")
        // Desktop's Intl "en" narrow symbols (node: new Intl.NumberFormat("en", { currencyDisplay: "narrowSymbol" })).
        for (code, text) in [("CAD", "$10.00"), ("AUD", "$10.00"), ("HKD", "$10.00"), ("GBP", "£10.00"), ("SEK", "kr10.00"), ("PLN", "zł10.00")] {
            XCTAssertEqual(Trading.formatMoney("10", code), text, code)
        }
    }

    func testPercentagesSignsAndChartNumbers() {
        XCTAssertEqual(Trading.formatPercent("0.43"), "+0.43%")
        XCTAssertEqual(Trading.formatPercent("-99.9"), "−99.90%")
        XCTAssertEqual(Trading.formatPercent(nil), "—")
        XCTAssertEqual(["-1", "0.000", "3", "x"].map { Trading.sign($0) }, [-1, 0, 1, nil])
        XCTAssertEqual(Trading.chartNumber("176.40"), 176.4)
        XCTAssertNil(Trading.chartNumber("abc"))
        XCTAssertEqual(Trading.changeRatio("100", "104.8"), "4.80")
        XCTAssertNil(Trading.changeRatio("0", "1"))
        XCTAssertEqual(Trading.changeRatio("100", "95"), "-5.00")
        XCTAssertNil(Trading.changeRatio(nil, "1"))
        // Rounded like desktop's toFixed(2).
        XCTAssertEqual(Trading.changeRatio("3", "4"), "33.33")
        XCTAssertEqual(Trading.changeRatio("1", "1.00005"), "0.01")
        XCTAssertEqual(Trading.changeRatio("8", "9"), "12.50")
        XCTAssertEqual(Trading.changeRatio("100", "100"), "0.00")
    }

    func testMovingAverageWaitsForNBarsAndSkipsGaps() {
        XCTAssertEqual(Trading.movingAverage([1, 2, 3, 4], 2), [nil, 1.5, 2.5, 3.5])
        XCTAssertEqual(Trading.movingAverage([1, nil, 3, 5], 2), [nil, nil, nil, 4])
        XCTAssertEqual(Trading.movingAverage([1, 2], 50), [nil, nil])
    }

    func testLabelsNameTheSourceAndTheDelay() throws {
        func label(_ delay: Any?) throws -> String {
            var object = portfolio()
            object["delay_minutes"] = delay ?? NSNull()
            return Trading.sourceLabel(try XCTUnwrap(read(object)))
        }
        XCTAssertEqual(try label(15), "IBKR · delayed 15 min")
        XCTAssertEqual(try label(0), "IBKR · real time")
        XCTAssertEqual(try label(nil), "IBKR · delay unknown")
        XCTAssertNil(read(portfolio(["delay_minutes": -1])))
        XCTAssertNil(read(portfolio(["delay_minutes": "15"])))
        XCTAssertEqual(Trading.ageText(3 * 3600), "3 h")
        XCTAssertEqual(Trading.ageText(.infinity), "an unknown time")
    }

    func testReadsBecomeScreenStates() throws {
        let now = try XCTUnwrap(parseCoreDate("2026-11-01T12:05:00Z"))
        let value = try XCTUnwrap(read(portfolio()))
        func kind(_ state: TradingState<Portfolio>) -> String {
            switch state {
            case .loading: "loading"
            case let .notConnected(reason): "not-connected \(reason)"
            case let .error(reason): "error \(reason)"
            case let .ok(_, empty, stale): "ok empty=\(empty) stale=\(stale != nil)"
            }
        }
        let empty: (Portfolio) -> Bool = { $0.positions.isEmpty }
        XCTAssertEqual(kind(Trading.state(.ok(value), now: now, isEmpty: empty)), "ok empty=false stale=false")
        XCTAssertEqual(kind(Trading.state(.ok(value), now: now.addingTimeInterval(Trading.staleAfter), isEmpty: empty)), "ok empty=false stale=true")
        XCTAssertEqual(kind(Trading.state(.ok(value), now: now, isEmpty: { _ in true })), "ok empty=true stale=false")
        XCTAssertEqual(kind(Trading.state(.unsupported, now: now, isEmpty: empty)), "not-connected noApi")
        XCTAssertEqual(kind(Trading.state(.unsupported, instrument: true, now: now, isEmpty: empty)), "error notHeld")
        XCTAssertEqual(kind(Trading.state(.error(.unavailable), now: now, isEmpty: empty)), "not-connected notSynced")
        XCTAssertEqual(kind(Trading.state(.error(.network), now: now, isEmpty: empty)), "not-connected unreachable")
        XCTAssertEqual(kind(Trading.state(.error(.signin), now: now, isEmpty: empty)), "error signin")
        XCTAssertEqual(kind(Trading.state(.error(.forbidden), now: now, isEmpty: empty)), "error failed")
        let unreadable = try XCTUnwrap(read(portfolio(["as_of": "garbage"])))
        XCTAssertEqual(kind(Trading.state(.ok(unreadable), now: now, isEmpty: empty)), "ok empty=false stale=true")
        // The IBKR link state comes from the same read.
        XCTAssertEqual(Trading.linkState(Trading.state(.ok(value), now: now, isEmpty: empty)).1, "Connected")
        XCTAssertEqual(Trading.linkState(Trading.state(.error(.unavailable), now: now, isEmpty: empty)).1, "Not connected")
        XCTAssertEqual(Trading.linkState(Trading.state(.unsupported, now: now, isEmpty: empty)).1, "Requires newer Core")
        for reason in [TradingNotConnected.noApi, .notSynced, .unreachable] {
            let text = Trading.notConnectedText(reason)
            XCTAssertGreaterThan(text.detail.count, 40)
            XCTAssertNil((text.title + text.detail).range(of: "gateway|client portal", options: [.regularExpression, .caseInsensitive]))
        }
        XCTAssertTrue(Trading.errorText(.notHeld).contains("no longer in your portfolio"))
    }

    func testAllocationCountsBucketsWithinTwoPointsOfTarget() throws {
        let value = try XCTUnwrap(read(portfolio(["allocation": [
            ["label": "a", "current_pct": "57", "target_pct": "60"], ["label": "b", "current_pct": "9", "target_pct": "10"],
            ["label": "c", "current_pct": "x", "target_pct": "10"],
        ]])))
        let allocation = try XCTUnwrap(value.allocation)
        XCTAssertEqual(Trading.onTarget(allocation), 1)
        XCTAssertEqual(Trading.offTarget(allocation[0])?.text, "3 pt below target")
    }

    func testPortfolioGuardChecksRequiredFieldsAndShapes() throws {
        XCTAssertNotNil(read(portfolio()))
        var noDelay = portfolio()
        noDelay.removeValue(forKey: "delay_minutes")
        XCTAssertNotNil(read(noDelay))
        // An optional display value of another type reads as missing.
        XCTAssertNil(read(portfolio(["day_pnl": 5]))?.day_pnl?.value)
        XCTAssertNotNil(read(portfolio(["day_pnl": 5])))
        var bad: [[String: Any]] = [
            portfolio(["base_currency": NSNull()]), portfolio(["net_liquidation": 1]), portfolio(["positions": "none"]),
            portfolio(["positions": [position.merging(["symbol": 7]) { $1 }]]), portfolio(["positions": [position.merging(["conid": "abc"]) { $1 }]]),
            portfolio(["positions": [position.merging(["conid": 0]) { $1 }]]), portfolio(["positions": [NSNull()]]),
            portfolio(["allocation": [["label": "x", "current_pct": 5, "target_pct": "5"]]]),
            portfolio(["upcoming_dividends": [["conid": 1, "symbol": "A", "ex_date": "2026-11-05"]]]),
            portfolio(["monthly_deposit": ["amount": "500"]]),
            portfolio(["positions": Array(repeating: position, count: 1001)]),
        ]
        var noAsOf = portfolio()
        noAsOf.removeValue(forKey: "as_of")
        bad.append(noAsOf)
        for object in bad { XCTAssertNil(read(object), "\(object.keys.sorted())") }
    }

    func testPositionsCoreLeftOutAreGuardedAndNamed() throws {
        XCTAssertNil(Trading.skippedText(try XCTUnwrap(read(portfolio()))))
        let two = try XCTUnwrap(read(portfolio(["skipped_count": 2, "skipped_positions": [["symbol": "AAPL", "currency": "USD"], ["symbol": "NOCUR"]]])))
        XCTAssertEqual(Trading.skippedText(two), "2 positions not shown (not in EUR): AAPL (USD), NOCUR (no currency)")
        let one = try XCTUnwrap(read(portfolio(["skipped_count": 1, "skipped_positions": [["symbol": "AAPL", "currency": "USD"]]])))
        XCTAssertEqual(Trading.skippedText(one), "1 position not shown (not in EUR): AAPL (USD)")
        let named = Array(repeating: ["symbol": "A", "currency": "USD"], count: 50)
        let many = try XCTUnwrap(read(portfolio(["skipped_count": 60, "skipped_positions": named])))
        XCTAssertTrue(Trading.skippedText(many)?.hasSuffix(" and 10 more") == true)
        // 32 astral characters are 32 code points: Core's bound.
        XCTAssertNotNil(read(portfolio(["skipped_count": 1, "skipped_positions": [["symbol": String(repeating: "\u{1F600}", count: 32)]]])))
        let skipped = [["symbol": "AAPL", "currency": "USD"], ["symbol": "NOCUR"]]
        let bad: [[String: Any]] = [
            ["skipped_count": 1], ["skipped_positions": [["symbol": "A"]]], ["skipped_count": 0, "skipped_positions": [Any]()],
            ["skipped_count": "2", "skipped_positions": skipped], ["skipped_count": 1, "skipped_positions": skipped],
            ["skipped_count": 1001, "skipped_positions": skipped], ["skipped_count": 2, "skipped_positions": [Any]()],
            ["skipped_count": 1, "skipped_positions": [["symbol": ""]]],
            ["skipped_count": 1, "skipped_positions": [["symbol": String(repeating: "X", count: 33)]]],
            ["skipped_count": 1, "skipped_positions": [["symbol": "A", "currency": "usd"]]],
            ["skipped_count": 60, "skipped_positions": Array(repeating: ["symbol": "A"], count: 51)],
        ]
        for object in bad { XCTAssertNil(read(portfolio(object)), "\(object)") }
    }

    func testBarsGuardDropsBarsWithoutFinitePricesAndRejectsBadShapes() throws {
        let bar: [String: Any] = ["t": "2026-11-01T12:00:00Z", "o": "1", "h": "2", "l": "0.5", "c": "1.5", "v": "100"]
        let base = meta.merging(["conid": 1, "symbol": "A", "currency": "USD", "range": "1M", "bars": [bar]]) { $1 }
        func bars(_ overrides: [String: Any]) -> InstrumentBars? {
            guard let value = try? JSONDecoder().decode(InstrumentBars.self, from: json(base.merging(overrides) { $1 })),
                  Trading.valid(value) else { return nil }
            return value
        }
        var noOpen = bar
        noOpen.removeValue(forKey: "o")
        let mixed = try XCTUnwrap(bars(["bars": [bar, noOpen, bar.merging(["c": "NaN"]) { $1 }, bar.merging(["h": 1]) { $1 }, NSNull(),
                                                bar.merging(["v": "x"]) { $1 }]]))
        XCTAssertEqual(mixed.readable.count, 2)
        XCTAssertNil(mixed.readable[1].volume)
        XCTAssertNil(bars(["symbol": NSNull()]))
        XCTAssertNil(bars(["currency": 3]))
        XCTAssertNil(bars(["conid": -1]))
        XCTAssertNil(bars(["bars": Array(repeating: bar, count: 5001)]))
        XCTAssertNil(bars(["notes": [["t": "x", "kind": "rumour", "text": "y"]]]))
        XCTAssertNil(bars(["notes": [["t": "x", "kind": "fact", "text": "y", "sources": [1]]]]))
        XCTAssertNil(bars(["notes": Array(repeating: ["t": "x", "kind": "fact", "text": "y"], count: 51)]))
        XCTAssertEqual(bars(["notes": [["t": "x", "kind": "inference", "text": "y"]]])?.notes?.first?.kind.label, "AI inference")
        XCTAssertEqual(Trading.barsPath(265598, "1M"), "/v1/trading/instruments/265598/bars?range=1M")
        XCTAssertNil(Trading.barsPath(0, "1M"))
        XCTAssertNil(Trading.barsPath(1, "2D"))
    }

    func testHistoryGuardDropsUnreadablePointsAndCapsTheList() throws {
        let base = meta.merging(["base_currency": "EUR", "range": "3M"]) { $1 }
        func history(_ points: [Any]) -> PortfolioHistory? {
            guard let value = try? JSONDecoder().decode(PortfolioHistory.self, from: json(base.merging(["points": points]) { $1 })),
                  Trading.valid(value) else { return nil }
            return value
        }
        XCTAssertEqual(history([["t": "a", "value": "1"], ["t": "b", "value": 2], ["value": "3"]])?.readable.count, 1)
        XCTAssertNil(history(Array(repeating: ["t": "a", "value": "1"], count: 5001)))
        XCTAssertNil(Trading.historyPath("2Y"))
        XCTAssertEqual(Trading.historyPath("ALL"), "/v1/trading/portfolio/history?range=ALL")
    }

    func testAChartIsThinnedOnceWithItsAveragesOverEveryBar() {
        let bars = (0..<1000).map { i in ChartBar(open: Double(i), high: Double(i) + 2, low: Double(i) - 1, close: Double(i) + 1, volume: 1) }
        let series = ChartSeries(bars, averages: [50])
        XCTAssertEqual(series.count, 1000)
        XCTAssertEqual(series.bars.count, ChartSeries.maxPoints)
        XCTAssertEqual(series.bars.first?.open, 0)
        XCTAssertEqual(series.bars.last?.close, 1000)
        XCTAssertEqual(series.bars.first?.high, Double(1000 / 180) + 1)
        XCTAssertEqual(series.bars.map { $0.volume ?? 0 }.reduce(0, +), 1000)
        XCTAssertEqual(series.averages[50]?.count, ChartSeries.maxPoints)
        XCTAssertEqual(series.averages[50]?.last ?? nil, Trading.movingAverage(bars.map { Optional($0.close) }, 50).last ?? nil)
        XCTAssertEqual(series.low, -1)
        XCTAssertEqual(series.high, 1001)
        XCTAssertTrue(series.hasVolume)
        let short = ChartSeries(Array(bars.prefix(10)))
        XCTAssertEqual(short.bars, Array(bars.prefix(10)))
        XCTAssertEqual(Trading.buckets(1000, max: 180).map(\.count).reduce(0, +), 1000)
    }

    func testAHoldingIsReadAsTypedWithADecimalComma() {
        XCTAssertEqual(Trading.newHolding(symbol: " aapl ", quantity: "12,5", avgCost: " 101.50 "),
                       NewHolding(symbol: "AAPL", quantity: "12.5", avg_cost: "101.50"))
        XCTAssertEqual(Trading.newHolding(symbol: "asml", quantity: "3", avgCost: "612,4")?.avg_cost, "612.4")
        for (symbol, quantity, cost) in [("", "1", "1"), ("A", "1.234,5", "1"), ("A", "1,2,3", "1"), ("A", "abc", "1"), ("A", "1", "")] {
            XCTAssertNil(Trading.newHolding(symbol: symbol, quantity: quantity, avgCost: cost), "\(symbol) \(quantity) \(cost)")
        }
    }

    func testHoldingsNeedSafeIdsAndRefusalsGetFixedWording() throws {
        for error in [JarvisAPIError.invalidResponse, .responseTooLarge] {
            XCTAssertEqual(Trading.holdingFailureText(error, adding: true), "Not confirmed; reloaded to check.")
            XCTAssertEqual(Trading.holdingFailureText(error, adding: false), "Not confirmed; reloaded to check.")
        }
        let holding: [String: Any] = ["id": "h-1", "symbol": "AAPL", "quantity": "2", "avg_cost": "100", "currency": "USD",
                                      "cost_basis": "200", "weight_pct": "100"]
        let list = try JSONDecoder().decode(HoldingList.self, from: json(["holdings": [holding], "total_cost": "200"]))
        XCTAssertTrue(Trading.valid(list))
        let unsafe = try JSONDecoder().decode(HoldingList.self, from: json(["holdings": [holding.merging(["id": "../x"]) { $1 }], "total_cost": "0"]))
        XCTAssertFalse(Trading.valid(unsafe))
        XCTAssertEqual(Trading.holdingFailureText(JarvisAPIError.rejected(status: 400, message: "raw <b>text</b>"), adding: true),
                       "Not added. Core refused this as invalid. Check the symbol, quantity and average cost.")
        XCTAssertTrue(Trading.holdingFailureText(JarvisAPIError.timedOut, adding: false).hasPrefix("Not removed. No answer"))
    }
}

/// Holding writes through the app model against a fixture Core: the lock and session gates
/// and the id check.
@MainActor
final class HoldingWriteModelTests: XCTestCase {
    private func signedIn() -> JarvisAppModel {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HoldingCoreProtocol.self]
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: URLSession(configuration: configuration))
        return JarvisAppModel(signedInChat: ChatService(api: api, tokenLoader: { "fixture-session-not-a-secret" }), api: api, realtime: false)
    }

    func testWritesGoToTheHoldingsRoutesAndRefuseAnUnsafeId() async throws {
        HoldingCoreProtocol.reset(status: 200)
        let model = signedIn()
        try await model.addHolding(NewHolding(symbol: "AAPL", quantity: "2", avg_cost: "100"))
        try await model.deleteHolding("h-1")
        do {
            try await model.deleteHolding("../jobs")
            XCTFail("sent an unsafe id")
        } catch {}
        XCTAssertEqual(HoldingCoreProtocol.requests(), ["POST /v1/holdings", "DELETE /v1/holdings/h-1"])
    }

    func testALockedAppSendsNoHoldingWrite() async {
        HoldingCoreProtocol.reset(status: 200)
        let model = signedIn()
        model.lockWhenBackgrounded()
        do {
            try await model.deleteHolding("h-1")
            XCTFail("sent while locked")
        } catch {}
        XCTAssertEqual(HoldingCoreProtocol.requests(), [])
    }

    func testA401SignsOutOnlyTheSessionItWasSentFor() async throws {
        HoldingCoreProtocol.reset(status: 401)
        let model = signedIn()
        do { try await model.deleteHolding("h-1") } catch {}
        XCTAssertFalse(model.isAuthenticated)

        HoldingCoreProtocol.reset(status: 401, delay: 0.5)
        let later = signedIn()
        let write = Task { try await later.deleteHolding("h-1") }
        var tries = 0
        while HoldingCoreProtocol.requests().isEmpty, tries < 300 { tries += 1; try await Task.sleep(nanoseconds: 10_000_000) }
        later.lockWhenBackgrounded()
        _ = await write.result
        XCTAssertEqual(HoldingCoreProtocol.requests().count, 1)
        XCTAssertTrue(later.isAuthenticated, "a late 401 from before the lock keeps the session")
    }
}

private final class HoldingCoreProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var log: [String] = []
    private static var status = 200
    private static var delay: TimeInterval = 0

    static func reset(status: Int, delay: TimeInterval = 0) {
        lock.withLock { log = []; self.status = status; self.delay = delay }
    }
    static func requests() -> [String] { lock.withLock { log } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, delay) = Self.lock.withLock {
            Self.log.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")")
            return (Self.status, Self.delay)
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [self] in
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("{}".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() { }
}
