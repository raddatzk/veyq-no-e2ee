import Foundation

/// uc.admin.invite-members-roles (Apple) — member management for a tenant: temp-wrap invites for new
/// people (email / white-label link), listing + rotating the scope, removing members and changing roles.
/// Adding people is invite-only — there is no directory lookup of arbitrary emails. The server stays blind.
public enum InviteMembers {
    public struct IdentityPublicKeyDTO: Codable { public let suiteId, kemPublicKey, signPublicKey: String }
    public struct MembershipSummaryDTO: Codable { public let tenantId, userId, role, status: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }

    public struct TenantMemberDTO: Codable {
        public let userId, displayName, role, status: String
        public let identityPublicKey: IdentityPublicKeyDTO
    }
    struct TenantMemberListDTO: Codable { let members: [TenantMemberDTO] }
    struct ScopeGrantInputDTO: Codable { let userId: String; let wrappedScopePrivateKey: BlobDTO }
    struct ScopeItemResealDTO: Codable { let id: String; let sealedContent: BlobDTO }
    struct ScopeNodeResealDTO: Codable { let id: String; let slugToken: String; let sealedContent: BlobDTO }
    struct RotateScopeRequestDTO: Codable {
        let scopePublicKey: KemPublicKeyDTO; let grants: [ScopeGrantInputDTO]
        let wrappedContentKey: BlobDTO?; let reSealedItems: [ScopeItemResealDTO]?; let reSealedNodes: [ScopeNodeResealDTO]?
    }
    struct ContentKeyDTO: Codable { let wrappedContentKey: BlobDTO }
    struct RotateItemDTO: Codable { let id: String; let sealedContent: BlobDTO }
    struct RotateItemListDTO: Codable { let items: [RotateItemDTO] }
    public struct RotateScopeResultDTO: Codable { public let scopeId: String; public let memberCount: Int }

    /// List a tenant's members with their published identity public keys (for re-wrapping a rotated scope).
    public static func listTenantMembers(apiBase: String, token: String, tenantId: String) async throws -> [TenantMemberDTO] {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant/members")!)
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

        // Forward secrecy: if the scope has content, mint a FRESH content key (not a re-wrap) and re-seal
        // every item under it. A removed member who cached the OLD content key cannot read content written
        // after the rotation, and the old ciphertext is overwritten — so the cached key opens nothing.
        let myGrant = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: owner.sessionToken, tenantId: tenantId)
        var wrappedContentKey: BlobDTO? = nil
        var reSealedItems: [ScopeItemResealDTO]? = nil
        var reSealedNodes: [ScopeNodeResealDTO]? = nil
        var ckReq = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(myGrant.scopeId)/content-key")!)
        ckReq.setValue("Bearer \(owner.sessionToken)", forHTTPHeaderField: "Authorization")
        let (ckData, ckResp) = try await URLSession.shared.data(for: ckReq)
        let ckCode = (ckResp as? HTTPURLResponse)?.statusCode ?? 0
        if ckCode == 200 {
            let oldScopePriv = try Tenant.openScopeGrant(grant: myGrant, identity: owner.identity, identityKemPublicKey: owner.kemPublicKey)
            let oldKem = Login.b64urlDecode(myGrant.scopePublicKey.kemPublicKey)
            let oldCk = try V1.asymUnwrap(
                skX: oldScopePriv.kemX, mlkemSeed: oldScopePriv.mlkemSeed, pkX: Data(oldKem.prefix(32)), pkM: Data(oldKem.dropFirst(32)),
                blob: Login.b64urlDecode(try JSONDecoder().decode(ContentKeyDTO.self, from: ckData).wrappedContentKey.bytes), context: "content-key")
            let newCk = V1.randomBytes(32)
            let rewrapped = try V1.asymWrap(pkX: newScope.pubKemX, pkM: newScope.pubKemM, targetKey: newCk, context: "content-key", nonce: V1.randomBytes(12))
            wrappedContentKey = BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(rewrapped))

