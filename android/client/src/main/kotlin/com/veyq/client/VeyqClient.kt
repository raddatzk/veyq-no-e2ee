package com.veyq.client

import com.veyq.api.account.api.AccountApi
import com.veyq.api.account.model.AccountKeyMaterial
import com.veyq.api.account.model.MasterKeyWrap
import com.veyq.api.auth.api.LoginApi
import com.veyq.api.auth.api.SessionApi
import com.veyq.api.auth.api.TokenApi
import com.veyq.api.auth.model.LoginChallengeRequest
import com.veyq.api.auth.model.LoginVerifyRequest
import com.veyq.api.auth.model.RefreshRequest
import com.veyq.api.setup.api.SetupApi
import com.veyq.crypto.V1
import okhttp3.OkHttpClient
import java.util.Base64
import com.veyq.api.account.infrastructure.ClientException as AccountClientException
import com.veyq.api.auth.infrastructure.ClientException as AuthClientException

/**
 * The operator-blind login + unlock data layer — the Android twin of Apple's `VeyqClient` and the web
 * `features/account`. The password never leaves the device: login derives an Ed25519 auth keypair (ADR
 * 0003) from the password + the instance salt the challenge carries and signs the server nonce; unlock
 * derives the Unlock KEK on-device and AEAD-unwraps the Master Key + identity private into the in-memory
 * [Vault]. Only ciphertext and opaque tokens cross the wire.
 *
 * The generated convenience methods are blocking and throw `ClientException`/`ServerException` on non-2xx,
 * so this orchestration is blocking too (a pure-JVM data layer). The `:app` calls it off the main thread
 * (`Dispatchers.IO`); a future headless JVM agent reuses it directly.
 */
