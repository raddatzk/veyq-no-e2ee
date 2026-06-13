import Foundation

/// uc.admin.invite-members-roles (Apple) — existing-user direct grant. A key-holding Tenant Owner reads
/// their own scope grant, unwraps the scope private key, re-wraps it to an existing user's published
/// identity public key (from the key directory) and assigns a role. The server stays blind.
public enum InviteMembers {
    public struct IdentityPublicKeyDTO: Codable { public let suiteId, kemPublicKey, signPublicKey: String }
    public struct DirectoryEntryDTO: Codable {
        public let userId, displayName: String
        let identityPublicKey: IdentityPublicKeyDTO
    }
    public struct MembershipSummaryDTO: Codable { public let tenantId, userId, role, status: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct GrantRequest: Codable { let recipientUserId, role: String; let wrappedScopePrivateKey: BlobDTO }

    /// Look up a user's published identity public key by email (the key directory).
    public static func lookupDirectoryEntry(apiBase: String, token: String, email: String) async throws -> DirectoryEntryDTO {
        let q = email.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? email
        var r = URLRequest(url: URL(string: "\(apiBase)/api/directory/users?email=\(q)")!)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.directory", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "directory lookup failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(DirectoryEntryDTO.self, from: data)
    }

    /// Grant an existing user (by email) access to the tenant scope + a role via a direct wrap.
    public static func grantScopeToMember(
        apiBase: String, owner: Login.UnlockedSession, tenantId: String, recipientEmail: String, role: String
    ) async throws -> MembershipSummaryDTO {
        // 1. read our own grant and recover the scope private key (raw serialized bytes)
        let myGrant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: owner.sessionToken, tenantId: tenantId)
        let ownerPkX = Data(owner.kemPublicKey.prefix(32))
        let ownerPkM = Data(owner.kemPublicKey.dropFirst(32))
        let scopePrivateBytes = try V1.asymUnwrap(
            skX: owner.identity.kemX, mlkemSeed: owner.identity.mlkemSeed, pkX: ownerPkX, pkM: ownerPkM,
            blob: Login.b64urlDecode(myGrant.wrappedScopePrivateKey.bytes), context: "scope-grant")

        // 2. look up the recipient and re-wrap the scope private key to their identity public key
        let recipient = try await lookupDirectoryEntry(apiBase: apiBase, token: owner.sessionToken, email: recipientEmail)
        let rKem = Login.b64urlDecode(recipient.identityPublicKey.kemPublicKey)
        let rewrapped = try V1.asymWrap(
            pkX: Data(rKem.prefix(32)), pkM: Data(rKem.dropFirst(32)), targetKey: scopePrivateBytes,
            context: "scope-grant", nonce: V1.randomBytes(12))

