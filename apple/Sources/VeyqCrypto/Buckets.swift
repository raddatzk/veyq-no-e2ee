import Foundation

/// uc.contributions (Apple, ADR 0023) — per-contributor media buckets. Bucket names are sealed under the scope
/// content key (operator-blind), like node names. A member's primary bucket is synthesized server-side (no
/// sealed name — the client labels it with the member's display name); sub-buckets + guest primaries are
/// persisted with sealed names. Create/rename/delete + media moves are own-bucket only (enforced server-side).
public enum Buckets {
    public struct Bucket: Codable, Sendable, Identifiable {
        public let id: String
        public let ownerKey: String
        public let parentBucketId: String?
        public let sealedName: Media.BlobDTO?
        public let primary: Bool
        public let mediaCount: Int
    }
    private struct BucketListDTO: Decodable { let buckets: [Bucket] }

    /// Decrypt a bucket's sealed name, or "" when absent (synthesized member primary) / undecryptable.
    public static func name(contentKey: Data, bucket: Bucket) -> String {
        guard let sealed = bucket.sealedName else { return "" }
        let bytes = Login.b64urlDecode(sealed.bytes)
        guard bytes.count > 12, let pt = try? V1.aeadOpen(key: contentKey, nonce: Data(bytes.prefix(12)), aead: Data(bytes.dropFirst(12)), aad: Data()) else { return "" }
        return String(data: pt, encoding: .utf8) ?? ""
    }

    private static func sealedName(contentKey: Data, _ name: String) throws -> [String: String] {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: Data(name.utf8), aad: Data()))
        return ["suiteId": "v1", "bytes": SetupServer.b64url(sealed)]
    }

    public static func list(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String) async throws -> [Bucket] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/buckets")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "list buckets")
        return try JSONDecoder().decode(BucketListDTO.self, from: data).buckets
    }

    /// Create a sub-bucket under the caller's own primary (sealed name).
    public static func create(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, name: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/buckets")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["sealedName": try sealedName(contentKey: contentKey, name)])
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "create bucket")
    }

    public static func rename(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, bucketId: String, name: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/buckets/\(bucketId)")!)
        r.httpMethod = "PATCH"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["sealedName": try sealedName(contentKey: contentKey, name)])
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "rename bucket")
    }

    public static func delete(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, bucketId: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/buckets/\(bucketId)")!)
        r.httpMethod = "DELETE"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "delete bucket", expect: [200, 204])
    }

    private static func ok(_ resp: URLResponse, _ what: String, expect: [Int] = [200]) throws {
        guard let http = resp as? HTTPURLResponse, expect.contains(http.statusCode) else {
            throw NSError(domain: "veyq.bucket", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(what) failed"])
        }
    }
}
