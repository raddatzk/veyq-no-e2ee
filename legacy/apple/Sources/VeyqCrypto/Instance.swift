import Foundation

/// uc.platform.app-shell (Apple) — a mobile client points at a chosen instance; this reports whether that
/// instance still needs first-run setup (which is web-only) and the suites it advertises.
public enum Instance {
    public struct SetupStatus: Codable, Sendable {
        public let setupRequired: Bool
        public let suites: [String]
        /// This instance's public app origin — clients assembling invite links build against it. Optional
        /// (older instances omit it); fall back to apiBase when absent.
        public let appBaseUrl: String?
        /// Optional operator support URL (drives a dismissible support banner). Absent when unset.
        public let supportUrl: String?
        /// Advisory deployed server version. Absent on older instances.
        public let serverVersion: String?
    }

    /// Fetch `GET /api/setup` (public, no auth).
    public static func getSetupStatus(apiBase: String) async throws -> SetupStatus {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/setup")!)
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.instance", code: 1, userInfo: [NSLocalizedDescriptionKey: "setup status failed"])
        }
        return try JSONDecoder().decode(SetupStatus.self, from: data)
    }
}
