import Foundation

/// uc.account.register-via-invitation (Apple) — a brand-new invitee accepts an owner invite. All key
/// material is born here; one POST creates the account, mints the tenant scope as a self grant, and
/// returns a session. The server only ever receives ciphertext + public keys (+ an auth verifier).
public enum RegisterViaInvitation {
    struct IdentityPublicKeyDTO: Codable { let suiteId, kemPublicKey, signPublicKey: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct MasterKeyWrapDTO: Codable { let unlockMethod, suiteId, wrappedKey: String; let kdfParams: String? }
    struct AuthVerifierDTO: Codable { let kind, suiteId, verifier: String }
    struct KemPublicKeyDTO: Codable { let suiteId, kemPublicKey: String }
    struct RegisterRequest: Codable {
        let bootstrapSecret, email, displayName: String
        let identityPublicKey: IdentityPublicKeyDTO
        let wrappedIdentityPrivateKey: BlobDTO
        let masterKeyWraps: [MasterKeyWrapDTO]
        let authVerifier: AuthVerifierDTO
        let recoveryAuthTag: String
        let personalScopePublicKey: KemPublicKeyDTO
        let wrappedPersonalScopePrivateKey: BlobDTO
        let scopePublicKey: KemPublicKeyDTO
        let wrappedScopePrivateKey: BlobDTO
    }
    public struct TenantInviteInfoDTO: Codable {
        public let inviteId: String
        public let tenant: Tenant.TenantSummary
        public let role, status, kind: String
        let scopePublicKey: KemPublicKeyDTO?
    }
    /// Temp-wrapped onboarding material, released only on proof of the bootstrap secret (defense in depth).
    public struct TenantInviteMaterialDTO: Codable {
        let tempPublicKey: KemPublicKeyDTO
        let tempWrappedScopePrivateKey: BlobDTO
    }
    struct GetInviteMaterialRequest: Codable { let bootstrapSecret: String }

    /// Builds the tenant scope grant for the new account, given the invitee's identity public key.
    public typealias TenantScopeProvider = (_ idPubKemX: Data, _ idPubKemM: Data) throws -> (scopePublicKey: String, wrappedScopePrivateKey: String)
    struct RegisterResponseDTO: Codable {
        let sessionToken: String
        let user: Login.UserSummaryDTO
        let tenant: Tenant.TenantSummary
    }
    public struct RegisterResult {
        public let sessionToken: String
        public let user: Login.UserSummaryDTO
        public let tenant: Tenant.TenantSummary
        public let emergencyKit: SetupServer.EmergencyKit
    }

    /// Public invite metadata — the bootstrap secret (from the URL fragment) is never sent here.
    public static func getInviteMetadata(apiBase: String, inviteId: String) async throws -> TenantInviteInfoDTO {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant-invites/\(inviteId)")!)
        r.httpMethod = "GET"
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.register", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "invite metadata failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(TenantInviteInfoDTO.self, from: data)
    }

