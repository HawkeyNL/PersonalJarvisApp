import XCTest
@testable import Jarvis

final class ModelAuthorizationTests: XCTestCase {
    func testFixedExpiryBindingAndClockChanges() {
        let lease = ModelAuthorization()
        let ticket = lease.ticket()
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 10, wall: 100))
        XCTAssertTrue(lease.remember("session", ticket: ticket, uptime: 10, wall: 100))
        XCTAssertTrue(lease.valid("session", ticket: ticket, uptime: 309, wall: 399))
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 310, wall: 400))
        XCTAssertFalse(lease.valid("other", ticket: ticket, uptime: 11, wall: 101))
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 11, wall: 99))
        XCTAssertFalse(lease.valid("session", ticket: ticket, uptime: 11, wall: 401))
    }

    func testLockDuringPromptCannotRestoreLease() {
        let lease = ModelAuthorization()
        let ticket = lease.ticket()
        lease.invalidate()
        XCTAssertFalse(lease.accepts(ticket))
        XCTAssertFalse(lease.remember("session", ticket: ticket))
        XCTAssertFalse(lease.valid("session", ticket: lease.ticket()))
    }
}
