import Foundation

/// uc.memory.create-tree (Apple) — build the encrypted Memory tree: Folders, Memories and Chapters under a
/// scope. The name is sealed under the scope content key; the slug becomes a blinded slug token (AD-26) so
/// the server enforces per-parent uniqueness without seeing the name. Server holds only structure + ciphertext.
public enum MemoryTree {
    struct BlobDTO: Codable { let suiteId, bytes: String }
    public struct MemoryNode: Codable {
        public let id: String
        public let parentId: String?
        public let scopeId, type, slugToken: String
        public let orderIndex: Int
        let sealedContent: BlobDTO
        public let createdAt: String
    }
    struct NodeListDTO: Codable { let nodes: [MemoryNode] }
    struct CreateNodeRequest: Codable {
        let parentId: String?
        let type, slugToken: String
        let orderIndex: Int
        let sealedContent: BlobDTO
    }

    /// Create one node: the name is sealed under the content key, the slug becomes a blinded token.
    public static func createNode(
        apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data,
        type: String, name: String, slug: String, orderIndex: Int, parentId: String? = nil
    ) async throws -> MemoryNode {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: Data(name.precomposedStringWithCanonicalMapping.utf8), aad: Data()))
        let body = CreateNodeRequest(
            parentId: parentId, type: type, slugToken: SetupServer.b64url(V1.slugToken(contentKey: contentKey, slug: slug)),
            orderIndex: orderIndex, sealedContent: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(sealed)))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(body)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.tree", code: 1, userInfo: [NSLocalizedDescriptionKey: "create node failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(MemoryNode.self, from: data)
    }

    /// List the scope's node graph (flat). The client reconstructs the tree + decrypts names locally.
    public static func listNodes(apiBase: String, session: Login.UnlockedSession, scopeId: String) async throws -> [MemoryNode] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.tree", code: 2, userInfo: [NSLocalizedDescriptionKey: "list nodes failed"])
        }
        return try JSONDecoder().decode(NodeListDTO.self, from: data).nodes
    }

    /// Decrypt a node's sealed name with the scope content key.
    public static func decodeNodeName(contentKey: Data, node: MemoryNode) throws -> String {
        let sealed = Login.b64urlDecode(node.sealedContent.bytes)
        let pt = try V1.aeadOpen(key: contentKey, nonce: Data(sealed.prefix(12)), aead: Data(sealed.dropFirst(12)), aad: Data())
        return String(data: pt, encoding: .utf8) ?? ""
    }

    /// The blinded slug token for a slug under a scope's content key (matches what the server stored).
    public static func slugTokenFor(contentKey: Data, slug: String) -> String {
        SetupServer.b64url(V1.slugToken(contentKey: contentKey, slug: slug))
    }
}
