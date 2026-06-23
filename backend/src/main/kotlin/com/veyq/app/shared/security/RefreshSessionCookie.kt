package com.veyq.app.shared.security

const val REFRESH_SESSION_COOKIE = "VEYQ_REFRESH_TOKEN"

fun refreshSessionCookie(session: IssuedSession): String =
    "$REFRESH_SESSION_COOKIE=${session.refreshToken}; Max-Age=${session.refreshMaxAgeSeconds}; Path=/api/auth/refresh; HttpOnly; SameSite=Lax"

fun clearRefreshSessionCookie(): String =
    "$REFRESH_SESSION_COOKIE=; Max-Age=0; Path=/api/auth/refresh; HttpOnly; SameSite=Lax"
