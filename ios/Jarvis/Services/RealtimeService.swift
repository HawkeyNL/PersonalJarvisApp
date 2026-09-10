import Foundation
import AVFoundation

// Wire semantics are authoritative in PersonalJarvis/crates/client-core.
struct RealtimeRun: Codable { let run_id: UUID; let request_id: UUID; let conversation_id: UUID }
struct RealtimeMessage: Decodable {
    let id: UUID; let conversation_id: UUID; let role: String
    let content: String; let model: String?; let created_at: String
    var conversationMessage: ConversationMessage {
        ConversationMessage(role: role, content: content, model: model, at: created_at, canonicalId: id.uuidString.lowercased())
    }
}
struct RealtimePayload: Decodable {
    let device_id: UUID?; let run_id: UUID?; let request_id: UUID?
    let conversation_id: UUID?; let id: UUID?; let title: String?; let updated_at: String?
    let run: RealtimeRun?; let text: String?; let message: RealtimeMessage?; let reason: String?
}
struct RealtimeEvent: Decodable {
    let `protocol`: Int; let epoch: UUID; let sequence: UInt64; let event_id: UUID
    let type: String; let payload: RealtimePayload
}
struct RealtimeCapability: Decodable { let `protocol`: Int; let asynchronous_chat: Bool }
struct AsyncChatRequest: Encodable { let request_id: UUID; let conversation_id: UUID?; let messages: [ChatTurn] }

/// ID-only correlation state; uncertainty is never evicted to permit more work.
struct PendingChatRequests {
    private var entries: [UUID: String] = [:]
    var isFull: Bool { entries.count >= 32 }
    var requests: [UUID] { Array(entries.keys) }
    subscript(id: UUID) -> String? { entries[id] }
    mutating func insert(_ id: UUID, optimisticID: String) -> Bool {
        guard !isFull, entries[id] == nil else { return false }
        entries[id] = optimisticID; return true
    }
    mutating func removeValue(forKey id: UUID) { entries.removeValue(forKey: id) }
    mutating func clear() { entries.removeAll() }
    mutating func reconcile(_ request: UUID, result: RecoveredChatRun) {
        if request == result.request_id && ["completed", "failed", "interrupted"].contains(result.state) { entries.removeValue(forKey: request) }
    }
}
struct RecoveredChatRun: Decodable, Sendable {
    let request_id: UUID; let run_id: UUID; let conversation_id: UUID; let state: String
}
struct VoiceReleaseRequest: Encodable { let run_id: UUID }
struct VoiceReleaseResult: Decodable {}
struct VoicePlaybackRequest: Encodable {
    let run_id: UUID
    let state: SpeechPlaybackState
}

// Reports need no response body. Cancel at headers, including error responses,
// so an untrusted endpoint cannot fill memory with a telemetry response body.
private final class PlaybackResponseDelegate: NSObject, URLSessionDataDelegate {
    let completed: @Sendable () -> Void
    init(completed: @escaping @Sendable () -> Void) { self.completed = completed }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        completionHandler(.cancel)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        completed() // Never forward transport errors, headers or body text.
    }
}

@MainActor
final class VoicePlaybackReporter {
    private let origin: URL
    private let token: String
    private var session: URLSession?
    private var active: URLSessionDataTask?
    private var pending: [SpeechPlaybackEvent] = []
    private var stopped = false
    init(origin: URL, token: String) {
        self.origin = origin; self.token = token
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil; config.urlCache = nil
        config.timeoutIntervalForRequest = 5; config.timeoutIntervalForResource = 5
        let delegate = PlaybackResponseDelegate { [weak self] in
            Task { @MainActor [weak self] in self?.completed() }
        }
        session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }
    static func request(origin: URL, token: String, event: SpeechPlaybackEvent) -> URLRequest? {
        guard let c = URLComponents(url: origin, resolvingAgainstBaseURL: false),
              c.scheme == "https", c.host != nil, c.user == nil, c.password == nil,
              c.query == nil, c.fragment == nil, c.path.isEmpty || c.path == "/" else { return nil }
        var request = URLRequest(url: origin.appendingPathComponent("v1/voice/playback"))
        request.httpMethod = "POST"; request.timeoutInterval = 5
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(VoicePlaybackRequest(run_id: event.run, state: event.state))
        return request
    }
    func enqueue(_ event: SpeechPlaybackEvent) {
        guard !stopped, pending.count < 16 else { return }
        pending.append(event); pump()
    }
    func stop() {
        stopped = true; pending.removeAll(); active?.cancel(); active = nil
        session?.invalidateAndCancel(); session = nil
    }
    private func pump() {
        guard !stopped, active == nil, !pending.isEmpty else { return }
        let event = pending.removeFirst()
        guard let request = Self.request(origin: origin, token: token, event: event) else { stop(); return }
        active = session?.dataTask(with: request); active?.resume()
    }
    private func completed() {
        guard !stopped else { return }
        active = nil; pump()
    }
}

