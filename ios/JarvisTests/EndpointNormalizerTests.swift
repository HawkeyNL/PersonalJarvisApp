import XCTest
@testable import Jarvis

final class EndpointNormalizerTests: XCTestCase {
    func testAddsHTTPSAndRemovesTrailingSlash() throws {
        XCTAssertEqual(
            try EndpointNormalizer.normalize(" jarvis.local/ ").absoluteString,
            "https://jarvis.local"
        )
    }

    func testPreservesExplicitLANHTTPPortWhenInsecureLocalIsAllowed() throws {
        XCTAssertEqual(
            try EndpointNormalizer.normalize("http://192.168.1.24:8080", allowInsecureLocal: true).absoluteString,
            "http://192.168.1.24:8080"
        )
    }

    func testReleasePolicyRejectsCleartextEvenForLocalHosts() {
        for endpoint in ["http://192.168.1.24:8080", "http://jarvis.local", "http://10.0.0.2", "http://localhost:8080"] {
            XCTAssertThrowsError(try EndpointNormalizer.normalize(endpoint, allowInsecureLocal: false)) { error in
                XCTAssertEqual(error as? EndpointValidationError, .insecureRemote)
            }
        }
        XCTAssertEqual(
            try? EndpointNormalizer.normalize("https://192.168.1.24:8443", allowInsecureLocal: false).absoluteString,
            "https://192.168.1.24:8443"
        )
    }

    func testCleartextIsOnlyAllowedByDefaultInDebugBuilds() {
        #if DEBUG
        XCTAssertTrue(EndpointNormalizer.allowsInsecureLocal)
        #else
        XCTAssertFalse(EndpointNormalizer.allowsInsecureLocal)
        #endif
    }

    func testRejectsCredentialsAndNonHTTPProtocols() {
        XCTAssertThrowsError(try EndpointNormalizer.normalize("https://owner:secret@jarvis.local"))
        XCTAssertThrowsError(try EndpointNormalizer.normalize("file:///tmp/jarvis"))
    }

    func testRejectsAPIPathToPreventDoubleVersionedRoutes() {
        XCTAssertThrowsError(try EndpointNormalizer.normalize("https://jarvis.local/v1"))
    }

    func testRejectsPublicCleartextEndpoint() {
        XCTAssertThrowsError(try EndpointNormalizer.normalize("http://example.com")) { error in
            XCTAssertEqual(error as? EndpointValidationError, .insecureRemote)
        }
    }
}
