import Foundation

/// uc.spaces.create-group (Apple) — a Group is a collaborative sub-space within a tenant (Tenant > Group >
/// Personal). Create one by minting a GROUP_ROOT scope, self-granting it, provisioning a content key, and
/// sealing the NAME under that content key (operator-blind). Members are added by re-wrapping the group
/// scope key to their identity. The server stays blind to the name and the content.
public enum Groups {
    struct KemDTO: Codable { let suiteId, kemPublicKey: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }

    public struct GroupSummary: Codable, Sendable {
        public let groupId, scopeId, tenantId, role: String
        let scopePublicKey: KemDTO
        let sealedName: BlobDTO
    }
    public struct GroupMember: Codable, Sendable, Identifiable {
        public let userId, displayName, role: String
        public var id: String { userId }
    }
    struct GroupMemberListDTO: Codable { let members: [GroupMember] }
    struct GrantDTO: Codable { let scopeId: String; let scopePublicKey: KemDTO; let wrappedScopePrivateKey: BlobDTO }

    struct CreateGroupReq: Codable { let scopePublicKey: KemDTO; let wrappedScopePrivateKey, contentKey, sealedName: BlobDTO }
    struct AddMemberReq: Codable { let userId: String; let wrappedScopePrivateKey: BlobDTO; let role: String }
    struct SetRoleReq: Codable { let role: String }

    private static func send(_ apiBase: String, _ path: String, _ token: String, method: String = "GET", body: Data? = nil) async throws -> (Data, Int) {
        var r = URLRequest(url: URL(string: "\(apiBase)\(path)")!)
        r.httpMethod = method
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body { r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.httpBody = body }
        let (data, resp) = try await URLSession.shared.data(for: r)
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }
    private static func err(_ m: String) -> NSError { NSError(domain: "veyq.groups", code: 1, userInfo: [NSLocalizedDescriptionKey: m]) }

    /// Decrypt a group's sealed name with its content key.
    public static func openSealedName(contentKey: Data, sealedB64: String) throws -> String {
        let sealed = Login.b64urlDecode(sealedB64)
        let pt = try V1.aeadOpen(key: contentKey, nonce: Data(sealed.prefix(12)), aead: Data(sealed.dropFirst(12)), aad: Data())
        return String(decoding: pt, as: UTF8.self)
    }

