import Foundation

/// uc.journal.write (Apple) — the Markdown READ projection of a Memory's journal, sealed under the scope
/// content key (nonce || AEAD). The server stores ciphertext only. Editing goes through the operator-blind
/// CRDT store (JournalPersistence); this is the read path (detail view) that opens the compacted Markdown.
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
}
