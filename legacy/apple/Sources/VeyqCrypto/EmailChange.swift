import Foundation

/// uc.account.change-email (Apple) — change the account login email. Email is not part of the KEK, so this is
/// a pure identifier swap (no re-wrap). `request` (authenticated) emails a one-time token to the NEW address;
/// `confirm` (token-only) commits the change, so the email link works on any device regardless of login state.
public enum EmailChange {
    /// Ask the server to email a confirmation token to a NEW address. Throws code 409 if the address is taken.
    public static func requestEmailChange(apiBase: String, sessionToken: String, newEmail: String) async throws {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/me/email-change/request")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        r.httpBody = try JSONEncoder().encode(["newEmail": newEmail])
        let (data, resp) = try await URLSession.shared.data(for: r)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 204 else {
            throw NSError(domain: "veyq.emailchange", code: code,
                          userInfo: [NSLocalizedDescriptionKey: "email change request failed: \(String(data: data, encoding: .utf8) ?? "")"])
        }
    }

    /// Confirm an email change with the token from the email link. Token-only — no session required.
    public static func confirmEmailChange(apiBase: String, token: String) async throws -> Login.UserSummaryDTO {
        var r = URLRequest(url: URL(string: "\(apiBase)/api/me/email-change/confirm")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONEncoder().encode(["token": token])
        let (data, resp) = try await URLSession.shared.data(for: r)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw NSError(domain: "veyq.emailchange", code: 1, userInfo: [NSLocalizedDescriptionKey: "email change confirm failed"])
        }
        return try JSONDecoder().decode(Login.UserSummaryDTO.self, from: data)
    }

    /// Parse a `veyq://email-verify?token=…` deep link, returning the token (or nil if it isn't one).
    public static func parseDeepLink(_ url: String) -> String? {
        guard let comps = URLComponents(string: url), comps.scheme == "veyq", comps.host == "email-verify" else { return nil }
        return comps.queryItems?.first(where: { $0.name == "token" })?.value
    }
}
