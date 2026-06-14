import Foundation

/// uc.uploads.add-media (Apple) — get encrypted media into a Memory. The bytes + a light thumbnail derivative
/// are AEAD-sealed under the scope content key; the server stores ciphertext and only low-sensitivity metadata
/// (node, mime, size, takenAt for ordering). Media live only inside a Memory/Chapter.
public enum AddMedia {
    struct BlobDTO: Codable { let suiteId, bytes: String }
    public struct MediaItem: Codable {
        public let id, scopeId, nodeId, takenAt, mimeType: String
        public let byteSize: Int64
        let sealedBlob: BlobDTO
        let sealedThumb: BlobDTO?
        public let createdAt: String
    }
    struct MediaListDTO: Codable { let media: [MediaItem] }
    struct CreateMediaRequest: Codable {
        let takenAt, mimeType: String
        let byteSize: Int64
        let sealedBlob: BlobDTO
        let sealedThumb: BlobDTO?
    }

    private static func seal(_ contentKey: Data, _ plain: Data) throws -> BlobDTO {
        let nonce = V1.randomBytes(12)
        return BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: plain, aad: Data()))))
    }

    /// Seal a media's bytes (+ optional thumbnail) under the content key and attach it to a Memory/Chapter node.
    public static func addMedia(
        apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String,
        bytes: Data, thumb: Data? = nil, takenAt: String, mimeType: String
    ) async throws -> MediaItem {
        let body = CreateMediaRequest(
            takenAt: takenAt, mimeType: mimeType, byteSize: Int64(bytes.count),
            sealedBlob: try seal(contentKey, bytes), sealedThumb: try thumb.map { try seal(contentKey, $0) })
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/media")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(body)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.media", code: 1, userInfo: [NSLocalizedDescriptionKey: "add media failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(MediaItem.self, from: data)
    }

    /// List a Memory's media, ordered by takenAt (gallery order).
    public static func listMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String) async throws -> [MediaItem] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/media")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.media", code: 2, userInfo: [NSLocalizedDescriptionKey: "list media failed"])
        }
        return try JSONDecoder().decode(MediaListDTO.self, from: data).media
    }

    /// Decrypt a media's sealed bytes with the scope content key.
    public static func decodeBlob(contentKey: Data, item: MediaItem) throws -> Data { try open(contentKey, item.sealedBlob) }
    /// Decrypt a media's sealed thumbnail (nil if none).
    public static func decodeThumb(contentKey: Data, item: MediaItem) throws -> Data? { try item.sealedThumb.map { try open(contentKey, $0) } }
    public static func hasThumb(_ item: MediaItem) -> Bool { item.sealedThumb != nil }

    private static func open(_ contentKey: Data, _ blob: BlobDTO) throws -> Data {
        let sealed = Login.b64urlDecode(blob.bytes)
        return try V1.aeadOpen(key: contentKey, nonce: Data(sealed.prefix(12)), aead: Data(sealed.dropFirst(12)), aad: Data())
    }
}