    /// Fetch the temp-wrapped onboarding material by proving the bootstrap secret (not in public metadata).
    public static func getInviteMaterial(apiBase: String, inviteId: String, bootstrapSecret: String) async throws -> TenantInviteMaterialDTO {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/tenant-invites/\(inviteId)/material")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(GetInviteMaterialRequest(bootstrapSecret: bootstrapSecret))
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.register", code: 6,
                          userInfo: [NSLocalizedDescriptionKey: "invite material failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(TenantInviteMaterialDTO.self, from: data)
    }

    static func buildRequest(
        inviteId: String, bootstrapSecret: String, email: String, displayName: String, password: String,
        tenantScope: TenantScopeProvider
    ) throws -> (RegisterRequest, SetupServer.EmergencyKit) {
        let id = try V1.generateIdentity()
        let masterKey = V1.randomBytes(32)
        let wrappedPriv = try V1.symWrap(kek: masterKey, targetKey: V1.serializeIdentityPrivate(id), context: "identity-priv", nonce: V1.randomBytes(12))

        let secretKey = V1.randomBytes(32)
        let salt = V1.randomBytes(16)
        let kek = try V1.kekPasswordSecret(secretKey: secretKey, password: password, salt: salt, params: SetupServer.argon)
        let masterWrap = try V1.symWrap(kek: kek, targetKey: masterKey, context: "master-key", nonce: V1.randomBytes(12))

        // RECOVERY wrap: master key under the Secret Key ALONE — released only via the email-token-gated reset.
        let recSalt = V1.randomBytes(16)
        let recWrap = try V1.symWrap(kek: try V1.kekRecovery(recoveryCode: secretKey, salt: recSalt, params: SetupServer.argon), targetKey: masterKey, context: "master-key", nonce: V1.randomBytes(12))

        let authSalt = V1.randomBytes(16)
        let verifier = try V1.kekRecovery(recoveryCode: Data(password.precomposedStringWithCanonicalMapping.utf8), salt: authSalt, params: SetupServer.argon)

        // create the unshared personal scope; only this user receives a grant
        let personal = try V1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
        let personalPublic = personal.pubKemX + personal.pubKemM
        let personalGrant = try V1.asymWrap(
            pkX: id.pubKemX, pkM: id.pubKemM, targetKey: V1.serializeIdentityPrivate(personal),
            context: "personal-scope-grant", nonce: V1.randomBytes(12))

        // the tenant scope grant — minted (owner) or temp-unwrapped + re-wrapped (member)
        let scope = try tenantScope(id.pubKemX, id.pubKemM)

        let req = RegisterRequest(
            bootstrapSecret: bootstrapSecret, email: email, displayName: displayName,
            identityPublicKey: IdentityPublicKeyDTO(suiteId: "v1", kemPublicKey: SetupServer.b64url(id.pubKemX + id.pubKemM), signPublicKey: SetupServer.b64url(id.pubSign)),
            wrappedIdentityPrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(wrappedPriv)),
            masterKeyWraps: [
                MasterKeyWrapDTO(unlockMethod: "PASSWORD", suiteId: "v1", wrappedKey: SetupServer.b64url(masterWrap), kdfParams: SetupServer.b64url(V1.encodeKdfParams(SetupServer.argon, salt: salt))),
                MasterKeyWrapDTO(unlockMethod: "RECOVERY", suiteId: "v1", wrappedKey: SetupServer.b64url(recWrap), kdfParams: SetupServer.b64url(V1.encodeKdfParams(SetupServer.argon, salt: recSalt))),
            ],
            authVerifier: AuthVerifierDTO(kind: "ARGON2_PROOF", suiteId: "v1", verifier: SetupServer.b64url(authSalt + verifier)),
            // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
            recoveryAuthTag: SetupServer.b64url(V1.recoveryAuthTag(masterKey: masterKey)),
            personalScopePublicKey: KemPublicKeyDTO(suiteId: "v1", kemPublicKey: SetupServer.b64url(personalPublic)),
            wrappedPersonalScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(personalGrant)),
            scopePublicKey: KemPublicKeyDTO(suiteId: "v1", kemPublicKey: scope.scopePublicKey),
            wrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: scope.wrappedScopePrivateKey))
        return (req, SetupServer.EmergencyKit(secretKey: SetupServer.b64url(secretKey)))
    }

    /// Register from an owner invite — the empty tenant's scope keypair is minted here (self grant).
    public static func registerViaInvitation(
        apiBase: String, inviteId: String, bootstrapSecret: String, email: String, displayName: String, password: String
    ) async throws -> RegisterResult {
        try await registerWithScope(
            apiBase: apiBase, inviteId: inviteId, bootstrapSecret: bootstrapSecret, email: email, displayName: displayName, password: password
        ) { idX, idM in
            let scope = try V1.generateIdentity()
            return (SetupServer.b64url(scope.pubKemX + scope.pubKemM),
                    SetupServer.b64url(try V1.asymWrap(pkX: idX, pkM: idM, targetKey: V1.serializeIdentityPrivate(scope), context: "scope-grant", nonce: V1.randomBytes(12))))
        }
    }

    /// Core registration assembly shared by the owner-claim and temp-wrap member flows.
    public static func registerWithScope(
        apiBase: String, inviteId: String, bootstrapSecret: String, email: String, displayName: String, password: String,
        tenantScope: TenantScopeProvider
    ) async throws -> RegisterResult {
        let (reqBody, kit) = try buildRequest(
            inviteId: inviteId, bootstrapSecret: bootstrapSecret, email: email, displayName: displayName, password: password, tenantScope: tenantScope)
        var request = URLRequest(url: URL(string: "\(apiBase)/api/tenant-invites/\(inviteId)/register")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(reqBody)
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.register", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "register failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        let body = try JSONDecoder().decode(RegisterResponseDTO.self, from: data)
        return RegisterResult(sessionToken: body.sessionToken, user: body.user, tenant: body.tenant, emergencyKit: kit)
    }
}
