import Combine
import Foundation
import UIKit

@MainActor
final class JarvisAppModel: ObservableObject {
    @Published private(set) var connectionState: ConnectionState = .unconfigured
    @Published private(set) var enrollmentState: EnrollmentState = .notStarted
    @Published private(set) var lockState: AppLockState = .unlocked
    @Published private(set) var conversations: [ConversationSummary] = []
    @Published private(set) var messages: [ConversationMessage] = []
    @Published private(set) var currentConversationId: UUID?
    @Published private(set) var currentConversationTitle = "New conversation"
    @Published private(set) var isSending = false
    @Published var endpointText: String
    @Published var notice: String?
    @Published var voiceRate = SpeechRate.normalize(UserDefaults.standard.object(forKey: "jarvis.voice.rate") as? Double ?? 1) {
        didSet {
            let rate = SpeechRate.normalize(voiceRate)
            speech.setRate(rate)
            UserDefaults.standard.set(rate, forKey: "jarvis.voice.rate")
        }
    }
    @Published var voiceEnabled = UserDefaults.standard.bool(forKey: "jarvis.voice.enabled") {
        didSet {
            speech.enabled = voiceEnabled
            UserDefaults.standard.set(voiceEnabled, forKey: "jarvis.voice.enabled")
            if !voiceEnabled { stopSpeaking() }
        }
    }
    private let realtime = RealtimeService()
    private let speech = RealtimeSpeech()
    private var realtimeAvailable = false
    private var pendingRequests = PendingChatRequests()
    private var voiceReleaseTask: Task<Void, Never>?

    func stopSpeaking() {
        let run = speech.ownedRun
        speech.stop()
        voiceReleaseTask?.cancel()
        guard let run, let origin = endpointStore.endpoint, isAuthenticated, lockState == .unlocked else { return }
        voiceReleaseTask = Task { [weak self] in
            guard let self else { return }
            do { try await chat.releaseVoice(run: run, origin: origin) }
            catch { /* Local stop already succeeded; stale/offline leases expire. */ }
        }
    }

    private let endpointStore: EndpointStore
    private let api: JarvisAPIClient
    private let auth: AuthService
    private let chat: ChatService
    private let biometricLock: BiometricLock

    init() {
        let endpointStore = EndpointStore()
        let api = JarvisAPIClient(baseURL: endpointStore.endpoint)
        let auth = AuthService(api: api)
        self.endpointStore = endpointStore
        self.api = api
        self.auth = auth
        self.chat = ChatService(api: api, auth: auth)
        self.biometricLock = BiometricLock()
        self.endpointText = endpointStore.endpoint?.absoluteString ?? ""
        speech.setRate(voiceRate)
    }

    var isAuthenticated: Bool { enrollmentState == .authenticated }

    func start() async {
        guard let endpoint = endpointStore.endpoint else {
            connectionState = .unconfigured
            return
        }
        await api.configure(baseURL: endpoint)
        guard await checkConnection() else { return }
        do {
            if try await auth.requiresLocalUnlock() {
                lockState = .locked
                await unlock()
            } else {
                await restoreAuthentication()
            }
        } catch {
            enrollmentState = .failed(safeMessage(error))
        }
    }

    @discardableResult
    func saveEndpoint() async -> Bool {
        do {
            let endpoint = try EndpointNormalizer.normalize(endpointText)
            if endpoint != endpointStore.endpoint {
                realtime.stop(); speech.stop(); voiceReleaseTask?.cancel(); pendingRequests.clear()
                try await auth.clearLocalBinding()
                messages = []; conversations = []; currentConversationId = nil
            }
            endpointStore.save(endpoint)
            endpointText = endpoint.absoluteString
            await api.configure(baseURL: endpoint)
            notice = nil
            guard await checkConnection() else { return false }
            if try await auth.requiresLocalUnlock() {
                lockState = .locked
                await unlock()
            } else {
                await restoreAuthentication()
            }
            return true
        } catch {
            connectionState = .unreachable(safeMessage(error))
            return false
        }
    }

    @discardableResult
    func checkConnection() async -> Bool {
        connectionState = .checking
        do {
            try await api.checkReadiness()
            connectionState = .reachable
            return true
        } catch {
            connectionState = .unreachable(safeMessage(error))
            return false
        }
    }

    func requestEnrollment() async {
        enrollmentState = .requesting
        do {
            apply(awaitResult: try await auth.requestEnrollment(deviceName: UIDevice.current.name))
        }
        catch { handle(error) }
    }

