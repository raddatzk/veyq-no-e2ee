import Foundation

/// uc.keys.rewrap-scope-grant (Apple) — replace only this user's existing wrapped grant.
public enum Keys {
    public struct MasterKeyWrapDTO: Codable {
        public let unlockMethod, suiteId, wrappedKey: String
        public let kdfParams, credentialRef: String?

        public init(unlockMethod: String, suiteId: String, wrappedKey: String, kdfParams: String? = nil, credentialRef: String? = nil) {
            self.unlockMethod = unlockMethod
            self.suiteId = suiteId
            self.wrappedKey = wrappedKey
            self.kdfParams = kdfParams
            self.credentialRef = credentialRef
        }
    }

    public struct ScopeGrantResponseDTO: Codable {
        public let scopeId: String
        public let scopePublicKey: Login.KemPublicKeyDTO
        public let wrappedScopePrivateKey: Login.BlobDTO
    }

    struct RewrapRequest: Codable {
        let wrappedScopePrivateKey: Login.BlobDTO
    }
    struct UpsertMasterKeyWrapRequest: Codable {
        let masterKeyWrap: MasterKeyWrapDTO
    }

    public static func upsertMyMasterKeyWrap(
        apiBase: String, sessionToken: String, masterKeyWrap: MasterKeyWrapDTO
    ) async throws -> MasterKeyWrapDTO {
        var request = URLRequest(url: URL(string: "\(apiBase)/api/me/key-wraps/\(masterKeyWrap.unlockMethod)")!)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(UpsertMasterKeyWrapRequest(masterKeyWrap: masterKeyWrap))
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.keys", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "master key wrap upsert failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(MasterKeyWrapDTO.self, from: data)
    }

    public static func removeMyMasterKeyWrap(
        apiBase: String, sessionToken: String, unlockMethod: String, credentialRef: String? = nil
    ) async throws {
        var components = URLComponents(string: "\(apiBase)/api/me/key-wraps/\(unlockMethod)")!
        if let credentialRef {
            components.queryItems = [URLQueryItem(name: "credentialRef", value: credentialRef)]
        }
        var request = URLRequest(url: components.url!)
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 204 else {
            throw NSError(domain: "veyq.keys", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "master key wrap removal failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
    }

    public static func rewrapMyScopeGrant(
        apiBase: String, sessionToken: String, scopeId: String, wrappedScopePrivateKey: Login.BlobDTO
    ) async throws -> ScopeGrantResponseDTO {
        var request = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/grants/me")!)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(RewrapRequest(wrappedScopePrivateKey: wrappedScopePrivateKey))
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.keys", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "scope grant rewrap failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(ScopeGrantResponseDTO.self, from: data)
    }
}
