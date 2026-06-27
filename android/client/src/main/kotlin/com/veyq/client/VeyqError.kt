package com.veyq.client

/**
 * Failures the login/unlock data layer surfaces — the Android twin of Apple's `VeyqError`. Non-enumerable
 * by design: a wrong password and an unknown account both arrive as [AuthenticationFailed] (the server
 * returns one generic 401, ADR 0003).
 */
sealed class VeyqError(message: String? = null) : Exception(message) {
    /** Login verify was rejected — wrong password, unknown account, or a stale/replayed nonce. One code for all three. */
    object AuthenticationFailed : VeyqError("authentication failed")

    /** The boundary rejected the request as malformed (400); carries the server's stable error code if known. */
    data class InvalidRequest(val code: String?) : VeyqError("invalid request: $code")

    /** A refresh was attempted with no refresh token held. */
    object NoSession : VeyqError("no session")

    /** The refresh token was invalid / expired / its family revoked — the session is gone. */
    object RefreshRejected : VeyqError("refresh rejected")

    /** An authed resource call hit a 401; [VeyqClient.withRefresh] catches this to rotate-and-retry once. */
    object SessionExpired : VeyqError("session expired")

    /** The account has no PASSWORD floor wrap to open — unlock is impossible on this path. */
    object UnlockUnavailable : VeyqError("unlock unavailable")

    /** The Secret Key entered was not valid base64. */
    object InvalidSecretKey : VeyqError("invalid secret key")

    /** Unwrap failed (AEAD tag mismatch) — wrong password or Secret Key. One generic failure. */
    object UnlockFailed : VeyqError("unlock failed")

    /** Enrolling device trust failed after the server wrap was created — the wrap was rolled back so no
     *  orphan remains (cr-0005 #3); the unlock that preceded it still stands. */
    object TrustFailed : VeyqError("trust failed")

    /** The per-account DEVICE-wrap cap was hit (409) — re-trust replaces, so real use never approaches it. */
    object TooManyDevices : VeyqError("too many devices")

    /** The server answered with a status the contract does not document. */
    data class UnexpectedStatus(val status: Int) : VeyqError("unexpected status $status")
}
