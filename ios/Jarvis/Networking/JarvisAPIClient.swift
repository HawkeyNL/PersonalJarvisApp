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
    case unreachable
    case timedOut
    case unauthorized
    case rejected(status: Int, message: String?)
    case invalidResponse
    case responseTooLarge

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration: "The Home Node address is not configured."
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
                     limit: Int = maximumBytes) async throws -> (Data, URLResponse) {
        guard limit >= 0 else { throw JarvisAPIError.responseTooLarge }
        let receiver = BoundedAPIReceiver(limit: limit)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                receiver.start(session: session, request: request, continuation: continuation)
            }
        } onCancel: {
            receiver.finish(error: CancellationError())
        }
    }
}

// URLSession's AsyncBytes may wait for body data before returning its response.
// Use header/data callbacks so an oversized advertised body is refused even
// when the peer sends no body at all. All state is protected by this lock;
// continuation completion and cancellation happen outside the lock.
private final class BoundedAPIReceiver: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let limit: Int
    private var finished = false
    private var task: URLSessionDataTask?
    private var transport: URLSession?
    private var continuation: CheckedContinuation<(Data, URLResponse), Error>?
    private var response: URLResponse?
    private var data = Data()

    init(limit: Int) { self.limit = limit }

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
        let transport = URLSession(configuration: session.configuration, delegate: self, delegateQueue: nil)
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

    func post<Response: Decodable>(
        _ path: String,
        token: String? = nil,
        response: Response.Type = Response.self
    ) async throws -> Response {
        try await request(path: path, method: "POST", token: token, response: response)
    }

    func delete(_ path: String, token: String) async throws {
        _ = try await request(path: path, method: "DELETE", token: token, response: EmptyOrJSON.self)
    }

    private func request<Response: Decodable>(
        path: String,
        method: String,
        body: (any Encodable)? = nil,
        token: String? = nil,
        headers: [String: String] = [:],
        expectedBinding: UUID? = nil,
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
        }
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }

        do {
            let (data, rawResponse) = try await BoundedAPIResponse.read(session: session, request: request)
            try Task.checkCancellation()
            if let expectedBinding, expectedBinding != bindingID { throw JarvisAPIError.invalidConfiguration }
            guard let http = rawResponse as? HTTPURLResponse else { throw JarvisAPIError.invalidResponse }
            guard (200..<300).contains(http.statusCode) else {
                if http.statusCode == 401 { throw JarvisAPIError.unauthorized }
                let body = try? decoder.decode(APIErrorBody.self, from: data)
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
