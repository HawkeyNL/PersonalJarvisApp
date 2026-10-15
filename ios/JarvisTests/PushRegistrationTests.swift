import XCTest
@testable import Jarvis

final class PushRegistrationTests: XCTestCase {
    /// A provisioning profile is a CMS envelope around an XML property list.
    private func profile(_ entitlements: String) -> Data {
        var data = Data([0x30, 0x82, 0x1f, 0x00, 0x06, 0x09])
        data.append(Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict><key>Name</key><string>fixture</string>
        <key>Entitlements</key><dict>\(entitlements)</dict></dict></plist>
        """.utf8))
        data.append(Data([0xa0, 0x82, 0x0d, 0x00]))
        return data
    }

    func testEnvironmentComesFromTheSigningProfile() {
        XCTAssertEqual(PushEnvironment.fromProfile(profile("<key>aps-environment</key><string>development</string>")), "sandbox")
        XCTAssertEqual(PushEnvironment.fromProfile(profile("<key>aps-environment</key><string>production</string>")), "production")
        XCTAssertNil(PushEnvironment.fromProfile(profile("<key>get-task-allow</key><true/>")))
        XCTAssertNil(PushEnvironment.fromProfile(profile("<key>aps-environment</key><string>other</string>")))
        XCTAssertNil(PushEnvironment.fromProfile(Data("not a profile".utf8)))
        XCTAssertNil(PushEnvironment.current(bundle: Bundle(for: Self.self)))
    }

    func testTokenIsLowercaseHexOfASaneLength() {
        XCTAssertEqual(apnsTokenHex(Data(repeating: 0xAB, count: 32)), String(repeating: "ab", count: 32))
        XCTAssertEqual(apnsTokenHex(Data(repeating: 0x01, count: 100))?.count, 200)
        XCTAssertNil(apnsTokenHex(Data(repeating: 0xAB, count: 31)))
        XCTAssertNil(apnsTokenHex(Data(repeating: 0xAB, count: 101)))
    }

    func testTappedPushOpensOnlyApprovalLinks() {
        XCTAssertEqual(approvalPushLink(["link": "jarvis://approvals/unlock"]), URL(string: "jarvis://approvals/unlock"))
        XCTAssertEqual(approvalPushLink(["link": "jarvis://approvals/pairing"]), URL(string: "jarvis://approvals/pairing"))
        for other: [AnyHashable: Any] in [[:], ["link": "jarvis://health/usage"], ["link": "https://example.com"],
                                          ["link": "jarvis://approvals/unlock?approve=1"], ["link": 1]] {
            XCTAssertNil(approvalPushLink(other))
        }
    }
}
