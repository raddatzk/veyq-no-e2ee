import Foundation

/// Client-side master-password policy. The operator-blind server never sees the password (only an Argon2
/// proof), so length/strength can only be checked on the client. Length is the dominant strength factor
/// (NIST 800-63B); no character-class rules — HIBP covers the breached case. Mirrors web passwordPolicy.ts.
public enum PasswordPolicy {
    public static let minLength = 10
    private static let strongLength = 16

    /// True when a non-empty password is below the hard minimum — submit is gated on this.
    public static func tooShort(_ pw: String) -> Bool { !pw.isEmpty && pw.count < minLength }

    /// i18n key for a soft length hint, or nil in the acceptable middle (10–15 chars).
    public static func hintKey(_ pw: String) -> String? {
        if pw.isEmpty { return nil }
        if pw.count < minLength { return "password.minLength" }
        if pw.count >= strongLength { return "password.strong" }
        return nil
    }
}
