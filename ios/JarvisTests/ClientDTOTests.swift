import XCTest
@testable import Jarvis

final class ClientDTOTests: XCTestCase {
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
