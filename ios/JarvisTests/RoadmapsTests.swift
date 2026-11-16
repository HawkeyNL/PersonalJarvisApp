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

/// Proposals, history and the roadmap's own edit; mirrors the rest of roadmaps.test.mjs.
final class RoadmapParityTests: XCTestCase {
    private func json(_ object: Any) -> Data { try! JSONSerialization.data(withJSONObject: object) }

    private func item(_ id: String, status: RoadmapStatus = .planned, notes: String? = nil) -> RoadmapItem {
        RoadmapItem(id: id, title: "Item \(id)", status: status, phase: "Now", position: 0, notes: notes)
    }

    private func proposals(_ list: [Any]) -> RoadmapProposals? {
        guard let value = try? JSONDecoder().decode(RoadmapProposals.self, from: json(["proposals": list])), Roadmaps.valid(value) else { return nil }
        return value
    }

    func testProposalGuardAndOperationsReadAsBeforeAndAfter() throws {
        let proposal: [String: Any] = [
            "id": "p1", "base_revision": 4, "rationale": NSNull(), "created_at": "2026-11-02T10:00:00Z", "expires_at": "2026-11-16T10:00:00Z",
            "ops": [["op": "create_item", "item": ["title": "New", "phase": "Next", "links": [["kind": "issue", "repo": "o/r", "number": 9]]]],
                    ["op": "update_item", "item_id": "a", "patch": ["status": "ready", "notes": NSNull(), "depends_on": ["b"]]]] as [Any],
        ]
        let read = try XCTUnwrap(proposals([proposal])?.proposals.first)
        XCTAssertNil(proposals([proposal.merging(["ops": [["op": "delete_item", "item_id": "a"]]]) { $1 }]))
        XCTAssertNil(proposals([proposal.merging(["ops": [["op": "create_item", "item": [String: Any]()]]]) { $1 }]))
        XCTAssertNil(proposals([proposal.merging(["id": "../p"]) { $1 }]))
        let items = [item("a", status: .inProgress, notes: "x"), item("b")]
        let create = Roadmaps.describe(read.ops[0], items: items)
        XCTAssertTrue(create.create)
        XCTAssertEqual(create.rows, [
            .init(field: "Status", before: "—", after: "Planned"), .init(field: "Phase", before: "—", after: "Next"),
            .init(field: "Links", before: "—", after: "o/r#9"),
        ])
        let update = Roadmaps.describe(read.ops[1], items: items)
        XCTAssertEqual(update.title, "Item a")
        XCTAssertEqual(update.rows, [
            .init(field: "Status", before: "In progress", after: "Ready"), .init(field: "Notes", before: "x", after: "—"),
            .init(field: "Depends on", before: "—", after: "Item b"),
        ])
        let gone = try XCTUnwrap(proposals([proposal.merging(["ops": [["op": "update_item", "item_id": "zz", "patch": ["title": "T"]]]]) { $1 }]))
        XCTAssertEqual(Roadmaps.describe(gone.proposals[0].ops[0], items: items).rows.first?.before, "—")
    }

    func testProposalTextKeepsLineBreaksAndFlagsHiddenCharacters() throws {
        XCTAssertEqual(Roadmaps.shownText("a\nb\tc").text, "a b c")
        XCTAssertEqual(Roadmaps.shownText("a\nb\tc", multiline: true).text, "a\nb c")
        XCTAssertFalse(Roadmaps.shownText("a\nb", multiline: true).hidden)
        XCTAssertNil(Roadmaps.shownText("\u{200B}").text)
        // Core's hidden characters, beyond the format category, and format characters.
        for hidden in ["\u{34F}", "\u{115F}", "\u{1160}", "\u{3164}", "\u{FE00}", "\u{FE0F}", "\u{202E}", "\u{200B}", "\u{E0001}", "\r"] {
            let shown = Roadmaps.shownText("a\(hidden)b", multiline: true)
            XCTAssertEqual(shown.text, "ab", hidden.unicodeScalars.first.map { String($0.value, radix: 16) } ?? "")
            XCTAssertTrue(shown.hidden)
        }
        let proposal: [String: Any] = [
            "id": "p1", "base_revision": 4, "rationale": "Why\nnow", "created_at": "2026-11-02T10:00:00Z", "expires_at": "2026-11-16T10:00:00Z",
            "ops": [["op": "create_item", "item": ["title": "Ti\u{200B}tle", "phase": "N\u{3164}ext", "notes": "one\ntwo"]],
                    ["op": "update_item", "item_id": "a", "patch": ["notes": "one\ntwo"]]] as [Any],
        ]
        let read = try XCTUnwrap(proposals([proposal])?.proposals.first)
        XCTAssertEqual(Roadmaps.shownText(read.rationale, multiline: true).text, "Why\nnow")
        let create = Roadmaps.describe(read.ops[0], items: [])
        XCTAssertEqual(create.title, "Title")
        XCTAssertTrue(create.hidden)
        XCTAssertEqual(create.rows, [
            .init(field: "Status", before: "—", after: "Planned"), .init(field: "Phase", before: "—", after: "Next"),
            .init(field: "Notes", before: "—", after: "one\ntwo"),
        ])
        let update = Roadmaps.describe(read.ops[1], items: [item("a", notes: "x\ny")])
        XCTAssertFalse(update.hidden)
        XCTAssertEqual(update.rows, [.init(field: "Notes", before: "x\ny", after: "one\ntwo")])
    }

