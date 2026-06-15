import Foundation

/// uc.memory.journal (Apple) — a Memory's journal is a Markdown body sealed under the scope content key
/// (nonce || AEAD), the same envelope as node names. The server stores ciphertext only; we seal before PUT
/// and open after GET. Whole-document replace (no live collaboration in the greenfield).
public enum Journal {
    public struct Content: Sendable { public let markdown: String; public let updatedAt: String? }

    private struct Blob: Decodable { let suiteId, bytes: String }
    private struct JournalResponse: Decodable {
        let nodeId: String
        let sealedContent: Blob?
        let updatedAt: String?
    }

    /// Load and decrypt a memory's journal Markdown ("" when none has been written yet).
    public static func getJournal(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String) async throws -> Content {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/journal")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw NSError(domain: "veyq.journal", code: 1, userInfo: [NSLocalizedDescriptionKey: "get journal failed"])
        }
        let body = try JSONDecoder().decode(JournalResponse.self, from: data)
        guard let sealed = body.sealedContent else { return Content(markdown: "", updatedAt: nil) }
        let bytes = Login.b64urlDecode(sealed.bytes)
        let pt = try V1.aeadOpen(key: contentKey, nonce: Data(bytes.prefix(12)), aead: Data(bytes.dropFirst(12)), aad: Data())
        return Content(markdown: String(decoding: pt, as: UTF8.self), updatedAt: body.updatedAt)
    }

    /// Seal and store a memory's journal Markdown (whole-document replace).
    @discardableResult
    public static func updateJournal(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, markdown: String) async throws -> Content {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: Data(markdown.utf8), aad: Data()))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/journal")!)
        r.httpMethod = "PUT"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["sealedContent": ["suiteId": "v1", "bytes": SetupServer.b64url(sealed)]])
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw NSError(domain: "veyq.journal", code: 2, userInfo: [NSLocalizedDescriptionKey: "update journal failed"])
        }
        let body = try JSONDecoder().decode(JournalResponse.self, from: data)
        return Content(markdown: markdown, updatedAt: body.updatedAt)
    }
}
