import Foundation

// Failures the login/unlock data layer surfaces. Non-enumerable by design: a wrong password and an
// unknown account both arrive as `.authenticationFailed` (the server returns one generic 401, ADR 0003).
public enum VeyqError: Error, Equatable {
    /// Login verify was rejected — wrong password, unknown account, or a stale/replayed nonce. One code
    /// for all three (non-enumerable).
    case authenticationFailed
    /// The boundary rejected the request as malformed (400); carries the server's stable error code.
    case invalidRequest(String?)
    /// A refresh was attempted with no refresh token held.
    case noSession
    /// The refresh token was invalid / expired / its family revoked — the session is gone.
    case refreshRejected
    /// An authed resource call hit a 401; `withRefresh` catches this to rotate-and-retry once.
    case sessionExpired
    /// The account has no PASSWORD floor wrap to open — unlock is impossible on this path.
    case unlockUnavailable
    /// The Secret Key entered was not valid base64.
    case invalidSecretKey
    /// Unwrap failed (AEAD tag mismatch) — wrong password or Secret Key. One generic failure.
    case unlockFailed
    /// The server answered with a status the contract does not document.
    case unexpectedStatus(Int)
}
