import Foundation

/// uc.account.reset-password (Apple) — recover a forgotten password with the Secret Key (Emergency Kit) AND
/// email access. The email delivers a one-time token (email factor); the Secret Key decrypts the RECOVERY
/// master-key wrap (crypto factor). The client unwraps the master key, re-wraps it under the new password and
/// ships the new PASSWORD wrap + auth verifier. The server sees neither secret.
public enum ResetPassword {
    struct MasterKeyWrapDTO: Codable { let unlockMethod, suiteId, wrappedKey: String; let kdfParams: String? }
    struct AuthVerifierDTO: Codable { let kind, suiteId, verifier: String }
    struct MaterialResponse: Codable { let email: String; let recoveryWrap: MasterKeyWrapDTO }
    struct RequestBody: Codable { let email: String }
    struct TokenBody: Codable { let token: String }
    struct ConfirmBody: Codable { let token: String; let masterKeyWrap: MasterKeyWrapDTO; let authVerifier: AuthVerifierDTO }

    /// Ask the server to email a reset token. Always succeeds (the server never reveals account existence).
    public static func requestReset(apiBase: String, email: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/account/password-reset/request")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(RequestBody(email: email.trimmingCharacters(in: .whitespacesAndNewlines)))
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 204 else {
            throw err("reset request failed: \(String(data: data, encoding: .utf8) ?? "")")
        }
    }

    /// Exchange the email token for the account email (e.g. for display). Throws on an invalid/expired token.
    public static func tokenEmail(apiBase: String, token: String) async throws -> String {
        try await material(apiBase: apiBase, token: token).email
    }

    private static func material(apiBase: String, token: String) async throws -> MaterialResponse {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/account/password-reset/material")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(TokenBody(token: token))
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw err("reset material failed: \(String(data: data, encoding: .utf8) ?? "")")
        }
        return try JSONDecoder().decode(MaterialResponse.self, from: data)
    }

    /// Complete the reset: unwrap the master key with the Secret Key, re-wrap under the new password, submit
    /// the new PASSWORD wrap + auth verifier. Returns the account email so the caller can sign in. Throws on a
    /// wrong Secret Key (the RECOVERY wrap fails to open).
    public static func confirmReset(apiBase: String, token: String, secretKeyB64: String, newPassword: String) async throws -> String {
        let mat = try await material(apiBase: apiBase, token: token)
        let secretKey = Login.b64urlDecode(secretKeyB64)
        guard let kdf = mat.recoveryWrap.kdfParams else { throw err("recovery wrap has no kdf params") }
        let (params, salt) = V1.decodeKdfParams(Login.b64urlDecode(kdf))
        let recKek = try V1.kekRecovery(recoveryCode: secretKey, salt: salt, params: params)
        let masterKey = try V1.symUnwrap(kek: recKek, blob: Login.b64urlDecode(mat.recoveryWrap.wrappedKey), context: "master-key")

        // re-wrap under the NEW password (PASSWORD KEK still folds in the Secret Key — login stays 2-factor)
        let newSalt = V1.randomBytes(16)
        let newWrap = try V1.symWrap(kek: try V1.kekPasswordSecret(secretKey: secretKey, password: newPassword, salt: newSalt, params: SetupServer.argon), targetKey: masterKey, context: "master-key", nonce: V1.randomBytes(12))
        let authSalt = V1.randomBytes(16)
        let verifier = try V1.kekRecovery(recoveryCode: Data(newPassword.precomposedStringWithCanonicalMapping.utf8), salt: authSalt, params: SetupServer.argon)

        let body = ConfirmBody(
            token: token,
            masterKeyWrap: MasterKeyWrapDTO(unlockMethod: "PASSWORD", suiteId: "v1", wrappedKey: SetupServer.b64url(newWrap), kdfParams: SetupServer.b64url(V1.encodeKdfParams(SetupServer.argon, salt: newSalt))),
            authVerifier: AuthVerifierDTO(kind: "ARGON2_PROOF", suiteId: "v1", verifier: SetupServer.b64url(authSalt + verifier)))
        var r = URLRequest(url: URL(string: "\(apiBase)/api/account/password-reset/confirm")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(body)
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 204 else {
            throw err("reset confirm failed: \(String(data: data, encoding: .utf8) ?? "")")
        }
        return mat.email
    }

    /// Read a `#reset=<token>` token out of a pasted reset link or bare fragment, or nil.
    public static func parseToken(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let frag = trimmed.firstIndex(of: "#").map { String(trimmed[trimmed.index(after: $0)...]) } ?? trimmed
        for pair in frag.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            if kv.count == 2, kv[0] == "reset" { return String(kv[1]) }
        }
        return nil
    }

    private static func err(_ m: String) -> NSError { NSError(domain: "veyq.reset", code: 1, userInfo: [NSLocalizedDescriptionKey: m]) }
}
