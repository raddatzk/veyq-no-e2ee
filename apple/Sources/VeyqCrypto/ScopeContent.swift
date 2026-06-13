import Foundation

/// uc.spaces / content (Apple) — the first real use of a scope: write and read encrypted content under
/// it. AD-03: a stable per-scope content key (wrapped to the scope public key) seals items; the server
/// only ever stores ciphertext. Targets the personal scope here, but the ops are scope-generic.
public enum ScopeContent {
    struct KemDTO: Codable { let suiteId, kemPublicKey: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct PersonalScopeDTO: Codable { let scopeId: String; let scopePublicKey: KemDTO; let wrappedScopePrivateKey: BlobDTO }
    struct ContentKeyDTO: Codable { let wrappedContentKey: BlobDTO }
    struct ProvisionDTO: Codable { let wrappedContentKey: BlobDTO }
    struct ItemDTO: Codable { let id: String; let sealedContent: BlobDTO; let createdAt: String }
    struct ItemListDTO: Codable { let items: [ItemDTO] }
    struct CreateItemDTO: Codable { let sealedContent: BlobDTO }

    public struct OpenScope {
        public let scopeId: String
        let scopePkX, scopePkM: Data
        let scopePriv: V1.IdentityPrivate
    }

    private static func send(_ apiBase: String, _ path: String, _ token: String, method: String = "GET", body: Data? = nil) async throws -> (Data, Int) {
        var r = URLRequest(url: URL(string: "\(apiBase)\(path)")!)
        r.httpMethod = method
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body { r.setValue("application/json", forHTTPHeaderField: "Content-Type"); r.httpBody = body }
        let (data, resp) = try await URLSession.shared.data(for: r)
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }

    /// Fetch + open the caller's personal scope: unwrap its private key with the user's identity.
    public static func openPersonalScope(apiBase: String, session: Login.UnlockedSession) async throws -> OpenScope {
        let (data, code) = try await send(apiBase, "/api/me/personal-scope", session.sessionToken)
        guard code == 200 else { throw err("personal-scope failed: \(String(data: data, encoding: .utf8) ?? "")") }
        let g = try JSONDecoder().decode(PersonalScopeDTO.self, from: data)
        let idKem = session.kemPublicKey
        let scopePrivBytes = try V1.asymUnwrap(
            skX: session.identity.kemX, mlkemSeed: session.identity.mlkemSeed,
            pkX: Data(idKem.prefix(32)), pkM: Data(idKem.dropFirst(32)),
            blob: Login.b64urlDecode(g.wrappedScopePrivateKey.bytes), context: "personal-scope-grant")
        let scopeKem = Login.b64urlDecode(g.scopePublicKey.kemPublicKey)
        return OpenScope(scopeId: g.scopeId, scopePkX: Data(scopeKem.prefix(32)), scopePkM: Data(scopeKem.dropFirst(32)),
                         scopePriv: V1.deserializeIdentityPrivate(scopePrivBytes))
    }

    /// Fetch + open a tenant scope the caller holds a grant for (re-fetches the current grant each call).
    public static func openTenantScope(apiBase: String, session: Login.UnlockedSession, tenantId: String) async throws -> OpenScope {
        let g = try await Tenant.getMyScopeGrant(apiBase: apiBase, token: session.sessionToken, tenantId: tenantId)
        let scopePriv = try Tenant.openScopeGrant(grant: g, identity: session.identity, identityKemPublicKey: session.kemPublicKey)
        let scopeKem = Login.b64urlDecode(g.scopePublicKey.kemPublicKey)
        return OpenScope(scopeId: g.scopeId, scopePkX: Data(scopeKem.prefix(32)), scopePkM: Data(scopeKem.dropFirst(32)), scopePriv: scopePriv)
    }

    /// Get the scope's content key, provisioning one (wrapped to the scope) on first use.
    public static func getOrCreateContentKey(apiBase: String, session: Login.UnlockedSession, scope: OpenScope) async throws -> Data {
        let path = "/api/scopes/\(scope.scopeId)/content-key"
        let (data, code) = try await send(apiBase, path, session.sessionToken)
        if code == 200 {
            let ck = try JSONDecoder().decode(ContentKeyDTO.self, from: data)
            return try V1.asymUnwrap(skX: scope.scopePriv.kemX, mlkemSeed: scope.scopePriv.mlkemSeed,
                                     pkX: scope.scopePkX, pkM: scope.scopePkM,
                                     blob: Login.b64urlDecode(ck.wrappedContentKey.bytes), context: "content-key")
        }
        guard code == 404 else { throw err("content-key fetch failed: \(code)") }

        let ck = V1.randomBytes(32)
        let wrapped = try V1.asymWrap(pkX: scope.scopePkX, pkM: scope.scopePkM, targetKey: ck, context: "content-key", nonce: V1.randomBytes(12))
        let body = try JSONEncoder().encode(ProvisionDTO(wrappedContentKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(wrapped))))
        let (pData, pCode) = try await send(apiBase, path, session.sessionToken, method: "PUT", body: body)
        if pCode == 409 { return try await getOrCreateContentKey(apiBase: apiBase, session: session, scope: scope) }
        guard pCode == 200 else { throw err("content-key provision failed: \(String(data: pData, encoding: .utf8) ?? "")") }
        return ck
    }

    /// Seal a string under the content key and store it; returns the item id.
    @discardableResult
    public static func writeItem(apiBase: String, session: Login.UnlockedSession, scope: OpenScope, contentKey: Data, plaintext: String) async throws -> String {
        let nonce = V1.randomBytes(12)
        let sealed = nonce + (try V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: Data(plaintext.utf8), aad: Data()))
        let body = try JSONEncoder().encode(CreateItemDTO(sealedContent: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(sealed))))
        let (data, code) = try await send(apiBase, "/api/scopes/\(scope.scopeId)/items", session.sessionToken, method: "POST", body: body)
        guard code == 200 else { throw err("item create failed: \(String(data: data, encoding: .utf8) ?? "")") }
        return try JSONDecoder().decode(ItemDTO.self, from: data).id
    }

    /// List the scope's items and decrypt each back to its plaintext string.
    public static func readItems(apiBase: String, session: Login.UnlockedSession, scope: OpenScope, contentKey: Data) async throws -> [String] {
        let (data, code) = try await send(apiBase, "/api/scopes/\(scope.scopeId)/items", session.sessionToken)
        guard code == 200 else { throw err("item list failed: \(code)") }
        return try JSONDecoder().decode(ItemListDTO.self, from: data).items.map { it in
            let sealed = Login.b64urlDecode(it.sealedContent.bytes)
            let pt = try V1.aeadOpen(key: contentKey, nonce: Data(sealed.prefix(12)), aead: Data(sealed.dropFirst(12)), aad: Data())
            return String(decoding: pt, as: UTF8.self)
        }
    }

    private static func err(_ m: String) -> NSError { NSError(domain: "veyq.content", code: 1, userInfo: [NSLocalizedDescriptionKey: m]) }
}
