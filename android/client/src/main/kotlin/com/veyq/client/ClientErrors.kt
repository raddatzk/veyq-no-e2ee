package com.veyq.client

import com.veyq.api.setup.infrastructure.ClientException as SetupClientException
import com.veyq.api.auth.infrastructure.ClientException as AuthClientException
import com.veyq.api.account.infrastructure.ClientException as AccountClientException
import com.veyq.api.scopes.infrastructure.ClientException as ScopesClientException
import com.veyq.api.content.infrastructure.ClientException as ContentClientException
import com.veyq.api.media.infrastructure.ClientException as MediaClientException

/**
 * The one place that knows every generated client's error type.
 *
 * Each generated OpenAPI package (`com.veyq.api.setup`, `…auth`, `…account`, `…scopes`, `…content`, `…media`)
 * ships its OWN `infrastructure.ClientException` with no shared base beyond `RuntimeException`, so a
 * per-method `catch (e: XxxClientException)` had to be copied ~15× across [VeyqClient] — the Android twin of
 * the web `shared/httpError.ts` and Apple's per-operation `switch out`. These two helpers collapse that: the
 * types are listed once here, and every resource call reuses [mapStatus].
 *
 * `ServerException` (5xx) is deliberately NOT listed — it was never caught before, so a 5xx keeps propagating
 * raw exactly as it did (behaviour preserved).
 */
internal fun clientStatusCode(e: Throwable): Int? = when (e) {
    is SetupClientException -> e.statusCode
    is AuthClientException -> e.statusCode
    is AccountClientException -> e.statusCode
    is ScopesClientException -> e.statusCode
    is ContentClientException -> e.statusCode
    is MediaClientException -> e.statusCode
    else -> null
}

/**
 * Run a generated blocking call, translating its `ClientException` into a [VeyqError] via [map] (the call's
 * own `when (status) { … }`, unchanged). A throwable that is not a known client error — a genuine bug, or a
 * 5xx `ServerException` — is re-thrown untouched, so nothing is swallowed.
 */
internal inline fun <T> mapStatus(map: (Int) -> VeyqError, block: () -> T): T =
    try {
        block()
    } catch (e: RuntimeException) {
        throw map(clientStatusCode(e) ?: throw e)
    }
