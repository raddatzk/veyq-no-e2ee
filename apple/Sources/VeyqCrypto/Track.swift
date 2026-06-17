import Foundation

/// uc.memory.track (Apple) — a Memory's GPX tracks are {name, points} routes sealed under the scope content
/// key (nonce || AEAD), the same envelope as node names and the journal. The server stores ciphertext only;
/// we seal before POST and open after GET. Route location data never leaves the client in the clear.
public enum Track {
    public struct Point: Codable, Sendable {
        public let lat, lng: Double
        public let ele: Double?
        public let time: String?
        public init(lat: Double, lng: Double, ele: Double? = nil, time: String? = nil) { self.lat = lat; self.lng = lng; self.ele = ele; self.time = time }
    }
    public struct Route: Codable, Sendable {
        public let name: String; public let points: [Point]
        public init(name: String, points: [Point]) { self.name = name; self.points = points }
    }
    public struct TrackItem: Identifiable, Sendable { public let id: String; public let name: String; public let points: [Point] }

    /// Track statistics derived from a route's points: distance always; ascent/descent when elevations are
    /// present; duration when timestamps are present. Missing fields → that stat is omitted, never wrong.
    /// Mirrored on web/Android.
    public struct Stats: Sendable {
        public let distanceM: Double
        public let ascentM: Double?
        public let descentM: Double?
        public let durationS: Int?
    }

    private static let earthRadiusM = 6_371_000.0

    private static func haversine(_ a: Point, _ b: Point) -> Double {
        let toRad = { (d: Double) in d * Double.pi / 180 }
        let dLat = toRad(b.lat - a.lat)
        let dLng = toRad(b.lng - a.lng)
        let h = pow(sin(dLat / 2), 2) + cos(toRad(a.lat)) * cos(toRad(b.lat)) * pow(sin(dLng / 2), 2)
        return 2 * earthRadiusM * asin(min(1, sqrt(h)))
    }

    public static func stats(_ points: [Point]) -> Stats {
        var distanceM = 0.0, ascentM = 0.0, descentM = 0.0, haveEle = false
        for i in 1..<max(points.count, 1) {
            distanceM += haversine(points[i - 1], points[i])
            if let a = points[i - 1].ele, let b = points[i].ele {
                haveEle = true
                let d = b - a
                if d > 0 { ascentM += d } else { descentM -= d }
            }
        }
        let times = points.compactMap { $0.time }
        var durationS: Int? = nil
        if let first = times.first, let last = times.last, let start = parseIso(first), let end = parseIso(last), end > start {
            durationS = Int((end.timeIntervalSince1970 - start.timeIntervalSince1970).rounded())
        }
        return Stats(distanceM: distanceM, ascentM: haveEle ? ascentM : nil, descentM: haveEle ? descentM : nil, durationS: durationS)
    }

    private static func parseIso(_ s: String) -> Date? {
        let withFrac = ISO8601DateFormatter()
        withFrac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return withFrac.date(from: s) ?? ISO8601DateFormatter().date(from: s)
    }

    /// "850 m" under 1 km, otherwise "12.3 km".
    public static func formatDistance(_ meters: Double) -> String {
        meters < 1000 ? "\(Int(meters.rounded())) m" : String(format: "%.1f km", meters / 1000)
    }

    /// "1 h 23 min" / "23 min" / "45 s".
    public static func formatDuration(_ seconds: Int) -> String {
        let h = seconds / 3600, m = (seconds % 3600) / 60
        if h > 0 { return "\(h) h \(m) min" }
        if m > 0 { return "\(m) min" }
        return "\(seconds) s"
    }

    /// "↑120 m ↓95 m" (rounded), or empty when no elevation data.
    public static func formatElevation(_ s: Stats) -> String {
        guard let ascent = s.ascentM else { return "" }
        return "↑\(Int(ascent.rounded())) m ↓\(Int((s.descentM ?? 0).rounded())) m"
    }

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