    func testHistoryLinesAndGuard() throws {
        let change: [String: Any] = [
            "id": "c1", "revision": 5, "at": "2026-11-02T10:00:00Z", "author": "github", "device_id": NSNull(), "proposal_id": NSNull(),
            "action": "item_updated", "item_id": "a", "title": "Item a", "fields": ["status", "depends_on"], "from_status": "planned", "to_status": "ready",
        ]
        func history(_ list: [Any]) -> RoadmapHistory? {
            guard let value = try? JSONDecoder().decode(RoadmapHistory.self, from: json(["changes": list])), Roadmaps.valid(value) else { return nil }
            return value
        }
        let read = try XCTUnwrap(history([change])?.changes.first)
        XCTAssertEqual(read.author.label, "GitHub")
        XCTAssertNil(history([change.merging(["author": "someone"]) { $1 }]))
        XCTAssertEqual(Roadmaps.describe(read), "Changed “Item a”: status Planned → Ready, depends on")
        let added = try XCTUnwrap(history([change.merging(["action": "item_created", "fields": [String](), "from_status": NSNull(), "to_status": NSNull()]) { $1 }]))
        XCTAssertEqual(Roadmaps.describe(added.changes[0]), "Added “Item a”")
        let archived = try XCTUnwrap(history([change.merging(["action": "roadmap_archived", "item_id": NSNull()]) { $1 }]))
        XCTAssertEqual(Roadmaps.describe(archived.changes[0]), "Archived the roadmap")
    }