class VeyqClient(
    val instance: VeyqInstance,
    val session: Session = Session(),
    val vault: Vault = Vault(),
    client: OkHttpClient = defaultClient(session),
) {
    private val setup = SetupApi(instance.baseUrl, client)
    private val login = LoginApi(instance.baseUrl, client)
    private val token = TokenApi(instance.baseUrl, client)
    private val sessions = SessionApi(instance.baseUrl, client)
    private val account = AccountApi(instance.baseUrl, client)

    /**
     * Whether this instance still needs first-run setup. Native never bootstraps (setup is browser-only),
     * so the connect screen uses this to tell the user to finish setup in a browser rather than offering a
     * form. Unauthenticated — no session needed.
     */
    fun setupRequired(): Boolean = setup.getSetupStatus().setupRequired

    /**
     * Log in with email + password. Requests a challenge, derives the auth keypair with the instance salt
     * the challenge carries, signs the nonce, and stores the issued session. Throws on failure — a wrong
     * password or unknown account both surface as [VeyqError.AuthenticationFailed] (non-enumerable).
     */
    fun login(email: String, password: String) {
        val challenge = try {
            login.requestLoginChallenge(LoginChallengeRequest(email = email))
        } catch (e: AuthClientException) {
            throw if (e.statusCode == 400) VeyqError.InvalidRequest(null) else VeyqError.UnexpectedStatus(e.statusCode)
        }

        val authSalt = V1.deriveAuthSalt(b64decode(challenge.instanceSalt), email)
        val keypair = V1.deriveAuthKeypair(password, authSalt)
        val signature = V1.authSign(keypair.privateKey, b64decode(challenge.nonce))

        val issued = try {
            login.verifyLogin(LoginVerifyRequest(email = email, signature = b64encode(signature)))
        } catch (e: AuthClientException) {
            throw when (e.statusCode) {
                401 -> VeyqError.AuthenticationFailed
                400 -> VeyqError.InvalidRequest(null)
                else -> VeyqError.UnexpectedStatus(e.statusCode)
            }
        }
        session.set(issued.accessToken, issued.refreshToken, issued.family.id.toString())
    }

    /**
     * Rotate the session tokens (silent refresh). On rejection it drops the dead tokens so the session
     * lands in a clean logged-out state instead of looping 401 → refresh → throw (mirrors web/Apple).
     */
    fun refresh() {
        val refreshToken = session.refreshToken ?: throw VeyqError.NoSession
        try {
            val pair = token.refreshSession(RefreshRequest(refreshToken = refreshToken))
            session.set(pair.accessToken, pair.refreshToken)
        } catch (e: Exception) {
            session.clear()
            throw VeyqError.RefreshRejected
        }
    }

    /**
     * Revoke the current session family and drop local tokens + vault. Idempotent — best-effort on the
     * wire (a network error still clears locally).
     */
    fun logout() {
        try {
            sessions.logout()
        } catch (_: Exception) {
            // best-effort: a failed revoke still clears locally
        }
        session.clear()
        vault.clear()
    }

    /**
     * Unlock with the password + Secret Key (PASSWORD floor, 2SKD). Fetches the caller's key material
     * (refreshing the session once on a 401), derives the Unlock KEK on-device, AEAD-unwraps the Master Key
     * then the identity private key — both into the in-memory [Vault]. Operator-blind: only ciphertext is
     * fetched; the password, the Secret Key, the KEK and the unwrapped keys never leave the device. Throws
     * [VeyqError.UnlockFailed] on a wrong password / Secret Key (one generic failure), or
     * [VeyqError.UnlockUnavailable] when there is no PASSWORD wrap. Mirrors web/Apple `unlock`.
     */
    fun unlock(password: String, secretKey: String) {
        val material = withRefresh { fetchKeyMaterial() }

        val wrap = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.PASSWORD }
            ?: throw VeyqError.UnlockUnavailable
        val kdfParams = wrap.kdfParams ?: throw VeyqError.UnlockUnavailable

        val secretKeyBytes = try {
            b64decode(secretKey.trim())
        } catch (e: IllegalArgumentException) {
            throw VeyqError.InvalidSecretKey
        }

        val (params, salt) = V1.decodeKdfParams(b64decode(kdfParams))
        val kek = V1.kekPasswordSecret(secretKeyBytes, password, salt, params)
        try {
            val masterKey = V1.symUnwrap(kek, b64decode(wrap.wrappedKey), "master-key")
            val identityPrivate = V1.symUnwrap(masterKey, b64decode(material.wrappedIdentityPrivateKey), "identity-priv")
            vault.open(masterKey, identityPrivate)
        } catch (e: Exception) {
            throw VeyqError.UnlockFailed
        }
    }

    /**
     * Fetch the caller's wrapped key material. A 401 is surfaced as [VeyqError.SessionExpired] so
     * [withRefresh] can rotate-and-retry (the resource analog of the web 401→refresh wiring).
     */
    private fun fetchKeyMaterial(): AccountKeyMaterial = try {
        account.getAccountKeyMaterial()
    } catch (e: AccountClientException) {
        throw if (e.statusCode == 401) VeyqError.SessionExpired else VeyqError.UnexpectedStatus(e.statusCode)
    }

    /**
     * Run an authed call, transparently refreshing once on a [VeyqError.SessionExpired] and retrying — the
     * rotate-on-401 wiring the key-material fetch uses. Resource calls signal a 401 by throwing
     * `SessionExpired`; this catches it, refreshes, and retries once.
     */
    fun <T> withRefresh(operation: () -> T): T {
        return try {
            operation()
        } catch (e: VeyqError.SessionExpired) {
            if (session.refreshToken == null) throw VeyqError.SessionExpired
            refresh()
            operation()
        }
    }

    companion object {
        /** The shared OkHttp client every generated API uses, with the [BearerInterceptor] reading [session]. */
        fun defaultClient(session: Session): OkHttpClient =
            OkHttpClient.Builder().addInterceptor(BearerInterceptor(session)).build()

        private fun b64decode(s: String): ByteArray = Base64.getDecoder().decode(s)
        private fun b64encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    }
}
