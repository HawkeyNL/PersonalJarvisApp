import XCTest
@testable import Jarvis

final class VoiceInputTests: XCTestCase {
    func testLevelRisesAtOnceAndFallsBackSlowly() {
        XCTAssertEqual(VoiceInputRules.rms([Float]()), 0)
        XCTAssertEqual(VoiceInputRules.rms([0.5, -0.5, 0.5, -0.5] as [Float]), 0.5, accuracy: 1e-6)
        XCTAssertEqual(VoiceInputRules.level(previous: 0, rms: 0.1), 0.4, accuracy: 1e-9)
        XCTAssertEqual(VoiceInputRules.level(previous: 0.4, rms: 0), 0.28, accuracy: 1e-9)
        XCTAssertEqual(VoiceInputRules.level(previous: 0, rms: 2), 1)
    }

    func testStopsAfterFiveSecondsOfQuiet() {
        let spoke = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertFalse(VoiceInputRules.isQuiet(since: spoke, now: spoke.addingTimeInterval(4.9)))
        XCTAssertTrue(VoiceInputRules.isQuiet(since: spoke, now: spoke.addingTimeInterval(5)))
    }

    func testHeardWordsJoinTheDraft() {
        XCTAssertEqual(VoiceInputRules.appending(" hallo Jarvis ", to: ""), "hallo Jarvis")
        XCTAssertEqual(VoiceInputRules.appending("hoe laat is het", to: "Hoi"), "Hoi hoe laat is het")
        XCTAssertEqual(VoiceInputRules.appending("tweede regel", to: "Eerste\n"), "Eerste\ntweede regel")
        XCTAssertEqual(VoiceInputRules.appending("  \n", to: "Hoi"), "Hoi")
        let long = String(repeating: "a", count: VoiceInputRules.maxTranscript + 10)
        XCTAssertEqual(VoiceInputRules.appending(long, to: "").count, VoiceInputRules.maxTranscript)
    }
}

/// VoiceInput over a fake microphone: words arrive once, and every discard is final.
@MainActor
final class VoiceInputSessionTests: XCTestCase {
    private var capture: FakeCapture!
    private var voice: VoiceInput!
    private var delivered: [String] = []

    override func setUp() async throws {
        capture = FakeCapture()
        voice = VoiceInput(capture: capture, defaults: try XCTUnwrap(UserDefaults(suiteName: "VoiceInputSessionTests")))
        delivered = []
    }

    private func listen(_ allowed: @escaping () -> Bool = { true }) async {
        await voice.start(while: allowed) { [weak self] said in self?.delivered.append(said) }
    }

    /// Lets the main-actor hops from the capture callbacks run.
    private func settle() async throws { try await Task.sleep(nanoseconds: 50_000_000) }

    func testWordsArriveOnceAfterTheMicStops() async throws {
        await listen()
        XCTAssertTrue(voice.listening)
        capture.say("hallo", final: false)
        try await settle()
        voice.stop()
        XCTAssertEqual(capture.ended, 1)
        XCTAssertTrue(voice.finishing)
        XCTAssertGreaterThanOrEqual(capture.released, 1)
        capture.say("hallo Jarvis", final: true)
        capture.say("late", final: true)
        try await settle()
        XCTAssertEqual(delivered, ["hallo Jarvis"])
        XCTAssertFalse(voice.listening || voice.finishing)
    }

    func testADiscardDropsWhatWasHeardAndEveryLateResult() async throws {
        await listen()
        capture.say("geheim", final: false)
        try await settle()
        voice.stop(discard: true)
        capture.say("geheim bericht", final: true)
        try await settle()
        XCTAssertEqual(delivered, [])
        XCTAssertGreaterThanOrEqual(capture.cancelled, 1)
        XCTAssertFalse(voice.listening || voice.finishing)
    }

    func testAllowedIsCheckedAgainAfterThePrompts() async {
        var asked = 0
        await listen { asked += 1; return asked == 1 }
        XCTAssertEqual(asked, 2)
        XCTAssertEqual(capture.started, 0)
        // A lock (discard) while the prompts show ends the attempt too.
        capture.onAuthorize = { [weak self] in self?.voice.stop(discard: true) }
        await listen()
        XCTAssertEqual(capture.started, 0)
    }

    func testRefusalsAndUnavailabilitySaySo() async {
        capture.refusal = VoiceInputRules.microphoneOff
        await listen()
        XCTAssertEqual(voice.problem, VoiceInputRules.microphoneOff)
        capture.refusal = nil
        capture.isAvailable = false
        await listen()
        XCTAssertEqual(voice.problem, VoiceInputRules.unavailable)
        XCTAssertEqual(capture.started, 0)
        capture.availabilityChanged?(false)
        XCTAssertFalse(voice.available)
    }

    func testServerRecognitionIsAnnouncedOnce() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "VoiceInputServerNotice"))
        defaults.removePersistentDomain(forName: "VoiceInputServerNotice")
        capture.recognizesOnDevice = false
        let voice = VoiceInput(capture: capture, defaults: defaults)
        XCTAssertTrue(voice.needsServerNotice)
        voice.acknowledgeServerNotice()
        XCTAssertFalse(VoiceInput(capture: capture, defaults: defaults).needsServerNotice)
        capture.recognizesOnDevice = true
        defaults.removePersistentDomain(forName: "VoiceInputServerNotice")
        XCTAssertFalse(VoiceInput(capture: capture, defaults: defaults).needsServerNotice)
    }

    func testTheLockAConversationChangeAndAReplyStopTheMic() async throws {
        let api = JarvisAPIClient(baseURL: URL(string: "https://jarvis.example.com")!)
        let model = JarvisAppModel(signedInChat: ChatService(api: api, tokenLoader: { "fixture-session-not-a-secret" }),
                                   api: api, realtime: false, voice: voice)
        await listen()
        model.newConversation()
        capture.say("voor het vorige gesprek", final: true)
        try await settle()
        XCTAssertFalse(voice.listening)
        XCTAssertEqual(delivered, [])

        await listen()
        XCTAssertTrue(voice.listening)
        model.lockWhenBackgrounded()
        capture.say("na het slot", final: true)
        try await settle()
        XCTAssertFalse(voice.listening)
        XCTAssertEqual(delivered, [])
    }
}

@MainActor
private final class FakeCapture: VoiceCapture {
    var isAvailable = true
    var recognizesOnDevice = true
    var availabilityChanged: ((Bool) -> Void)?
    var refusal: String?
    var onAuthorize: (() -> Void)?
    private(set) var started = 0
    private(set) var ended = 0
    private(set) var cancelled = 0
    private(set) var released = 0
    private var heard: (@Sendable (String?, Bool) -> Void)?

    func say(_ text: String, final: Bool) { heard?(text, final) }

    func authorize() async -> String? {
        onAuthorize?()
        return refusal
    }

    func start(level: @escaping @Sendable (Double) -> Void, heard: @escaping @Sendable (String?, Bool) -> Void,
               interrupted: @escaping @Sendable () -> Void) throws {
        started += 1
        self.heard = heard
    }

    func endAudio() { ended += 1 }
    func cancel() { cancelled += 1 }
    func releaseAudio() { released += 1 }
}

