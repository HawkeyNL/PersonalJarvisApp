import XCTest
@testable import Jarvis

private final class NoNetworkProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost)) }
    override func stopLoading() {}
}

final class ClientDTOTests: XCTestCase {
    @MainActor
    func testPlaybackRequestContainsOnlyNativeAuthAndTypedRunState() throws {
        let run = UUID()
        let event = SpeechPlaybackEvent(run: run, state: .started)
        let request = try XCTUnwrap(VoicePlaybackReporter.request(origin: URL(string: "https://jarvis.example.com")!, token: "fixture-session", event: event))
        XCTAssertEqual(request.url?.absoluteString, "https://jarvis.example.com/v1/voice/playback")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.timeoutInterval, 5)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-session")
        let body = try JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: String]
        XCTAssertEqual(body, ["run_id":run.uuidString,"state":"started"])
        for invalid in ["http://jarvis.example.com", "https://user:password@jarvis.example.com", "https://jarvis.example.com/wrong", "https://jarvis.example.com?token=fixture", "https://jarvis.example.com#fragment"] {
            XCTAssertNil(VoicePlaybackReporter.request(origin: URL(string: invalid)!, token: "fixture-session", event: event))
        }
    }
    func testSpeechRunWaitsForFinalSealAndDrainsOnlyOnce() {
        let queue = SpeechQueueRegistry()
        let run = UUID()
        let first = NSObject(), second = NSObject()
        queue.begin(run: run)
        XCTAssertTrue(queue.insert(first))
        queue.didStart(first)
        queue.remove(first)
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: run, state: .started)])
        XCTAssertTrue(queue.insert(second))
        queue.didStart(second)
        queue.seal(run: run)
        XCTAssertTrue(queue.drain().isEmpty)
        queue.remove(second)
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: run, state: .stopped)])
        queue.remove(second); queue.seal(run: run); queue.clear()
        XCTAssertTrue(queue.drain().isEmpty)
    }
    func testCancelledOldUtteranceCannotFailNewRun() {
        let queue = SpeechQueueRegistry()
        let oldRun = UUID(), newRun = UUID()
        let old = NSObject(), current = NSObject()
        queue.begin(run: oldRun); XCTAssertTrue(queue.insert(old))
        queue.clear()
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: oldRun, state: .stopped)])
        queue.begin(run: newRun); XCTAssertTrue(queue.insert(current))
        queue.fail(old); queue.didStart(old); queue.remove(old); queue.seal(run: oldRun)
        XCTAssertTrue(queue.drain().isEmpty)
        queue.didStart(current); queue.fail(current); queue.remove(current)
        XCTAssertEqual(queue.drain(), [SpeechPlaybackEvent(run: newRun, state: .started), SpeechPlaybackEvent(run: newRun, state: .failed)])
        queue.seal(run: newRun)
        XCTAssertTrue(queue.drain().isEmpty)
    }
    func testSpeechMetadataQueueIsBounded() {
        let queue = SpeechQueueRegistry()
        for _ in 0..<100 { queue.begin(run: UUID()); queue.clear() }
        XCTAssertEqual(queue.drain().count, 16)
        XCTAssertTrue(queue.drain().isEmpty)
    }
    func testRecoveryCannotDispatchUsingAnOldOriginBinding() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [NoNetworkProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let origin = URL(string: "https://jarvis.example.com")!
        let api = JarvisAPIClient(baseURL: origin, session: session)
        let binding = await api.binding()
        await api.configure(baseURL: URL(string: "https://home.example.org")!)
        await api.configure(baseURL: origin)
        do {
            let _: RecoveredChatRun = try await api.get("/v1/assistant/requests/00000000-0000-0000-0000-000000000001", token: "fixture-session", expectedBinding: binding)
            XCTFail("Old binding must be refused")
        } catch let error as JarvisAPIError {
            XCTAssertEqual(error, .invalidConfiguration)
        }
        do {
            let _: VoiceReleaseResult = try await api.post("/v1/voice/release",
                body: VoiceReleaseRequest(run_id: UUID()), token: "fixture-session", expectedBinding: binding)
            XCTFail("Old binding must also refuse voice mutation")
        } catch let error as JarvisAPIError {
            XCTAssertEqual(error, .invalidConfiguration)
        }
    }
    func testOnlyMatchingTerminalRecoveryClearsPendingRequest() {
        var pending = PendingChatRequests()
        let request = UUID()
        XCTAssertTrue(pending.insert(request, optimisticID: "row"))
        pending.reconcile(request, result: RecoveredChatRun(request_id: UUID(), run_id: UUID(), conversation_id: UUID(), state: "completed"))
        pending.reconcile(request, result: RecoveredChatRun(request_id: request, run_id: UUID(), conversation_id: UUID(), state: "running"))
        XCTAssertEqual(pending[request], "row")
        pending.reconcile(request, result: RecoveredChatRun(request_id: request, run_id: UUID(), conversation_id: UUID(), state: "interrupted"))
        XCTAssertNil(pending[request])
    }
    func testPendingRequestsAreBoundedAndClearedAcrossSessions() {
        var pending = PendingChatRequests()
        let first = UUID()
        XCTAssertTrue(pending.insert(first, optimisticID: "first"))
        for _ in 0..<31 { XCTAssertTrue(pending.insert(UUID(), optimisticID: "fixture")) }
        XCTAssertTrue(pending.isFull)
        XCTAssertFalse(pending.insert(UUID(), optimisticID: "overflow"))
        XCTAssertFalse(pending.insert(first, optimisticID: "replacement"))
        XCTAssertEqual(pending[first], "first")
        pending.removeValue(forKey: first)
        XCTAssertFalse(pending.isFull)
        XCTAssertNil(pending[first])
        pending.clear()
        XCTAssertFalse(pending.isFull)
    }
    func testConversationRecoveryPreservesActiveGenerationAndAcceptsLegacyShape() throws {
        let legacy = Data(#"{"id":"00000000-0000-0000-0000-000000000001","title":"Fixture","messages":[]}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(ConversationResponse.self, from: legacy).assistantRunning)
        let active = Data(#"{"id":"00000000-0000-0000-0000-000000000001","title":"Fixture","messages":[],"assistant_running":true}"#.utf8)
        XCTAssertEqual(try JSONDecoder().decode(ConversationResponse.self, from: active).assistantRunning, true)
    }
    func testStoppedUtteranceCallbackCannotFreeANewerQueueSlot() {
        let queue = SpeechQueueRegistry()
        let old = NSObject()
        XCTAssertTrue(queue.insert(old))
        queue.clear()
        let current = (0..<32).map { _ in NSObject() }
        for utterance in current { XCTAssertTrue(queue.insert(utterance)) }
        queue.remove(old)
        XCTAssertFalse(queue.insert(NSObject()))
        queue.remove(current[0])
        queue.remove(current[0])
        let next = NSObject()
        XCTAssertTrue(queue.insert(next))
        XCTAssertFalse(queue.insert(NSObject()))
    }
    @MainActor
    func testFragmentedFencesNeverSpeakEmbeddedCode() throws {
        final class FakeSpeech: SpeechOutput {
            var spoken: [String] = []
            func speak(_ text: String) { spoken.append(text) }
            func stop() {}
        }
        let id = "00000000-0000-0000-0000-000000000001"
        let identity = ["run_id":id,"request_id":id,"conversation_id":id]
        func event(_ type: String, _ payload: [String: Any]) throws -> RealtimeEvent {
            let bytes = try JSONSerialization.data(withJSONObject: ["protocol":1,"epoch":id,"sequence":1,"event_id":id,"type":type,"payload":payload])
            return try JSONDecoder().decode(RealtimeEvent.self, from: bytes)
        }
        func render(_ text: String, streaming: Bool) throws -> [String] {
            let out = FakeSpeech(); let voice = RealtimeSpeech(output: out); voice.enabled = true
            voice.receive(try event("connection.ready", ["device_id":id]))
            voice.receive(try event("voice.owner_changed", ["device_id":id,"run_id":id]))
            voice.receive(try event("assistant.started", identity))
            if streaming { for ch in text { voice.receive(try event("assistant.delta", ["run":identity,"text":String(ch)])) } }
            let completed = try event("assistant.completed", ["run":identity,"message":["id":id,"conversation_id":id,"role":"assistant","content":text,"created_at":"2026-01-01T00:00:00Z"]])
            voice.receive(completed); voice.receive(completed)
            return out.spoken
        }
        for text in [
            "Before.\n   ```rust\nlet s = \"```\";\nnot speech\n   ```\nAfter.",
            "Before.\n  ~~~~text\ncode\n~~~\nstill code\n  ~~~~\nAfter.",
            "Before.\n```\nunterminated code",
        ] {
            let spoken = try render(text, streaming: true)
            XCTAssertEqual(spoken, try render(text, streaming: false))
            XCTAssertEqual(spoken.first, "Before.")
            XCTAssertTrue(spoken.allSatisfy { $0 == "Before." || $0 == "After." })
        }
    }
    @MainActor
    func testRealtimeVoiceOnlySpeaksOnOwnerAndDoesNotRepeatFinal() throws {
        final class FakeSpeech: SpeechOutput {
            var spoken: [String] = []
            func speak(_ text: String) { spoken.append(text) }
            func stop() {}
        }
        let a = "00000000-0000-0000-0000-000000000001"
        let b = "00000000-0000-0000-0000-000000000002"
        let run = "00000000-0000-0000-0000-000000000003"
        func event(_ type: String, _ payload: [String: Any]) throws -> RealtimeEvent {
            let bytes = try JSONSerialization.data(withJSONObject: ["protocol":1,"epoch":a,"sequence":1,"event_id":a,"type":type,"payload":payload])
            return try JSONDecoder().decode(RealtimeEvent.self, from: bytes)
        }
        let outA = FakeSpeech(); let outB = FakeSpeech()
        let voiceA = RealtimeSpeech(output:outA); let voiceB = RealtimeSpeech(output:outB)
        voiceA.enabled = true; voiceB.enabled = true
        voiceA.receive(try event("connection.ready",["device_id":a]))
        voiceB.receive(try event("connection.ready",["device_id":b]))
        let identity = ["run_id":run,"request_id":a,"conversation_id":b]
        let events = [
            try event("voice.owner_changed",["device_id":a,"run_id":run]),
            try event("assistant.started",identity),
            try event("assistant.delta",["run":identity,"text":"One answer. Next"]),
            try event("assistant.completed",["run":identity,"message":["id":a,"conversation_id":b,"role":"assistant","content":"One answer. Next sentence.","created_at":"2026-01-01T00:00:00Z"]]),
        ]
        for event in events { voiceA.receive(event); voiceB.receive(event) }
        voiceA.receive(events.last!)
        XCTAssertEqual(outA.spoken,["One answer.","Next sentence."])
        XCTAssertTrue(outB.spoken.isEmpty)
        XCTAssertEqual(voiceA.ownedRun, UUID(uuidString: run))
        XCTAssertNil(voiceB.ownedRun)
        voiceA.stop()
        XCTAssertTrue(voiceA.enabled)
        voiceA.receive(try event("assistant.delta", ["run":identity,"text":"Late speech. "]))
        XCTAssertEqual(outA.spoken,["One answer.","Next sentence."])
        let release = try JSONSerialization.jsonObject(with: JSONEncoder().encode(VoiceReleaseRequest(run_id: UUID(uuidString: run)!))) as? [String: String]
        XCTAssertEqual(release?.count, 1)
        XCTAssertEqual(release?["run_id"].flatMap(UUID.init(uuidString:)), UUID(uuidString: run))
        // A new connection must not reuse an old voice lease. Even a late
        // started/delta event cannot speak until ownership is explicitly sent.
        voiceA.receive(try event("connection.ready", ["device_id":a]))
        voiceA.receive(try event("assistant.started", identity))
        voiceA.receive(try event("assistant.delta", ["run":identity,"text":"Stale speech. "]))
        XCTAssertEqual(outA.spoken,["One answer.","Next sentence."])
    }
    func testEnrollmentUsesBackendFieldNames() throws {
        let encoded = try JSONEncoder().encode(
            EnrollmentRequest(name: "Gus's iPhone", platform: "ios", publicKey: "ab")
        )
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: String])
        XCTAssertEqual(json["public_key"], "ab")
        XCTAssertNil(json["publicKey"])
        XCTAssertEqual(json["platform"], "ios")
    }

    func testDecodesCurrentPairingContract() throws {
        let data = #"{"request_id":"018f47de-936a-7000-8000-000000000001","nonce":"00","expires_at":1800000000}"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(PairingRequestResponse.self, from: data)
        XCTAssertEqual(decoded.expiresAt, 1_800_000_000)
        XCTAssertEqual(decoded.nonce, "00")
    }

    func testDecodesCurrentConversationContract() throws {
        let data = #"{"id":"018f47de-936a-7000-8000-000000000001","title":"Hello","messages":[{"role":"assistant","content":"Hi","model":null,"at":"2026-08-30T10:00:00Z"}]}"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(ConversationResponse.self, from: data)
        XCTAssertEqual(decoded.messages.first?.content, "Hi")
        XCTAssertTrue(decoded.messages.first?.isAssistant == true)
    }

    func testHexChallengeRejectsMalformedInput() {
        XCTAssertNil(Data(hexEncoded: "0"))
        XCTAssertNil(Data(hexEncoded: "zz"))
        XCTAssertEqual(Data(hexEncoded: "00ff"), Data([0, 255]))
    }
}
