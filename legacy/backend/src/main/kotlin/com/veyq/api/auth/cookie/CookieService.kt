package com.veyq.api.auth.cookie

import com.veyq.api.auth.token.TokenConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.NewCookie.SameSite

@ApplicationScoped
class CookieService @Inject constructor(
    private val cookieConfig: CookieConfig,
    private val tokenConfig: TokenConfig,
){

    fun createAccessCookie(tokenValue: String): NewCookie {
        return createCookie(cookieConfig.accessTokenName, tokenValue, "/api", tokenConfig.accessTokenTtlSeconds)
    }

    fun createRefreshCookie(tokenValue: String): NewCookie {
        return createCookie(cookieConfig.refreshTokenName, tokenValue, "/api/auth", tokenConfig.refreshTokenTtlSeconds)
    }

    fun deleteAccessCookie(): NewCookie {
        return createCookie(cookieConfig.accessTokenName, "", "/api", 0)
    }

    fun deleteRefreshCookie(): NewCookie {
        return createCookie(cookieConfig.refreshTokenName, "", "/api/auth", 0)
    }

    private fun createCookie(name: String, value: String, path: String, maxAge: Int): NewCookie {
        return NewCookie.Builder(name)
            .value(value)
            .path(path)
            .comment("veyq session ${name} cookie")
            .maxAge(maxAge)
            .secure(cookieConfig.secure)
            .httpOnly(true)
            .sameSite(SameSite.STRICT)
            .build()
    }

}