import Foundation

/// uc.uploads (Apple) — ONE upload path, ONE download path for all bytes. Every file becomes a deduplicated
/// "blob": a set of AEAD frames (V1.sealFrame, AAD binds index+total) in the server's BlobStore (never the
/// DB). A small file is one frame, a large one many — both resumable, both opened frame-by-frame coming back.
/// A "media" attaches a stored blob to a Memory/Chapter with typed derivatives (thumbnail/preview), which are
/// themselves ordinary blobs uploaded the same way. The server stays blind: only ciphertext frames, a
/// per-scope dedup token, and low-sensitivity metadata (mime, size, takenAt).
public enum Media {
    // 4 MiB plaintext frames: small files stay one frame, large ones chunk transparently.
    public static let defaultChunkSize = 4 * 1024 * 1024

    struct BlobDTO: Codable { let suiteId, bytes: String }
    public struct BlobRef: Codable { public let blobId: String; public let totalChunks: Int; public let byteSize: Int64 }
    public struct BlobTicket: Codable { public let status: String; public let blobId: String?; public let uploadId: String?; public let totalChunks: Int }
    public struct UploadProgress: Codable { public let uploadId: String; public let totalChunks: Int; public let receivedChunks: [Int]; public let status: String }
    public struct Derivative: Codable { public let kind: String; public let blob: BlobRef }
    public struct MediaItem: Codable {
        public let id, scopeId, nodeId, takenAt, mimeType: String
        public let original: BlobRef
        public let derivatives: [Derivative]
        public let createdAt: String
    }
    struct MediaListDTO: Codable { let media: [MediaItem] }
    struct StartBlobRequest: Codable { let dedupToken: String; let totalChunks: Int; let byteSize: Int64 }
    struct PutChunkRequest: Codable { let frame: BlobDTO }
    struct ChunkResponse: Codable { let frame: BlobDTO }
    struct DerivativeInput: Codable { let kind, blobId: String }
    struct CreateMediaRequest: Codable { let originalBlobId, takenAt, mimeType: String; let derivatives: [DerivativeInput] }

    // ---- blob layer (the single upload/download path) ---------------------------------------------------

    /// Split a plaintext into fixed-size chunks (always ≥1 so an empty file still has one frame).
    public static func chunkBytes(_ bytes: Data, _ chunkSize: Int = defaultChunkSize) -> [Data] {
        var out: [Data] = []
        var o = 0
        while o < bytes.count { out.append(bytes.subdata(in: o..<min(o + chunkSize, bytes.count))); o += chunkSize }
        return out.isEmpty ? [Data()] : out
    }

    /// The per-scope dedup token for some content (matches what the server keys on — a blind dedup token).
    public static func dedupTokenFor(contentKey: Data, content: Data) -> String {
        SetupServer.b64url(V1.dedupToken(contentKey: contentKey, content: content))
    }

    private static func authed(_ url: String, _ session: Login.UnlockedSession, method: String = "GET", body: Data? = nil) -> URLRequest {
        var r = URLRequest(url: URL(string: url)!)
        r.httpMethod = method
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        if body != nil { r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.httpBody = body }
        return r
    }

