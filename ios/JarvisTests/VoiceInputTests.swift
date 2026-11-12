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
