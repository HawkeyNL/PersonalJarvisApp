import CryptoKit
import Foundation

struct ModelAccessEntry: Decodable, Identifiable {
    let provider: String
    let model: String
    let enabled: Bool
    var id: String { provider + "/" + model }
}

struct ModelPolicySnapshot: Decodable {
    let models: [ModelAccessEntry]
    let mutation: String
    let policy_sha256: String?
    let user_id: UUID
    let device_id: UUID
    let server_time: Int64
    var mutable: Bool { mutation == "device-signed-model-toggle-v1" && policy_sha256 != nil }
}

/// Wire contract matches authoritative client-core model_control v1. No token,
/// private key or owner password belongs in these presentation/approval values.
struct ModelToggleApproval {
    let request: UUID
    let nonce: Data
    let user: UUID
    let device: UUID
    let issued: Int64
    let expires: Int64
    let provider: String
    let model: String
    let enabled: Bool
    let hash: String

    func operationBytes() throws -> Data {
        let providers = ["anthropic-api", "openai-api", "deepseek-api", "xai-api", "zai-api", "ollama", "ollama-cloud", "huggingface", "claude-cli"]
        guard providers.contains(provider), !model.isEmpty, model.utf8.count <= 256,
              model.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }),
              Data(hexEncoded: hash)?.count == 32 else { throw JarvisAPIError.invalidResponse }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        func quote(_ text: String) throws -> String { String(decoding: try encoder.encode(text), as: UTF8.self) }
        return Data(try "{\"action\":\"model_set_enabled\",\"provider\":\(quote(provider)),\"model\":\(quote(model)),\"enabled\":\(enabled ? "true" : "false"),\"expected_policy_sha256\":\(quote(hash))}".utf8)
    }

    func message() throws -> Data {
        guard nonce.count == 32, issued >= 0, expires > issued, expires - issued <= 300,
              let state = Data(hexEncoded: hash), state.count == 32 else { throw JarvisAPIError.invalidResponse }
        var result = Data("jarvis-privileged-config-v1\0".utf8)
        result.append(contentsOf: [0, 17])
        result.append(Data("model.set_enabled".utf8))
        result.append(Data(SHA256.hash(data: try operationBytes())))
        func uuid(_ value: UUID) -> Data { var bytes = value.uuid; return withUnsafeBytes(of: &bytes) { Data($0) } }
        func seconds(_ value: Int64) -> Data { var bytes = value.bigEndian; return withUnsafeBytes(of: &bytes) { Data($0) } }
        result.append(uuid(request)); result.append(nonce); result.append(uuid(user)); result.append(uuid(device))
        result.append(seconds(issued)); result.append(seconds(expires)); result.append(state)
        return result
    }

    func signed(_ signature: String) throws -> SignedModelToggle {
        _ = try message()
        guard Data(hexEncoded: signature)?.count == 64 else { throw JarvisAPIError.invalidResponse }
        let format = ISO8601DateFormatter()
        return SignedModelToggle(request_id: request, nonce_hex: nonce.hexEncodedString(), user_id: user, device_id: device,
            issued_at: format.string(from: Date(timeIntervalSince1970: TimeInterval(issued))),
            expires_at: format.string(from: Date(timeIntervalSince1970: TimeInterval(expires))),
            operation: .init(provider: provider, model: model, enabled: enabled, expected_policy_sha256: hash), signature_hex: signature)
    }
}

struct SignedModelToggle: Encodable {
    struct Operation: Encodable {
        let action = "model_set_enabled"
        let provider: String
        let model: String
        let enabled: Bool
        let expected_policy_sha256: String
    }
    let request_id: UUID
    let nonce_hex: String
    let user_id: UUID
    let device_id: UUID
    let issued_at: String
    let expires_at: String
    let operation: Operation
    let signature_hex: String
}
