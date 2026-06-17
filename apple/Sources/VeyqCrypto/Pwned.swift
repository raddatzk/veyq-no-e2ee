import Foundation
import CryptoKit

/// Have I Been Pwned (Pwned Passwords) k-anonymity check — a SOFT warning signal, never a gate. Only the first
/// 5 hex chars of the password's SHA-1 leave the device, straight to the HIBP range API (not via our server);
/// the suffix match is computed locally. Fail-open: any error returns 0, so it can neither block sign-up nor
/// reveal that it ran. Mirrors the web shared/pwned.ts (operator-blind).
public enum Pwned {
    /// Uppercase hex SHA-1 of the UTF-8 string (HIBP keys passwords on SHA-1).
    public static func sha1HexUpper(_ s: String) -> String {
        Insecure.SHA1.hash(data: Data(s.utf8)).map { String(format: "%02X", $0) }.joined()
    }

    /// Full uppercase SHA-1 + a HIBP range body ("SUFFIX:COUNT" per line) → the breach count (0 if absent). Pure.
    public static func breachCount(hashHexUpper: String, rangeBody: String) -> Int {
        let suffix = String(hashHexUpper.uppercased().dropFirst(5))
        for rawLine in rangeBody.split(whereSeparator: { $0.isNewline }) {
            guard let colon = rawLine.firstIndex(of: ":") else { continue }
            if String(rawLine[..<colon]).uppercased() == suffix {
                return Int(String(rawLine[rawLine.index(after: colon)...]).trimmingCharacters(in: .whitespaces)) ?? 0
            }
        }
        return 0
    }

    /// How many known breaches a password appears in (0 = none, or the check was unavailable). Fail-open.
    public static func count(_ password: String) async -> Int {
        if password.isEmpty { return 0 }
        let hash = sha1HexUpper(password)
        guard let url = URL(string: "https://api.pwnedpasswords.com/range/\(hash.prefix(5))") else { return 0 }
        var req = URLRequest(url: url)
        req.setValue("true", forHTTPHeaderField: "Add-Padding")
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard (resp as? HTTPURLResponse)?.statusCode == 200, let body = String(data: data, encoding: .utf8) else { return 0 }
            return breachCount(hashHexUpper: hash, rangeBody: body)
        } catch { return 0 }
    }
}
