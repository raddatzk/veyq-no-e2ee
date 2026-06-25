import Foundation

/// uc.spaces.switch-active-space (Apple) — the unified space switcher: list every enterable PERSONAL,
/// TENANT_ROOT and GROUP_ROOT space and switch the active context into any of them. Switching fetches no
/// secrets; it opens the chosen scope with the wrap the client already holds. Content ops reuse ScopeContent.
public enum SwitchActiveSpace {
    public struct KemPublicKeyDTO: Codable, Sendable { public let suiteId, kemPublicKey: String }
    public struct BlobDTO: Codable, Sendable { public let suiteId, bytes: String }
    public struct SpaceEntry: Codable, Sendable, Identifiable {
        public var id: String { scopeId }
        public let kind: String            // "PERSONAL" | "TENANT" | "GROUP"
        public let tenantId: String?
        public let groupId: String?
        public let name: String?
        public let domain: String?  // TENANT/GROUP — the tenant's domain (host resolves the tenant)
        public let displayName: String?
        public let role: String?
        public let sealedName: BlobDTO?    // GROUP only — the name sealed under the group content key
        public let scheduledDeletionAt: String?  // TENANT only — set when scheduled for deletion (grace period)
        public let scopeId: String
        public let scopePublicKey: KemPublicKeyDTO
    }
    struct SpaceListDTO: Codable { let spaces: [SpaceEntry] }

    /// List every PERSONAL, TENANT_ROOT and GROUP_ROOT space the caller can enter.
    public static func listMySpaces(apiBase: String, session: Login.UnlockedSession) async throws -> [SpaceEntry] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/spaces")!)
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
            return try await ScopeContent.openScopeById(apiBase: apiBase, session: session, scopeId: entry.scopeId, context: "personal-scope-grant")
        }
        // Every space is opened by scope id; groupId is only for group-management APIs.
        if entry.kind == "GROUP" {
            return try await ScopeContent.openScopeById(apiBase: apiBase, session: session, scopeId: entry.scopeId)
        }
        return try await ScopeContent.openScopeById(apiBase: apiBase, session: session, scopeId: entry.scopeId)
    }
}
