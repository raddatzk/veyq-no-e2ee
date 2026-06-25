import Foundation

/// uc.memory.curate (Apple) — mutate a node via PATCH /nodes/{id}: rename (re-seal name + recomputed
/// per-parent slug token, retrying on collision), move (slug token recomputed under the new parent), set
/// cover, reorder. The server stays blind to names.
public enum Curate {
    private struct CurateError: Error { let status: Int; let body: String; var slugClash: Bool { status == 409 && body.contains("SLUG_EXISTS") } }

    public static func renameNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, node: MemoryTree.MemoryNode, newName: String) async throws -> MemoryTree.MemoryNode {
        let slot = node.parentId ?? scopeId
        let name = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = slugify(name)
        for attempt in 0 ..< 25 {
            let slug = attempt == 0 ? base : "\(base)-\(attempt + 1)"
            let sealed = try MemoryTree.resealNode(contentKey: contentKey, content: MemoryTree.NodeContent(name: name, slug: slug))
            let body: [String: Any] = [
                "sealedContent": ["suiteId": "v1", "bytes": SetupServer.b64url(sealed)],
                "slugToken": SetupServer.b64url(V1.slugToken(contentKey: contentKey, parentSlot: slot, slug: slug)),
            ]
            do { return try await patch(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: node.id, body: body) }
            catch let e as CurateError where e.slugClash { continue }
        }
        throw NSError(domain: "veyq.curate", code: 1, userInfo: [NSLocalizedDescriptionKey: "rename slug conflict"])
    }

    public static func moveNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, node: MemoryTree.MemoryNode, newParentId: String) async throws -> MemoryTree.MemoryNode {
        let name = try MemoryTree.decodeNode(contentKey: contentKey, node: node).name
        let slot = newParentId == "root" ? scopeId : newParentId
        let base = slugify(name)
        for attempt in 0 ..< 25 {
            let slug = attempt == 0 ? base : "\(base)-\(attempt + 1)"
            var body: [String: Any] = ["newParentId": newParentId, "slugToken": SetupServer.b64url(V1.slugToken(contentKey: contentKey, parentSlot: slot, slug: slug))]
            if attempt > 0 {
                let sealed = try MemoryTree.resealNode(contentKey: contentKey, content: MemoryTree.NodeContent(name: name, slug: slug))
                body["sealedContent"] = ["suiteId": "v1", "bytes": SetupServer.b64url(sealed)]
            }
            do { return try await patch(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: node.id, body: body) }
            catch let e as CurateError where e.slugClash { continue }
        }
        throw NSError(domain: "veyq.curate", code: 2, userInfo: [NSLocalizedDescriptionKey: "move slug conflict"])
    }

    public static func setCover(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, coverMediaId: String) async throws -> MemoryTree.MemoryNode {
        try await patch(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, body: ["coverMediaId": coverMediaId])
    }

    public static func reorderNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, orderIndex: Int) async throws -> MemoryTree.MemoryNode {
        try await patch(apiBase: apiBase, session: session, scopeId: scopeId, nodeId: nodeId, body: ["orderIndex": orderIndex])
    }

    private static func patch(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, body: [String: Any]) async throws -> MemoryTree.MemoryNode {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)")!)
        r.httpMethod = "PATCH"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, resp) = try await URLSession.shared.data(for: r)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw CurateError(status: status, body: String(data: data, encoding: .utf8) ?? "") }
        return try JSONDecoder().decode(MemoryTree.MemoryNode.self, from: data)
    }

    static func slugify(_ name: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789")
        let folded = name.folding(options: .diacriticInsensitive, locale: nil).lowercased()
        let mapped = String(folded.map { allowed.contains($0) ? $0 : "-" })
        let collapsed = mapped.split(separator: "-").joined(separator: "-")
        return collapsed.isEmpty ? "item" : collapsed
    }
}