    func refreshEnrollment() async {
        enrollmentState = .authenticating
        do {
            let result = try await auth.refreshEnrollment()
            apply(awaitResult: result)
            if result == .authenticated {
                lockState = .locked
                await unlock()
            }
        } catch { handle(error) }
    }

    func restoreAuthentication() async {
        enrollmentState = .authenticating
        do {
            let result = try await auth.restore()
            apply(awaitResult: result)
            if result == .authenticated {
                await loadConversations()
                realtimeAvailable = await chat.realtimeAvailable()
                if realtimeAvailable, let origin = endpointStore.endpoint, lockState == .unlocked {
                    speech.enabled = voiceEnabled
                    realtime.start(origin: origin, auth: auth, speech: speech) { [weak self] event in await self?.receiveRealtime(event) }
                }
            }
        } catch { handle(error) }
    }

    func unlock() async {
        let result = await biometricLock.unlock(reason: "Unlock your Jarvis conversations")
        switch result {
        case .unlocked:
            lockState = .unlocked
            await restoreAuthentication()
        case .cancelled:
            lockState = .locked
        case .denied:
            lockState = .denied
        case let .unavailable(message):
            lockState = .unavailable(message)
        }
    }

    func beginSignIn() async {
        do {
            if try await auth.requiresLocalUnlock() {
                lockState = .locked
                await unlock()
            } else {
                await restoreAuthentication()
            }
        } catch { handle(error) }
    }

    func lockWhenBackgrounded() {
        voiceReleaseTask?.cancel()
        realtime.stop(); speech.stop()
        if isAuthenticated { lockState = .locked }
    }

    func logout() async {
        voiceReleaseTask?.cancel()
        pendingRequests.clear()
        realtime.stop(); speech.stop()
        do {
            apply(awaitResult: try await auth.logout())
            messages = []
            conversations = []
        } catch { handle(error) }
    }

    func resetDevice() async {
        voiceReleaseTask?.cancel()
        pendingRequests.clear()
        realtime.stop(); speech.stop()
        do {
            try await auth.resetDevice()
            enrollmentState = .notStarted
            lockState = .unlocked
            messages = []
            conversations = []
            currentConversationId = nil
            notice = "This device identity was removed locally. Request enrollment to create a new one."
        } catch { handle(error) }
    }

    func loadConversations() async {
        guard isAuthenticated, lockState == .unlocked else { return }
        do {
            conversations = try await chat.conversations()
            if let id = currentConversationId ?? conversations.first?.id {
                await openConversation(id)
            }
        } catch { handle(error) }
    }

    func openConversation(_ id: UUID) async {
        currentConversationId = id
        do {
            let conversation = try await chat.conversation(id: id)
            guard currentConversationId == id else { return }
            currentConversationId = conversation.id
            currentConversationTitle = conversation.title
            messages = conversation.messages
            isSending = conversation.assistantRunning == true
        } catch { handle(error) }
    }

    func newConversation() {
        currentConversationId = nil
        currentConversationTitle = "New conversation"
        messages = []
        isSending = false
    }

    func send(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, isAuthenticated, lockState == .unlocked, !isSending else { return }
        if realtimeAvailable && pendingRequests.isFull {
            notice = "Too many unconfirmed requests. Reconnect before sending more; no automatic regeneration will occur."
            return
        }
        let priorMessages = messages
        let now = ISO8601DateFormatter().string(from: Date())
        messages.append(ConversationMessage(role: "user", content: trimmed, model: nil, at: now))
        isSending = true
        if realtimeAvailable {
            let requestId = UUID()
            guard let optimisticID = messages.last?.id,
                  pendingRequests.insert(requestId, optimisticID: optimisticID) else { isSending = false; return }
            let selected = currentConversationId
            do {
                let run = try await chat.submit(requestId: requestId, text: trimmed, conversationId: selected, history: priorMessages)
                if selected == nil, currentConversationId == nil, pendingRequests[requestId] != nil { currentConversationId = run.conversation_id }
            } catch {
                isSending = false
                notice = "Delivery was not confirmed. Reconnect to check saved history; Jarvis will not automatically generate again."
            }
            return
        }
        defer { isSending = false }
        do {
            let response = try await chat.send(
                text: trimmed,
                conversationId: currentConversationId,
                history: priorMessages
            )
            currentConversationId = response.conversationId
            currentConversationTitle = response.conversationTitle
            if response.newTopic && !priorMessages.isEmpty {
                await openConversation(response.conversationId)
            } else {
                messages.append(ConversationMessage(
                    role: "assistant",
                    content: response.reply,
                    model: response.model,
                    at: ISO8601DateFormatter().string(from: Date())
                ))
            }
            conversations = try await chat.conversations()
        } catch { handle(error) }
    }

