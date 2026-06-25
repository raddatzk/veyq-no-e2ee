import Foundation

/// uc.people.manage-participants (Apple, ADR 0032 operator-blind) — Memory participants are sealed People
/// metadata. The server stores an opaque payload {displayName, linkedUserId?}; linked-vs-anonymous semantics
/// are decrypted locally. Wire/seal format matches the web + the participant backend slice (nonce || AEAD over
/// the JSON payload, AAD empty), so entries are readable across all clients.
public enum Participants {
    public struct Item: Identifiable, Sendable {
        public let id: String
        public let displayName: String
        public let linkedUserId: String?
    }

    /// A node a linked participant appears in — drives the participant profile screen.
    public struct NodeHit: Identifiable, Sendable {
        public let id: String          // node id
        public let name: String
        public let type: String        // MEMORY | CHAPTER | FOLDER
        public let participant: Item
        public let node: MemoryTree.MemoryNode
    }

    private struct Payload: Codable { let displayName: String; let linkedUserId: String? }
    private struct Blob: Codable { let suiteId, bytes: String }
    private struct ParticipantDTO: Decodable { let id: String; let sealedPayload: Blob }
    private struct ParticipantListDTO: Decodable { let participants: [ParticipantDTO] }

    private static func seal(contentKey: Data, _ payload: Payload) throws -> [String: String] {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: try JSONEncoder().encode(payload), aad: Data()))
        return ["suiteId": "v1", "bytes": SetupServer.b64url(sealed)]
    }

    private static func toItem(contentKey: Data, _ dto: ParticipantDTO) throws -> Item {
        let bytes = Login.b64urlDecode(dto.sealedPayload.bytes)
        let pt = try V1.aeadOpen(key: contentKey, nonce: Data(bytes.prefix(12)), aead: Data(bytes.dropFirst(12)), aad: Data())
        let p = try JSONDecoder().decode(Payload.self, from: pt)
        return Item(id: dto.id, displayName: p.displayName, linkedUserId: p.linkedUserId)
    }

    /// List + decrypt a memory's participants.
    public static func list(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String) async throws -> [Item] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/participants")!)
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "list participants")
        return try JSONDecoder().decode(ParticipantListDTO.self, from: data).participants.compactMap { try? toItem(contentKey: contentKey, $0) }
    }

    /// Seal {displayName, linkedUserId?} and create a participant.
    public static func create(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, nodeId: String, displayName: String, linkedUserId: String? = nil) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/participants")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["sealedPayload": try seal(contentKey: contentKey, Payload(displayName: displayName, linkedUserId: linkedUserId))])
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "create participant")
    }

    public static func delete(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, participantId: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(scopeId)/nodes/\(nodeId)/participants/\(participantId)")!)
        r.httpMethod = "DELETE"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (_, resp) = try await URLSession.shared.data(for: r)
        try ok(resp, "delete participant", expect: [200, 204])
    }

    /// All non-folder nodes a linked participant appears in (client-side scan — the server is blind to participants).
    public static func listNodeHits(apiBase: String, session: Login.UnlockedSession, scopeId: String, contentKey: Data, linkedUserId: String) async throws -> [NodeHit] {
        let nodes = try await MemoryTree.listNodes(apiBase: apiBase, session: session, scopeId: scopeId).filter { $0.type != "FOLDER" }
        var hits: [NodeHit] = []
        for node in nodes {
            let participants = (try? await list(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, nodeId: node.id)) ?? []
            guard let match = participants.first(where: { $0.linkedUserId == linkedUserId }) else { continue }
            let name = (try? MemoryTree.decodeNodeName(contentKey: contentKey, node: node)) ?? node.id
            hits.append(NodeHit(id: node.id, name: name, type: node.type, participant: match, node: node))
        }
        return hits.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private static func ok(_ resp: URLResponse, _ what: String, expect: [Int] = [200]) throws {
        guard let http = resp as? HTTPURLResponse, expect.contains(http.statusCode) else {
            throw NSError(domain: "veyq.participants", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(what) failed"])
        }
    }
}
