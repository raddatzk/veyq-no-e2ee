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
    /// Enrolling device trust failed after the server wrap was created — the wrap was rolled back so no
    /// orphan remains (cr-0019 / cr-0005 #3); the unlock that preceded it still stands.
    case trustFailed
    /// The per-account DEVICE-wrap cap was hit (409) — this device cannot be trusted until another is
    /// untrusted. Re-trust replaces, so real use never approaches it.
    case tooManyDevices
    /// The server answered with a status the contract does not document.
    case unexpectedStatus(Int)
    /// The active tenant could not be resolved (no Host match) — scope ops need a tenant context (cr-0026).
    case scopeTenantUnavailable
    /// A personal scope already exists for this member in this tenant (one per tenant, cr-0026).
    case personalScopeExists
    /// A sibling node already holds this blinded slug under the same parent (409, cr-0027).
    case slugTaken
    /// The node tree operation broke a structural rule — folder-in-Memory, chapter-at-root, or a move
    /// cycle (422, cr-0027).
    case illegalStructure
    /// The addressed scope does not exist for the caller (404, cr-0027).
    case scopeNotFound
    /// The addressed node does not exist in this scope (404, cr-0027).
    case nodeNotFound
    /// The object store cannot presign / has no transport path (the in-memory dev store, cr-0028).
    case storeUnavailable
    /// A media item failed in a batch (per-item error code carried, cr-0028): illegal_target,
    /// budget_exceeded, upload_not_found, …
    case mediaItemFailed(String?)
    /// A direct-to-store PUT/GET to a presigned URL failed (non-2xx / transport error, cr-0028).
    case blobTransferFailed
}