    private func apply(awaitResult result: AuthServiceOutcome) {
        switch result {
        case .needsEnrollment: enrollmentState = .notStarted
        case let .awaitingApproval(expiresAt): enrollmentState = .awaitingApproval(expiresAt: expiresAt)
        case .authenticated: enrollmentState = .authenticated
        case .signedOut: enrollmentState = .signedOut
        }
    }

    private func receiveRealtime(_ event: RealtimeEvent) async {
        guard isAuthenticated, lockState == .unlocked else { return }
        speech.receive(event)
        let payload = event.payload
        switch event.type {
        case "connection.ready":
            do {
                let origin = endpointStore.endpoint
                let recovered = try await chat.recover(requests: pendingRequests.requests)
                try Task.checkCancellation()
                guard isAuthenticated, lockState == .unlocked, endpointStore.endpoint == origin else { return }
                for (request, result) in recovered { pendingRequests.reconcile(request, result: result) }
                conversations = try await chat.conversations()
                if let selected = currentConversationId {
                    let snapshot = try await chat.conversation(id: selected)
                    if currentConversationId == selected { messages = snapshot.messages; currentConversationTitle = snapshot.title; isSending = snapshot.assistantRunning == true }
                }
            } catch { notice = "Realtime connected; history reconciliation will retry after reconnect." }
        case "conversation.created", "conversation.updated":
            if let id = payload.id, let title = payload.title, let at = payload.updated_at {
                conversations.removeAll { $0.id == id }
                conversations.append(ConversationSummary(id: id, title: title, updatedAt: at))
                conversations.sort { $0.updatedAt > $1.updatedAt }
            }
        case "conversation.deleted":
            conversations.removeAll { $0.id == payload.conversation_id }
            if currentConversationId == payload.conversation_id { newConversation() }
        case "message.created":
            if let message = payload.message {
                let optimistic = payload.request_id.flatMap { pendingRequests[$0] }
                if currentConversationId == nil, let optimistic, messages.contains(where: { $0.id == optimistic }) { currentConversationId = message.conversation_id }
                if currentConversationId == message.conversation_id {
                    upsertRealtime(message, optimistic: optimistic)
                }
            }
        case "assistant.started":
            if payload.conversation_id == currentConversationId, let run = payload.run_id {
                isSending = true
                if !messages.contains(where: { $0.id == "run:\(run)" }) {
                    messages.append(ConversationMessage(role: "assistant", content: "", model: nil, at: "", canonicalId: "run:\(run)"))
                }
            }
        case "assistant.delta":
            if let run = payload.run, run.conversation_id == currentConversationId, let text = payload.text {
                isSending = true
                if !messages.contains(where: { $0.id == "run:\(run.run_id)" }) {
                    messages.append(ConversationMessage(role: "assistant", content: "", model: nil, at: "", canonicalId: "run:\(run.run_id)"))
                }
                guard let index = messages.firstIndex(where: { $0.id == "run:\(run.run_id)" }) else { return }
                let old = messages[index]
                if old.content.utf8.count + text.utf8.count <= 128 * 1024 {
                    messages[index] = ConversationMessage(role: "assistant", content: old.content + text, model: nil, at: old.at, canonicalId: old.id)
                }
            }
        case "assistant.completed":
            if let run = payload.run, let message = payload.message {
                pendingRequests.removeValue(forKey: run.request_id)
                if currentConversationId == message.conversation_id {
                    upsertRealtime(message, optimistic: "run:\(run.run_id)"); isSending = false
                }
            }
        case "assistant.failed":
            if let run = payload.run {
                pendingRequests.removeValue(forKey: run.request_id)
                if currentConversationId == run.conversation_id { isSending = false; notice = "Response interrupted. Your message is saved." }
            }
        default: break
        }
    }

    private func handle(_ error: Error) {
        if (error as? JarvisAPIError) == .unauthorized {
            enrollmentState = .signedOut
        } else if (error as? JarvisAPIError) == .unreachable ||
                    (error as? JarvisAPIError) == .timedOut {
            connectionState = .unreachable(safeMessage(error))
        } else {
            enrollmentState = .failed(safeMessage(error))
        }
        notice = safeMessage(error)
    }

    private func upsertRealtime(_ message: RealtimeMessage, optimistic: String?) {
        let row = message.conversationMessage
        var inserted = false
        messages = messages.compactMap { existing in
            guard existing.id == row.id || existing.id == optimistic else { return existing }
            if inserted { return nil }
            inserted = true
            return row
        }
        if !inserted { messages.append(row) }
    }

    private func safeMessage(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? "Jarvis could not complete the request."
    }
}
