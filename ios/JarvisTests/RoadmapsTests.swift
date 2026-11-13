import XCTest
@testable import Jarvis

/// Mirrors desktop/tests/roadmaps.test.mjs. Neutral test data only: no real roadmap
/// content belongs in this repository.
final class RoadmapsTests: XCTestCase {
    private let r1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c81"

    private func json(_ object: Any) -> String {
        String(decoding: try! JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }

    private var roadmap: [String: Any] {
        ["id": r1, "title": "Example", "description": NSNull(), "phases": ["Now", "Next", "Later"], "revision": 4,
         "created_at": "2026-11-01T10:00:00Z", "updated_at": "2026-11-02T10:00:00Z", "archived_at": NSNull()]
    }

    private func itemJSON(_ id: String, _ overrides: [String: Any] = [:]) -> [String: Any] {
        ([
            "id": id, "title": "Item \(id)", "status": "planned", "auto_status": NSNull(), "phase": "Now", "position": 0,
            "notes": NSNull(), "depends_on": [String](), "links": [Any](), "updated_at": "2026-11-02T10:00:00Z",
        ] as [String: Any]).merging(overrides) { $1 }
    }

    private func snapshot(_ object: Any) -> RoadmapSnapshot? {
        guard let snap = try? JSONDecoder().decode(RoadmapSnapshot.self, from: Data(json(object).utf8)), Roadmaps.valid(snap) else { return nil }
        return snap
    }

    private func item(_ id: String, status: RoadmapStatus = .planned, phase: String? = "Now", position: Int = 0,
                      notes: String? = nil, dependsOn: [String] = []) -> RoadmapItem {
        RoadmapItem(id: id, title: "Item \(id)", status: status, phase: phase, position: position, notes: notes, depends_on: dependsOn)
    }

    func testSnapshotGuardAcceptsCoresShapeAndFailsClosed() throws {
        let link: [String: Any] = ["kind": "pr", "repo": "o/r", "number": 3, "state": "open"]
        let good: [String: Any] = ["roadmap": roadmap, "items": [itemJSON("a", ["links": [link], "auto_status": "in_progress"])]]
        let snap = try XCTUnwrap(snapshot(good))
        XCTAssertEqual(snap.items[0].links[0].state, "open")
        XCTAssertEqual(snap.items[0].auto_status, .inProgress)
        // Absent optional fields read as nil; display-only fields of another type too.
        var raw = roadmap
        raw.removeValue(forKey: "description")
        XCTAssertNil(snapshot(["roadmap": raw, "items": [itemJSON("a", ["notes": NSNull()])]])?.roadmap.description)
        XCTAssertNil(snapshot(["roadmap": roadmap.merging(["description": 5]) { $1 }, "items": [Any]()])?.roadmap.description)
        XCTAssertNil(snapshot(["roadmap": roadmap, "items": [itemJSON("a", ["auto_status": "merged"])]])?.items[0].auto_status)
        let broken: [Any] = [
            [Any](), ["roadmap": roadmap],
            ["roadmap": roadmap.merging(["revision": "4"]) { $1 }, "items": [Any]()],
            ["roadmap": roadmap.merging(["id": "r1"]) { $1 }, "items": [Any]()],
            ["roadmap": roadmap, "items": [itemJSON("a", ["status": "done"])]],
            ["roadmap": roadmap, "items": [itemJSON("a", ["links": [["kind": "ftp", "url": "x"]]])]],
            ["roadmap": roadmap, "items": [itemJSON("a", ["depends_on": "b"])]],
            ["roadmap": roadmap, "items": [itemJSON("a", ["position": -1])]],
            ["roadmap": roadmap, "items": [itemJSON("../x")]],
        ]
        for object in broken { XCTAssertNil(snapshot(object), json(object)) }
        let list = try JSONDecoder().decode(RoadmapList.self, from: Data(json(["roadmaps": [roadmap]]).utf8))
        XCTAssertTrue(Roadmaps.valid(list))
        XCTAssertNil(try? JSONDecoder().decode(RoadmapList.self, from: Data(json(["roadmaps": [roadmap.merging(["phases": [1]]) { $1 }]]).utf8)))
    }

    func testTextRulesMirrorCore() {
        XCTAssertTrue(Roadmaps.validText("Ship it", max: 120))
        XCTAssertFalse(Roadmaps.validText("   ", max: 120))
        XCTAssertFalse(Roadmaps.validText(String(repeating: "a", count: 121), max: 120))
        XCTAssertFalse(Roadmaps.validText("two\nlines", max: 120))
        XCTAssertTrue(Roadmaps.validText("two\nlines\tok", max: 4000, multiline: true))
        XCTAssertFalse(Roadmaps.validText("zero\u{200B}width", max: 120))
        XCTAssertFalse(Roadmaps.validText("bidi\u{202E}flip", max: 4000, multiline: true))
        XCTAssertTrue(Roadmaps.validText("Gus’s plan ✓", max: 120))
        XCTAssertEqual(Roadmaps.parsePhases("Now\n Next \n\nLater\n"), ["Now", "Next", "Later"])
        XCTAssertEqual(Roadmaps.parsePhases(""), [])
        XCTAssertNil(Roadmaps.parsePhases("Now\nNow"))
        XCTAssertNil(Roadmaps.parsePhases((0..<21).map { "P\($0)" }.joined(separator: "\n")))
        XCTAssertNil(Roadmaps.newRoadmapIssue(title: " Plan ", description: "", phases: "Now"))
        XCTAssertEqual(Roadmaps.newRoadmapIssue(title: "", description: "", phases: "Now"), "The title must be 1 to 120 characters on one line.")
    }

    func testGitHubURLsAndShorthandsParseIntoTypedLinks() {
        func ok(_ input: String, _ hint: RoadmapLink.Kind = .pr) -> RoadmapLink? {
            if case let .link(link) = Roadmaps.parseLink(input, hint: hint) { return link }
            XCTFail(input)
            return nil
        }
        XCTAssertEqual(ok("https://github.com/owner/repo/pull/12"), RoadmapLink(kind: .pr, repo: "owner/repo", number: 12))
        XCTAssertEqual(ok("https://github.com/owner/repo/pull/12/files#diff"), RoadmapLink(kind: .pr, repo: "owner/repo", number: 12))
        XCTAssertEqual(ok("https://github.com/owner/repo/issues/7?x=1"), RoadmapLink(kind: .issue, repo: "owner/repo", number: 7))
        XCTAssertEqual(ok(" https://github.com/o-1/r.e_p/releases/tag/v1.2.3 "), RoadmapLink(kind: .release, repo: "o-1/r.e_p", tag: "v1.2.3"))
        XCTAssertEqual(ok("https://github.com/o/r/releases/tag/app%2Fv1"), RoadmapLink(kind: .release, repo: "o/r", tag: "app/v1"))
        XCTAssertEqual(ok("owner/repo#5"), RoadmapLink(kind: .pr, repo: "owner/repo", number: 5))
        XCTAssertEqual(ok("owner/repo#5", .issue), RoadmapLink(kind: .issue, repo: "owner/repo", number: 5))
        XCTAssertEqual(ok("owner/repo@v0.0.78"), RoadmapLink(kind: .release, repo: "owner/repo", tag: "v0.0.78"))
        XCTAssertEqual(ok("https://example.com/a?b=c"), RoadmapLink(kind: .url, url: "https://example.com/a?b=c"))
        for bad in [
            "http://example.com", "javascript:alert(1)", "https://user:pw@example.com", "https://exa mple.com",
            "https://github.com/owner/repo/pull/0", "https://github.com/owner/repo/pull/abc", "https://github.com/-o/r/pull/1",
            "owner/repo#0", "owner#5", "../x#1", "owner/..#1", "", "data:text/html,x",
        ] {
            if case .link = Roadmaps.parseLink(bad) { XCTFail(bad) }
        }
        XCTAssertEqual(Roadmaps.parseLink("http://example.com"), .invalid("Only https links are allowed."))
        XCTAssertTrue(Roadmaps.validHttpsUrl("https://example.com"))
        XCTAssertFalse(Roadmaps.validHttpsUrl("https://example.com/" + String(repeating: "a", count: 500)))
    }

    func testLinksDisplayOpenOnlyOverHttpsAndNeverSendTheCachedState() throws {
        let pr = RoadmapLink(kind: .pr, repo: "o/r", number: 3, state: "merged")
        XCTAssertEqual(Roadmaps.linkLabel(pr), "o/r#3")
        XCTAssertEqual(Roadmaps.linkHref(pr), "https://github.com/o/r/pull/3")
        XCTAssertEqual(Roadmaps.linkHref(RoadmapLink(kind: .issue, repo: "o/r", number: 4)), "https://github.com/o/r/issues/4")
        XCTAssertEqual(Roadmaps.linkHref(RoadmapLink(kind: .release, repo: "o/r", tag: "app/v1+b")), "https://github.com/o/r/releases/tag/app/v1%2Bb")
        XCTAssertNil(Roadmaps.linkHref(RoadmapLink(kind: .url, url: "javascript:alert(1)")))
        let sent = try JSONSerialization.jsonObject(with: JSONEncoder().encode(pr)) as? [String: Any]
        XCTAssertEqual(sent?.keys.sorted(), ["kind", "number", "repo"])
        XCTAssertNil(Roadmaps.addLink([pr], RoadmapLink(kind: .pr, repo: "o/r", number: 3)))
        XCTAssertNil(Roadmaps.addLink((1...10).map { RoadmapLink(kind: .pr, repo: "o/r", number: $0) }, RoadmapLink(kind: .url, url: "https://a.b")))
        XCTAssertEqual(Roadmaps.addLink([pr], RoadmapLink(kind: .issue, repo: "o/r", number: 3))?.count, 2)
    }

    func testTallyPhasesAndWaitingOn() {
        let items = [
            item("a", status: .live, position: 2),
            item("b", status: .blocked, phase: "Next", notes: "Needs a decision\nmore"),
            item("c", position: 1, dependsOn: ["b", "a"]),
            item("d", status: .ready, phase: "Gone"),
            item("e", phase: nil, dependsOn: ["d"]),
        ]
        XCTAssertEqual(Roadmaps.tally(items), [
            .init(status: .live, count: 1), .init(status: .ready, count: 1), .init(status: .planned, count: 2), .init(status: .blocked, count: 1),
        ])
        let groups = Roadmaps.groupByPhase(["Now", "Next", "Later"], items)
        XCTAssertEqual(groups.map(\.phase), ["Now", "Next", "Later", nil])
        XCTAssertEqual(groups.map { $0.items.map(\.id) }, [["c", "a"], ["b"], [], ["d", "e"]])
        // Blocked items, and open items with a dependency that is not ready or live.
        let waiting = Roadmaps.waitingOn(items)
        XCTAssertEqual(waiting.map(\.item.id), ["b", "c"])
        XCTAssertEqual(waiting.map { $0.unmet.map(\.id) }, [[], ["b"]])
        XCTAssertEqual(Roadmaps.firstLine("\n  \nNeeds a decision\nmore"), "Needs a decision")
    }

    func testDependencyPickerLeavesOutTheItemAndEverythingThatDependsOnIt() {
        let items = [item("a"), item("b", dependsOn: ["a"]), item("c", dependsOn: ["b"]), item("d")]
        XCTAssertEqual(Roadmaps.dependencyOptions(items, "a").map(\.id), ["d"])
        XCTAssertEqual(Roadmaps.dependencyOptions(items, "c").map(\.id), ["a", "b", "d"])
        XCTAssertEqual(Roadmaps.dependencyOptions(items, nil).count, 4)
    }

    func testAnItemPatchHoldsOnlyWhatChanged() throws {
        var base = item("a", notes: "Old")
        base.links = [RoadmapLink(kind: .pr, repo: "o/r", number: 1, state: "open")]
        var draft = RoadmapItemDraft(base)
        XCTAssertNil(Roadmaps.patch(base, draft))
        draft.title = "  Item a  "
        XCTAssertNil(Roadmaps.patch(base, draft))
        draft.status = .blocked
        draft.phase = nil
        draft.notes = "  "
        let patch = try XCTUnwrap(Roadmaps.patch(base, draft))
        let sent = try JSONSerialization.jsonObject(with: JSONEncoder().encode(patch)) as? [String: Any]
        XCTAssertEqual(sent?.keys.sorted(), ["notes", "phase", "status"])
        XCTAssertTrue(sent?["phase"] is NSNull && sent?["notes"] is NSNull, "cleared, not left out")
        draft.links.append(RoadmapLink(kind: .url, url: "https://a.b"))
        let links = try JSONSerialization.jsonObject(with: JSONEncoder().encode(XCTUnwrap(Roadmaps.patch(base, draft)?.links))) as? [[String: Any]]
        XCTAssertEqual(links?.map { $0.keys.sorted() }, [["kind", "number", "repo"], ["kind", "url"]])
        XCTAssertNil(Roadmaps.draftIssue(draft))
        draft.title = ""
        XCTAssertEqual(Roadmaps.draftIssue(draft), "The title must be 1 to 200 characters on one line.")
    }

    func testWriteFailures() {
        let conflict = JarvisAPIError.rejected(status: 409, message: nil)
        XCTAssertEqual(Roadmaps.writeFailureText(conflict, revisionMoved: true), Roadmaps.changedElsewhere)
        XCTAssertTrue(Roadmaps.writeFailureText(conflict, revisionMoved: false).contains("archived or a limit"))
        XCTAssertTrue(Roadmaps.writeFailureText(JarvisAPIError.rejected(status: 400, message: "raw"), revisionMoved: false).contains("invalid"))
        XCTAssertTrue(Roadmaps.writeFailureText(JarvisAPIError.unreachable, revisionMoved: false).contains("unknown whether"))
        for error in [JarvisAPIError.invalidResponse, .responseTooLarge] {
            XCTAssertEqual(Roadmaps.writeFailureText(error, revisionMoved: false), "Not confirmed; reloaded to check.")
        }
        XCTAssertEqual(Roadmaps.writeFailureText(JarvisAPIError.rejected(status: 405, message: nil), revisionMoved: false),
                       "Not saved. This Core has no roadmaps yet. Update Core on your Home Node.")
    }
}

/// Roadmap writes through the app model against a fixture Core: the lock and session
/// gates, the revision in every write and the item id check.
@MainActor
final class RoadmapWriteModelTests: XCTestCase {
    private let roadmap = UUID(uuidString: "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c81")!

