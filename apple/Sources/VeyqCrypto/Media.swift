import Foundation

/// uc.uploads (Apple) — ONE upload path, ONE download path for all bytes. Every file becomes a deduplicated
/// "blob": a set of AEAD frames (V1.sealFrame, AAD binds index+total) in the server's BlobStore (never the
/// DB). A small file is one frame, a large one many — both resumable, both opened frame-by-frame coming back.
/// A "media" attaches a stored blob to a Memory/Chapter with typed derivatives (thumbnail/preview), which are
/// themselves ordinary blobs uploaded the same way. The server stays blind: it sees ciphertext frames, a
/// per-scope dedup token, blob sizes and the mime type — but NOT capture time or GPS location, which are
/// sealed per-media (sealedMeta) under the scope content key.
public enum Media {
    // 4 MiB plaintext frames: small files stay one frame, large ones chunk transparently.
    public static let defaultChunkSize = 4 * 1024 * 1024

    public struct BlobDTO: Codable, Sendable { public let suiteId, bytes: String }
    public struct BlobRef: Codable, Sendable { public let blobId: String; public let totalChunks: Int; public let byteSize: Int64 }
    public struct PresignedPut: Codable, Sendable { public let index: Int; public let url: String }
    // ---- Batched, direct-to-store transport (ADR 0021) ----
    public struct BatchStartItem: Codable, Sendable {
        public let dedupToken: String; public let totalChunks: Int; public let byteSize: Int64; public let suiteId: String?; public let uploadId: String?
        public init(dedupToken: String, totalChunks: Int, byteSize: Int64, suiteId: String? = "v1", uploadId: String? = nil) {
            self.dedupToken = dedupToken; self.totalChunks = totalChunks; self.byteSize = byteSize; self.suiteId = suiteId; self.uploadId = uploadId
        }
    }
    public struct BatchBlobStart: Codable, Sendable {
        public let status: String; public let blobId: String?; public let uploadId: String?; public let totalChunks: Int?
        public let receivedChunks: [Int]?; public let presignedPuts: [PresignedPut]?; public let errorCode: String?
    }
    public struct PresignGetItem: Codable, Sendable { public let blobId: String; public let suiteId: String; public let chunks: [PresignedPut] }
    public struct Derivative: Codable, Sendable { public let kind: String; public let blob: BlobRef }
    public struct MediaItem: Codable, Sendable {
        public let id, scopeId, nodeId, mimeType: String
        public let sealedMeta: BlobDTO
        public let original: BlobRef
        public let derivatives: [Derivative]
        public let highlighted: Bool
        public let createdBy: String?
        public let bucketId: String?
        public let createdAt: String
    }
    /// Per-media sensitive metadata sealed under the content key: capture time, optional GPS location, and a
    /// best-effort set of EXIF camera details (operator-blind — sealed with everything else).
    public struct MediaMeta: Codable, Sendable {
        public let takenAt: String; public let lat: Double?; public let lng: Double?
        public let cameraMake: String?; public let cameraModel: String?; public let lens: String?
        public let width: Int?; public let height: Int?; public let fileSize: Int?
        public let exif: [String: String]?
        public init(takenAt: String, lat: Double? = nil, lng: Double? = nil,
                    cameraMake: String? = nil, cameraModel: String? = nil, lens: String? = nil,
                    width: Int? = nil, height: Int? = nil, fileSize: Int? = nil, exif: [String: String]? = nil) {
            self.takenAt = takenAt; self.lat = lat; self.lng = lng
            self.cameraMake = cameraMake; self.cameraModel = cameraModel; self.lens = lens
            self.width = width; self.height = height; self.fileSize = fileSize; self.exif = exif
        }
    }
    struct MediaListDTO: Codable { let media: [MediaItem] }
    struct BatchStartRequest: Codable { let items: [BatchStartItem] }
    struct BatchStartResponse: Codable { let results: [BatchBlobStart] }
    public struct BlobRefInput: Codable, Sendable { public let uploadId: String?; public let blobId: String? }
    public struct FinalizeDerivativeInput: Codable, Sendable { public let kind: String; public let blob: BlobRefInput }
    public struct FinalizeItem: Codable, Sendable { public let original: BlobRefInput; public let derivatives: [FinalizeDerivativeInput]; public let sealedMeta: BlobDTO; public let mimeType: String }
    struct BatchFinalizeRequest: Codable { let items: [FinalizeItem] }
    public struct FinalizeResult: Codable, Sendable { public let ok: Bool; public let media: MediaItem?; public let errorCode: String? }
    struct BatchFinalizeResponse: Codable { let results: [FinalizeResult] }
    struct BatchPresignRequest: Codable { let blobIds: [String] }
    struct BatchPresignResponse: Codable { let results: [PresignGetItem] }