private final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class RealtimeService {
    private var worker: Task<Void, Never>?
    private var socket: URLSessionWebSocketTask?
    private var generation = UUID()
    private var playbackReporter: VoicePlaybackReporter?
    private weak var speech: RealtimeSpeech?
    private let session: URLSession
    init() {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.urlCredentialStorage = nil; config.urlCache = nil
        config.timeoutIntervalForRequest = 20
        session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    func stop() {
        generation = UUID(); playbackReporter?.stop(); playbackReporter = nil
        speech?.setPlaybackHandler(nil); speech = nil
        worker?.cancel(); worker = nil; socket?.cancel(with: .goingAway, reason: nil); socket = nil
    }
    func start(origin: URL, auth: AuthService, speech: RealtimeSpeech, receive: @escaping @MainActor (RealtimeEvent) async -> Void) {
        stop()
        self.speech = speech
        let current = generation
        worker = Task { [weak self] in
            var retry = 0
            while !Task.isCancelled {
                guard let self else { return }
                do {
                    guard let token = try await auth.sessionToken() else { return }
                    // The auth actor may yield while the owner changes origin
                    // or logs out. Never construct a handshake for a cancelled
                    // connection generation after that suspension point.
                    guard !Task.isCancelled, self.generation == current else { return }
                    let reports = VoicePlaybackReporter(origin: origin, token: token)
                    self.playbackReporter = reports
                    speech.setPlaybackHandler { event in reports.enqueue(event) }
                    defer {
                        reports.stop()
                        if self.generation == current { speech.setPlaybackHandler(nil); self.playbackReporter = nil }
                    }
                    var components = URLComponents(url: origin, resolvingAgainstBaseURL: false)!
                    guard components.scheme == "https" else { return }
                    components.scheme = "wss"; components.path = "/v1/events"; components.query = nil
                    var request = URLRequest(url: components.url!)
                    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                    let socket = self.session.webSocketTask(with: request)
                    defer { socket.cancel(with: .goingAway, reason: nil) }
                    socket.maximumMessageSize = 256 * 1024
                    self.socket = socket; socket.resume()
                    var epoch: UUID?; var sequence: UInt64 = 0
                    let connectedAt = Date()
                    while !Task.isCancelled {
                        let message = try await socket.receive()
                        let data: Data
                        switch message {
                        case let .string(text): data = Data(text.utf8)
                        case let .data(bytes): data = bytes
                        @unknown default: throw JarvisAPIError.invalidResponse
                        }
                        guard data.count <= 256 * 1024 else { throw JarvisAPIError.invalidResponse }
                        let event = try JSONDecoder().decode(RealtimeEvent.self, from: data)
                        guard event.protocol == 1 else { throw JarvisAPIError.invalidResponse }
                        if epoch != event.epoch {
                            guard event.type == "connection.ready" else { throw JarvisAPIError.invalidResponse }
                            epoch = event.epoch; sequence = 0
                        }
                        guard event.sequence > sequence else { continue }
                        guard !Task.isCancelled, self.generation == current else { return }
                        sequence = event.sequence
                        await receive(event)
                        if Date().timeIntervalSince(connectedAt) > 30 { retry = 0 }
                    }
                } catch { /* Fixed reconnect policy; never log request/token or private content. */ }
                if Task.isCancelled { return }
                retry = min(retry + 1, 6)
                try? await Task.sleep(nanoseconds: UInt64(min(30_000, 500 << retry) + Int.random(in: 0...750)) * 1_000_000)
            }
        }
    }
}

@MainActor
protocol SpeechOutput: AnyObject {
    func speak(_ text: String)
    func stop()
    func begin(run: UUID)
    func seal(run: UUID)
    func setPlaybackHandler(_ handler: ((SpeechPlaybackEvent) -> Void)?)
}
extension SpeechOutput {
    func begin(run: UUID) {}
    func seal(run: UUID) {}
    func setPlaybackHandler(_ handler: ((SpeechPlaybackEvent) -> Void)?) {}
}

enum SpeechPlaybackState: String, Codable, Sendable { case started, stopped, failed }
struct SpeechPlaybackEvent: Equatable, Sendable {
    let run: UUID
    let state: SpeechPlaybackState
}

/// All mutable state is private and accessed under one lock. No speech text
/// crosses this callback boundary; old callbacks remove only their own identity.
final class SpeechQueueRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var pending = Set<ObjectIdentifier>()
    private var run: UUID?
    private var started = false
    private var sealed = false
    private var events: [SpeechPlaybackEvent] = []
    private func emit(_ state: SpeechPlaybackState) {
        guard let run else { return }
        // Metadata only. Slow consumers cannot grow memory without bound.
        if events.count == 16 { events.removeFirst() }
        events.append(SpeechPlaybackEvent(run: run, state: state))
    }
    func begin(run: UUID) {
        lock.lock(); defer { lock.unlock() }
        emit(.stopped)
        pending.removeAll(); self.run = run; started = false; sealed = false
    }
    func seal(run: UUID) {
        lock.lock(); defer { lock.unlock() }
        guard self.run == run else { return }
        sealed = true
        if pending.isEmpty { emit(.stopped); self.run = nil }
    }
    func didStart(_ utterance: AnyObject) {
        lock.lock(); defer { lock.unlock() }
        guard pending.contains(ObjectIdentifier(utterance)), !started else { return }
        started = true; emit(.started)
    }
    func drain() -> [SpeechPlaybackEvent] {
        lock.lock(); defer { lock.unlock() }
        let result = events; events.removeAll(); return result
    }
    func fail(_ utterance: AnyObject? = nil) {
        lock.lock(); defer { lock.unlock() }
        if let utterance, !pending.contains(ObjectIdentifier(utterance)) { return }
        emit(.failed); run = nil; pending.removeAll()
    }
    func insert(_ utterance: AnyObject) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let id = ObjectIdentifier(utterance)
        guard pending.count < 32, !pending.contains(id) else { return false }
        pending.insert(id); return true
    }
    func remove(_ utterance: AnyObject) {
        lock.lock(); defer { lock.unlock() }
        guard pending.remove(ObjectIdentifier(utterance)) != nil else { return }
        if sealed && pending.isEmpty { emit(.stopped); run = nil }
    }
    func clear() {
        lock.lock(); defer { lock.unlock() }
        emit(.stopped); run = nil; pending.removeAll()
    }
}