        // 3. hand the server ciphertext + the recipient + the role
        let req = GrantRequest(
            recipientUserId: recipient.userId, role: role,
            wrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(rewrapped)))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenants/\(tenantId)/grants")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(owner.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(req)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.grant", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "grant failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(MembershipSummaryDTO.self, from: data)
    }

    public struct TenantMemberDTO: Codable {
        public let userId, displayName, role, status: String
        public let identityPublicKey: IdentityPublicKeyDTO
    }
    struct TenantMemberListDTO: Codable { let members: [TenantMemberDTO] }
    struct ScopeGrantInputDTO: Codable { let userId: String; let wrappedScopePrivateKey: BlobDTO }
    struct RotateScopeRequestDTO: Codable { let scopePublicKey: KemPublicKeyDTO; let grants: [ScopeGrantInputDTO] }
    public struct RotateScopeResultDTO: Codable { public let scopeId: String; public let memberCount: Int }

    /// List a tenant's members with their published identity public keys (for re-wrapping a rotated scope).
    public static func listTenantMembers(apiBase: String, token: String, tenantId: String) async throws -> [TenantMemberDTO] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenants/\(tenantId)/members")!)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.members", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "list members failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(TenantMemberListDTO.self, from: data).members
    }

    /// Rotate the tenant scope (eventual layer of AD-24): mint a NEW scope keypair and re-wrap it to every
    /// active member, so a previously-removed member loses key access to future content.
    public static func rotateScope(apiBase: String, owner: Login.UnlockedSession, tenantId: String) async throws -> RotateScopeResultDTO {
        let members = try await listTenantMembers(apiBase: apiBase, token: owner.sessionToken, tenantId: tenantId).filter { $0.status == "ACTIVE" }
        let newScope = try V1.generateIdentity()
        let newScopePrivate = V1.serializeIdentityPrivate(newScope)
        let grants = try members.map { m -> ScopeGrantInputDTO in
            let kem = Login.b64urlDecode(m.identityPublicKey.kemPublicKey)
            let wrapped = try V1.asymWrap(
                pkX: Data(kem.prefix(32)), pkM: Data(kem.dropFirst(32)), targetKey: newScopePrivate, context: "scope-grant", nonce: V1.randomBytes(12))
            return ScopeGrantInputDTO(userId: m.userId, wrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(wrapped)))
        }
        let req = RotateScopeRequestDTO(
            scopePublicKey: KemPublicKeyDTO(suiteId: "v1", kemPublicKey: SetupServer.b64url(newScope.pubKemX + newScope.pubKemM)), grants: grants)
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenants/\(tenantId)/scope/rotate")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(owner.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(req)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.rotate", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "rotate scope failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(RotateScopeResultDTO.self, from: data)
    }

    /// Remove a member (instant layer of AD-24): revoke the member's scope grant + sessions. No crypto.
    public static func removeMember(apiBase: String, ownerToken: String, tenantId: String, userId: String) async throws -> MembershipSummaryDTO {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenants/\(tenantId)/members/\(userId)")!)
        r.httpMethod = "DELETE"
        r.setValue("Bearer \(ownerToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.removemember", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "remove member failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(MembershipSummaryDTO.self, from: data)
    }

    struct KemPublicKeyDTO: Codable { let suiteId, kemPublicKey: String }
    struct MemberInviteRequest: Codable { let role: String; let tempPublicKey: KemPublicKeyDTO; let tempWrappedScopePrivateKey: BlobDTO }
    struct OwnerInviteDTO: Codable { let inviteId, bootstrapSecret, expiresAt: String }
    public struct MemberInvite { public let inviteId, bootstrapSecret, tempPrivateKey: String }

    /// Issue a temp-wrap invite for a NEW user. The owner unwraps their own scope private key, generates a
    /// one-time temp keypair, and wraps the scope to the temp public key. The temp private key is returned
    /// to ride the invite URL fragment; the server stores only the temp public key + ciphertext.
    public static func issueMemberInvite(
        apiBase: String, owner: Login.UnlockedSession, tenantId: String, role: String
    ) async throws -> MemberInvite {
        let myGrant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: owner.sessionToken, tenantId: tenantId)
        let ownerPkX = Data(owner.kemPublicKey.prefix(32))
        let ownerPkM = Data(owner.kemPublicKey.dropFirst(32))
        let scopePrivateBytes = try V1.asymUnwrap(
            skX: owner.identity.kemX, mlkemSeed: owner.identity.mlkemSeed, pkX: ownerPkX, pkM: ownerPkM,
            blob: Login.b64urlDecode(myGrant.wrappedScopePrivateKey.bytes), context: "scope-grant")

        let temp = try V1.generateIdentity()
        let tempWrapped = try V1.asymWrap(
            pkX: temp.pubKemX, pkM: temp.pubKemM, targetKey: scopePrivateBytes, context: "scope-grant", nonce: V1.randomBytes(12))

        let req = MemberInviteRequest(
            role: role,
            tempPublicKey: KemPublicKeyDTO(suiteId: "v1", kemPublicKey: SetupServer.b64url(temp.pubKemX + temp.pubKemM)),
            tempWrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(tempWrapped)))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenants/\(tenantId)/member-invites")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(owner.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(req)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.memberinvite", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "member-invite failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        let body = try JSONDecoder().decode(OwnerInviteDTO.self, from: data)
        return MemberInvite(
            inviteId: body.inviteId, bootstrapSecret: body.bootstrapSecret,
            tempPrivateKey: SetupServer.b64url(V1.serializeIdentityPrivate(temp)))
    }

    /// Register a NEW account from a temp-wrap member invite: unwrap the scope with the temp private key
    /// (from the URL fragment), re-wrap it to the new identity, register onto the existing scope.
    public static func registerViaMemberInvite(
        apiBase: String, inviteId: String, bootstrapSecret: String, tempPrivateKeyB64: String,
        email: String, displayName: String, password: String
    ) async throws -> RegisterViaInvitation.RegisterResult {
        let meta = try await RegisterViaInvitation.getInviteMetadata(apiBase: apiBase, inviteId: inviteId)
        guard let tempPub = meta.tempPublicKey, let tempWrapped = meta.tempWrappedScopePrivateKey, let scopePub = meta.scopePublicKey else {
            throw NSError(domain: "veyq.memberinvite", code: 2, userInfo: [NSLocalizedDescriptionKey: "not a temp-wrap member invite"])
        }
        let tempPriv = V1.deserializeIdentityPrivate(Login.b64urlDecode(tempPrivateKeyB64))
        let tempKem = Login.b64urlDecode(tempPub.kemPublicKey)
        let scopePrivateBytes = try V1.asymUnwrap(
            skX: tempPriv.kemX, mlkemSeed: tempPriv.mlkemSeed, pkX: Data(tempKem.prefix(32)), pkM: Data(tempKem.dropFirst(32)),
            blob: Login.b64urlDecode(tempWrapped.bytes), context: "scope-grant")
        let scopePublicKey = scopePub.kemPublicKey
        return try await RegisterViaInvitation.registerWithScope(
            apiBase: apiBase, inviteId: inviteId, bootstrapSecret: bootstrapSecret, email: email, displayName: displayName, password: password
        ) { idX, idM in
            (scopePublicKey, SetupServer.b64url(try V1.asymWrap(pkX: idX, pkM: idM, targetKey: scopePrivateBytes, context: "scope-grant", nonce: V1.randomBytes(12))))
        }
    }
}
