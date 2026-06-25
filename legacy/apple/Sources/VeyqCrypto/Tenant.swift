import Foundation

/// uc.admin.create-tenant-invite-owner (Apple) — provision a tenant shell + owner invite, and claim
/// ownership by minting the tenant scope keypair and granting it to oneself. The server stays blind.
public enum Tenant {
    static func b64urlDecode(_ s: String) -> Data { Login.b64urlDecode(s) }
    static func b64urlEncode(_ d: Data) -> String { SetupServer.b64url(d) }

    public struct TenantSummary: Codable {
        public let id, name, displayName, status: String
        public let domain: String?
    }
    struct OwnerInviteDTO: Codable { let inviteId, bootstrapSecret, expiresAt: String }
    struct KemPublicKeyDTO: Codable { let suiteId, kemPublicKey: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct MyScopeGrantDTO: Codable { let scopeId: String; let scopePublicKey: KemPublicKeyDTO; let wrappedScopePrivateKey: BlobDTO }
    struct CurrentTenantDTO: Codable { let tenantId, tenantScopeId: String }
    struct ClaimRequest: Codable { let bootstrapSecret: String; let tenantScopePublicKey: KemPublicKeyDTO; let wrappedTenantScopePrivateKey: BlobDTO }

    private static func send<T: Decodable>(_ apiBase: String, _ path: String, _ token: String, method: String, body: Data? = nil) async throws -> T {
        var r = URLRequest(url: URL(string: "\(apiBase)\(path)")!)
        r.httpMethod = method
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body { r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.httpBody = body }
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.tenant", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "\(method) \(path) failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    public static func createTenant(apiBase: String, token: String, name: String, displayName: String, domain: String?) async throws -> TenantSummary {
        // A tenant is domain-scoped (domain required + unique). The UI passes a real domain; tests/owner-claim
        // omit it → derive a unique one from the (unique) handle so the request still validates.
        let dom = (domain?.isEmpty == false) ? domain! : "\(name).local"
        let body: [String: String] = ["name": name, "displayName": displayName, "domain": dom]
        return try await send(apiBase, "/api/tenants", token, method: "POST", body: try JSONEncoder().encode(body))
    }

    static func issueOwnerInvite(apiBase: String, token: String, tenantId: String) async throws -> OwnerInviteDTO {
        try await send(apiBase, "/api/tenant/owner-invite", token, method: "POST")
    }

    static func getMyScopeGrant(apiBase: String, token: String, tenantId: String) async throws -> MyScopeGrantDTO {
        let tenant: CurrentTenantDTO = try await send(apiBase, "/api/tenant", token, method: "GET")
        if !tenantId.isEmpty && tenant.tenantId != tenantId {
            throw NSError(domain: "veyq.tenant", code: 2, userInfo: [NSLocalizedDescriptionKey: "requested tenant does not match current host"])
        }
        return try await send(apiBase, "/api/scopes/\(tenant.tenantScopeId)/grants/me", token, method: "GET")
    }

    public static func claimTenantOwnership(
        apiBase: String, token: String, inviteId: String, bootstrapSecret: String, identityKemPublicKey: Data
    ) async throws -> TenantSummary {
        let pkX = Data(identityKemPublicKey.prefix(32))
        let pkM = Data(identityKemPublicKey.dropFirst(32))
        let scope = try V1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
        let scopePublic = scope.pubKemX + scope.pubKemM
        let scopePrivate = V1.serializeIdentityPrivate(scope)
        let grant = try V1.asymWrap(pkX: pkX, pkM: pkM, targetKey: scopePrivate, context: "scope-grant", nonce: V1.randomBytes(12))
        let req = ClaimRequest(
            bootstrapSecret: bootstrapSecret,
            tenantScopePublicKey: KemPublicKeyDTO(suiteId: "v1", kemPublicKey: b64urlEncode(scopePublic)),
            wrappedTenantScopePrivateKey: BlobDTO(suiteId: "v1", bytes: b64urlEncode(grant)))
        return try await send(apiBase, "/api/tenant-invites/\(inviteId)/claim", token, method: "POST", body: try JSONEncoder().encode(req))
    }

    /// Create a shared group the caller owns: a tenant + a self owner-claim (mints + self-grants the
    /// scope). Mirrors the web `createGroup`; retries on handle conflicts. The server only sees the handle,
    /// display name, scope public key and the wrapped grant — never plaintext content.
    public static func createGroup(apiBase: String, token: String, identityKemPublicKey: Data, displayName: String, domain: String? = nil) async throws -> TenantSummary {
        let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = slugify(name)
        for attempt in 0 ..< 25 {
            let handle = attempt == 0 ? base : "\(base)-\(attempt + 1)"
            do {
                let tenant = try await createTenant(apiBase: apiBase, token: token, name: handle, displayName: name, domain: domain)
                let invite = try await issueOwnerInvite(apiBase: apiBase, token: token, tenantId: tenant.id)
                _ = try await claimTenantOwnership(apiBase: apiBase, token: token, inviteId: invite.inviteId, bootstrapSecret: invite.bootstrapSecret, identityKemPublicKey: identityKemPublicKey)
                return tenant
            } catch {
                let msg = "\(error)".lowercased()
                if !msg.contains("409") && !msg.contains("exists") { throw error }
            }
        }
        throw NSError(domain: "veyq.groups", code: 1, userInfo: [NSLocalizedDescriptionKey: "could not create group (handle conflict)"])
    }

    private static func slugify(_ name: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789")
        let folded = name.folding(options: .diacriticInsensitive, locale: nil).lowercased()
        let mapped = String(folded.map { allowed.contains($0) ? $0 : "-" })
        let collapsed = mapped.split(separator: "-").joined(separator: "-")
        return collapsed.isEmpty ? "group" : collapsed
    }

    /// Unwrap a scope grant with the holder's identity key → the scope private bundle.
    static func openScopeGrant(grant: MyScopeGrantDTO, identity: V1.IdentityPrivate, identityKemPublicKey: Data) throws -> V1.IdentityPrivate {
        let pkX = Data(identityKemPublicKey.prefix(32))
        let pkM = Data(identityKemPublicKey.dropFirst(32))
        let scopePrivBytes = try V1.asymUnwrap(
            skX: identity.kemX, mlkemSeed: identity.mlkemSeed, pkX: pkX, pkM: pkM,
            blob: b64urlDecode(grant.wrappedScopePrivateKey.bytes), context: "scope-grant")
        return V1.deserializeIdentityPrivate(scopePrivBytes)
    }
}