            var itemsReq = URLRequest(url: URL(string: "\(apiBase)/api/scopes/\(myGrant.scopeId)/items")!)
            itemsReq.setValue("Bearer \(owner.sessionToken)", forHTTPHeaderField: "Authorization")
            let (itemsData, itemsResp) = try await URLSession.shared.data(for: itemsReq)
            guard (itemsResp as? HTTPURLResponse)?.statusCode == 200 else {
                throw NSError(domain: "veyq.rotate", code: 4, userInfo: [NSLocalizedDescriptionKey: "item list failed"])
            }
            reSealedItems = try JSONDecoder().decode(RotateItemListDTO.self, from: itemsData).items.map { it in
                let sealed = Login.b64urlDecode(it.sealedContent.bytes)
                let pt = try V1.aeadOpen(key: oldCk, nonce: Data(sealed.prefix(12)), aead: Data(sealed.dropFirst(12)), aad: Data())
                let nonce = V1.randomBytes(12)
                let resealed = nonce + (try V1.aeadSeal(key: newCk, nonce: nonce, plaintext: pt, aad: Data()))
                return ScopeItemResealDTO(id: it.id, sealedContent: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(resealed)))
            }

            // Memory-tree nodes are sealed under the content key too — re-seal each + recompute its slug token.
            let scopeNodes = try await MemoryTree.listNodes(apiBase: apiBase, session: owner, scopeId: myGrant.scopeId)
            reSealedNodes = try scopeNodes.map { n in
                let content = try MemoryTree.decodeNode(contentKey: oldCk, node: n)
                let resealed = try MemoryTree.resealNode(contentKey: newCk, content: content)
                let parentSlot = n.parentId ?? myGrant.scopeId // root node → the scope id (the server's parent_id sentinel)
                return ScopeNodeResealDTO(
                    id: n.id, slugToken: MemoryTree.slugTokenFor(contentKey: newCk, parentSlot: parentSlot, slug: content.slug),
                    sealedContent: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(resealed)))
            }
        } else if ckCode != 404 {
            throw NSError(domain: "veyq.rotate", code: 3, userInfo: [NSLocalizedDescriptionKey: "content-key fetch failed: \(ckCode)"])
        }

        let req = RotateScopeRequestDTO(
            scopePublicKey: KemPublicKeyDTO(suiteId: "v1", kemPublicKey: SetupServer.b64url(newScope.pubKemX + newScope.pubKemM)), grants: grants,
            wrappedContentKey: wrappedContentKey, reSealedItems: reSealedItems, reSealedNodes: reSealedNodes)
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant/scope/rotate")!)
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

    /// Change a member's role (membership/settings authority only — not content access, ADR 0005). No crypto.
    public static func changeMemberRole(apiBase: String, ownerToken: String, tenantId: String, userId: String, role: String) async throws -> MembershipSummaryDTO {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant/members/\(userId)/role")!)
        r.httpMethod = "PUT"
        r.setValue("Bearer \(ownerToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(["role": role])
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.role", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "change role failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(MembershipSummaryDTO.self, from: data)
    }

    /// Remove a member (instant layer of AD-24): revoke the member's scope grant + sessions. No crypto.
    public static func removeMember(apiBase: String, ownerToken: String, tenantId: String, userId: String) async throws -> MembershipSummaryDTO {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant/members/\(userId)")!)
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
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant/member-invites")!)
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
        guard let scopePub = meta.scopePublicKey else {
            throw NSError(domain: "veyq.memberinvite", code: 2, userInfo: [NSLocalizedDescriptionKey: "not a temp-wrap member invite"])
        }
        // the temp-wrapped grant is gated behind the bootstrap secret (not in public metadata)
        let material = try await RegisterViaInvitation.getInviteMaterial(apiBase: apiBase, inviteId: inviteId, bootstrapSecret: bootstrapSecret)
        let tempPriv = V1.deserializeIdentityPrivate(Login.b64urlDecode(tempPrivateKeyB64))
        let tempKem = Login.b64urlDecode(material.tempPublicKey.kemPublicKey)
        let scopePrivateBytes = try V1.asymUnwrap(
            skX: tempPriv.kemX, mlkemSeed: tempPriv.mlkemSeed, pkX: Data(tempKem.prefix(32)), pkM: Data(tempKem.dropFirst(32)),
            blob: Login.b64urlDecode(material.tempWrappedScopePrivateKey.bytes), context: "scope-grant")
        let scopePublicKey = scopePub.kemPublicKey
        return try await RegisterViaInvitation.registerWithScope(
            apiBase: apiBase, inviteId: inviteId, bootstrapSecret: bootstrapSecret, email: email, displayName: displayName, password: password
        ) { idX, idM in
            (scopePublicKey, SetupServer.b64url(try V1.asymWrap(pkX: idX, pkM: idM, targetKey: scopePrivateBytes, context: "scope-grant", nonce: V1.randomBytes(12))))
        }
    }

    // A passphrase that protects an invite's temp key is folded into a KEK via Argon2id. The params are NOT
    // carried in the link, so they MUST equal the web ARGON params (t=3, m=32768, p=1) or a link minted on one
    // platform won't open on another. The temp key is sym-wrapped under it; the link carries only the
    // ciphertext + salt, so the email relay can't resolve it without the passphrase.
    static let inviteArgon = V1.ArgonParams(t: 3, m: 32768, p: 1, dkLen: 32)
    static let tempKeyContext = "invite-temp-key"

    /// Encrypt a temp private key (b64) under a passphrase → the `ek`/`ps` link fields (operator-blind invite).
    public static func protectTempKey(tempPrivateKeyB64: String, passphrase: String) throws -> (encTempKey: String, pwSalt: String) {
        let salt = V1.randomBytes(16)
        let kek = try V1.kekRecovery(recoveryCode: Data(passphrase.precomposedStringWithCanonicalMapping.utf8), salt: salt, params: inviteArgon)
        let enc = try V1.symWrap(kek: kek, targetKey: Login.b64urlDecode(tempPrivateKeyB64), context: tempKeyContext, nonce: V1.randomBytes(12))
        return (SetupServer.b64url(enc), SetupServer.b64url(salt))
    }

    /// Decrypt a passphrase-protected temp key back to its b64 form (wrong passphrase → throws on AEAD failure).
    public static func unprotectTempKey(encTempKey: String, pwSalt: String, passphrase: String) throws -> String {
        let kek = try V1.kekRecovery(recoveryCode: Data(passphrase.precomposedStringWithCanonicalMapping.utf8), salt: Login.b64urlDecode(pwSalt), params: inviteArgon)
        return SetupServer.b64url(try V1.symUnwrap(kek: kek, blob: Login.b64urlDecode(encTempKey), context: tempKeyContext))
    }

    struct EmailInviteRequest: Codable { let email, inviteId, link: String }

    /// Have the server email an already-assembled invite link to an address. The link is built client-side,
    /// so the mode decides what the server/mail relay sees (pragmatic = temp key in the clear; passphrase =
    /// only the encrypted temp key). The server validates ownership + that the link targets this instance.
    public static func emailMemberInvite(apiBase: String, token: String, tenantId: String, email: String, inviteId: String, link: String) async throws {
        let req = EmailInviteRequest(email: email.trimmingCharacters(in: .whitespacesAndNewlines), inviteId: inviteId, link: link)
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant/member-invites/email")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(req)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 204 else {
            throw NSError(domain: "veyq.emailinvite", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "email invite failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
    }

    struct AcceptRequest: Codable { let bootstrapSecret: String; let wrappedScopePrivateKey: BlobDTO }

    /// Accept a temp-wrap member invite onto an EXISTING (already logged-in) account — the "confirm" path.
    /// Unwrap with the temp key, re-wrap to the caller's own identity; the server adds grant + membership.
    public static func acceptMemberInvite(
        apiBase: String, session: Login.UnlockedSession, inviteId: String, bootstrapSecret: String, tempPrivateKeyB64: String
    ) async throws -> MembershipSummaryDTO {
        // the temp-wrapped grant is gated behind the bootstrap secret (not in public metadata)
        let material = try await RegisterViaInvitation.getInviteMaterial(apiBase: apiBase, inviteId: inviteId, bootstrapSecret: bootstrapSecret)
        let tempPriv = V1.deserializeIdentityPrivate(Login.b64urlDecode(tempPrivateKeyB64))
        let tempKem = Login.b64urlDecode(material.tempPublicKey.kemPublicKey)
        let scopePrivateBytes = try V1.asymUnwrap(
            skX: tempPriv.kemX, mlkemSeed: tempPriv.mlkemSeed, pkX: Data(tempKem.prefix(32)), pkM: Data(tempKem.dropFirst(32)),
            blob: Login.b64urlDecode(material.tempWrappedScopePrivateKey.bytes), context: "scope-grant")
        let rewrapped = try V1.asymWrap(
            pkX: Data(session.kemPublicKey.prefix(32)), pkM: Data(session.kemPublicKey.dropFirst(32)),
            targetKey: scopePrivateBytes, context: "scope-grant", nonce: V1.randomBytes(12))

        let req = AcceptRequest(bootstrapSecret: bootstrapSecret, wrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(rewrapped)))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant-invites/\(inviteId)/accept")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(req)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.accept", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "accept failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(MembershipSummaryDTO.self, from: data)
    }
}
