import Foundation

/// uc.memory.create-tree (Apple) — build the encrypted Memory tree: Folders, Memories and Chapters under a
/// scope. The name is sealed under the scope content key; the slug becomes a blinded slug token (AD-26) so
/// the server enforces per-parent uniqueness without seeing the name. Server holds only structure + ciphertext.
public enum MemoryTree {
    struct BlobDTO: Codable, Sendable { let suiteId, bytes: String }
    public struct MemoryNode: Codable, Sendable {
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
    /// A node's decrypted metadata. The slug is sealed alongside the name so rotation can recompute the token.
    public struct NodeContent: Codable { public let name, slug: String }

    /// Create one node: the name is sealed under the content key, the slug becomes a blinded token.
    public static func createNode(
        apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data,
        type: String, name: String, slug: String, orderIndex: Int, parentId: String? = nil
    ) async throws -> MemoryNode {
        let sealed = try resealNode(contentKey: contentKey, content: NodeContent(name: name, slug: slug))
        // the slug token's parent slot is the parent node id, or the scope id at the root (the server's sentinel)
        let parentSlot = parentId ?? scopeId
        let body = CreateNodeRequest(
            parentId: parentId, type: type, slugToken: SetupServer.b64url(V1.slugToken(contentKey: contentKey, parentSlot: parentSlot, slug: slug)),
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

    /// Decrypt a node's sealed metadata ({name, slug}) with the scope content key.
    public static func decodeNode(contentKey: Data, node: MemoryNode) throws -> NodeContent {
        let sealed = Login.b64urlDecode(node.sealedContent.bytes)
        let pt = try V1.aeadOpen(key: contentKey, nonce: Data(sealed.prefix(12)), aead: Data(sealed.dropFirst(12)), aad: Data())
        return try JSONDecoder().decode(NodeContent.self, from: pt)
    }

    /// Convenience: a node's decrypted display name.
    public static func decodeNodeName(contentKey: Data, node: MemoryNode) throws -> String {
        try decodeNode(contentKey: contentKey, node: node).name
    }

    /// Re-seal a node's metadata under a content key, returning the new sealed blob (nonce || AEAD).
    public static func resealNode(contentKey: Data, content: NodeContent) throws -> Data {
        let nonce = V1.randomBytes(12)
        return nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: try JSONEncoder().encode(content), aad: Data()))
    }

    /// The blinded slug token for a slug under a given parent slot (parent node id, or scope id at the root).
    public static func slugTokenFor(contentKey: Data, parentSlot: String, slug: String) -> String {
        SetupServer.b64url(V1.slugToken(contentKey: contentKey, parentSlot: parentSlot, slug: slug))
    }
}
