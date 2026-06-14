import Foundation

/// uc.spaces.use-group (Apple) — enter a group space and collaborate on its shared content. A group is a
/// tenant the caller is an active member of AND holds the scope key for; open scope + content key + items
/// reuse the scope-generic content slice (ScopeContent) — this only adds the group roster.
public enum UseGroup {
    public struct KemPublicKeyDTO: Codable { public let suiteId, kemPublicKey: String }
    public struct GroupSpaceDTO: Codable {
        public let tenantId, name, displayName, role, scopeId: String
        public let scopePublicKey: KemPublicKeyDTO
    }
    struct GroupListDTO: Codable { let groups: [GroupSpaceDTO] }

    /// List the group spaces the caller can enter (active member + holds the scope key).
    public static func listMyGroups(apiBase: String, session: Login.UnlockedSession) async throws -> [GroupSpaceDTO] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/me/groups")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.groups", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "list groups failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(GroupListDTO.self, from: data).groups
    }
}
