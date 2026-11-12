import Foundation

private final class RejectAPIRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum JarvisAPIError: LocalizedError, Equatable {
    case invalidConfiguration
    case invalidActivationCode
    case invalidAccountPassword
    case unreachable
    case timedOut
    case unauthorized
    case rejected(status: Int, message: String?)
    case invalidResponse
    case responseTooLarge

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration: "The Home Node address is not configured."
        case .invalidActivationCode: "Enter the case-sensitive 12-character activation code from your Home Node (legacy 64-character codes are also supported)."
        case .invalidAccountPassword: "Choose an account password of at least 15 characters and at most 1024 UTF-8 bytes."
        case .unreachable: "The Home Node could not be reached. Check its address and your network."
        case .timedOut: "The Home Node did not respond in time."
        case .unauthorized: "This session is no longer authorized."
        case let .rejected(status, message): message ?? "The Home Node rejected the request (HTTP \(status))."
        case .invalidResponse: "The Home Node returned an unexpected response."
        case .responseTooLarge: "The Home Node response exceeds the safe size limit."
        }
    }
}

enum BoundedAPIResponse {
    static let maximumBytes = 16 * 1024 * 1024

    static func read(session: URLSession, request: URLRequest,
                     limit: Int = maximumBytes, resourceTimeout: TimeInterval? = nil) async throws -> (Data, URLResponse) {
        guard limit >= 0 else { throw JarvisAPIError.responseTooLarge }
        let receiver = BoundedAPIReceiver(limit: limit, resourceTimeout: resourceTimeout)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                receiver.start(session: session, request: request, continuation: continuation)
            }
        } onCancel: {
            receiver.finish(error: CancellationError())
        }
    }
}

// Bound headers when Foundation delivers the response, and each body chunk
// before accumulation. A peer sending headers without body bytes may not trigger
// the response callback; the session resource timeout bounds that case.
// All state is protected by this lock;
// continuation completion and cancellation happen outside the lock.
private final class BoundedAPIReceiver: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let limit: Int
    private let resourceTimeout: TimeInterval?
    private var finished = false
    private var task: URLSessionDataTask?
    private var transport: URLSession?
    private var continuation: CheckedContinuation<(Data, URLResponse), Error>?
    private var response: URLResponse?
    private var data = Data()

    init(limit: Int, resourceTimeout: TimeInterval?) { self.limit = limit; self.resourceTimeout = resourceTimeout }

    func start(session: URLSession, request: URLRequest,
               continuation: CheckedContinuation<(Data, URLResponse), Error>) {
        lock.lock()
        guard !finished else {
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return
        }
        self.continuation = continuation
        // Data/response callbacks belong to the session delegate, not merely
        // the task-specific progress delegate. Preserve all transport settings.
        // `configuration` is a copy: changing it leaves the shared session alone.
        let configuration = session.configuration
        if let resourceTimeout { configuration.timeoutIntervalForResource = resourceTimeout }
        let transport = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.transport = transport
        let task = transport.dataTask(with: request)
        self.task = task
        lock.unlock()
        task.resume()
    }

    func finish(error: Error?) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let result: Result<(Data, URLResponse), Error>
        if let error { result = .failure(error) }
        else if let response { result = .success((data, response)) }
        else { result = .failure(JarvisAPIError.invalidResponse) }
        let continuation = self.continuation
        let task = self.task
        let transport = self.transport
        self.continuation = nil
        self.task = nil
        self.transport = nil
        data = Data()
        lock.unlock()
        task?.cancel()
        transport?.invalidateAndCancel()
        continuation?.resume(with: result)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if response.expectedContentLength > Int64(limit) {
            finish(error: JarvisAPIError.responseTooLarge)
            completionHandler(.cancel)
            return
        }
        lock.lock()
        let active = !finished
        if active { self.response = response }
        lock.unlock()
        completionHandler(active ? .allow : .cancel)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        let oversized = chunk.count > limit - data.count
        if !oversized { data.append(chunk) }
        lock.unlock()
        if oversized { finish(error: JarvisAPIError.responseTooLarge) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error: error)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

