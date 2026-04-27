package com.lunva.api.auth

import com.lunva.api.auth.cookie.CookieConfig
import com.lunva.api.auth.cookie.CookieService
import com.lunva.api.auth.token.TokenConfig
import jakarta.ws.rs.core.NewCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CookieServiceTest {

    private val cookieConfig = object : CookieConfig {
        override val accessTokenName: String = "LUNVA_ACCESS_TOKEN"
        override val refreshTokenName: String = "LUNVA_REFRESH_TOKEN"
        override val secure: Boolean = true
    }

    private val tokenConfig = object : TokenConfig {
        override val accessTokenTtlMinutes: Long = 15
        override val refreshTokenTtlMinutes: Long = 60
        override val cleanupAccessTokenAuditDays: Long = 30
        override val cleanupRefreshTokenAuditDays: Long = 90
        override val cleanupCron: String = "0 0 0 * * ?"
    }

    private val cookieService = CookieService(cookieConfig, tokenConfig)

    @Test
    fun `createAccessCookie sets expected flags and values`() {
        val cookie = cookieService.createAccessCookie("access-token")

        assertEquals("LUNVA_ACCESS_TOKEN", cookie.name)
        assertEquals("access-token", cookie.value)
        assertEquals("/api", cookie.path)
        assertEquals(15 * 60, cookie.maxAge)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.isSecure)
        assertEquals(NewCookie.SameSite.STRICT, cookie.sameSite)
    }

    @Test
    fun `createRefreshCookie sets expected flags and values`() {
        val cookie = cookieService.createRefreshCookie("refresh-token")

        assertEquals("LUNVA_REFRESH_TOKEN", cookie.name)
        assertEquals("refresh-token", cookie.value)
        assertEquals("/api/auth", cookie.path)
        assertEquals(60 * 60, cookie.maxAge)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.isSecure)
        assertEquals(NewCookie.SameSite.STRICT, cookie.sameSite)
    }

    @Test
    fun `delete cookies set max age zero and preserve names`() {
        val access = cookieService.deleteAccessCookie()
        val refresh = cookieService.deleteRefreshCookie()

        assertEquals("LUNVA_ACCESS_TOKEN", access.name)
        assertEquals("", access.value)
        assertEquals(0, access.maxAge)
        assertEquals("/api", access.path)

        assertEquals("LUNVA_REFRESH_TOKEN", refresh.name)
        assertEquals("", refresh.value)
        assertEquals(0, refresh.maxAge)
        assertEquals("/api/auth", refresh.path)
    }
}

