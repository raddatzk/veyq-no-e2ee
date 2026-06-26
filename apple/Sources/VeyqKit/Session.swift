import Foundation

// The opaque session tokens (ADR 0004), in memory only — the Apple analog of the web `app/session.ts`.
// Auth ≠ unlock: this holds a *session*, never key material (no Master Key here — that is the `Vault`,
// phase 3). An `actor` so the bearer middleware and the orchestration can touch it concurrently.
public actor Session {
    /// Short-lived bearer access token (sent as the `Authorization` header by `BearerMiddleware`).
    public private(set) var accessToken: String?
    /// Long-lived refresh token (sent only to `/api/token/refresh`).
    public private(set) var refreshToken: String?
    /// Opaque id of the session family (one login / device); set at login, kept across refreshes.
    public private(set) var familyId: String?

    public init() {}

    /// True once a login has stored an access token.
    public var isAuthenticated: Bool { accessToken != nil }

    /// Store the tokens from a login or a rotation. `familyId` is set at login and preserved across
    /// refreshes (a refresh call passes `nil`, mirroring the web data layer).
    func set(access: String, refresh: String, familyId: String? = nil) {
        accessToken = access
        refreshToken = refresh
        if let familyId { self.familyId = familyId }
    }

    /// Drop the session (logout / refresh rejected).
    func clear() {
        accessToken = nil
        refreshToken = nil
        familyId = nil
    }
}
