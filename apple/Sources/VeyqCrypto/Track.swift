import Foundation

/// uc.memory.track (Apple) — a Memory's GPX tracks are {name, points} routes sealed under the scope content
/// key (nonce || AEAD), the same envelope as node names and the journal. The server stores ciphertext only;
/// we seal before POST and open after GET. Route location data never leaves the client in the clear.
public enum Track {
    public struct Point: Codable, Sendable { public let lat, lng: Double; public init(lat: Double, lng: Double) { self.lat = lat; self.lng = lng } }
    public struct Route: Codable, Sendable {
        public let name: String; public let points: [Point]
        public init(name: String, points: [Point]) { self.name = name; self.points = points }
    }
    public struct TrackItem: Identifiable, Sendable { public let id: String; public let name: String; public let points: [Point] }

    private struct Blob: Codable { let suiteId, bytes: String }
    private struct TrackDTO: Decodable { let id: String; let sealedContent: Blob }
    private struct TrackListDTO: Decodable { let tracks: [TrackDTO] }

    /// List + decrypt a memory's tracks.
    public static func getTracks(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String) async throws -> [TrackItem] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/tracks")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "list tracks")
        return try JSONDecoder().decode(TrackListDTO.self, from: data).tracks.map { dto in
            let bytes = Login.b64urlDecode(dto.sealedContent.bytes)
            let pt = try V1.aeadOpen(key: contentKey, nonce: Data(bytes.prefix(12)), aead: Data(bytes.dropFirst(12)), aad: Data())
            let route = try JSONDecoder().decode(Route.self, from: pt)
            return TrackItem(id: dto.id, name: route.name, points: route.points)
        }
    }

    /// Seal a parsed GPX route and store it as a new track.
    public static func createTrack(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, route: Route) async throws {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: try JSONEncoder().encode(route), aad: Data()))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/tracks")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["sealedContent": ["suiteId": "v1", "bytes": SetupServer.b64url(sealed)]])
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "create track")
    }

    /// Remove a track.
    public static func deleteTrack(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, trackId: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/tracks/\(trackId)")!)
        r.httpMethod = "DELETE"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "delete track", expect: [200, 204])
    }

    private static func ok(_ resp: URLResponse, _ what: String, expect: [Int] = [200]) throws {
        guard let http = resp as? HTTPURLResponse, expect.contains(http.statusCode) else {
            throw NSError(domain: "veyq.track", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(what) failed"])
        }
    }
}
