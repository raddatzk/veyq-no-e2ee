import Foundation

/// uc.uploads.resumable-upload (Apple) — chunked, resumable media upload. A large media is split into
/// independent AEAD frames (V1.sealFrame, AAD binds index+total); the ciphertext frames go to the server's
/// BlobStore (never the DB). The session survives interruptions: ask which chunks landed, re-send only the
/// missing ones. Download decrypts frame by frame and concatenates.
public enum ResumableUpload {
    public struct UploadSession: Codable {
        public let uploadId, scopeId, nodeId: String
        public let totalChunks: Int
        public let mimeType: String
        public let byteSize: Int64
        public let takenAt, status, createdAt: String
    }
    public struct UploadProgress: Codable {
        public let uploadId: String
        public let totalChunks: Int
        public let receivedChunks: [Int]
        public let status: String
    }
    struct CreateUploadRequest: Codable { let totalChunks: Int; let mimeType: String; let byteSize: Int64; let takenAt: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct PutChunkRequest: Codable { let frame: BlobDTO }
    struct ChunkResponse: Codable { let frame: BlobDTO }

    /// Split a blob into fixed-size plaintext chunks.
    public static func chunkBytes(_ bytes: Data, _ chunkSize: Int) -> [Data] {
        var out: [Data] = []
        var o = 0
        while o < bytes.count { out.append(bytes.subdata(in: o..<min(o + chunkSize, bytes.count))); o += chunkSize }
        return out.isEmpty ? [Data()] : out
    }

    private static func authed(_ url: String, _ session: Login.UnlockedSession, method: String = "GET", body: Data? = nil) -> URLRequest {
        var r = URLRequest(url: URL(string: url)!)
        r.httpMethod = method
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        if body != nil { r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.httpBody = body }
        return r
    }

    public static func createUpload(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String,
                                    totalChunks: Int, mimeType: String, byteSize: Int64, takenAt: String) async throws -> UploadSession {
        let body = try JSONEncoder().encode(CreateUploadRequest(totalChunks: totalChunks, mimeType: mimeType, byteSize: byteSize, takenAt: takenAt))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/uploads", session, method: "POST", body: body))
        try ok(resp, data, "create upload")
        return try JSONDecoder().decode(UploadSession.self, from: data)
    }

    public static func putChunk(apiBase: String, session: Login.UnlockedSession, uploadId: String, index: Int, total: Int, contentKey: Data, plain: Data) async throws {
        let frame = try V1.sealFrame(contentKey: contentKey, index: index, total: total, plaintext: plain, nonce: V1.randomBytes(12))
        let body = try JSONEncoder().encode(PutChunkRequest(frame: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(frame))))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/uploads/\(uploadId)/chunks/\(index)", session, method: "PUT", body: body))
        try ok(resp, data, "put chunk \(index)", expect: 204)
    }

    public static func getProgress(apiBase: String, session: Login.UnlockedSession, uploadId: String) async throws -> UploadProgress {
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/uploads/\(uploadId)", session))
        try ok(resp, data, "upload progress")
        return try JSONDecoder().decode(UploadProgress.self, from: data)
    }

    public static func completeUpload(apiBase: String, session: Login.UnlockedSession, uploadId: String) async throws -> UploadSession {
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/uploads/\(uploadId)/complete", session, method: "POST"))
        try ok(resp, data, "complete upload")
        return try JSONDecoder().decode(UploadSession.self, from: data)
    }

    /// Resume: send only the chunks the server is still missing, then finalize.
    public static func resumeUpload(apiBase: String, session: Login.UnlockedSession, uploadId: String, contentKey: Data, chunks: [Data]) async throws -> UploadSession {
        let have = Set(try await getProgress(apiBase: apiBase, session: session, uploadId: uploadId).receivedChunks)
        for i in 0..<chunks.count where !have.contains(i) {
            try await putChunk(apiBase: apiBase, session: session, uploadId: uploadId, index: i, total: chunks.count, contentKey: contentKey, plain: chunks[i])
        }
        return try await completeUpload(apiBase: apiBase, session: session, uploadId: uploadId)
    }

    /// Download a finalized media: pull each frame, open it (AAD checks position), concatenate.
    public static func downloadResumable(apiBase: String, session: Login.UnlockedSession, uploadId: String, contentKey: Data, totalChunks: Int) async throws -> Data {
        var out = Data()
        for i in 0..<totalChunks {
            let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/uploads/\(uploadId)/chunks/\(i)", session))
            try ok(resp, data, "get chunk \(i)")
            let frame = Login.b64urlDecode(try JSONDecoder().decode(ChunkResponse.self, from: data).frame.bytes)
            out.append(try V1.openFrame(contentKey: contentKey, index: i, total: totalChunks, frame: frame))
        }
        return out
    }

    private static func ok(_ resp: URLResponse, _ data: Data, _ what: String, expect: Int = 200) throws {
        guard let http = resp as? HTTPURLResponse, http.statusCode == expect else {
            throw NSError(domain: "veyq.upload", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(what) failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
    }
}