    func testTheRoadmapEditSendsOnlyWhatChangedAndWarnsAboutRemovedPhases() throws {
        let object: [String: Any] = ["id": "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c81", "title": "Example", "description": "Old",
                                     "phases": ["Now", "Next", "Later"], "revision": 4]
        let roadmap = try JSONDecoder().decode(Roadmap.self, from: json(object))
        XCTAssertEqual(try Roadmaps.roadmapPatch(roadmap, title: " Example ", description: "Old", phases: "Now\nNext\nLater").get(), nil)
        let patch = try XCTUnwrap(Roadmaps.roadmapPatch(roadmap, title: "Renamed", description: "  ", phases: "Now\nLater").get())
        let sent = try JSONSerialization.jsonObject(with: JSONEncoder().encode(patch)) as? [String: Any]
        XCTAssertEqual(sent?.keys.sorted(), ["description", "phases", "title"])
        XCTAssertTrue(sent?["description"] is NSNull, "an empty description clears it")
        XCTAssertEqual(sent?["phases"] as? [String], ["Now", "Later"])
        if case let .failure(issue) = Roadmaps.roadmapPatch(roadmap, title: "", description: "", phases: "Now") {
            XCTAssertEqual(issue.text, "The title must be 1 to 120 characters on one line.")
        } else { XCTFail("an empty title saved") }
        let items = [RoadmapItem(id: "a", title: "A", status: .planned, phase: "Next")]
        XCTAssertEqual(Roadmaps.removedPhases(roadmap, items: items, phases: "Now\nLater"), ["Next"])
        XCTAssertEqual(Roadmaps.removedPhases(roadmap, items: items, phases: "Now\nNext"), [])
        for error in [JarvisAPIError.unreachable, .timedOut] {
            XCTAssertEqual(Roadmaps.decisionFailureText(error), "No answer from your Home Node. It is unknown whether it was applied; reloaded to check.")
        }
        XCTAssertEqual(Roadmaps.readFailureText(nil), "This Core has no roadmaps yet. Update Core on your Home Node.")
        XCTAssertEqual(Roadmaps.readFailureText(nil, missing: "proposals"), "This Core has no proposals yet. Update Core on your Home Node.")
        XCTAssertEqual(Roadmaps.readFailureText(nil, missing: "history"), "This Core has no history yet. Update Core on your Home Node.")
        XCTAssertEqual(Roadmaps.readFailureText(.network, missing: "history"), "No answer from your Home Node. Check the connection.")
        XCTAssertEqual(Roadmaps.readFailureText(.network), "No answer from your Home Node. Check the connection.")
        XCTAssertTrue(Roadmaps.readFailureText(.signin).contains("Sign in again"))
        // Jarvis-written text is shown without bidi or invisible characters.
        XCTAssertEqual(Memecoin.plain("Ship\u{202E}it\u{200B}"), "Shipit")
        XCTAssertEqual(Roadmaps.decisionFailureText(JarvisAPIError.rejected(status: 409, message: nil)),
                       "Not applied: the roadmap changed since Jarvis proposed this, or a limit is reached. Reloaded.")
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

    func testRoadmapWritesOpenTheNewRoadmapAndNameTheRevision() async throws {
        let created = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c99"
        RoadmapCoreProtocol.reset(status: 201, body: #"{"roadmap":{"id":"\#(created)"},"items":[]}"#)
        let model = signedIn()
        let id = try await model.createRoadmap(NewRoadmap(title: "Plan", description: nil, phases: ["Now"]))
        XCTAssertEqual(id, UUID(uuidString: created))
        RoadmapCoreProtocol.reset(status: 200)
        let unnamed = try await model.createRoadmap(NewRoadmap(title: "Plan", description: nil, phases: ["Now"]))
        XCTAssertNil(unnamed, "an answer without an id opens nothing")
        try await model.updateRoadmap(roadmap, revision: 4, patch: RoadmapPatch(title: "New"))
        try await model.archiveRoadmap(roadmap, revision: 5)
        try await model.decideRoadmapProposal(roadmap, proposal: "p-1", accept: true)
        try await model.decideRoadmapProposal(roadmap, proposal: "p-1", accept: false)
        do {
            try await model.decideRoadmapProposal(roadmap, proposal: "../x", accept: true)
            XCTFail("sent an unsafe proposal id")
        } catch {}
        let base = "/v1/roadmaps/0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c81"
        XCTAssertEqual(RoadmapCoreProtocol.requests(), [
            "POST /v1/roadmaps", "PATCH \(base)?expected_revision=4", "DELETE \(base)?expected_revision=5",
            "POST \(base)/proposals/p-1/accept", "POST \(base)/proposals/p-1/reject",
        ])
    }

    func testALockedAppSendsNoRoadmapWrite() async {
        RoadmapCoreProtocol.reset(status: 200)
        let model = signedIn()
        model.lockWhenBackgrounded()
        do {
            _ = try await model.createRoadmap(NewRoadmap(title: "Plan", description: nil, phases: ["Now"]))
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
    private static var body = "{}"

    static func reset(status: Int, delay: TimeInterval = 0, body: String = "{}") {
        lock.withLock { log = []; self.status = status; self.delay = delay; self.body = body }
    }
    static func requests() -> [String] { lock.withLock { log } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, delay, body) = Self.lock.withLock {
            let query = request.url?.query.map { "?\($0)" } ?? ""
            Self.log.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")\(query)")
            return (Self.status, Self.delay, Self.body)
        }
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [self] in
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() { }
}

/// Roadmap cards from chat; mirrors desktop/tests/roadmapProposals.test.mjs. Neutral data only.
final class RoadmapCardsTests: XCTestCase {
    private let p1 = "00000000-0000-4000-8000-000000000001"
    private let p2 = "00000000-0000-4000-8000-000000000002"
    private let r1 = "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c81"

    private var newBody: [String: Any] {
        [
            "title": "Example", "description": "A plan.", "phases": ["Done", "Now"],
            "items": [
                ["title": "First", "status": "live", "phase": "Done", "notes": "Shipped.",
                 "links": [["kind": "pr", "repo": "example/app", "number": 12] as [String: Any], ["kind": "release", "repo": "example/app", "tag": "v1.0.0"]]] as [String: Any],
                ["title": "Second", "status": "in_progress", "phase": "Now"],
                ["title": "Third", "status": "planned"],
            ] as [Any],
        ]
    }
    private let itemsBody: [String: Any] = [
        "items": [["title": "Fourth", "status": "ready", "phase": "Later"], ["title": "Fifth", "status": "blocked"]] as [Any],
    ]

    private func newEntry(_ body: [String: Any]? = nil, _ overrides: [String: Any] = [:]) -> [String: Any] {
        (["id": p1, "title": "Example", "body": body ?? newBody] as [String: Any]).merging(overrides) { $1 }
    }
    private func itemsEntry(_ body: [String: Any]? = nil, _ overrides: [String: Any] = [:]) -> [String: Any] {
        (["id": p2, "roadmap_id": r1, "title": "Example", "body": body ?? itemsBody] as [String: Any]).merging(overrides) { $1 }
    }
    private func merged(_ body: [String: Any], _ overrides: [String: Any]) -> [String: Any] { body.merging(overrides) { $1 } }
    private func newItem(_ overrides: [String: Any]) -> [String: Any] {
        merged(newBody, ["items": [merged(["title": "First", "status": "planned", "phase": "Now"], overrides)]])
    }

    /// An `assistant.completed` event; nil leaves `roadmap_proposals` out.
    private func completed(_ proposals: Any?) throws -> RealtimeEvent {
        let run = UUID().uuidString
        var payload: [String: Any] = [
            "run": ["run_id": run, "request_id": run, "conversation_id": run],
            "message": ["id": run, "conversation_id": run, "role": "assistant", "content": "Done.", "created_at": "2026-01-01T00:00:00Z"],
        ]
        if let proposals { payload["roadmap_proposals"] = proposals }
        return try JSONDecoder().decode(RealtimeEvent.self, from: JSONSerialization.data(withJSONObject: [
            "protocol": 1, "epoch": run, "sequence": 1, "event_id": run, "type": "assistant.completed", "payload": payload,
        ] as [String: Any]))
    }

    private func read(_ proposals: Any?) throws -> ChatRoadmapProposal? { RoadmapCards.read(try completed(proposals).payload) }

    private func sent(_ body: ChatRoadmapBody) throws -> NSDictionary? {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? NSDictionary
    }

    func testANewRoadmapReadsAndAddSendsExactlyTheBody() throws {
        let proposal = try XCTUnwrap(read([newEntry()]))
        XCTAssertEqual(proposal.id.uuidString.lowercased(), p1)
        XCTAssertNil(proposal.roadmapId)
        XCTAssertEqual(try sent(proposal.body), newBody as NSDictionary)
        XCTAssertEqual(RoadmapCards.heading(proposal), "New roadmap: Example")
        XCTAssertEqual(RoadmapCards.summary(proposal), "3 items in 2 phases")
        let groups = RoadmapCards.groups(proposal)
        XCTAssertEqual(groups.map(\.phase), ["Done", "Now", nil])
        XCTAssertEqual(groups.map { $0.items.map(\.title) }, [["First"], ["Second"], ["Third"]])
        XCTAssertEqual(groups[1].items.first?.status, .inProgress)
        XCTAssertEqual(groups.map { $0.items.map(\.status.label) }, [["Live"], ["In progress"], ["Planned"]])
        XCTAssertEqual(groups[0].items.first.map { RoadmapCards.linkText($0.links) }, "example/app#12, example/app@v1.0.0")

        let one = try XCTUnwrap(read([newEntry(merged(newBody, ["phases": ["Now"], "items": [["title": "Only", "status": "planned"]]]))]))
        XCTAssertEqual(RoadmapCards.summary(one), "1 item in 1 phase")
        let empty = try XCTUnwrap(read([newEntry(merged(newBody, ["phases": [String](), "items": [Any]()]))]))
        XCTAssertEqual(RoadmapCards.summary(empty), "0 items in 0 phases")
    }

    /// What the card shows: the same parts ChatView renders.
    private func shown(_ proposal: ChatRoadmapProposal) -> [String] {
        [RoadmapCards.heading(proposal), RoadmapCards.summary(proposal), proposal.body.description].compactMap { $0 }
            + RoadmapCards.groups(proposal).flatMap { group in
                [group.phase ?? "No phase"] + group.items.flatMap { item in
                    [item.status.label, item.title, item.notes, item.links.isEmpty ? nil : RoadmapCards.linkText(item.links)].compactMap { $0 }
                }
            }
    }

    /// Every text and number in a sent body, except the enum values `status` and `kind`.
    private func leaves(_ value: Any, key: String = "") -> [String] {
        switch value {
        case let dict as [String: Any]: return dict.flatMap { leaves($0.value, key: $0.key) }
        case let list as [Any]: return list.flatMap { leaves($0, key: key) }
        case let text as String: return key == "status" || key == "kind" ? [] : [text]
        case let number as NSNumber: return [number.stringValue]
        default: return []
        }
    }

    func testTheCardShowsEverythingAddSends() throws {
        let body = merged(newBody, ["phases": ["Done", "Now", "Empty"]])
        let proposal = try XCTUnwrap(read([newEntry(body)]))
        let card = shown(proposal)
        XCTAssertTrue(card.contains("Empty"), "an empty phase is shown")
        let sent = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(proposal.body)))
        for text in leaves(sent) {
            XCTAssertTrue(card.contains { $0.contains(text) }, "not shown: \(text)")
        }
        let items = try XCTUnwrap(read([itemsEntry(merged(itemsBody, ["items": [["title": "Fourth", "status": "ready", "notes": "Why.\nMore."]]]))]))
        let itemCard = shown(items)
        for text in leaves(try JSONSerialization.jsonObject(with: JSONEncoder().encode(items.body))) {
            XCTAssertTrue(itemCard.contains { $0.contains(text) }, "not shown: \(text)")
        }
    }

    func testItemsForAnExistingRoadmapRead() throws {
        let proposal = try XCTUnwrap(read([itemsEntry()]))
        XCTAssertEqual(proposal.roadmapId, UUID(uuidString: r1))
        XCTAssertEqual(try sent(proposal.body), itemsBody as NSDictionary)
        XCTAssertEqual(RoadmapCards.heading(proposal), "Add 2 items to Example")
        XCTAssertEqual(RoadmapCards.summary(proposal), "2 items")
        XCTAssertEqual(RoadmapCards.groups(proposal).map(\.phase), ["Later", nil])
        let one = try XCTUnwrap(read([itemsEntry(["items": [["title": "Only", "status": "planned"]]])]))
        XCTAssertEqual(RoadmapCards.heading(one), "Add 1 item to Example")
        XCTAssertEqual(RoadmapCards.summary(one), "1 item")
    }

    func testEveryInvalidShapeSkipsTheCardAndTheMessageStays() throws {
        let item: [String: Any] = ["title": "x", "status": "planned"]
        let bad: [Any] = [
            NSNull(), "roadmap", newEntry(merged(newBody, [:]), ["id": "not-a-uuid"]),
            newEntry(nil, ["extra": true]),
            newEntry(merged(newBody, ["extra": true])),
            newEntry(merged(newBody, ["title": "Other"])),
            newEntry(merged(newBody, ["phases": NSNull()])),
            ["id": p1, "title": "Example", "body": merged(newBody, [:]).filter { $0.key != "phases" }] as [String: Any],
            newEntry(nil, ["title": String(repeating: "t", count: 121)]),
            newEntry(merged(newBody, ["description": String(repeating: "d", count: 2001)])),
            newEntry(merged(newBody, ["phases": (1...21).map { "P\($0)" }, "items": [Any]()])),
            newEntry(merged(newBody, ["phases": [String(repeating: "p", count: 61)], "items": [Any]()])),
            newEntry(merged(newBody, ["phases": ["Now", "Now"], "items": [Any]()])),
            newEntry(merged(newBody, ["phases": "Now", "items": [Any]()])),
            // Hidden and bidi characters anywhere.
            newEntry(merged(newBody, ["title": "Example\u{202E}"]), ["title": "Example\u{202E}"]),
            newEntry(merged(newBody, ["description": "A\u{202E} plan."])),
            newEntry(merged(newBody, ["phases": ["Now\u{202E}"], "items": [Any]()])),
            newEntry(newItem(["notes": "Note\u{202E}"])),
            newEntry(newItem(["phase": "Now\u{200B}"])),
            // Unknown status, unknown item keys, and Core's text rules.
            newEntry(newItem(["status": "done"])),
            newEntry(newItem(["position": 0])),
            newEntry(newItem(["depends_on": [String]()])),
            newEntry(newItem(["title": ""])),
            newEntry(newItem(["title": "Two\nlines"])),
            newEntry(newItem(["title": String(repeating: "t", count: 201)])),
            newEntry(newItem(["notes": String(repeating: "n", count: 4001)])),
            newEntry(newItem(["title": "Hidden\u{202E}"])),
            // Links: pr, issue or release on a valid repo; no url, no cached state.
            newEntry(newItem(["links": [["kind": "url", "url": "https://example.com"]]])),
            newEntry(newItem(["links": [["kind": "pr", "repo": "example/app", "number": 1, "url": "https://example.com"] as [String: Any]]])),
            newEntry(newItem(["links": [["kind": "pr", "repo": "example/app", "number": 1, "state": "open"] as [String: Any]]])),
            newEntry(newItem(["links": [["kind": "pr", "repo": "not a repo", "number": 1] as [String: Any]]])),
            newEntry(newItem(["links": [["kind": "issue", "repo": "example/app"]]])),
            newEntry(newItem(["links": [["kind": "release", "repo": "example/app", "number": 1] as [String: Any]]])),
            newEntry(newItem(["links": (1...11).map { ["kind": "pr", "repo": "example/app", "number": $0] as [String: Any] }])),
            // A phase that is not among the new roadmap's phases.
            newEntry(newItem(["phase": "Someday"])),
            newEntry(merged(newBody, ["items": Array(repeating: item, count: 101)])),
            // An existing roadmap: items only, 1 to 100.
            itemsEntry(["items": [Any]()]),
            itemsEntry(["items": Array(repeating: item, count: 101)]),
            itemsEntry(merged(itemsBody, ["phases": ["Now"]])),
            itemsEntry(merged(itemsBody, ["title": "Example"])),
            itemsEntry(nil, ["roadmap_id": "not-a-uuid"]),
            // A field is left out only by being absent: present but null is refused.
            newEntry(nil, ["roadmap_id": NSNull()]),
            itemsEntry(nil, ["roadmap_id": NSNull()]),
            newEntry(merged(newBody, ["description": NSNull()])),
            newEntry(merged(newBody, ["items": NSNull()])),
            newEntry(newItem(["phase": NSNull()])),
            newEntry(newItem(["notes": NSNull()])),
            newEntry(newItem(["links": NSNull()])),
            newEntry(newItem(["links": [["kind": "pr", "repo": NSNull(), "number": 1] as [String: Any]]])),
            newEntry(newItem(["links": [["kind": "pr", "repo": "example/app", "number": NSNull()] as [String: Any]]])),
            newEntry(newItem(["links": [["kind": "pr", "repo": "example/app", "number": 1, "tag": NSNull()] as [String: Any]]])),
            newEntry(newItem(["links": [["kind": "release", "repo": "example/app", "tag": "v1", "number": NSNull()] as [String: Any]]])),
        ]
        for entry in bad {
            let event = try completed([entry])
            XCTAssertNil(RoadmapCards.read(event.payload), "\(entry)")
            XCTAssertEqual(event.payload.message?.content, "Done.")
        }
        XCTAssertNotNil(try read([newEntry(merged(newBody, ["items": Array(repeating: item, count: 100)]))]), "100 items fit")
        // Only the first entry counts.
        XCTAssertNil(try read([bad[3], newEntry()] as [Any]))
        XCTAssertEqual(try read([itemsEntry(), newEntry()])?.id.uuidString.lowercased(), p2)
        XCTAssertNil(try read(nil))
        XCTAssertNil(try read([Any]()))
    }

    func testEachProposalShowsOncePerId() throws {
        var seen = Set<UUID>()
        let proposal = try read([newEntry()])
        let conversation = UUID()
        let cards = RoadmapCards.adding(proposal, to: [], seen: &seen, conversation: conversation, message: "m")
        XCTAssertEqual(cards.map(\.state), [.open])
        XCTAssertEqual(RoadmapCards.adding(proposal, to: cards, seen: &seen, conversation: conversation, message: "m"), cards)
        // Dismissed (gone from the list) stays gone when the event comes again.
        XCTAssertEqual(RoadmapCards.adding(proposal, to: [], seen: &seen, conversation: conversation, message: "m"), [])
        XCTAssertEqual(RoadmapCards.adding(nil, to: [], seen: &seen, conversation: conversation, message: "m"), [])
    }

    private func rejected(_ status: Int) -> JarvisAPIError { .rejected(status: status, message: nil) }

    func testANewRoadmapIsOneCreate() async throws {
        let proposal = try XCTUnwrap(read([newEntry()]))
        let created = UUID()
        var bodies: [ChatRoadmapBody] = []
        let id = try await RoadmapCards.add(proposal, proceed: {}, create: { bodies.append($0); return created },
                                            revision: { _ in XCTFail("no read"); return 0 },
                                            createItems: { _, _, _ in XCTFail("no batch"); return nil })
        XCTAssertEqual(id, created)
        XCTAssertEqual(bodies, [proposal.body])
        do {
            _ = try await RoadmapCards.add(proposal, proceed: {}, create: { _ in nil }, revision: { _ in 0 }, createItems: { _, _, _ in nil })
            XCTFail("an answer without an id is not confirmed")
        } catch { XCTAssertEqual(error as? JarvisAPIError, .invalidResponse) }
        // A 409 on a new roadmap is Core's refusal: no retry.
        var creates = 0
        do {
            _ = try await RoadmapCards.add(proposal, proceed: {}, create: { _ in creates += 1; throw self.rejected(409) },
                                           revision: { _ in 0 }, createItems: { _, _, _ in nil })
            XCTFail("refused")
        } catch { XCTAssertEqual(RoadmapCards.outcome(.failure(error)).1, RoadmapCards.refused) }
        XCTAssertEqual(creates, 1)
    }

    func testItemsGoOnTheCurrentRevisionAndA409IsTriedOnceMore() async throws {
        let proposal = try XCTUnwrap(read([itemsEntry()]))
        let roadmap = try XCTUnwrap(proposal.roadmapId)
        var calls: [String] = []
        var revision = 4
        var conflicts = 1
        let add: () async throws -> UUID = {
            try await RoadmapCards.add(proposal, proceed: {}, create: { _ in XCTFail("no create"); return nil },
                                       revision: { id in calls.append("GET \(id == roadmap)"); return revision },
                                       createItems: { id, rev, body in
                                           calls.append("POST \(rev)")
                                           XCTAssertEqual(body, proposal.body)
                                           if conflicts > 0 { conflicts -= 1; revision += 1; throw self.rejected(409) }
                                           return id
                                       })
        }
        let added = try await add()
        XCTAssertEqual(added, roadmap)
        XCTAssertEqual(calls, ["GET true", "POST 4", "GET true", "POST 5"])

        // A second 409 is Core's refusal.
        calls = []; conflicts = 2
        do { _ = try await add(); XCTFail("refused") } catch {
            XCTAssertEqual(Jobs.status(error), 409)
            XCTAssertEqual(RoadmapCards.outcome(.failure(error)).1, RoadmapCards.refused)
        }
        XCTAssertEqual(calls, ["GET true", "POST 5", "GET true", "POST 6"])

        // A failed revision read sends nothing and opens the card again with why.
        let reads: [(JarvisAPIError, String)] = [
            (.unreachable, "No answer from your Home Node. Check the connection."),
            (rejected(503), "Core could not read the roadmaps just now. Try again in a minute."),
            (.invalidResponse, "Core returned an answer this app does not understand, or an error. Try again later."),
        ]
        for (failure, text) in reads {
            var writes = 0
            do {
                _ = try await RoadmapCards.add(proposal, proceed: {}, create: { _ in nil }, revision: { _ in throw failure },
                                               createItems: { _, _, _ in writes += 1; return nil })
                XCTFail("read failed")
            } catch {
                let (state, shown) = RoadmapCards.outcome(.failure(error))
                XCTAssertEqual(state, .open)
                XCTAssertEqual(shown, text)
            }
            XCTAssertEqual(writes, 0)
        }
    }

    func testALockOrLeavingBetweenTheReadAndTheWriteSendsNothingMore() async throws {
        let proposal = try XCTUnwrap(read([itemsEntry()]))
        var calls: [String] = []
        var checks = 0
        do {
            _ = try await RoadmapCards.add(proposal, proceed: {
                checks += 1
                calls.append("check")
                // The app locks (or the conversation changes) once the revision was read.
                if checks == 2 { throw RoadmapCards.Locked() }
            }, create: { _ in XCTFail("no create"); return nil },
            revision: { _ in calls.append("GET"); return 4 },
            createItems: { _, _, _ in calls.append("POST"); return nil })
            XCTFail("stopped")
        } catch {
            let (state, shown) = RoadmapCards.outcome(.failure(error))
            XCTAssertEqual(state, .open, "the card opens again")
            XCTAssertNil(shown)
        }
        XCTAssertEqual(calls, ["check", "GET", "check"])

        // A new roadmap is checked before its create.
        let new = try XCTUnwrap(read([newEntry()]))
        var creates = 0
        do {
            _ = try await RoadmapCards.add(new, proceed: { throw RoadmapCards.Locked() }, create: { _ in creates += 1; return UUID() },
                                           revision: { _ in 0 }, createItems: { _, _, _ in nil })
            XCTFail("stopped")
        } catch { XCTAssertTrue(error is RoadmapCards.Locked) }
        XCTAssertEqual(creates, 0)
    }

    func testAddOutcomesFailClosed() throws {
        let proposal = try XCTUnwrap(read([newEntry()]))
        let fresh = { [RoadmapCard(proposal: proposal, conversationId: UUID(), messageId: "m")] }
        var cards = fresh()
        XCTAssertNil(RoadmapCards.begin(proposal.id, in: &cards, unlocked: false), "locked: nothing sent")
        XCTAssertEqual(cards[0].state, .open)
        XCTAssertNotNil(RoadmapCards.begin(proposal.id, in: &cards, unlocked: true))
        XCTAssertEqual(cards[0].state, .adding)
        XCTAssertNil(RoadmapCards.begin(proposal.id, in: &cards, unlocked: true), "a second tap sends nothing")

        // A result after the owner left the session or conversation is dropped.
        let added = UUID()
        RoadmapCards.finish(proposal.id, in: &cards, result: .success(added), current: false)
        XCTAssertEqual(cards[0].state, .adding)
        RoadmapCards.finish(proposal.id, in: &cards, result: .success(added), current: true)
        XCTAssertEqual(cards[0].state, .added(added))
        XCTAssertNil(cards[0].error)
        XCTAssertNil(RoadmapCards.begin(proposal.id, in: &cards, unlocked: true), "added once")

        // 5xx, no answer or an unreadable answer: it may exist, so Add stays off.
        let unknown: [JarvisAPIError] = [.invalidResponse, .responseTooLarge, .unreachable, .timedOut,
                                         rejected(500), rejected(502), rejected(503), rejected(504)]
        for error in unknown {
            cards = fresh()
            _ = RoadmapCards.begin(proposal.id, in: &cards, unlocked: true)
            RoadmapCards.finish(proposal.id, in: &cards, result: .failure(error), current: true)
            XCTAssertEqual(cards[0].state, .unconfirmed, "\(error)")
            XCTAssertEqual(cards[0].error, "Not confirmed: it may have been added.")
            XCTAssertNil(RoadmapCards.begin(proposal.id, in: &cards, unlocked: true))
        }

        // A 4xx opens the card again with why.
        let refused = "Core refused this roadmap, for example because a phase changed or a limit was reached. Ask Jarvis again."
        let open: [(JarvisAPIError, String)] = [
            (rejected(400), refused), (rejected(409), refused), (rejected(413), refused), (rejected(422), refused),
            (rejected(429), "Core returned an answer this app does not understand, or an error. Try again later."),
            (rejected(404), "Update Jarvis Core to add roadmaps from chat."),
            (rejected(405), "Update Jarvis Core to add roadmaps from chat."),
            (.unauthorized, "Your session is not valid. Sign in again from Settings."),
        ]
        for (error, text) in open {
            cards = fresh()
            _ = RoadmapCards.begin(proposal.id, in: &cards, unlocked: true)
            RoadmapCards.finish(proposal.id, in: &cards, result: .failure(error), current: true)
            XCTAssertEqual(cards[0].state, .open, "\(error)")
            XCTAssertEqual(cards[0].error, text)
            XCTAssertNotNil(RoadmapCards.begin(proposal.id, in: &cards, unlocked: true))
            XCTAssertNil(cards[0].error)
        }
        XCTAssertEqual("\(RoadmapCards.added) · \(RoadmapCards.viewRoadmap)", "Added to Roadmaps · View roadmap")
        XCTAssertEqual("\(RoadmapCards.notConfirmed) \(RoadmapCards.checkRoadmaps)", "Not confirmed: it may have been added. Check Roadmaps")
    }
}
