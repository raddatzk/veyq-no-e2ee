package com.veyq.api.auth

import com.veyq.api.auth.cookie.CookieConfig
import com.veyq.api.auth.cookie.CookieService
import com.veyq.api.auth.token.TokenConfig
import jakarta.ws.rs.core.NewCookie
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class CookieServiceTest {

    private val cookieConfig = object : CookieConfig {
        override val accessTokenName: String = "VEYQ_ACCESS_TOKEN"
        override val refreshTokenName: String = "VEYQ_REFRESH_TOKEN"
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

        assertThat(cookie.name).isEqualTo("VEYQ_ACCESS_TOKEN")
        assertThat(cookie.value).isEqualTo("access-token")
        assertThat(cookie.path).isEqualTo("/api")
        assertThat(cookie.maxAge).isEqualTo(15 * 60)
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.isSecure).isTrue()
        assertThat(cookie.sameSite).isEqualTo(NewCookie.SameSite.STRICT)
    }

    @Test
    fun `createRefreshCookie sets expected flags and values`() {
        val cookie = cookieService.createRefreshCookie("refresh-token")

        assertThat(cookie.name).isEqualTo("VEYQ_REFRESH_TOKEN")
        assertThat(cookie.value).isEqualTo("refresh-token")
        assertThat(cookie.path).isEqualTo("/api/auth")
        assertThat(cookie.maxAge).isEqualTo(60 * 60)
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.isSecure).isTrue()
        assertThat(cookie.sameSite).isEqualTo(NewCookie.SameSite.STRICT)
    }

    @Test
    fun `delete cookies set max age zero and preserve names`() {
        val access = cookieService.deleteAccessCookie()
        val refresh = cookieService.deleteRefreshCookie()

        assertThat(access.name).isEqualTo("VEYQ_ACCESS_TOKEN")
        assertThat(access.value).isEqualTo("")
        assertThat(access.maxAge).isEqualTo(0)
        assertThat(access.path).isEqualTo("/api")

        assertThat(refresh.name).isEqualTo("VEYQ_REFRESH_TOKEN")
        assertThat(refresh.value).isEqualTo("")
        assertThat(refresh.maxAge).isEqualTo(0)
        assertThat(refresh.path).isEqualTo("/api/auth")
    }
}
