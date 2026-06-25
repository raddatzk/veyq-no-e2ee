import Foundation

/// uc.keys.rotate-identity (Apple) — replace the account identity keypair and re-wrap every owned grant
/// to it. The master key + unlock wraps are unchanged, so login still works; only the identity moves.
public enum RotateIdentity {
    struct KemDTO: Codable { let suiteId, kemPublicKey: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct OwnedGrantDTO: Codable { let scopeId, kind: String; let scopePublicKey: KemDTO; let wrappedScopePrivateKey: BlobDTO }
    struct GrantListDTO: Codable { let grants: [OwnedGrantDTO] }
    struct RewrapDTO: Codable { let scopeId: String; let wrappedScopePrivateKey: BlobDTO }
    struct IdentityPubDTO: Codable { let suiteId, kemPublicKey, signPublicKey: String }
    struct RotateReqDTO: Codable { let identityPublicKey: IdentityPubDTO; let wrappedIdentityPrivateKey: BlobDTO; let grants: [RewrapDTO] }

    private static func context(_ kind: String) -> String { kind == "PERSONAL_ROOT" ? "personal-scope-grant" : "scope-grant" }

    @discardableResult
    public static func rotateIdentity(apiBase: String, session: Login.UnlockedSession) async throws -> Login.UserSummaryDTO {
        // 1. new identity, its private key wrapped under the unchanged master key
        let newId = try V1.generateIdentity()
        let wrappedNewPriv = try V1.symWrap(kek: session.masterKey, targetKey: V1.serializeIdentityPrivate(newId), context: "identity-priv", nonce: V1.randomBytes(12))

        // 2. list every grant and re-wrap each from the OLD identity to the NEW one (per-scope context)
        var listReq = URLRequest(url: URL(string: "\(apiBase)/api/me/grants")!)
        listReq.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        let (listData, listResp) = try await URLSession.shared.data(for: listReq)
        guard (listResp as? HTTPURLResponse)?.statusCode == 200 else { throw err("list grants failed") }
        let owned = try JSONDecoder().decode(GrantListDTO.self, from: listData).grants

        let oldPkX = Data(session.kemPublicKey.prefix(32)), oldPkM = Data(session.kemPublicKey.dropFirst(32))
        let grants = try owned.map { g -> RewrapDTO in
            let ctx = context(g.kind)
            let scopePrivBytes = try V1.asymUnwrap(
                skX: session.identity.kemX, mlkemSeed: session.identity.mlkemSeed, pkX: oldPkX, pkM: oldPkM,
                blob: Login.b64urlDecode(g.wrappedScopePrivateKey.bytes), context: ctx)
            let rewrapped = try V1.asymWrap(pkX: newId.pubKemX, pkM: newId.pubKemM, targetKey: scopePrivBytes, context: ctx, nonce: V1.randomBytes(12))
            return RewrapDTO(scopeId: g.scopeId, wrappedScopePrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(rewrapped)))
        }

        // 3. swap the identity + grants in one request
        let req = RotateReqDTO(
            identityPublicKey: IdentityPubDTO(suiteId: "v1", kemPublicKey: SetupServer.b64url(newId.pubKemX + newId.pubKemM), signPublicKey: SetupServer.b64url(newId.pubSign)),
            wrappedIdentityPrivateKey: BlobDTO(suiteId: "v1", bytes: SetupServer.b64url(wrappedNewPriv)),
            grants: grants)
        var r = URLRequest(url: URL(string: "\(apiBase)/api/me/identity/rotate")!)
        r.httpMethod = "POST"
        r.setValue("Bearer \(session.sessionToken)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(req)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw err("rotate identity failed: \(String(data: data, encoding: .utf8) ?? "")") }
        return try JSONDecoder().decode(Login.UserSummaryDTO.self, from: data)
    }

    private static func err(_ m: String) -> NSError { NSError(domain: "veyq.rotateidentity", code: 1, userInfo: [NSLocalizedDescriptionKey: m]) }
}
