import Foundation

/// uc.keys.rewrap-scope-grant (Apple) — replace only this user's existing wrapped grant.
public enum Keys {
    public struct ScopeGrantResponseDTO: Codable {
        public let scopeId: String
        public let scopePublicKey: Login.KemPublicKeyDTO
        public let wrappedScopePrivateKey: Login.BlobDTO
    }

    struct RewrapRequest: Codable {
        let wrappedScopePrivateKey: Login.BlobDTO
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
