import Foundation

/// uc.spaces.switch-active-space (Apple) — the unified space switcher: list every space the caller can
/// enter (personal + groups) and switch the active context into any of them. Switching fetches no secrets;
/// it opens the chosen scope with the wrap the client already holds. Content ops reuse ScopeContent.
public enum SwitchActiveSpace {
    public struct KemPublicKeyDTO: Codable, Sendable { public let suiteId, kemPublicKey: String }
    public struct BlobDTO: Codable, Sendable { public let suiteId, bytes: String }
    public struct SpaceEntry: Codable, Sendable, Identifiable {
        public var id: String { scopeId }
        public let kind: String            // "PERSONAL" | "TENANT" | "GROUP"
        public let tenantId: String?
        public let name: String?
        public let displayName: String?
        public let role: String?
        public let sealedName: BlobDTO?    // GROUP only — the name sealed under the group content key
        public let scopeId: String
        public let scopePublicKey: KemPublicKeyDTO
    }
    struct SpaceListDTO: Codable { let spaces: [SpaceEntry] }

    /// List every space the caller can enter (personal + every group they hold the scope key for).
    public static func listMySpaces(apiBase: String, session: Login.UnlockedSession) async throws -> [SpaceEntry] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/me/spaces")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.spaces", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "list spaces failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(SpaceListDTO.self, from: data).spaces
    }

    /// Switch into a space: open its scope with the already-held wrap (no secrets fetched).
    public static func openSpace(apiBase: String, session: Login.UnlockedSession, entry: SpaceEntry) async throws -> ScopeContent.OpenScope {
        if entry.kind == "PERSONAL" {
            return try await ScopeContent.openPersonalScope(apiBase: apiBase, session: session)
        }
        // A group is opened by scope id (no tenant-keyed grant path); a tenant-wide space by its tenant id.
        if entry.kind == "GROUP" {
            return try await ScopeContent.openScopeById(apiBase: apiBase, session: session, scopeId: entry.scopeId)
        }
        guard let tenantId = entry.tenantId else {
            throw NSError(domain: "veyq.spaces", code: 2, userInfo: [NSLocalizedDescriptionKey: "tenant space entry is missing its tenantId"])
        }
        return try await ScopeContent.openTenantScope(apiBase: apiBase, session: session, tenantId: tenantId)
    }
}