@MainActor
final class NativeSpeechOutput: NSObject, SpeechOutput, AVSpeechSynthesizerDelegate {
    private let engine = AVSpeechSynthesizer()
    nonisolated private let queued = SpeechQueueRegistry()
    var onPlayback: ((SpeechPlaybackEvent) -> Void)?
    private var suppressed = true
    override init() { super.init(); engine.delegate = self }
    func setPlaybackHandler(_ handler: ((SpeechPlaybackEvent) -> Void)?) {
        stop() // Drain old-run callbacks through the old session, never the new one.
        onPlayback = handler
    }
    func speak(_ text: String) {
        guard !suppressed else { return }
        let utterance = AVSpeechUtterance(string: text)
        guard queued.insert(utterance) else {
            queued.fail(); suppressed = true; engine.stopSpeaking(at: .immediate); drain(); return
        }
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        engine.speak(utterance)
    }
    func begin(run: UUID) { queued.begin(run: run); suppressed = false; drain() }
    func seal(run: UUID) { queued.seal(run: run); drain() }
    func stop() { suppressed = true; queued.clear(); engine.stopSpeaking(at: .immediate); drain() }
    private func drain() {
        for event in queued.drain() {
            if event.state == .failed { suppressed = true; engine.stopSpeaking(at: .immediate) }
            onPlayback?(event)
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        queued.didStart(utterance)
        Task { @MainActor [weak self] in self?.drain() }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        queued.remove(utterance)
        Task { @MainActor [weak self] in self?.drain() }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        queued.fail(utterance)
        Task { @MainActor [weak self] in self?.drain() }
    }
}

// No model/HTTP calls. Code fences are omitted deterministically. A completed
// answer only flushes the suffix not already received through streaming.
@MainActor
final class RealtimeSpeech {
    private let output: SpeechOutput
    private var device: UUID?; private var owner: UUID?; private var ownerRun: UUID?
    private var run: UUID?; private var received = ""; private var pending = ""; private var fence: String?; private var lineStart = true
    var enabled = false { didSet { if !enabled { stop() } } }
    var ownedRun: UUID? { device != nil && owner == device ? ownerRun : nil }
    init(output: SpeechOutput) { self.output = output }
    convenience init() { self.init(output: NativeSpeechOutput()) }
    func setPlaybackHandler(_ handler: ((SpeechPlaybackEvent) -> Void)?) {
        stop(); output.setPlaybackHandler(handler)
    }
    func stop() { output.stop(); run = nil; received = ""; pending = ""; fence = nil; lineStart = true }
    func receive(_ event: RealtimeEvent) {
        if event.type == "connection.ready" { device = event.payload.device_id; owner = nil; ownerRun = nil; stop(); return }
        if event.type == "voice.owner_changed" { owner = event.payload.device_id; ownerRun = event.payload.run_id; stop(); return }
        guard enabled, owner == device, device != nil else { return }
        if event.type == "assistant.started", event.payload.run_id == ownerRun {
            stop(); run = event.payload.run_id
            if let run { output.begin(run: run) }
            return
        }
        guard let identity = event.payload.run, identity.run_id == run else { return }
        if event.type == "assistant.failed" { stop(); return }
        if event.type == "assistant.delta", let text = event.payload.text {
            guard received.utf8.count + text.utf8.count <= 128 * 1024 else { stop(); return }
            received += text; pending += text; flush(complete: false)
        }
        if event.type == "assistant.completed", let canonical = event.payload.message?.content {
            guard canonical.hasPrefix(received), canonical.utf8.count <= 128 * 1024 else { stop(); return }
            pending += String(canonical.dropFirst(received.count)); flush(complete: true)
            output.seal(run: identity.run_id); run = nil
        }
    }
    private func flush(complete: Bool) {
        while !pending.isEmpty {
            let lineEnd = pending.firstIndex(of: "\n").map { pending.index(after: $0) } ?? (complete ? pending.endIndex : nil)
            let line = String(pending[..<(lineEnd ?? pending.endIndex)])
            let trimmed = String(line.drop(while: { $0 == " " }))
            let marker = trimmed.first.flatMap { "`~".contains($0) ? $0 : nil }
            let count = marker.map { mark in trimmed.prefix(while: { $0 == mark }).count } ?? 0
            let opening = lineStart && line.count - trimmed.count <= 3 && count >= 3
            if fence != nil || opening {
                guard let end = lineEnd else { return }
                if let current = fence {
                    if opening && marker == current.first && count >= current.count && trimmed.dropFirst(count).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { fence = nil }
                } else if let marker { fence = String(repeating: String(marker), count: count) }
                lineStart = line.hasSuffix("\n")
                pending.removeSubrange(..<end)
                continue
            }
            let chars = Array(pending)
            var count: Int?
            for index in chars.indices {
                if chars[index] == "\n" || (".!?".contains(chars[index]) && index + 1 < chars.count && chars[index + 1].isWhitespace)
                    || (index >= 240 && chars[index].isWhitespace) || index >= 480 { count = index + 1; break }
            }
            guard let count = count ?? (complete ? chars.count : nil) else { return }
            let raw = String(chars.prefix(count)); pending = String(chars.dropFirst(count))
            lineStart = raw.hasSuffix("\n")
            let clean = raw.filter { !"`*#_~".contains($0) }.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
            if !clean.isEmpty { output.speak(clean) }
        }
    }
}
