import Foundation

actor ChatService {
    private let api: JarvisAPIClient
    private let loadToken: @Sendable () async throws -> String?

    init(api: JarvisAPIClient, auth: AuthService) {
        self.api = api
        self.loadToken = { try await auth.sessionToken() }
    }

    // Native test seam: no token loader is exposed through the application UI.
    init(api: JarvisAPIClient, tokenLoader: @escaping @Sendable () async throws -> String?) {
        self.api = api
        self.loadToken = tokenLoader
    }

    func conversations() async throws -> [ConversationSummary] {
        let binding = await api.binding()
        let token = try await requiredToken()
        let response: ConversationListResponse = try await api.get("/v1/conversations", token: token, expectedBinding: binding)
        return response.conversations
    }

    func realtimeAvailable() async -> Bool {
        let binding = await api.binding()
        guard let token = try? await requiredToken(), let capability: RealtimeCapability = try? await api.get("/v1/events/capability", token: token, expectedBinding: binding) else { return false }
        return capability.protocol == 1 && capability.asynchronous_chat
    }

    func submit(requestId: UUID, text: String, conversationId: UUID?, history: [ConversationMessage],
                research: Bool = false, attachments: [UUID] = []) async throws -> RealtimeRun {
        let binding = await api.binding()
        let token = try await requiredToken()
        let turns = history.suffix(19).map { ChatTurn(role: $0.isAssistant ? "assistant" : "user", content: $0.content) } + [ChatTurn(role: "user", content: text)]
        let request = AsyncChatRequest(request_id: requestId, conversation_id: conversationId, messages: turns,
                                       mode: research ? "research" : nil, attachments: attachments.isEmpty ? nil : attachments)
        return try await api.post("/v1/assistant/runs", body: request, token: token, expectedBinding: binding)
    }

    func deleteConversation(id: UUID) async throws {
        let binding = await api.binding()
        let token = try await requiredToken()
        try await api.deleteConversation(id: id, token: token, expectedBinding: binding)
    }

    /// The new photo's id, and the Home Node binding it was uploaded under.
    func uploadPhoto(_ jpeg: Data) async throws -> (id: UUID, binding: UUID) {
        let binding = await api.binding()
        let token = try await requiredToken()
        return (try await api.uploadPhoto(jpeg, token: token, expectedBinding: binding).id, binding)
    }

    /// Keep (or stop keeping) a photo past its 90 days.
    func setPhotoSaved(id: UUID, saved: Bool) async throws -> PhotoMeta {
        let binding = await api.binding()
        let token = try await requiredToken()
        return try await api.setPhotoSaved(id: id, saved: saved, token: token, expectedBinding: binding)
    }

    /// With `binding`, the delete is refused once the Home Node changed since the upload.
    func deletePhoto(id: UUID, binding: UUID? = nil) async throws {
        let expected: UUID
        if let binding { expected = binding } else { expected = await api.binding() }
        let token = try await requiredToken()
        try await api.deletePhoto(id: id, token: token, expectedBinding: expected)
    }

    func conversation(id: UUID) async throws -> ConversationResponse {
        let binding = await api.binding()
        let token = try await requiredToken()
        return try await api.get("/v1/conversations/\(id.uuidString)", token: token, expectedBinding: binding)
    }

    func photo(id: UUID) async throws -> Data {
        let binding = await api.binding()
        let token = try await requiredToken()
        return try await api.photo(id: id, token: token, expectedBinding: binding)
    }

    func releaseVoice(run: UUID, origin: URL) async throws {
        let binding = try await api.binding(for: origin)
        let token = try await requiredToken()
        let _: VoiceReleaseResult = try await api.post("/v1/voice/release",
            body: VoiceReleaseRequest(run_id: run), token: token, expectedBinding: binding)
    }

    func recover(requests: [UUID]) async throws -> [(UUID, RecoveredChatRun)] {
        // Capture endpoint generation BEFORE loading credentials. API refuses
        // dispatch if configure() ran in between, including origin A -> B -> A.
        let binding = await api.binding()
        let token = try await requiredToken()
        let api = self.api
        return try await withThrowingTaskGroup(of: (UUID, RecoveredChatRun)?.self) { group in
            for request in requests.prefix(32) {
                group.addTask {
                    do {
                        let run: RecoveredChatRun = try await api.get("/v1/assistant/requests/\(request.uuidString)", token: token, expectedBinding: binding)
                        return (request, run)
                    } catch is CancellationError { throw CancellationError() }
                    catch { return nil }
                }
            }
            var results: [(UUID, RecoveredChatRun)] = []
            for try await result in group { if let result { results.append(result) } }
            return results
        }
    }

    func send(text: String, conversationId: UUID?, history: [ConversationMessage]) async throws -> ChatResponse {
        let binding = await api.binding()
        let token = try await requiredToken()
        let turns = history.suffix(19).map {
            ChatTurn(role: $0.isAssistant ? "assistant" : "user", content: $0.content)
        } + [ChatTurn(role: "user", content: text)]
        return try await api.post(
            "/v1/assistant/chat",
            body: ChatRequest(messages: turns, conversationId: conversationId),
            token: token, expectedBinding: binding
        )
    }

    private func requiredToken() async throws -> String {
        guard let token = try await loadToken() else { throw JarvisAPIError.unauthorized }
        return token
    }
}