    /// Create a group within a tenant: mint the scope, self-grant, provision the content key, seal the name.
    @discardableResult
    public static func createGroup(apiBase: String, session: Login.UnlockedSession, tenantId: String, name: String) async throws -> GroupSummary {
        let idKem = session.kemPublicKey
        let scope = try V1.generateIdentity()
        let scopePublic = scope.pubKemX + scope.pubKemM
        let grant = try V1.asymWrap(pkX: Data(idKem.prefix(32)), pkM: Data(idKem.dropFirst(32)), targetKey: V1.serializeIdentityPrivate(scope), context: "scope-grant", nonce: V1.randomBytes(12))
        let ck = V1.randomBytes(32)
        let wrappedCk = try V1.asymWrap(pkX: scope.pubKemX, pkM: scope.pubKemM, targetKey: ck, context: "content-key", nonce: V1.randomBytes(12))
        let nn = V1.randomBytes(12)
        let sealedName = nn + (try V1.aeadSeal(key: ck, nonce: nn, plaintext: Data(name.precomposedStringWithCanonicalMapping.utf8), aad: Data()))

        let req = CreateGroupReq(
            scopePublicKey: KemDTO(suiteId: "v1", kemPublicKey: SetupServer.b64url(scopePublic)),
            wrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(grant)),
            contentKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(wrappedCk)),
            sealedName: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(sealedName)))
        let (data, code) = try await send(apiBase, "/api/groups", session.sessionToken, method: "POST", body: try JSONEncoder().encode(req))
        guard code == 200 else { throw err("create group failed: \(String(data: data, encoding: .utf8) ?? "")") }
        return try JSONDecoder().decode(GroupSummary.self, from: data)
    }

    public static func listGroupMembers(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String) async throws -> [GroupMember] {
        let (data, code) = try await send(apiBase, "/api/groups/\(scopeId)/members", session.sessionToken)
        guard code == 200 else { throw err("list group members failed: \(String(data: data, encoding: .utf8) ?? "")") }
        return try JSONDecoder().decode(GroupMemberListDTO.self, from: data).members
    }

    /// Add a tenant member to a group (GroupOwner only). Unwraps the caller's OWN group grant to the raw
    /// scope private bytes, then re-wraps them to the target's identity — the server never sees the key.
    @discardableResult
    public static func addGroupMember(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String, userId: String, targetKemPublicKeyB64: String, role: String = "GroupMember") async throws -> GroupMember {
        let (gData, gCode) = try await send(apiBase, "/api/scopes/\(scopeId)/grants/me", session.sessionToken)
        guard gCode == 200 else { throw err("group grant failed: \(String(data: gData, encoding: .utf8) ?? "")") }
        let g = try JSONDecoder().decode(GrantDTO.self, from: gData)
        let myKem = session.kemPublicKey
        let scopePrivBytes = try V1.asymUnwrap(skX: session.identity.kemX, mlkemSeed: session.identity.mlkemSeed, pkX: Data(myKem.prefix(32)), pkM: Data(myKem.dropFirst(32)), blob: Login.b64urlDecode(g.wrappedScopePrivateKey.bytes), context: "scope-grant")
        let tKem = Login.b64urlDecode(targetKemPublicKeyB64)
        let wrapped = try V1.asymWrap(pkX: Data(tKem.prefix(32)), pkM: Data(tKem.dropFirst(32)), targetKey: scopePrivBytes, context: "scope-grant", nonce: V1.randomBytes(12))

        let req = AddMemberReq(userId: userId, wrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(wrapped)), role: role)
        let (data, code) = try await send(apiBase, "/api/groups/\(scopeId)/members", session.sessionToken, method: "POST", body: try JSONEncoder().encode(req))
        guard code == 200 else { throw err("add group member failed: \(String(data: data, encoding: .utf8) ?? "")") }
        return try JSONDecoder().decode(GroupMember.self, from: data)
    }

    @discardableResult
    public static func setGroupMemberRole(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String, userId: String, role: String) async throws -> GroupMember {
        let (data, code) = try await send(apiBase, "/api/groups/\(scopeId)/members/\(userId)/role", session.sessionToken, method: "PUT", body: try JSONEncoder().encode(SetRoleReq(role: role)))
        guard code == 200 else { throw err("set group role failed: \(String(data: data, encoding: .utf8) ?? "")") }
        return try JSONDecoder().decode(GroupMember.self, from: data)
    }

    public static func removeGroupMember(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String, userId: String) async throws {
        let (data, code) = try await send(apiBase, "/api/groups/\(scopeId)/members/\(userId)", session.sessionToken, method: "DELETE")
        guard code == 204 else { throw err("remove group member failed: \(String(data: data, encoding: .utf8) ?? "")") }
    }

    public static func deleteGroup(apiBase: String, session: Login.UnlockedSession, tenantId: String, scopeId: String) async throws {
        let (data, code) = try await send(apiBase, "/api/groups/\(scopeId)", session.sessionToken, method: "DELETE")
        guard code == 204 else { throw err("delete group failed: \(String(data: data, encoding: .utf8) ?? "")") }
    }

    // The other deletes (named-resource confirm-by-name lives in the UI) — thin DELETE calls.
    public static func deleteNode(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String) async throws {
        let (data, code) = try await send(apiBase, "/api/scopes/\(scopeId)/nodes/\(nodeId)", session.sessionToken, method: "DELETE")
        guard code == 204 else { throw err("delete node failed: \(String(data: data, encoding: .utf8) ?? "")") }
    }
    public static func deleteMedia(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String, mediaId: String) async throws {
        let (data, code) = try await send(apiBase, "/api/scopes/\(scopeId)/nodes/\(nodeId)/media/\(mediaId)", session.sessionToken, method: "DELETE")
        guard code == 204 else { throw err("delete media failed: \(String(data: data, encoding: .utf8) ?? "")") }
    }
    /// Schedule a tenant for deletion after a grace period (owner only). The tenant stays usable until the
    /// deadline (members secure their data), then the server GC purges it. Idempotent.
    public static func deleteTenant(apiBase: String, session: Login.UnlockedSession, tenantId: String) async throws {
        let (data, code) = try await send(apiBase, "/api/tenant", session.sessionToken, method: "DELETE")
        guard code == 204 else { throw err("delete tenant failed: \(String(data: data, encoding: .utf8) ?? "")") }
    }
    /// Cancel a scheduled tenant deletion — clears the deadline (owner only).
    public static func cancelTenantDeletion(apiBase: String, session: Login.UnlockedSession, tenantId: String) async throws {
        let (data, code) = try await send(apiBase, "/api/tenant/cancel-deletion", session.sessionToken, method: "POST")
        guard code == 204 else { throw err("cancel tenant deletion failed: \(String(data: data, encoding: .utf8) ?? "")") }
    }
}
