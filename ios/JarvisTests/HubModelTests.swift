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

        XCTAssertEqual(agentsCard(.ok(1)), CardSummary(line1: "1 agent", line2: "Configured on Core", tone: .ok))
        XCTAssertEqual(agentsCard(.ok(0)).tone, .idle)
        XCTAssertEqual(agentsCard(.unsupported).line1, "Requires newer Core")
        XCTAssertEqual(agentsCard(.error(.network)), CardSummary(line1: "Unavailable", line2: "Check the connection", tone: .warn))
        XCTAssertEqual(agentsCard(.loading).line1, "Loading…")

        XCTAssertEqual(healthCard(online: nil, registry: .loading).line1, "Checking…")
        XCTAssertEqual(healthCard(online: false, registry: .loading).tone, .error)
        XCTAssertEqual(healthCard(online: true, registry: .unsupported), CardSummary(line1: "Core ready", line2: "", tone: .ok))
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
        XCTAssertEqual(ibkrState(IbkrStatus(reachable: true, authenticated: true, connected: nil)).1, "Connected")
        XCTAssertEqual(ibkrState(IbkrStatus(reachable: true, authenticated: true, connected: false)).0, .warn)
        XCTAssertEqual(ibkrState(IbkrStatus(reachable: false, authenticated: false, connected: nil)).1, "Gateway unreachable")
    }

    func testRegistryStillDecodesWithoutNewerFields() throws {
        let registry = try decode(HomeNodeRegistry.self, """
        {"host":{"os":"linux","arch":"x86_64","cpu":"Ryzen","cpu_cores":16,"mem_total_gb":32,"gpu":"Radeon"},
         "software":[{"name":"git","present":true,"version":"2.43"},{"name":"docker","present":false,"version":null}]}
        """)
        XCTAssertNil(registry.brains)
        XCTAssertNil(registry.liveHost)
        XCTAssertEqual(integrationsCard(.ok(registry.software)),
                       CardSummary(line1: "1 of 2 present", line2: "1 missing", tone: .warn))
        XCTAssertEqual(healthCard(online: true, registry: .ok(registry)).line2, "Live vitals need newer Core")
    }

    func testStatusPillAndMood() {
        XCTAssertEqual(statusPill(mood: .idle, online: false).label, "Offline")
        XCTAssertEqual(statusPill(mood: .thinking, online: true).label, "Thinking")
        XCTAssertEqual(statusPill(mood: .idle, online: nil).tone, .idle)
        XCTAssertEqual(moodFactor(.thinking), 0.4)
        XCTAssertEqual(moodFactor(.listening), 0.7)
        XCTAssertEqual(moodFactor(.idle), 1)
    }
}
