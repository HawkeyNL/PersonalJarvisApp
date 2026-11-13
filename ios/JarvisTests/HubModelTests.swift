import XCTest
@testable import Jarvis

final class HubModelTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func iso(_ secondsAgo: TimeInterval) -> String {
        ISO8601DateFormatter().string(from: now.addingTimeInterval(-secondsAgo))
    }

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    func testFailedReadsAreClassifiedLikeDesktop() {
        func state(_ error: Error) -> String {
            switch Availability<Int>(error: error) {
            case .unsupported: "unsupported"
            case let .error(reason): "\(reason)"
            case .loading, .ok: "unexpected"
            }
        }
        XCTAssertEqual(state(JarvisAPIError.rejected(status: 404, message: nil)), "unsupported")
        XCTAssertEqual(state(JarvisAPIError.rejected(status: 405, message: nil)), "unsupported")
        XCTAssertEqual(state(JarvisAPIError.rejected(status: 403, message: nil)), "forbidden")
        XCTAssertEqual(state(JarvisAPIError.rejected(status: 500, message: nil)), "failed")
        XCTAssertEqual(state(JarvisAPIError.rejected(status: 503, message: nil)), "unavailable")
        XCTAssertEqual(state(JarvisAPIError.unauthorized), "signin")
        XCTAssertEqual(state(JarvisAPIError.unreachable), "network")
        XCTAssertEqual(state(JarvisAPIError.timedOut), "network")
        XCTAssertEqual(state(JarvisAPIError.invalidResponse), "failed")
        XCTAssertEqual(state(CancellationError()), "failed")
    }

    func testRelativeTimeAndUptime() {
        XCTAssertEqual(relativeTime(iso(10), now: now), "just now")
        XCTAssertEqual(relativeTime(iso(4 * 60), now: now), "4m ago")
        XCTAssertEqual(relativeTime(iso(2 * 3600), now: now), "2h ago")
        XCTAssertEqual(relativeTime(iso(3 * 86_400), now: now), "3d ago")
        XCTAssertEqual(relativeTime("2026-01-01T10:00:00.123Z", now: Date(timeIntervalSince1970: 1_767_261_600 + 150)), "2m ago")
        XCTAssertEqual(relativeTime("not a date", now: now), "")
        XCTAssertEqual(formatUptime(5 * 60), "5m")
        XCTAssertEqual(formatUptime(3 * 3600 + 12 * 60), "3h 12m")
        XCTAssertEqual(formatUptime(14 * 86_400 + 2 * 3600), "14d 2h")
        XCTAssertEqual(formatUptime(-1), "0m")
        XCTAssertEqual(formatMs(850), "850 ms")
        XCTAssertEqual(formatMs(1200), "1.2 s")
        XCTAssertEqual(formatMs(nil), "—")
    }

    func testHubCardsUseOnlyReportedData() {
        XCTAssertEqual(conversationsCard([], now: now).line1, "No conversations yet")
        let conversations = conversationsCard([iso(3600), iso(2 * 86_400), iso(10 * 86_400)], now: now)
        XCTAssertEqual(conversations, CardSummary(line1: "2 this week", line2: "Last: 1h ago", tone: .ok))

        XCTAssertEqual(tasksCard(sessions: .ok([]), pending: .ok([])), CardSummary(line1: "0 active", line2: "0 awaiting approval", tone: .idle))
        XCTAssertEqual(tasksCard(sessions: .unsupported, pending: .ok([])).line1, "Requires newer Core")
        XCTAssertEqual(tasksCard(sessions: .error(.network), pending: .ok([])),
                       CardSummary(line1: "Unavailable", line2: "Check the connection", tone: .warn))
        XCTAssertEqual(tasksCard(sessions: .loading, pending: .ok([])).line1, "Loading…")
    }

    func testUsageSummaryDecodesBudgetFlags() throws {
        let u = try decode(UsageSummary.self, """
        {"budget_eur":10,"spent_eur":8.5,"remaining_eur":1.5,"over_budget":false,"reserved_eur":0.25,"above_soft_budget":true,
         "by_backend":[{"backend":"anthropic","spent_eur":8.5,"total_tokens":1200}],"daily":[]}
        """)
        XCTAssertEqual(u.reserved_eur, 0.25)
        XCTAssertEqual(u.above_soft_budget, true)
        XCTAssertEqual(u.by_backend?.first?.backend, "anthropic")
        let old = try decode(UsageSummary.self, #"{"budget_eur":1,"spent_eur":0,"over_budget":false}"#)
        XCTAssertNil(old.above_soft_budget)
    }

    func testTasksCardAndSessions() throws {
        let sessions = try decode(CodingSessionList.self, """
        {"sessions":[{"id":"a","state":"active"},{"id":"b","state":"suspended","repository":"r"},{"id":"c","state":"completed"}]}
        """).sessions
        let pending = try decode(PendingActionList.self, """
        {"pending":[{"pending_id":"p-1","action":"git.push","preview":"push main","nonce":"00","created_at":"2026-01-01T00:00:00Z"}]}
        """).pending
        XCTAssertEqual(tasksCard(sessions: .ok(sessions), pending: .ok(pending)),
                       CardSummary(line1: "1 active", line2: "1 awaiting approval", tone: .warn))
        XCTAssertEqual(tasksCard(sessions: .ok(sessions), pending: .unsupported).line2, "Approvals unavailable")
        let split = splitSessions(sessions)
        XCTAssertEqual(split.open.map(\.id), ["a", "b"])
        XCTAssertEqual(split.finished.map(\.id), ["c"])
        XCTAssertEqual(sessionState("suspended").1, "Paused")
        XCTAssertEqual(sessionState("weird").1, "weird")
        XCTAssertEqual(outcomeTone("denied"), .warn)
    }

    func testPendingIdsMustBePlainPathSegments() {
        XCTAssertTrue(isSafePathSegment("3f2b9c4e-1a2b-4c3d-9e8f-0a1b2c3d4e5f"))
        XCTAssertTrue(isSafePathSegment("abc_123"))
        for unsafe in ["", "../approve", "a/b", "a?x=1", "a%2F", "a b", "é", String(repeating: "a", count: 129)] {
            XCTAssertFalse(isSafePathSegment(unsafe), unsafe)
        }
    }

    func testAgentsAreGroupedAndNeverShowFakeActivity() throws {
        let response = try decode(AgentsResponse.self, """
        {"bundle_id":"b","agent_count":3,"unavailable_reason":null,"usage_unavailable_reason":null,"agents":[
          {"id":"trader","name":"Trader","group":"Finance","description":"d","model_policy":"auto","allowed_tools":["ibkr"],
           "limits":{"max_runtime_seconds":1,"max_context_chars":1,"max_output_chars":1,"max_parallel_runs":1},
           "usage":{"requests":2,"input_tokens":1,"output_tokens":1,"total_tokens":2,"spent_eur":0.5,"failures":null}},
          {"id":"clip","name":"Clipper","group":null,"description":"d","model_policy":"auto","allowed_tools":[],"usage":null},
          {"id":"code","name":"Code","group":"Build","description":"d","model_policy":"auto","allowed_tools":[],
           "usage":{"requests":0,"input_tokens":0,"output_tokens":0,"total_tokens":0,"spent_eur":0}}
        ]}
        """)
        let groups = groupAgents(response.agents)
        XCTAssertEqual(groups.map { $0.group }, ["Build", "Finance", "Other"])
        XCTAssertEqual(agentStatus(response.agents[0].usage, unavailableReason: nil).1, "2 requests this month")
        XCTAssertEqual(agentStatus(response.agents[2].usage, unavailableReason: nil).1, "Not used this month")
        XCTAssertEqual(agentStatus(nil, unavailableReason: agentUsageNotInstrumented).1, "Not measured yet")
        XCTAssertEqual(agentStatus(nil, unavailableReason: nil).1, "Usage unavailable")
        XCTAssertTrue(isTrader(response.agents[0]))
        XCTAssertFalse(isTrader(response.agents[1]))
    }

    func testServicesDisksAndBroker() throws {
        let response = try decode(ServicesResponse.self, """
        {"services":[{"label":"Core","unit":"jarvis-core.service","state":"active"},
                     {"label":"Voice","unit":"jarvis-voice.service","state":"inactive"}],
         "disks":[{"label":"/","state":"ok","total_bytes":1073741824000,"free_bytes":107374182400,"used_percent":90},
                  {"label":"/data","state":"unavailable"}]}
        """)
        XCTAssertEqual(servicesSummary(response.services).1, "1 of 2 running")
        XCTAssertEqual(servicesSummary(response.services).0, .warn)
        XCTAssertEqual(servicesSummary([]).1, "No services reported")
        XCTAssertEqual(serviceState("inactive").1, "Inactive")
        XCTAssertEqual(serviceState("failed").0, .error)
        XCTAssertEqual(diskState(response.disks[0]).0, .error)
        XCTAssertEqual(diskState(response.disks[0]).1, "90% used · 100.0 GiB free of 1000.0 GiB")
        XCTAssertEqual(diskState(response.disks[1]).1, "Unknown")
    }

    func testResourceMetersShowCpuMemoryAndEveryDisk() throws {
        let registry = try decode(HomeNodeRegistry.self, """
        {"host":{"os":"Linux","arch":"x86_64","cpu":"Ryzen","cpu_cores":16,"mem_total_gb":32,"gpu":"Radeon"},"software":[],
         "live_host":{"sampled_at":1,"cpu_percent":12.34,"memory_total_bytes":34359738368,"memory_used_bytes":30064771072,"uptime_seconds":60}}
        """)
        let disks = try decode(ServicesResponse.self, """
        {"services":[],"disks":[{"label":"system","state":"ok","total_bytes":107374182400,"free_bytes":5368709120,"used_percent":95},
                                {"label":"data","state":"unknown"}]}
        """).disks
        XCTAssertEqual(resourceMeters(live: registry.liveHost, disks: disks), [
            Meter(id: "cpu", label: "CPU", percent: 12, tone: .ok, detail: "12.3% in use"),
            Meter(id: "memory", label: "Memory", percent: 88, tone: .warn, detail: "28.0 GiB used · 4.0 GiB free of 32.0 GiB"),
            Meter(id: "disk-system-0", label: "Disk · system", percent: 95, tone: .error, detail: "95.0 GiB used · 5.0 GiB free of 100.0 GiB"),
            Meter(id: "disk-data-1", label: "Disk · data", percent: nil, tone: .idle, detail: "Unknown"),
        ])
        // The first CPU sample and an unknown memory total have no reading.
        let first = try decode(HomeNodeRegistry.LiveHost.self, """
        {"sampled_at":1,"cpu_percent":null,"memory_total_bytes":0,"memory_used_bytes":0,"uptime_seconds":60}
        """)
        XCTAssertEqual(resourceMeters(live: first, disks: []).map(\.detail), ["First sample…", "Unknown"])
        XCTAssertEqual(resourceMeters(live: first, disks: []).map(\.percent), [nil, nil])
        XCTAssertEqual(resourceMeters(live: nil, disks: []), [])
    }

    func testIbkrLinkStateComesFromThePortfolioRead() throws {
        let now = try XCTUnwrap(parseCoreDate("2026-11-01T12:00:00Z"))
        let synced = try decode(IbkrPortfolio.self, """
        {"source":"IBKR","as_of":"2026-11-01T11:55:00Z","base_currency":"EUR","net_liquidation":"1.00","cash":"1.00",
         "positions":[{"conid":1,"symbol":"VWRL"},{"conid":2,"symbol":"AAPL"}]}
        """)
        XCTAssertEqual(synced.positions.count, 2)
        XCTAssertEqual(ibkrState(.ok(synced), now: now).0, .ok)
        XCTAssertEqual(ibkrState(.ok(synced), now: now).1, "Connected")
        let old = try decode(IbkrPortfolio.self, #"{"as_of":"2026-11-01T11:00:00Z","positions":[]}"#)
        XCTAssertEqual(ibkrState(.ok(old), now: now).0, .warn)
        XCTAssertEqual(ibkrState(.ok(old), now: now).1, "Connected, sync is old")
        XCTAssertEqual(ibkrState(.error(.unavailable), now: now).0, .warn)
        XCTAssertEqual(ibkrState(.error(.unavailable), now: now).1, "Not connected")
        XCTAssertEqual(ibkrState(.error(.failed), now: now).0, .warn)
        XCTAssertEqual(ibkrState(.error(.failed), now: now).1, "Unavailable")
        XCTAssertEqual(ibkrState(.unsupported, now: now).0, .idle)
        XCTAssertEqual(ibkrState(.unsupported, now: now).1, "Requires newer Core")
        XCTAssertFalse(ibkrNotSynced.localizedCaseInsensitiveContains("gateway"))
    }

    func testRegistryStillDecodesWithoutNewerFields() throws {
        let registry = try decode(HomeNodeRegistry.self, """
        {"host":{"os":"linux","arch":"x86_64","cpu":"Ryzen","cpu_cores":16,"mem_total_gb":32,"gpu":"Radeon"},
         "software":[{"name":"git","present":true,"version":"2.43"},{"name":"docker","present":false,"version":null}]}
        """)
        XCTAssertNil(registry.brains)
        XCTAssertNil(registry.liveHost)
        XCTAssertEqual(registry.software.filter(\.present).count, 1)
    }

    func testIntegrationsShowBrainFactsAndTheCatalogLikeDesktop() throws {
        let registry = try decode(HomeNodeRegistry.self, """
        {"host":{"os":"linux","arch":"x86_64","cpu":"Ryzen","cpu_cores":16,"mem_total_gb":32,"gpu":"Radeon"},
         "software":[{"name":"git","present":true,"version":"2.43"},{"name":"docker","present":false,"version":null}],
         "brains":[{"id":"a","label":"A","cost":"plan","available":true},{"id":"b","label":"B","cost":"plan","available":false},
                   {"id":"c","label":"C","cost":"local","available":true}],
         "models":[{"id":"m1","backend":"ollama","class":"local","cost":"local","available":true}],
         "active_brain":"a"}
        """)
        XCTAssertEqual(brainFacts(registry).map(\.value), ["a", "2", "1 ready", "0 ready", "1 ready", "1 present"])
        XCTAssertEqual(catalogRows(registry.models?.entries ?? []).map(\.detail), ["ollama · local · Local"])
        XCTAssertEqual(catalogRows(registry.models?.entries ?? []).map(\.toneLabel), ["Provider set up"])
        let older = try decode(HomeNodeRegistry.self, """
        {"host":{"os":"linux","arch":"x86_64","cpu":"Ryzen","cpu_cores":16,"mem_total_gb":32,"gpu":"Radeon"},"software":[],"active_brain":""}
        """)
        XCTAssertEqual(brainFacts(older).first?.value, "Not reported")
        XCTAssertNil(older.models)
    }

    func testNewerDisplayFieldsNeverFailTheRegistryOrPolicy() throws {
        let host = #""host":{"os":"linux","arch":"x86_64","cpu":"Ryzen","cpu_cores":16,"mem_total_gb":32,"gpu":"Radeon"},"software":[]"#
        let long = String(repeating: "b", count: 100)
        let models = #"[{"id":"m1","backend":null,"class":7},{"id":"m2"},{"backend":"no id"},"junk",{"id":"m3","available":"yes"}]"#
        let registry = try decode(HomeNodeRegistry.self, #"{\#(host),"active_brain":"\#(long)","models":\#(models)}"#)
        XCTAssertEqual(registry.models?.entries.map(\.id), ["m1", "m2", "m3"])
        XCTAssertEqual(catalogRows(registry.models?.entries ?? []).map(\.detail), ["", "", ""])
        XCTAssertEqual(catalogRows(registry.models?.entries ?? []).map(\.toneLabel), Array(repeating: "Provider not set up", count: 3))
        XCTAssertEqual(registry.activeBrain?.count, 64)
        let odd = try decode(HomeNodeRegistry.self, #"{\#(host),"active_brain":5,"models":{"id":"m1"}}"#)
        XCTAssertNil(odd.activeBrain)
        XCTAssertEqual(odd.models?.entries.count, 0)
        let older = try decode(HomeNodeRegistry.self, #"{\#(host)}"#)
        let id = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b"
        let policy = try decode(ModelPolicySnapshot.self, #"""
        {"models":[{"provider":"p","model":"a","enabled":true,"route":3},{"provider":"p","model":"b","enabled":false,"route":null}],
         "mutation":"unavailable","user_id":"\#(id)","device_id":"\#(id)","server_time":100}
        """#)
        XCTAssertEqual(policyFacts(policy, catalog: older).map(\.value), ["1", "1", "0", "Under System health"])
        XCTAssertEqual(policyFacts(policy, catalog: nil)[2].value, "Unavailable")
        XCTAssertEqual(policy.models.map(\.routeName), [nil, nil])
        XCTAssertEqual(try decode(ModelAccessEntry.self, #"{"provider":"p","model":"m","enabled":true,"route":"fast"}"#).routeName, "fast")
    }

    func testStatusPillAndMood() {
        XCTAssertEqual(statusPill(mood: .idle, online: false).label, "Offline")
        XCTAssertEqual(statusPill(mood: .thinking, online: true).label, "Thinking")
        XCTAssertEqual(statusPill(mood: .idle, online: nil).tone, .idle)
    }

    func testDiskBadgeFollowsTheDiskTone() {
        XCTAssertEqual([Tone.ok, .warn, .error, .idle].map(diskBadge), ["OK", "Filling up", "Nearly full", "Unknown"])
    }

    func testOnlyOtherActiveDevicesCanBeRevoked() {
        func device(_ id: String, _ status: String = "active") -> LinkedDevice {
            LinkedDevice(id: id, name: id, platform: "ios", status: status, created_at: nil)
        }
        let mine = "AAAAAAAA-0000-0000-0000-000000000001"
        let list = [device(mine), device("other-active"), device("other-revoked", "revoked")]
        // Nothing is offered before this device's own id is known.
        XCTAssertTrue(revocableDevices(list, thisDevice: nil).isEmpty)
        // This device is matched case-insensitively and never offered.
        XCTAssertEqual(revocableDevices(list, thisDevice: mine.lowercased()).map(\.id), ["other-active"])
    }

    func testDeepLinksOnlyMapTheTwoSupportedRoutes() {
        func route(_ text: String) -> HubRoute? { deepLinkRoute(URL(string: text)!) }
        XCTAssertEqual(route("jarvis://approvals/pairing"), .pairingApprovals)
        XCTAssertEqual(route("JARVIS://Health/usage"), .healthUsage)
        XCTAssertEqual(route("jarvis://health/usage?approve=1"), .healthUsage)
        XCTAssertNil(route("jarvis://approvals/pairing/approve"))
        XCTAssertNil(route("jarvis://approvals"))
        XCTAssertNil(route("jarvis://health/models"))
        XCTAssertNil(route("jarvis://settings"))
        XCTAssertNil(route("https://approvals/pairing"))
    }

    func testHubNeedsUnlockReachableNodeAndSession() {
        XCTAssertEqual(rootScreen(lock: .unlocked, connection: .unconfigured, authenticated: false), .enrollment)
        XCTAssertEqual(rootScreen(lock: .unlocked, connection: .checking, authenticated: true), .enrollment)
        XCTAssertEqual(rootScreen(lock: .unlocked, connection: .unreachable("offline"), authenticated: true), .enrollment)
        XCTAssertEqual(rootScreen(lock: .unlocked, connection: .reachable, authenticated: false), .enrollment)
        XCTAssertEqual(rootScreen(lock: .unlocked, connection: .reachable, authenticated: true), .hub)
        XCTAssertEqual(rootScreen(lock: .locked, connection: .reachable, authenticated: true), .lock)
        XCTAssertEqual(rootScreen(lock: .denied, connection: .unconfigured, authenticated: false), .lock)
    }

    func testRootScreenDecisionIsUnchanged() {
        let locks: [AppLockState] = [.unlocked, .locked, .denied, .unavailable("no biometrics")]
        let connections: [ConnectionState] = [.unconfigured, .checking, .reachable, .unreachable("offline")]
        for lock in locks {
            for connection in connections {
                for authenticated in [false, true] {
                    // The pre-tabs rule: lock first, then the hub only with a reachable node and a session.
                    let expected: RootScreen = lock != .unlocked ? .lock
                        : (connection == .reachable && authenticated ? .hub : .enrollment)
                    XCTAssertEqual(rootScreen(lock: lock, connection: connection, authenticated: authenticated), expected,
                                   "\(lock) \(connection) \(authenticated)")
                }
            }
        }
    }

    /// Onboarding progress follows the real connection and enrollment states.
    func testOnboardingStepFollowsConnectionAndEnrollment() {
        let enrollments: [EnrollmentState] = [.notStarted, .needsPassword, .needsActivation, .requesting,
                                              .awaitingApproval(expiresAt: now), .authenticating, .authenticated,
                                              .signedOut, .failed("no")]
        for connection in [ConnectionState.unconfigured, .checking, .unreachable("offline")] {
            for enrollment in enrollments {
                XCTAssertEqual(OnboardingStep(connection: connection, enrollment: enrollment), .connect)
            }
        }
        let reachable = enrollments.map { OnboardingStep(connection: .reachable, enrollment: $0) }
        XCTAssertEqual(reachable, [.signIn, .signIn, .signIn, .signIn, .approve, .finish, .finish, .signIn, .signIn])
        XCTAssertEqual(OnboardingStep.allCases.map(\.label), ["Connect", "Sign in", "Approve", "Finish"])
        XCTAssertEqual(OnboardingStep.approve.accessibilityLabel, "Step 3 of 4, Approve")
    }

    func testEveryRouteIsReachableFromATab() {
        XCTAssertEqual(AppTab.allCases.map(\.title), ["Jarvis", "Trading", "Activity", "Settings"])
        XCTAssertEqual(HubRoute.allCases.count, 15)
        for route in HubRoute.allCases {
            let destination = tabDestination(route)
            if destination.path.isEmpty {
                // Shown on the tab's root screen itself.
                XCTAssertEqual(destination.tab.root, route, "\(route)")
            } else {
                // One push from a row the tab's root screen draws from `links`.
                XCTAssertEqual(destination.path, [route], "\(route)")
                XCTAssertTrue(destination.tab.links.contains(route), "\(route)")
            }
        }
        // Each route has exactly one home: no tab links to a route another tab owns.
        for tab in AppTab.allCases {
            for route in tab.links { XCTAssertEqual(tabDestination(route).tab, tab, "\(route)") }
        }
        XCTAssertEqual(tabDestination(.pairingApprovals).tab, .activity)
        XCTAssertEqual(tabDestination(.healthUsage).tab, .settings)
        XCTAssertEqual(tabDestination(.settings).path, [])
    }

    func testApproveStaysOffUntilTheEndOfTheDetailsWasShown() {
        var gate = ReviewGate()
        XCTAssertFalse(gate.reachedEnd)
        // Before layout: zero viewport, infinite marker. Never opens.
        gate.observe(endY: 0, viewport: 0)
        gate.observe(endY: .infinity, viewport: 600)
        gate.observe(endY: .nan, viewport: 600)
        XCTAssertFalse(gate.reachedEnd)
        // Long details: the end marker is below the visible area while scrolling.
        gate.observe(endY: 2400, viewport: 600)
        gate.observe(endY: 1200, viewport: 600)
        gate.observe(endY: 601, viewport: 600)
        XCTAssertFalse(gate.reachedEnd)
        gate.observe(endY: 580, viewport: 600)
        XCTAssertTrue(gate.reachedEnd)
        // Scrolling back up keeps it open for this review.
        gate.observe(endY: 2400, viewport: 600)
        XCTAssertTrue(gate.reachedEnd)
        // A new review starts closed; details that fit open it at once.
        var short = ReviewGate()
        XCTAssertFalse(short.reachedEnd)
        short.observe(endY: 320, viewport: 600)
        XCTAssertTrue(short.reachedEnd)
    }
}
