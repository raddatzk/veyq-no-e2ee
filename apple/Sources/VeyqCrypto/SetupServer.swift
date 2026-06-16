import Foundation

/// uc.admin.setup-server (Apple) — cold E2EE registration of the first Server Owner.
/// All key material is generated here; the server only ever receives ciphertext + public keys.
public enum SetupServer {
    static let argon = V1.ArgonParams(t: 3, m: 32768, p: 1, dkLen: 32)

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    struct IdentityPublicKeyDTO: Codable { let suiteId, kemPublicKey, signPublicKey: String }
    struct KemPublicKeyDTO: Codable { let suiteId, kemPublicKey: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct MasterKeyWrapDTO: Codable { let unlockMethod, suiteId, wrappedKey: String; let kdfParams: String? }
    struct AuthVerifierDTO: Codable { let kind, suiteId, verifier: String }
    struct CreateServerOwnerRequest: Codable {
        let email, displayName: String
        let identityPublicKey: IdentityPublicKeyDTO
        let wrappedIdentityPrivateKey: BlobDTO
        let masterKeyWraps: [MasterKeyWrapDTO]
        let authVerifier: AuthVerifierDTO
        let recoveryAuthTag: String
        let personalScopePublicKey: KemPublicKeyDTO
        let wrappedPersonalScopePrivateKey: BlobDTO
    }
    public struct UserSummaryDTO: Codable { public let id, email, displayName, serverRole: String }
    public struct EmergencyKit { public let secretKey: String }

    static func buildRequest(email: String, displayName: String, password: String) throws -> (CreateServerOwnerRequest, EmergencyKit) {
        let id = try V1.generateIdentity()
        let masterKey = V1.randomBytes(32)
        let wrappedPriv = try V1.symWrap(kek: masterKey, targetKey: V1.serializeIdentityPrivate(id), context: "identity-priv", nonce: V1.randomBytes(12))

        let secretKey = V1.randomBytes(32)
        let salt = V1.randomBytes(16)
        let kek = try V1.kekPasswordSecret(secretKey: secretKey, password: password, salt: salt, params: argon)
        let masterWrap = try V1.symWrap(kek: kek, targetKey: masterKey, context: "master-key", nonce: V1.randomBytes(12))

        // RECOVERY wrap: master key under the Secret Key ALONE. Never returned in the login key bag — released
        // only via the email-token-gated reset (recovery needs Secret Key AND email). kdfParams carry the
        // Argon params so the reset re-derives correctly on any platform.
        let recSalt = V1.randomBytes(16)
        let recWrap = try V1.symWrap(kek: try V1.kekRecovery(recoveryCode: secretKey, salt: recSalt, params: argon), targetKey: masterKey, context: "master-key", nonce: V1.randomBytes(12))

        let authSalt = V1.randomBytes(16)
        let verifier = try V1.kekRecovery(recoveryCode: Data(password.precomposedStringWithCanonicalMapping.utf8), salt: authSalt, params: argon)

        // personal scope is self-owned and unshared; grant it only to this identity.
        let personal = try V1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
        let personalPublic = personal.pubKemX + personal.pubKemM
        let personalGrant = try V1.asymWrap(
            pkX: id.pubKemX, pkM: id.pubKemM, targetKey: V1.serializeIdentityPrivate(personal),
            context: "personal-scope-grant", nonce: V1.randomBytes(12))

        let req = CreateServerOwnerRequest(
            email: email, displayName: displayName,
            identityPublicKey: IdentityPublicKeyDTO(suiteId: "v1", kemPublicKey: b64url(id.pubKemX + id.pubKemM), signPublicKey: b64url(id.pubSign)),
            wrappedIdentityPrivateKey: BlobDTO(suiteId: "v1", bytes: b64url(wrappedPriv)),
            masterKeyWraps: [
                MasterKeyWrapDTO(unlockMethod: "PASSWORD", suiteId: "v1", wrappedKey: b64url(masterWrap), kdfParams: b64url(V1.encodeKdfParams(argon, salt: salt))),
                MasterKeyWrapDTO(unlockMethod: "RECOVERY", suiteId: "v1", wrappedKey: b64url(recWrap), kdfParams: b64url(V1.encodeKdfParams(argon, salt: recSalt))),
            ],
            authVerifier: AuthVerifierDTO(kind: "ARGON2_PROOF", suiteId: "v1", verifier: b64url(authSalt + verifier)),
            // commitment to the master key — lets password reset prove Secret-Key possession (the RECOVERY wrap above)
            recoveryAuthTag: b64url(V1.recoveryAuthTag(masterKey: masterKey)),
            personalScopePublicKey: KemPublicKeyDTO(suiteId: "v1", kemPublicKey: b64url(personalPublic)),
            wrappedPersonalScopePrivateKey: BlobDTO(suiteId: "v1", bytes: b64url(personalGrant))
        )
        return (req, EmergencyKit(secretKey: b64url(secretKey)))
    }

    public static func registerServerOwner(apiBase: String, email: String, displayName: String, password: String) async throws -> (UserSummaryDTO, EmergencyKit) {
        let (req, kit) = try buildRequest(email: email, displayName: displayName, password: password)
        var request = URLRequest(url: URL(string: "\(apiBase)/api/setup/owner")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(req)
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.setup", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "setup/owner failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        return (try JSONDecoder().decode(UserSummaryDTO.self, from: data), kit)
    }
}