    /// Seal {takenAt, lat?, lng?} into a sealedMeta blob (nonce || AEAD) for the create request.
    public static func sealMediaMeta(contentKey: Data, meta: MediaMeta) throws -> BlobDTO {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: try JSONEncoder().encode(meta), aad: Data()))
        return BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(sealed))
    }

    /// Decrypt a media item's sealedMeta back to {takenAt, lat?, lng?}.
    public static func decodeMediaMeta(contentKey: Data, item: MediaItem) throws -> MediaMeta {
        let bytes = Login.b64urlDecode(item.sealedMeta.bytes)
        let pt = try V1.aeadOpen(key: contentKey, nonce: Data(bytes.prefix(12)), aead: Data(bytes.dropFirst(12)), aad: Data())
        return try JSONDecoder().decode(MediaMeta.self, from: pt)
    }

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

    // Direct-to-store only (ADR 0021): every byte travels client↔object-store via short-lived presigned URLs;
    // the backend handles only the control plane (batch start/finalize/presign). The store is blind (AEAD
    // ciphertext), so a presigned URL discloses nothing. There is no proxied fallback — the store MUST presign.

    /// ONE round-trip to dedup/open (and presign) many blobs. READY = dedup hit; UPLOADING = presigned PUT URLs
    /// for the missing chunks; ERROR = per-item failure. Pass an item's uploadId to resume.
    public static func batchStartBlobs(apiBase: String, session: Login.UnlockedSession, scopeId: String, items: [BatchStartItem]) async throws -> [BatchBlobStart] {
        let body = try JSONEncoder().encode(BatchStartRequest(items: items))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/blob-uploads-batch", session, method: "POST", body: body))
        try ok(resp, data, "batch start blobs")
        return try JSONDecoder().decode(BatchStartResponse.self, from: data).results
    }

    /// ONE round-trip to finalize many items (complete each item's uploads + attach as media). Per-item best-effort.
    public static func batchFinalizeMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, items: [FinalizeItem]) async throws -> [FinalizeResult] {
        let body = try JSONEncoder().encode(BatchFinalizeRequest(items: items))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/media-batch", session, method: "POST", body: body))
        try ok(resp, data, "batch finalize media")
        return try JSONDecoder().decode(BatchFinalizeResponse.self, from: data).results
    }

    /// ONE round-trip to presign GET URLs for many blobs (a whole gallery page); keyed by blobId. Inaccessible
    /// or missing blobs are simply absent from the map.
    public static func batchPresignGet(apiBase: String, session: Login.UnlockedSession, scopeId: String, blobIds: [String]) async throws -> [String: PresignGetItem] {
        let body = try JSONEncoder().encode(BatchPresignRequest(blobIds: blobIds))
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/blob-downloads-batch", session, method: "POST", body: body))
        try ok(resp, data, "batch presign get")
        return Dictionary((try JSONDecoder().decode(BatchPresignResponse.self, from: data)).results.map { ($0.blobId, $0) }, uniquingKeysWith: { a, _ in a })
    }

    /// PUT a sealed frame's raw bytes straight to a presigned object-store URL (no auth header, no JSON wrapper).
    private static func putPresigned(url: String, frame: Data, index: Int) async throws {
        var r = URLRequest(url: URL(string: url)!)
        r.httpMethod = "PUT"
        r.httpBody = frame
        let (_, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "presigned put chunk \(index) failed"])
        }
    }

    /// Fetch one presigned blob: pull each frame DIRECTLY from the store, open it (AAD checks position), reassemble.
    private static func fetchBlobFrames(contentKey: Data, ref: BlobRef, presigned: PresignGetItem) async throws -> Data {
        guard presigned.chunks.count == ref.totalChunks else {
            throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "direct-to-store download unavailable for \(ref.blobId)"])
        }
        let urls = Dictionary(presigned.chunks.map { ($0.index, $0.url) }, uniquingKeysWith: { a, _ in a })
        var out = Data()
        for i in 0..<ref.totalChunks {
            guard let url = urls[i] else { throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "missing presigned url for chunk \(i)"]) }
            let (raw, gresp) = try await URLSession.shared.data(for: URLRequest(url: URL(string: url)!))
            guard let http = gresp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "presigned get chunk \(i) failed"])
            }
            out.append(try V1.openFrame(contentKey: contentKey, index: i, total: ref.totalChunks, frame: raw))
        }
        return out
    }

    /// Download a finalized blob directly from the store (presign + sequential GET), open + concatenate frames.
    public static func downloadBlob(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, ref: BlobRef) async throws -> Data {
        guard let p = (try await batchPresignGet(apiBase: apiBase, session: session, scopeId: scopeId, blobIds: [ref.blobId]))[ref.blobId] else {
            throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "blob \(ref.blobId) not available"])
        }
        return try await fetchBlobFrames(contentKey: contentKey, ref: ref, presigned: p)
    }

    /// Download many blobs for a gallery page: ONE batch-presign-get, then every blob fetched direct + parallel.
    public static func downloadBlobsBatch(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, refs: [BlobRef]) async throws -> [String: Data] {
        let presigned = try await batchPresignGet(apiBase: apiBase, session: session, scopeId: scopeId, blobIds: refs.map { $0.blobId })
        var out: [String: Data] = [:]
        try await withThrowingTaskGroup(of: (String, Data).self) { group in
            var inFlight = 0
            for ref in refs {
                guard let p = presigned[ref.blobId] else {
                    throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "blob \(ref.blobId) not available"])
                }
                group.addTask { (ref.blobId, try await fetchBlobFrames(contentKey: contentKey, ref: ref, presigned: p)) }
                inFlight += 1
                if inFlight >= 6 { if let (id, data) = try await group.next() { out[id] = data }; inFlight -= 1 }
            }
            for try await (id, data) in group { out[id] = data }
        }
        return out
    }

    // ---- media layer (attach blobs to a Memory) ---------------------------------------------------------

    /// The full client flow for ONE photo, batched + direct-to-store: the original and any client-generated
    /// thumbnail/preview are all blobs uploaded the SAME way. ONE batch-start dedups/opens every blob and
    /// presigns its missing chunks; the client seals each frame and PUTs it DIRECTLY to the store, parallel
    /// across all blobs; ONE batch-finalize completes the uploads + attaches the medium.
    public static func uploadMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, bytes: Data, thumb: Data? = nil, preview: Data? = nil, takenAt: String, mimeType: String, lat: Double? = nil, lng: Double? = nil, meta: MediaMeta? = nil, chunkSize: Int = defaultChunkSize) async throws -> MediaItem {
        var plan: [(kind: String, chunks: [Data], bytes: Data)] = [("original", chunkBytes(bytes, chunkSize), bytes)]
        if let thumb = thumb { plan.append(("THUMBNAIL", chunkBytes(thumb, chunkSize), thumb)) }
        if let preview = preview { plan.append(("PREVIEW", chunkBytes(preview, chunkSize), preview)) }

        // 1. ONE batch-start: dedup or open each blob, presigning every missing chunk.
        let starts = try await batchStartBlobs(apiBase: apiBase, session: session, scopeId: scopeId, items: plan.map {
            BatchStartItem(dedupToken: dedupTokenFor(contentKey: contentKey, content: $0.bytes), totalChunks: $0.chunks.count, byteSize: Int64($0.bytes.count), suiteId: "v1", uploadId: nil)
        })

        // 2. Seal + PUT every still-missing frame DIRECTLY to the store, parallel across all blobs.
        var puts: [(url: String, frame: Data, index: Int)] = []
        for (bi, s) in starts.enumerated() {
            guard s.status != "ERROR" else {
                throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "upload start failed (\(plan[bi].kind)): \(s.errorCode ?? "")"])
            }
            guard s.status == "UPLOADING" else { continue }
            let have = Set(s.receivedChunks ?? [])
            let presigned = Dictionary((s.presignedPuts ?? []).map { ($0.index, $0.url) }, uniquingKeysWith: { a, _ in a })
            let chunks = plan[bi].chunks
            for i in 0..<chunks.count where !have.contains(i) {
                guard let url = presigned[i] else {
                    throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "direct-to-store unavailable: no upload URL for chunk \(i)"])
                }
                let frame = try V1.sealFrame(contentKey: contentKey, index: i, total: chunks.count, plaintext: chunks[i], nonce: V1.randomBytes(12))
                puts.append((url, frame, i))
            }
        }
        try await withThrowingTaskGroup(of: Void.self) { group in
            for p in puts { group.addTask { try await putPresigned(url: p.url, frame: p.frame, index: p.index) } }
            try await group.waitForAll()
        }

        // 3. ONE batch-finalize: complete each blob's upload + attach the medium with its derivatives.
        func refFor(_ s: BatchBlobStart) -> BlobRefInput { s.status == "READY" ? BlobRefInput(uploadId: nil, blobId: s.blobId) : BlobRefInput(uploadId: s.uploadId, blobId: nil) }
        var derivatives: [FinalizeDerivativeInput] = []
        for (i, p) in plan.enumerated() where i > 0 { derivatives.append(FinalizeDerivativeInput(kind: p.kind, blob: refFor(starts[i]))) }
        let sealedMeta = try sealMediaMeta(contentKey: contentKey, meta: meta ?? MediaMeta(takenAt: takenAt, lat: lat, lng: lng))
        let results = try await batchFinalizeMedia(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, items: [FinalizeItem(original: refFor(starts[0]), derivatives: derivatives, sealedMeta: sealedMeta, mimeType: mimeType)])
        guard let r = results.first, r.ok, let media = r.media else {
            throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "media finalize failed: \(results.first?.errorCode ?? "unknown")"])
        }
        return media
    }

    /// Patch a media item's server-visible highlight flag and/or its (re-sealed) sealedMeta. Both fields are
    /// optional; omitted fields are left unchanged. Used by the lightbox favourite toggle + capture-date edit.
    public static func updateMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, mediaId: String, highlighted: Bool? = nil, sealedMeta: BlobDTO? = nil, bucketId: String? = nil) async throws -> MediaItem {
        var fields: [String: Any] = [:]
        if let highlighted = highlighted { fields["highlighted"] = highlighted }
        if let sealedMeta = sealedMeta { fields["sealedMeta"] = ["suiteId": sealedMeta.suiteId, "bytes": sealedMeta.bytes] }
        if let bucketId = bucketId { fields["bucketId"] = bucketId }
        let body = try JSONSerialization.data(withJSONObject: fields)
        let (data, resp) = try await URLSession.shared.data(for: authed("\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/media/\(mediaId)", session, method: "PATCH", body: body))
        try ok(resp, data, "update media")
        return try JSONDecoder().decode(MediaItem.self, from: data)
    }

    /// List a Memory's media in upload order (the client sorts by decrypted takenAt for the gallery).
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
