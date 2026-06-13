import Foundation

/// uc.admin.setup-server (Apple) — cold E2EE registration of the first Server Owner.
/// All key material is generated here; the server only ever receives ciphertext + public keys.
public enum SetupServer {
    static let argon = V1.ArgonParams(t: 3, m: 8192, p: 1, dkLen: 32)

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    struct IdentityPublicKeyDTO: Codable { let suiteId, kemPublicKey, signPublicKey: String }
    struct BlobDTO: Codable { let suiteId, bytes: String }
    struct MasterKeyWrapDTO: Codable { let unlockMethod, suiteId, wrappedKey: String; let kdfParams: String? }
    struct AuthVerifierDTO: Codable { let kind, suiteId, verifier: String }
    struct CreateServerOwnerRequest: Codable {
        let email, displayName: String
        let identityPublicKey: IdentityPublicKeyDTO
        let wrappedIdentityPrivateKey: BlobDTO
        let masterKeyWraps: [MasterKeyWrapDTO]
        let authVerifier: AuthVerifierDTO
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

        let authSalt = V1.randomBytes(16)
        let verifier = try V1.kekRecovery(recoveryCode: Data(password.precomposedStringWithCanonicalMapping.utf8), salt: authSalt, params: argon)

        let req = CreateServerOwnerRequest(
            email: email, displayName: displayName,
            identityPublicKey: IdentityPublicKeyDTO(suiteId: "v1", kemPublicKey: b64url(id.pubKemX + id.pubKemM), signPublicKey: b64url(id.pubSign)),
            wrappedIdentityPrivateKey: BlobDTO(suiteId: "v1", bytes: b64url(wrappedPriv)),
            masterKeyWraps: [MasterKeyWrapDTO(unlockMethod: "PASSWORD", suiteId: "v1", wrappedKey: b64url(masterWrap), kdfParams: b64url(salt))],
            authVerifier: AuthVerifierDTO(kind: "ARGON2_PROOF", suiteId: "v1", verifier: b64url(authSalt + verifier))
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