actor JarvisAPIClient {
    private var baseURL: URL?
    private var bindingID = UUID()
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(baseURL: URL? = nil, session: URLSession? = nil) {
        self.baseURL = baseURL
        if let session {
            self.session = session
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
            configuration.urlCache = nil
            configuration.urlCredentialStorage = nil
            configuration.httpCookieStorage = nil
            configuration.httpShouldSetCookies = false
            configuration.timeoutIntervalForRequest = 15
            configuration.timeoutIntervalForResource = 30
            self.session = URLSession(configuration: configuration, delegate: RejectAPIRedirects(), delegateQueue: nil)
        }
    }

    func configure(baseURL: URL) { bindingID = UUID(); self.baseURL = baseURL }
    func binding() -> UUID { bindingID }
    func binding(for origin: URL) throws -> UUID {
        guard baseURL == origin else { throw JarvisAPIError.invalidConfiguration }
        return bindingID
    }

    func checkReadiness() async throws {
        _ = try await request(path: "/readyz", method: "GET", response: EmptyOrJSON.self)
    }

    func get<Response: Decodable>(
        _ path: String,
        token: String? = nil,
        headers: [String: String] = [:],
        expectedBinding: UUID? = nil,
        response: Response.Type = Response.self
    ) async throws -> Response {
        try await request(path: path, method: "GET", token: token, headers: headers, expectedBinding: expectedBinding, response: response)
    }

    func post<Body: Encodable, Response: Decodable>(
        _ path: String,
        body: Body,
        token: String? = nil,
        expectedBinding: UUID? = nil,
        response: Response.Type = Response.self
    ) async throws -> Response {
        try await request(path: path, method: "POST", body: body, token: token, expectedBinding: expectedBinding, response: response)
    }

    func activateFirstDevice(_ body: EnrollmentRequest, code: String) async throws -> FirstDeviceResponse {
        guard Self.validActivationCode(code) else { throw JarvisAPIError.invalidActivationCode }
        return try await request(path: "/v1/auth/bootstrap", method: "POST", body: body,
            headers: ["X-Jarvis-Bootstrap-Secret": code], expectedBinding: bindingID, response: FirstDeviceResponse.self)
    }

    static func validActivationCode(_ code: String) -> Bool {
        let bytes = Array(code.utf8)
        let alphabet = Set("ABCDEFGHJKLMNPQRSTUVWXYZ23456789".utf8)
        let hex = Set("0123456789abcdefABCDEF".utf8)
        return (bytes.count == 12 && bytes.allSatisfy { alphabet.contains($0) }) ||
            (bytes.count == 64 && bytes.allSatisfy { hex.contains($0) })
    }

    func post<Response: Decodable>(
        _ path: String,
        token: String? = nil,
        response: Response.Type = Response.self
    ) async throws -> Response {
        try await request(path: path, method: "POST", token: token, response: response)
    }

    /// POST whose response body (empty or any JSON object) is not needed.
    func postDiscardingResponse<Body: Encodable>(
        _ path: String,
        body: Body,
        token: String,
        expectedBinding: UUID
    ) async throws {
        _ = try await request(path: path, method: "POST", body: body, token: token,
                              expectedBinding: expectedBinding, response: EmptyOrJSON.self)
    }

    func put<Body: Encodable>(_ path: String, body: Body, token: String, expectedBinding: UUID) async throws {
        _ = try await request(path: path, method: "PUT", body: body, token: token,
                              expectedBinding: expectedBinding, response: EmptyOrJSON.self)
    }

    // The only bearer-only DELETEs: this device's own APNs push target, and the owner's
    // own chat data (a conversation, a photo). A device is revoked with a signed approval,
    // never with a bearer-only DELETE.
    func deletePushTarget(token: String, expectedBinding: UUID) async throws {
        try await delete("/v1/devices/self/push", token: token, expectedBinding: expectedBinding)
    }

    func deleteConversation(id: UUID, token: String, expectedBinding: UUID) async throws {
        try await delete("/v1/conversations/\(id.uuidString.lowercased())", token: token, expectedBinding: expectedBinding)
    }

    func deletePhoto(id: UUID, token: String, expectedBinding: UUID) async throws {
        try await delete("/v1/photos/\(id.uuidString.lowercased())", token: token, expectedBinding: expectedBinding)
    }

    /// Keep (or stop keeping) a photo past its 90 days.
    func setPhotoSaved(id: UUID, saved: Bool, token: String, expectedBinding: UUID) async throws -> PhotoMeta {
        try await request(path: "/v1/photos/\(id.uuidString.lowercased())", method: "PATCH", body: PhotoSaved(saved: saved),
                          token: token, expectedBinding: expectedBinding, response: PhotoMeta.self)
    }

    /// One chat photo, already shrunk to a JPEG, as raw bytes. The reply is a few fields; a
    /// slow uplink gets two minutes instead of the usual 30 seconds.
    func uploadPhoto(_ jpeg: Data, token: String, expectedBinding: UUID) async throws -> PhotoUpload {
        try await request(path: "/v1/photos", method: "POST", upload: jpeg, token: token,
                          expectedBinding: expectedBinding, limit: 4 * 1024, resourceTimeout: 120, response: PhotoUpload.self)
    }

    // Scheduled jobs (desktop jobsApi.ts). The answers are not needed: the page reloads.
    func createJob(_ body: NewJob, token: String, expectedBinding: UUID) async throws {
        _ = try await request(path: "/v1/jobs", method: "POST", body: body, token: token,
                              expectedBinding: expectedBinding, limit: 64 * 1024, response: EmptyOrJSON.self)
    }

    /// Core answers 409 when the job moved past `revision`.
    func updateJob(id: UUID, revision: Int, patch: JobPatch, token: String, expectedBinding: UUID) async throws {
        _ = try await request(path: "/v1/jobs/\(id.uuidString.lowercased())?expected_revision=\(revision)", method: "PATCH",
                              body: patch, token: token, expectedBinding: expectedBinding, limit: 64 * 1024, response: EmptyOrJSON.self)
    }

    /// 202 when queued; 429 within an hour of the last owner run.
    func runJobNow(id: UUID, token: String, expectedBinding: UUID) async throws {
        _ = try await request(path: "/v1/jobs/\(id.uuidString.lowercased())/run", method: "POST", body: [String: String](),
                              token: token, expectedBinding: expectedBinding, limit: 64 * 1024, response: EmptyOrJSON.self)
    }

    private func delete(_ path: String, token: String, expectedBinding: UUID) async throws {
        _ = try await request(path: path, method: "DELETE", token: token,
                              expectedBinding: expectedBinding, response: EmptyOrJSON.self)
    }

    static let maximumPhotoBytes = 2 * 1024 * 1024

    /// One chat photo: a 200 `image/jpeg` of at most 2 MiB starting FF D8 FF. A deleted or
    /// expired photo is `.rejected(status: 404, …)`.
    func photo(id: UUID, token: String, expectedBinding: UUID) async throws -> Data {
        try Task.checkCancellation()
        guard expectedBinding == bindingID, let baseURL,
              let url = URL(string: "/v1/photos/\(id.uuidString.lowercased())", relativeTo: baseURL)?.absoluteURL else {
            throw JarvisAPIError.invalidConfiguration
        }
        var request = URLRequest(url: url)
        request.setValue("image/jpeg", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        do {
            let (data, rawResponse) = try await BoundedAPIResponse.read(session: session, request: request, limit: Self.maximumPhotoBytes)
            try Task.checkCancellation()
            guard expectedBinding == bindingID else { throw JarvisAPIError.invalidConfiguration }
            guard let http = rawResponse as? HTTPURLResponse else { throw JarvisAPIError.invalidResponse }
            if http.statusCode == 401 { throw JarvisAPIError.unauthorized }
            guard http.statusCode == 200 else { throw JarvisAPIError.rejected(status: http.statusCode, message: nil) }
            guard Self.isJPEG(data, contentType: http.value(forHTTPHeaderField: "Content-Type")) else {
                throw JarvisAPIError.invalidResponse
            }
            return data
        } catch let error as JarvisAPIError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .timedOut {
            throw JarvisAPIError.timedOut
        } catch {
            throw JarvisAPIError.unreachable
        }
    }

    static func isJPEG(_ data: Data, contentType: String?) -> Bool {
        let type = contentType?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased()
        return type == "image/jpeg" && data.count <= maximumPhotoBytes && data.starts(with: [0xFF, 0xD8, 0xFF])
    }

    private func request<Response: Decodable>(
        path: String,
        method: String,
        body: (any Encodable)? = nil,
        upload: Data? = nil,
        token: String? = nil,
        headers: [String: String] = [:],
        expectedBinding: UUID? = nil,
        limit: Int = BoundedAPIResponse.maximumBytes,
        resourceTimeout: TimeInterval? = nil,
        response: Response.Type
    ) async throws -> Response {
        try Task.checkCancellation()
        if let expectedBinding, expectedBinding != bindingID { throw JarvisAPIError.invalidConfiguration }
        guard let baseURL else { throw JarvisAPIError.invalidConfiguration }
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL else {
            throw JarvisAPIError.invalidConfiguration
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = try encoder.encode(AnyEncodable(body))
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if let upload {
            request.httpBody = upload
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        }
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }

        do {
            let (data, rawResponse) = try await BoundedAPIResponse.read(session: session, request: request, limit: limit,
                                                                           resourceTimeout: resourceTimeout)
            try Task.checkCancellation()
            if let expectedBinding, expectedBinding != bindingID { throw JarvisAPIError.invalidConfiguration }
            guard let http = rawResponse as? HTTPURLResponse else { throw JarvisAPIError.invalidResponse }
            guard (200..<300).contains(http.statusCode) else {
                if http.statusCode == 401 { throw JarvisAPIError.unauthorized }
                let body = try? decoder.decode(APIErrorBody.self, from: data)
                if body?.code == "pairing_password_not_set" {
                    throw JarvisAPIError.rejected(status: http.statusCode, message: "Set an account password on an active device first.")
                }
                throw JarvisAPIError.rejected(status: http.statusCode, message: body?.error)
            }
            if Response.self == EmptyOrJSON.self, data.isEmpty {
                return EmptyOrJSON() as! Response
            }
            return try decoder.decode(Response.self, from: data)
        } catch let error as JarvisAPIError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .timedOut {
            throw JarvisAPIError.timedOut
        } catch is DecodingError {
            throw JarvisAPIError.invalidResponse
        } catch {
            throw JarvisAPIError.unreachable
        }
    }
}

private struct EmptyOrJSON: Codable {}

private struct AnyEncodable: Encodable {
    private let encodeValue: (Encoder) throws -> Void
    init(_ value: any Encodable) { encodeValue = { try value.encode(to: $0) } }
    func encode(to encoder: Encoder) throws { try encodeValue(encoder) }
}
