package com.veyq.app.features.account.login

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.NewCookie
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * The web refresh-token cookie (ADR 0004 transport; cr-0052). `httpOnly` (never readable by JS) +
 * `SameSite=Strict`, scoped to the refresh path so it rides only the rotation call. `Secure` is **config-driven**
 * (`veyq.session.cookie.secure`, on in prod; off in local dev over http, where a Secure cookie would never be
 * sent). The value is the same opaque refresh token the body carries for native clients — no key material — so
 * the cookie keeps the server operator-blind.
 */
@ApplicationScoped
class SessionCookie(
    @param:ConfigProperty(name = "veyq.session.cookie.secure", defaultValue = "true") private val secure: Boolean,
) {
    /** A persistent refresh cookie (survives a browser restart) carrying [token] — web "remember me" / L3. */
    fun persistent(token: String): NewCookie = build(token, REFRESH_TTL_SECONDS)

    /** A clearing cookie (Max-Age 0) — drops the refresh cookie on logout. */
    fun cleared(): NewCookie = build("", 0)

    /** Read the refresh token from the request cookie, or null when absent/blank (native / no "remember me"). */
    fun read(headers: HttpHeaders): String? = headers.cookies[NAME]?.value?.ifBlank { null }

    private fun build(value: String, maxAge: Int): NewCookie =
        NewCookie.Builder(NAME)
            .value(value)
            .path(PATH)
            .httpOnly(true)
            .secure(secure)
            .sameSite(NewCookie.SameSite.STRICT)
            .maxAge(maxAge)
            .build()

    private companion object {
        const val NAME = "veyq_refresh"
        const val PATH = "/api/token/refresh"
        val REFRESH_TTL_SECONDS = (SessionService.REFRESH_TTL_DAYS * 24 * 60 * 60).toInt()
    }
}
