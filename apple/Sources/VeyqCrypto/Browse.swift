import Foundation

/// uc.memory.browse (Apple) — navigate the encrypted tree. The server is blind to names/paths, so path
/// resolution is a CLIENT operation: walk one level per path segment (GET /nodes?parentId=…), matching each
/// slug to a child by its blinded slug token. The node's TYPE comes from the node (server-visible structure),
/// not the URL — so /urlaube/italien-2026 stays clean and the client learns folder/memory/chapter per step.
public enum Browse {
    struct NodeListDTO: Codable { let nodes: [MemoryTree.MemoryNode] }

    /// List the direct children of a level: a node id, or the scope root when parentId is nil.
    public static func listChildren(apiBase: String, session: Login.UnlockedSession, scopeId: String, parentId: String? = nil) async throws -> [MemoryTree.MemoryNode] {
        let slot = parentId ?? "root"
        let encoded = slot.addingPercentEncoding(withAllowedCharacters: .urlQueryValueAllowed) ?? slot
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes?parentId=\(encoded)")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.browse", code: 1, userInfo: [NSLocalizedDescriptionKey: "list children failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(NodeListDTO.self, from: data).nodes
    }

    /// Resolve a clean slug path to the chain of nodes, one per segment, by matching each slug to a child's
    /// per-parent slug token. Throws if a segment doesn't exist. Each node carries its own type.
    public static func resolvePath(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, segments: [String]) async throws -> [MemoryTree.MemoryNode] {
        var chain: [MemoryTree.MemoryNode] = []
        var parentId: String? = nil       // nil → root
        var parentSlot = scopeId          // the root's parent slot is the scope id (the server's sentinel)
        for slug in segments {
            let children = try await listChildren(apiBase: apiBase, session: session, scopeId: scopeId, parentId: parentId)
            let token = MemoryTree.slugTokenFor(contentKey: contentKey, parentSlot: parentSlot, slug: slug)
            guard let node = children.first(where: { $0.slugToken == token }) else {
                throw NSError(domain: "veyq.browse", code: 2, userInfo: [NSLocalizedDescriptionKey: "path segment not found: \(slug)"])
            }
            chain.append(node)
            parentId = node.id
            parentSlot = node.id
        }
        return chain
    }
}

private extension CharacterSet {
    static let urlQueryValueAllowed: CharacterSet = {
        var cs = CharacterSet.urlQueryAllowed
        cs.remove(charactersIn: "&=?+")
        return cs
    }()
}
