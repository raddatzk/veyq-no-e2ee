import Foundation

/// uc.sharing.share-public-viewer (Apple) — owner side. Create a read-only public link for a Memory: the scope content key
/// is wrapped under a random per-share key, which rides the returned link's fragment (/s/<id>#k=…) and never
/// reaches the server. The web viewer at the instance origin opens it; the server stays blind to the content.
public enum Share {
    private struct CreateResponse: Decodable { let shareId: String }

    /// Create a share (role "VIEWER" or "CONTRIBUTOR") and return the public link (origin = the instance base
    /// URL that serves the web viewer).
    public static func createShare(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, role: String = "VIEWER", origin: String) async throws -> String {
        let shareKey = V1.randomBytes(32)
        let nonce = V1.randomBytes(12)
        let wrapped = nonce + (try V1.aeadSeal(key: shareKey, nonce: nonce, plaintext: contentKey, aad: Data()))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/shares")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["wrappedContentKey": ["suiteId": "v1", "bytes": SetupServer.b64url(wrapped)], "role": role])
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw NSError(domain: "veyq.share", code: 1, userInfo: [NSLocalizedDescriptionKey: "create share failed"])
        }
        let shareId = try JSONDecoder().decode(CreateResponse.self, from: data).shareId
        return "\(origin.hasSuffix("/") ? String(origin.dropLast()) : origin)/s/\(shareId)#k=\(SetupServer.b64url(shareKey))"
    }
}
