import Foundation

/// uc.memory.playlist (Apple, ADR 0010 operator-blind) — link streaming playlists to a Memory. The payload
/// {provider, url, title?, note?} is sealed under the scope content key (the server is blind to it); only the
/// benign `featured` flag is server-visible. Provider is detected client-side from the URL host.
public enum Playlists {
    public enum Provider: String, Codable, Sendable {
        case spotify = "SPOTIFY", appleMusic = "APPLE_MUSIC", youtube = "YOUTUBE", soundcloud = "SOUNDCLOUD", deezer = "DEEZER", other = "OTHER"
        public var label: String {
            switch self {
            case .spotify: return "Spotify"
            case .appleMusic: return "Apple Music"
            case .youtube: return "YouTube"
            case .soundcloud: return "SoundCloud"
            case .deezer: return "Deezer"
            case .other: return "Link"
            }
        }
    }

    public struct Item: Identifiable, Sendable {
        public let id: String
        public let featured: Bool
        public let provider: Provider
        public let url: String
        public let title: String?
        public let note: String?
    }

    private struct Payload: Codable { let provider: Provider; let url: String; let title: String?; let note: String? }
    private struct Blob: Codable { let suiteId, bytes: String }
    private struct PlaylistDTO: Decodable { let id: String; let featured: Bool; let sealedContent: Blob }
    private struct PlaylistListDTO: Decodable { let playlists: [PlaylistDTO] }

    /// Detect the streaming provider from a URL host (best-effort; .other for anything else).
    public static func detectProvider(_ url: String) -> Provider {
        guard let host = URL(string: url)?.host?.lowercased() else { return .other }
        if host.contains("spotify.com") { return .spotify }
        if host.contains("music.apple.com") { return .appleMusic }
        if host.contains("youtube.com") || host.contains("youtu.be") { return .youtube }
        if host.contains("soundcloud.com") { return .soundcloud }
        if host.contains("deezer.com") { return .deezer }
        return .other
    }

    private static func seal(contentKey: Data, _ payload: Payload) throws -> [String: String] {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: try JSONEncoder().encode(payload), aad: Data()))
        return ["suiteId": "v1", "bytes": SetupServer.b64url(sealed)]
    }

    private static func toItem(contentKey: Data, _ dto: PlaylistDTO) throws -> Item {
        let bytes = Login.b64urlDecode(dto.sealedContent.bytes)
        let pt = try V1.aeadOpen(key: contentKey, nonce: Data(bytes.prefix(12)), aead: Data(bytes.dropFirst(12)), aad: Data())
        let p = try JSONDecoder().decode(Payload.self, from: pt)
        return Item(id: dto.id, featured: dto.featured, provider: p.provider, url: p.url, title: p.title, note: p.note)
    }

    private static func decode(_ data: Data, contentKey: Data) throws -> [Item] {
        try JSONDecoder().decode(PlaylistListDTO.self, from: data).playlists.compactMap { try? toItem(contentKey: contentKey, $0) }
    }

    /// List + decrypt a memory's playlists.
    public static func list(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String) async throws -> [Item] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/playlists")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "list playlists")
        return try decode(data, contentKey: contentKey)
    }

    /// Seal {provider, url, title?, note?} and create a new playlist.
    public static func create(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, url: String, title: String?, note: String?, featured: Bool) async throws {
        let payload = Payload(provider: detectProvider(url), url: url, title: title?.isEmpty == true ? nil : title, note: note?.isEmpty == true ? nil : note)
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/playlists")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["sealedContent": try seal(contentKey: contentKey, payload), "featured": featured])
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "create playlist")
    }

    /// Toggle the (at-most-one) featured playlist; the server clears any other featured entry.
    public static func setFeatured(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, playlistId: String, featured: Bool) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/playlists/\(playlistId)")!)
        r.httpMethod = "PATCH"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["featured": featured])
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "feature playlist")
    }

    public static func delete(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, playlistId: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/playlists/\(playlistId)")!)
        r.httpMethod = "DELETE"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "delete playlist", expect: [200, 204])
    }

    private static func ok(_ resp: URLResponse, _ what: String, expect: [Int] = [200]) throws {
        guard let http = resp as? HTTPURLResponse, expect.contains(http.statusCode) else {
            throw NSError(domain: "veyq.playlist", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(what) failed"])
        }
    }
}