    private func signedIn() -> JarvisAppModel {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RoadmapCoreProtocol.self]
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!, session: URLSession(configuration: configuration))
        return JarvisAppModel(signedInChat: ChatService(api: api, tokenLoader: { "fixture-session-not-a-secret" }), api: api, realtime: false)
    }

    func testWritesNameTheRevisionAndRefuseAnUnsafeItemId() async throws {
        RoadmapCoreProtocol.reset(status: 200)
        let model = signedIn()
        let base = "/v1/roadmaps/0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c81"
        try await model.createRoadmapItem(roadmap, revision: 4, item: NewRoadmapItem(title: "New", phase: nil))
        try await model.updateRoadmapItem(roadmap, revision: 5, item: "item-1", patch: RoadmapItemPatch(status: .ready))
        try await model.deleteRoadmapItem(roadmap, revision: 6, item: "item-1")
        do {
            try await model.deleteRoadmapItem(roadmap, revision: 7, item: "../../jobs")
            XCTFail("sent an unsafe item id")
        } catch {}
        XCTAssertEqual(RoadmapCoreProtocol.requests(), [
            "POST \(base)/items?expected_revision=4",
            "PATCH \(base)/items/item-1?expected_revision=5",
            "DELETE \(base)/items/item-1?expected_revision=6",
        ])
        XCTAssertTrue(model.isAuthenticated)
    }

    func testALockedAppSendsNoRoadmapWrite() async {
        RoadmapCoreProtocol.reset(status: 200)
        let model = signedIn()
        model.lockWhenBackgrounded()
        do {
            try await model.createRoadmap(NewRoadmap(title: "Plan", description: nil, phases: ["Now"]))
            XCTFail("sent while locked")
        } catch {}
        XCTAssertEqual(RoadmapCoreProtocol.requests(), [])
    }

    func testA401SignsOutOnlyTheSessionItWasSentFor() async throws {
        RoadmapCoreProtocol.reset(status: 401)
        let model = signedIn()
        do { try await model.deleteRoadmapItem(roadmap, revision: 1, item: "a") } catch {}
        XCTAssertFalse(model.isAuthenticated)

        // The 401 arrives after a lock started a new presentation: the session stays.
        RoadmapCoreProtocol.reset(status: 401, delay: 0.5)
        let later = signedIn()
        let write = Task { try await later.deleteRoadmapItem(roadmap, revision: 1, item: "a") }
        var tries = 0
        while RoadmapCoreProtocol.requests().isEmpty, tries < 300 { tries += 1; try await Task.sleep(nanoseconds: 10_000_000) }
        later.lockWhenBackgrounded()
        _ = await write.result
        XCTAssertEqual(RoadmapCoreProtocol.requests().count, 1)
        XCTAssertTrue(later.isAuthenticated, "a late 401 from before the lock keeps the session")
    }
}

private final class RoadmapCoreProtocol: URLProtocol {
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
            let query = request.url?.query.map { "?\($0)" } ?? ""
            Self.log.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")\(query)")
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
