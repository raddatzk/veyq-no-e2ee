import Foundation
import YSwift

/// uc.memory.journal-live persistence (Apple) — the operator-blind CRDT store behind the live editor, wire-
/// compatible with web/Android. The journal is a Yrs document; the server keeps a sealed Yrs *state snapshot*
/// plus an append-only log of sealed Yrs *updates* (it never decrypts either). A client reconstructs the
/// document by applying the snapshot then the updates, appends a sealed update per local edit, and on close
/// compacts (a fresh snapshot + the Markdown reader projection) which prunes the folded-in updates.
public enum JournalPersistence {
    private struct Blob: Decodable { let suiteId, bytes: String }
    private struct Update: Decodable { let seq: Int; let sealedUpdate: Blob; let authorId: String }
    private struct LiveState: Decodable { let snapshot: Blob?; let baseSeq: Int; let updates: [Update] }
    private struct AppendResponse: Decodable { let seq: Int }

    // salt(16) || nonce(12) || AEAD(HKDF(key, salt, "veyq/v1/journal"), …) — a fresh per-message sub-key so the
    // GCM nonce can never collide under the long-lived content key (see V1.journalSeal).
    static func seal(_ key: Data, _ inner: [UInt8]) -> Data { V1.journalSeal(contentKey: key, plaintext: Data(inner)) }
    static func open(_ key: Data, _ blob: [UInt8]) -> [UInt8]? { V1.journalOpen(contentKey: key, blob: Data(blob)).map { [UInt8]($0) } }

    private static func request(_ apiBase: String, _ session: Login.UnlockedSession, _ path: String, method: String = "GET", body: Any? = nil) throws -> URLRequest {
        var r = URLRequest(url: URL(string: "\(apiBase)\(path)")!)
        r.httpMethod = method
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        if let body { r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.httpBody = try JSONSerialization.data(withJSONObject: body) }
        return r
    }
    private static func ok(_ resp: URLResponse) -> Bool { (resp as? HTTPURLResponse).map { $0.statusCode == 200 } ?? false }

    /// Reconstruct the journal into `doc` from the server's snapshot + update log. Returns the max seq applied.
    @discardableResult
    public static func loadJournalDoc(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: Data, doc: YDocument) async throws -> Int {
        let r = try request(apiBase, session, "/api/scopes/\(scopeId)/nodes/\(nodeId)/journal/live")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard ok(resp) else { throw err("load journal failed") }
        let state = try JSONDecoder().decode(LiveState.self, from: data)
        var maxSeq = state.baseSeq
        doc.transactSync { txn in
            if let s = state.snapshot, let u = open(contentKey, [UInt8](Login.b64urlDecode(s.bytes))) { try? txn.transactionApplyUpdate(update: u) }
            for upd in state.updates {
                if let u = open(contentKey, [UInt8](Login.b64urlDecode(upd.sealedUpdate.bytes))) {
                    try? txn.transactionApplyUpdate(update: u)
                    maxSeq = max(maxSeq, upd.seq)
                }
            }
        }
        return maxSeq
    }

    /// Persist one local Yrs update; returns its server-assigned monotonic seq.
    @discardableResult
    public static func appendJournalUpdate(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: Data, update: [UInt8]) async throws -> Int {
        let r = try request(apiBase, session, "/api/scopes/\(scopeId)/nodes/\(nodeId)/journal/updates", method: "POST",
                            body: ["sealedUpdate": ["suiteId": "v1", "bytes": SetupServer.b64url(seal(contentKey, update))]])
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard ok(resp) else { throw err("append journal update failed") }
        return try JSONDecoder().decode(AppendResponse.self, from: data).seq
    }

    /// Compact: write a fresh sealed snapshot + the Markdown reader projection, pruning updates <= coveredSeq.
    public static func compactJournal(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: Data, doc: YDocument, markdown: String, coveredSeq: Int) async throws {
        let full = doc.transactSync { $0.transactionEncodeStateAsUpdate() }
        // The Markdown projection is low-volume at-rest content read by the plain getJournal reader, so it keeps
        // the plain nonce||AEAD(contentKey) envelope (not the per-message journal sub-key — that's the CRDT layer).
        let mdNonce = V1.randomBytes(12)
        let md = mdNonce + ((try? V1.aeadSeal(key: contentKey, nonce: mdNonce, plaintext: Data(markdown.utf8), aad: Data())) ?? Data())
        let r = try request(apiBase, session, "/api/scopes/\(scopeId)/nodes/\(nodeId)/journal/snapshot", method: "PUT", body: [
            "snapshot": ["suiteId": "v1", "bytes": SetupServer.b64url(seal(contentKey, full))],
            "markdown": ["suiteId": "v1", "bytes": SetupServer.b64url(md)],
            "coveredSeq": coveredSeq,
        ])
        let (_, resp) = try await URLSession.shared.data(for: r)
        guard ok(resp) else { throw err("compact journal failed") }
    }

    /// Whole-document save (the plain, non-collaborative editor): replace the text on the current CRDT and compact.
    public static func saveJournalMarkdown(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, contentKey: Data, markdown: String) async throws {
        let doc = YDocument()
        let maxSeq = try await loadJournalDoc(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, contentKey: contentKey, doc: doc)
        let text = doc.getOrCreateText(named: "journal")
        doc.transactSync { txn in
            text.removeRange(start: 0, length: text.length(in: txn), in: txn)
            text.insert(markdown, at: 0, in: txn)
        }
        try await compactJournal(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, contentKey: contentKey, doc: doc, markdown: markdown, coveredSeq: maxSeq)
    }

    private static func err(_ m: String) -> NSError { NSError(domain: "veyq.journal", code: 3, userInfo: [NSLocalizedDescriptionKey: m]) }
}
