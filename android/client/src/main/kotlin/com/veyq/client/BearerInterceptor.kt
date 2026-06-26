package com.veyq.client

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects the current access token as `Authorization: Bearer …` on every outgoing request — the Android
 * twin of Apple's `BearerMiddleware`. Login / challenge / verify carry no token yet, so they go out bare;
 * once a session is held, authed resource calls (logout, the key-material fetch) get the bearer. The token
 * is read from [Session] per request, so a refresh is picked up on the next call automatically.
 *
 * This is why the orchestration does NOT set the generated `ApiClient.accessToken` static: one interceptor
 * on the shared OkHttp client covers all three generated packages from a single [Session].
 */
class BearerInterceptor(private val session: Session) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = session.accessToken
        val request = if (token != null) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