    /// Begin (or dedup) storing a blob. READY → already stored (attach blobId); UPLOADING → push the frames.
    public static func startBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, dedupToken: String, totalChunks: Int, byteSize: Int64) async throws -> BlobTicket {
        let body = try JSONEncoder().encode(StartBlobRequest(dedupToken: dedupToken, totalChunks: totalChunks, byteSize: byteSize))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/blobs", session, method: "POST", body: body))
        try ok(resp, data, "start blob")
        return try JSONDecoder().decode(BlobTicket.self, from: data)
    }

    /// Seal one plaintext chunk into a frame and PUT it (idempotent — re-PUT is the basis of resume).
    public static func putChunk(apiBase: String, session: Login.UnlockedSession, scopeId: String, uploadId: String, index: Int, total: Int, contentKey: Data, plain: Data) async throws {
        let frame = try V1.sealFrame(contentKey: contentKey, index: index, total: total, plaintext: plain, nonce: V1.randomBytes(12))
        let body = try JSONEncoder().encode(PutChunkRequest(frame: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(frame))))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/blob-uploads/\(uploadId)/chunks/\(index)", session, method: "PUT", body: body))
        try ok(resp, data, "put chunk \(index)", expect: 204)
    }

    /// Which chunk indices have landed (drives resume).
    public static func getProgress(apiBase: String, session: Login.UnlockedSession, scopeId: String, uploadId: String) async throws -> UploadProgress {
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/blob-uploads/\(uploadId)", session))
        try ok(resp, data, "upload progress")
        return try JSONDecoder().decode(UploadProgress.self, from: data)
    }

    /// Finalize the upload once every chunk has landed; promotes the frames to a content blob.
    public static func completeBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, uploadId: String) async throws -> BlobRef {
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/blob-uploads/\(uploadId)/complete", session, method: "POST"))
        try ok(resp, data, "complete blob")
        return try JSONDecoder().decode(BlobRef.self, from: data)
    }

    /// Upload arbitrary content as a deduplicated blob. Dedups first (skips the bytes entirely if the scope
    /// already holds them); otherwise opens an upload and pushes only the still-missing frames, so a retried
    /// call resumes a half-done upload rather than restarting it.
    public static func uploadBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, bytes: Data, chunkSize: Int = defaultChunkSize) async throws -> BlobRef {
        let chunks = chunkBytes(bytes, chunkSize)
        let ticket = try await startBlob(apiBase: apiBase, session: session, scopeId: scopeId, dedupToken: dedupTokenFor(contentKey: contentKey, content: bytes), totalChunks: chunks.count, byteSize: Int64(bytes.count))
        if ticket.status == "READY" { return BlobRef(blobId: ticket.blobId!, totalChunks: ticket.totalChunks, byteSize: Int64(bytes.count)) }
        let uploadId = ticket.uploadId!
        let have = Set(try await getProgress(apiBase: apiBase, session: session, scopeId: scopeId, uploadId: uploadId).receivedChunks)
        for i in 0..<chunks.count where !have.contains(i) {
            try await putChunk(apiBase: apiBase, session: session, scopeId: scopeId, uploadId: uploadId, index: i, total: chunks.count, contentKey: contentKey, plain: chunks[i])
        }
        return try await completeBlob(apiBase: apiBase, session: session, scopeId: scopeId, uploadId: uploadId)
    }

    /// Download a finalized blob: pull each frame, open it (AAD checks position), concatenate the plaintext.
    public static func downloadBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, ref: BlobRef) async throws -> Data {
        var out = Data()
        for i in 0..<ref.totalChunks {
            let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/blobs/\(ref.blobId)/chunks/\(i)", session))
            try ok(resp, data, "get chunk \(i)")
            let frame = Login.b64urlDecode(try JSONDecoder().decode(ChunkResponse.self, from: data).frame.bytes)
            out.append(try V1.openFrame(contentKey: contentKey, index: i, total: ref.totalChunks, frame: frame))
        }
        return out
    }

    // ---- media layer (attach blobs to a Memory) ---------------------------------------------------------

    /// Attach a stored blob (+ derivatives) to a Memory/Chapter node.
    public static func createMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, originalBlobId: String, takenAt: String, mimeType: String, derivatives: [(kind: String, blobId: String)] = []) async throws -> MediaItem {
        let body = try JSONEncoder().encode(CreateMediaRequest(originalBlobId: originalBlobId, takenAt: takenAt, mimeType: mimeType, derivatives: derivatives.map { DerivativeInput(kind: $0.kind, blobId: $0.blobId) }))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/media", session, method: "POST", body: body))
        try ok(resp, data, "create media")
        return try JSONDecoder().decode(MediaItem.self, from: data)
    }

    /// The full client flow: upload the original bytes (chunked + deduped transparently) and any
    /// client-generated thumbnail through the SAME blob path, then attach them as a media. The thumbnail is a
    /// typed derivative — no special API.
    public static func uploadMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, bytes: Data, thumb: Data? = nil, takenAt: String, mimeType: String, chunkSize: Int = defaultChunkSize) async throws -> MediaItem {
        let original = try await uploadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, bytes: bytes, chunkSize: chunkSize)
        var derivatives: [(kind: String, blobId: String)] = []
        if let thumb = thumb {
            let t = try await uploadBlob(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, bytes: thumb, chunkSize: chunkSize)
            derivatives.append((kind: "THUMBNAIL", blobId: t.blobId))
        }
        return try await createMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, originalBlobId: original.blobId, takenAt: takenAt, mimeType: mimeType, derivatives: derivatives)
    }

    /// List a Memory's media, ordered by takenAt (gallery order).
    public static func listMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String) async throws -> [MediaItem] {
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/media", session))
        try ok(resp, data, "list media")
        return try JSONDecoder().decode(MediaListDTO.self, from: data).media
    }

    private static func ok(_ resp: URLResponse, _ data: Data, _ what: String, expect: Int = 200) throws {
        guard let http = resp as? HTTPURLResponse, http.statusCode == expect else {
            throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(what) failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
    }
}
