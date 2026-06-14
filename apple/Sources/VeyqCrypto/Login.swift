import Foundation

/// uc.account.login (Apple) — authenticate, fetch the wrapped key bag, and unlock the master key
/// locally. The server returns ciphertext only; the master/identity keys are recovered here.
public enum Login {

    static func b64urlDecode(_ s: String) -> Data {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t) ?? Data()
    }

    public struct BlobDTO: Codable {
        public let suiteId, bytes: String

        public init(suiteId: String, bytes: String) {
            self.suiteId = suiteId
            self.bytes = bytes
        }
    }
    struct IdentityPublicKeyDTO: Codable { let suiteId, kemPublicKey, signPublicKey: String }
    public struct KemPublicKeyDTO: Codable {
        public let suiteId, kemPublicKey: String

        public init(suiteId: String, kemPublicKey: String) {
            self.suiteId = suiteId
            self.kemPublicKey = kemPublicKey
        }
    }
    struct MasterKeyWrapDTO: Codable { let unlockMethod, suiteId, wrappedKey: String; let kdfParams: String? }
    struct KeyBagDTO: Codable {
        let identityPublicKey: IdentityPublicKeyDTO
        let wrappedIdentityPrivateKey: BlobDTO
        let masterKeyWraps: [MasterKeyWrapDTO]
    }
    public struct UserSummaryDTO: Codable, Sendable { public let id, email, displayName, serverRole: String }
    public struct PersonalScopeGrantDTO: Codable {
        public let scopeId: String
        public let scopePublicKey: KemPublicKeyDTO
        public let wrappedScopePrivateKey: BlobDTO
    }
    struct LoginResponseDTO: Codable { let sessionToken: String; let user: UserSummaryDTO; let keyBag: KeyBagDTO }

    public struct UnlockedSession: Sendable {
        public let sessionToken: String
        public let user: UserSummaryDTO
        public let identity: V1.IdentityPrivate
        public let kemPublicKey: Data
        public let masterKey: Data
    }

    public static func login(apiBase: String, email: String, password: String, secretKeyB64: String) async throws -> UnlockedSession {
        var request = URLRequest(url: URL(string: "\(apiBase)/api/login")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["email": email, "password": password])
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.login", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "login failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
        let body = try JSONDecoder().decode(LoginResponseDTO.self, from: data)
        guard let wrap = body.keyBag.masterKeyWraps.first(where: { $0.unlockMethod == "PASSWORD" }),
              let kdf = wrap.kdfParams else {
            throw NSError(domain: "veyq.login", code: 2, userInfo: [NSLocalizedDescriptionKey: "no PASSWORD unlock wrap"])
        }

        // the unlock cost params travel with the wrap, so a future cost bump never strands this account
        let (params, salt) = V1.decodeKdfParams(b64urlDecode(kdf))
        let kek = try V1.kekPasswordSecret(secretKey: b64urlDecode(secretKeyB64), password: password, salt: salt, params: params)
        let masterKey = try V1.symUnwrap(kek: kek, blob: b64urlDecode(wrap.wrappedKey), context: "master-key")
        let privBytes = try V1.symUnwrap(kek: masterKey, blob: b64urlDecode(body.keyBag.wrappedIdentityPrivateKey.bytes), context: "identity-priv")
        let identity = V1.deserializeIdentityPrivate(privBytes)

        return UnlockedSession(
            sessionToken: body.sessionToken, user: body.user, identity: identity,
            kemPublicKey: b64urlDecode(body.keyBag.identityPublicKey.kemPublicKey), masterKey: masterKey)
    }

    /// Logout: invalidate the current session token server-side. Idempotent for an already-dead token.
    public static func logout(apiBase: String, sessionToken: String) async throws {
        var request = URLRequest(url: URL(string: "\(apiBase)/api/me/session")!)
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        let (_, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 204 else {
            throw NSError(domain: "veyq.login", code: 5, userInfo: [NSLocalizedDescriptionKey: "logout failed"])
        }
    }

    public static func fetchCurrentUser(apiBase: String, sessionToken: String) async throws -> UserSummaryDTO {
        var request = URLRequest(url: URL(string: "\(apiBase)/api/me")!)
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.login", code: 3, userInfo: [NSLocalizedDescriptionKey: "me failed"])
        }
        return try JSONDecoder().decode(UserSummaryDTO.self, from: data)
    }

    public static func fetchPersonalScope(apiBase: String, sessionToken: String) async throws -> PersonalScopeGrantDTO {
        var request = URLRequest(url: URL(string: "\(apiBase)/api/me/personal-scope")!)
        request.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: request)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw NSError(domain: "veyq.login", code: 4, userInfo: [NSLocalizedDescriptionKey: "personal scope failed"])
        }
        return try JSONDecoder().decode(PersonalScopeGrantDTO.self, from: data)
    }

    static func openPersonalScopeGrant(
        grant: PersonalScopeGrantDTO, identity: V1.IdentityPrivate, identityKemPublicKey: Data
    ) throws -> V1.IdentityPrivate {
        let pkX = Data(identityKemPublicKey.prefix(32))
        let pkM = Data(identityKemPublicKey.dropFirst(32))
        let scopePrivBytes = try V1.asymUnwrap(
            skX: identity.kemX, mlkemSeed: identity.mlkemSeed, pkX: pkX, pkM: pkM,
            blob: b64urlDecode(grant.wrappedScopePrivateKey.bytes), context: "personal-scope-grant")
        return V1.deserializeIdentityPrivate(scopePrivBytes)
    }
}
