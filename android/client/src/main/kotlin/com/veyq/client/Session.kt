package com.veyq.client

/**
 * The opaque session tokens (ADR 0004), in memory only — the Android twin of Apple's `Session` actor and
 * web `app/session.ts`. Auth ≠ unlock: this holds a *session*, never key material (no Master Key here —
 * that is the [Vault]). Thread-safe so the bearer interceptor and the orchestration can touch it
 * concurrently (`@Volatile` reads + `@Synchronized` writes).
 */
class Session {
    /** Short-lived bearer access token (sent as the `Authorization` header by [BearerInterceptor]). */
    @Volatile
    var accessToken: String? = null
        private set

    /** Long-lived refresh token (sent only to `/api/token/refresh`). */
    @Volatile
    var refreshToken: String? = null
        private set

    /** Opaque id of the session family (one login / device); set at login, kept across refreshes. */
    @Volatile
    var familyId: String? = null
        private set

    /** The signed-in account email — set at login/resume, kept across refreshes. Lets [VeyqClient.refresh]
     *  persist the rotated refresh token to the right account for a passwordless device (cr-0022). */
    @Volatile
    var account: String? = null
        private set

    /** True once a login has stored an access token. */
    val isAuthenticated: Boolean get() = accessToken != null

    /**
     * Store the tokens from a login or a rotation. [familyId] + [account] are set at login/resume and
     * preserved across refreshes (a refresh call passes `null` for both, mirroring web/Apple).
     */
    @Synchronized
    fun set(access: String, refresh: String, familyId: String? = null, account: String? = null) {
        accessToken = access
        refreshToken = refresh
        if (familyId != null) this.familyId = familyId
        if (account != null) this.account = account
    }

    /** Drop the session (logout / refresh rejected). */
    @Synchronized
    fun clear() {
        accessToken = null
        refreshToken = null
        familyId = null
        account = null
    }
}
