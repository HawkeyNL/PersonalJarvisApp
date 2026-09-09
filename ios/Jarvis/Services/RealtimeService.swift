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
    private let session: URLSession
    init() {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.urlCredentialStorage = nil; config.urlCache = nil
        config.timeoutIntervalForRequest = 20
        session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
    }
    func stop() { generation = UUID(); worker?.cancel(); worker = nil; socket?.cancel(with: .goingAway, reason: nil); socket = nil }
    func start(origin: URL, auth: AuthService, receive: @escaping @MainActor (RealtimeEvent) async -> Void) {
        stop()
        let current = generation
        worker = Task { [weak self] in
            var retry = 0
            while !Task.isCancelled {
                guard let self else { return }
                do {
                    guard let token = try await auth.sessionToken() else { return }
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
protocol SpeechOutput: AnyObject { func speak(_ text: String); func stop() }
@MainActor
final class NativeSpeechOutput: NSObject, SpeechOutput, AVSpeechSynthesizerDelegate {
    private let engine = AVSpeechSynthesizer()
    private var queued = 0
    override init() { super.init(); engine.delegate = self }
    func speak(_ text: String) {
        guard queued < 32 else { stop(); return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        queued += 1
        engine.speak(utterance)
    }
    func stop() { engine.stopSpeaking(at: .immediate); queued = 0 }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in self?.queued = max(0, (self?.queued ?? 0) - 1) }
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
    init(output: SpeechOutput) { self.output = output }
    convenience init() { self.init(output: NativeSpeechOutput()) }
    func stop() { output.stop(); run = nil; received = ""; pending = ""; fence = nil; lineStart = true }
    func receive(_ event: RealtimeEvent) {
        if event.type == "connection.ready" { device = event.payload.device_id; stop(); return }
        if event.type == "voice.owner_changed" { owner = event.payload.device_id; ownerRun = event.payload.run_id; stop(); return }
        guard enabled, owner == device, device != nil else { return }
        if event.type == "assistant.started", event.payload.run_id == ownerRun {
            stop(); run = event.payload.run_id; return
        }
        guard let identity = event.payload.run, identity.run_id == run else { return }
        if event.type == "assistant.failed" { stop(); return }
        if event.type == "assistant.delta", let text = event.payload.text {
            guard received.utf8.count + text.utf8.count <= 128 * 1024 else { stop(); return }
            received += text; pending += text; flush(complete: false)
        }
        if event.type == "assistant.completed", let canonical = event.payload.message?.content {
            guard canonical.hasPrefix(received), canonical.utf8.count <= 128 * 1024 else { stop(); return }
            pending += String(canonical.dropFirst(received.count)); flush(complete: true); run = nil
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
