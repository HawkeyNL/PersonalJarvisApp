import Foundation

/// Native, memory-only lease. Lock invalidation is synchronous, including while
/// AuthService is suspended in the OS authentication dialog.
final class ModelAuthorization: @unchecked Sendable {
    private let lock = NSLock()
    private var generation = UUID()
    private var grant: (context: String, uptime: TimeInterval, wall: TimeInterval)?

    func ticket() -> UUID { lock.withLock { generation } }
    func accepts(_ ticket: UUID) -> Bool { lock.withLock { generation == ticket } }
    func invalidate() { lock.withLock { generation = UUID(); grant = nil } }

    func valid(_ context: String, ticket: UUID,
               uptime: TimeInterval = ProcessInfo.processInfo.systemUptime,
               wall: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        lock.withLock {
            guard generation == ticket, let grant, grant.context == context else { return false }
            return (0..<300).contains(uptime - grant.uptime) && (0..<300).contains(wall - grant.wall)
        }
    }

    @discardableResult
    func remember(_ context: String, ticket: UUID,
                  uptime: TimeInterval = ProcessInfo.processInfo.systemUptime,
                  wall: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        lock.withLock {
            guard generation == ticket else { return false }
            grant = (context, uptime, wall)
            return true
        }
    }
}
