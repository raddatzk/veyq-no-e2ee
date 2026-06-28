package com.veyq.client

import com.veyq.api.account.api.AccountApi
import com.veyq.api.account.api.AccountWrapsApi
import com.veyq.api.account.model.AccountKeyMaterial
import com.veyq.api.account.model.AddWrapRequest
import com.veyq.api.account.model.MasterKeyWrap
import com.veyq.api.auth.api.LoginApi
import com.veyq.api.auth.api.SessionApi
import com.veyq.api.auth.api.TokenApi
import com.veyq.api.auth.model.LoginChallengeRequest
import com.veyq.api.auth.model.LoginVerifyRequest
import com.veyq.api.auth.model.RefreshRequest
import com.veyq.api.scopes.api.ScopesApi
import com.veyq.api.scopes.model.CreatePersonalScopeRequest
import com.veyq.api.scopes.model.ScopeKind
import com.veyq.api.scopes.model.ScopeSummary
import com.veyq.api.setup.api.SetupApi
import com.veyq.crypto.V1
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.Base64
import com.veyq.api.account.infrastructure.ClientException as AccountClientException
import com.veyq.api.auth.infrastructure.ClientException as AuthClientException
import com.veyq.api.scopes.infrastructure.ClientException as ScopesClientException

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
    private val deviceSecurity: DeviceSecurityStore = DisabledDeviceSecurityStore,
) {
    private val setup = SetupApi(instance.baseUrl, client)
    private val login = LoginApi(instance.baseUrl, client)
    private val token = TokenApi(instance.baseUrl, client)
    private val sessions = SessionApi(instance.baseUrl, client)
    private val account = AccountApi(instance.baseUrl, client)
    private val accountWraps = AccountWrapsApi(instance.baseUrl, client)
    private val scopes = ScopesApi(instance.baseUrl, client)

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
        session.set(issued.accessToken, issued.refreshToken, issued.family.id.toString(), account = email)
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
            persistRotatedTokenIfPasswordless()
        } catch (e: Exception) {
            session.clear()
            throw VeyqError.RefreshRejected
        }
    }

    /** Keep the persisted refresh token in sync after a rotation for an L2/L3 device — so the next app launch
     *  resumes with a live token. The token is **sealed under K** (cr-0023) so it is never stored in the
     *  clear; sealing uses the in-memory K (held since resume/enrol), so this stays prompt-free. No-op for
     *  L0/L1, or if K isn't held — we never write a plaintext token (a stale sealed token just falls back to
     *  the floor on next launch, which is safe). */
    private fun persistRotatedTokenIfPasswordless() {
        val account = session.account ?: return
        val level = deviceSecurity.currentLevel(account)
        if (level != DeviceLevel.BIOMETRIC && level != DeviceLevel.NO_LOGIN) return
        val token = session.refreshToken ?: return
        val k = vault.deviceKey() ?: return
        try {
            deviceSecurity.updateRefreshToken(account, tokenSeal(token, k))
        } finally {
            k.fill(0)
        }
    }

    /** Seal a refresh token under the device wrap key K (cr-0023): base64(symWrap(K, utf8(token),
     *  "session-token")). Stored where the plaintext token used to live. */
    private fun tokenSeal(token: String, k: ByteArray): String =
        b64encode(V1.symWrap(k, token.toByteArray(Charsets.UTF_8), "session-token", randomBytes(12)))

    /** Open a sealed token, or null if it isn't a valid `session-token` blob — which is also how a legacy
     *  plaintext token is detected (unseal fails → use the value as-is once, then the next rotation seals it). */
    private fun tokenUnseal(stored: String, k: ByteArray): String? = try {
        String(V1.symUnwrap(k, b64decode(stored), "session-token"), Charsets.UTF_8)
    } catch (e: Exception) {
        null
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
            vault.open(masterKey, identityPrivate)   // vault now owns these buffers — do NOT zero them here
        } catch (e: Exception) {
            throw VeyqError.UnlockFailed
        } finally {
            // The derived KEK and the Secret-Key bytes are purely local; wipe them so they don't linger on
            // the heap (the Master Key itself is zeroed by Vault.clear on lock/sign-out).
            kek.fill(0)
            secretKeyBytes.fill(0)
        }
    }

    // --- Device trust (cr-0021, L1 — "only password"; uc.account.trust-device) ---

    /** Whether this device is at the "only password" level (an L1 DEVICE wrap is enrolled → the login-fold
     *  can unlock with just the password). */
    fun isTrusted(email: String): Boolean = deviceSecurity.currentLevel(email) == DeviceLevel.ONLY_PASSWORD

    /**
     * Routine unlock on a trusted device: open the Master Key from this device's DEVICE wrap with **only
     * the password** (no Secret Key). Returns `false` when unavailable — no trust record, or no matching
     * server wrap — so the caller falls back to the floor; throws [VeyqError.UnlockFailed] on a wrong
     * password (AEAD tag mismatch). Does **not** clear the local key on a missing server wrap (cr-0005 #2):
     * a blind operator must not be able to withhold the wrap to force a wipe.
     */
    fun unlockWithDevice(email: String, password: String): Boolean {
        if (deviceSecurity.currentLevel(email) != DeviceLevel.ONLY_PASSWORD) return false
        val wrapId = deviceSecurity.wrapId(email) ?: return false
        val deviceFactor = deviceSecurity.deviceFactor(email) ?: return false
        val material = withRefresh { fetchKeyMaterial() }
        val wrap = material.wraps.firstOrNull { it.id == wrapId && it.method == MasterKeyWrap.Method.DEVICE }
            ?: return false
        val kdfParams = wrap.kdfParams ?: return false

        val (params, salt) = V1.decodeKdfParams(b64decode(kdfParams))
        val kek = V1.kekDevicePassword(deviceFactor, password, salt, params)
        try {
            val masterKey = V1.symUnwrap(kek, b64decode(wrap.wrappedKey), "master-key")
            val identityPrivate = V1.symUnwrap(masterKey, b64decode(material.wrappedIdentityPrivateKey), "identity-priv")
            vault.open(masterKey, identityPrivate)   // vault-owned now — do NOT zero
        } catch (e: Exception) {
            throw VeyqError.UnlockFailed
        } finally {
            kek.fill(0)   // local derived KEK (the deviceFactor is store-owned — the store may hand back its
                          // own retained buffer, so we must not wipe it here)
        }
        return true
    }

    /**
     * Trust this device: enrol a DEVICE wrap of the **already-unlocked** Master Key under
     * `KEK = Argon2id(deviceFactor + password)`. Runs at unlock time, reusing the known-correct password.
     * **Re-trust replaces** (deletes any prior wrap first; the device-bound `deviceFactor` is stable). The
     * **Secret Key is never read or stored**. On a commit failure the just-created server wrap is **rolled
     * back** (no orphan) and [VeyqError.TrustFailed] is thrown; the preceding unlock still stands.
     */
    fun trustThisDevice(email: String, password: String) {
        val masterKey = vault.masterKey() ?: throw VeyqError.UnlockUnavailable

        deviceSecurity.wrapId(email)?.let { priorId ->
            runCatching { withRefresh { deleteWrap(priorId) } }   // re-trust replaces
        }

        val deviceFactor = deviceSecurity.beginEnrol(email)
        val salt = randomBytes(16)
        val kek = V1.kekDevicePassword(deviceFactor, password, salt, V1.ARGON)
        val wrappedKey = V1.symWrap(kek, masterKey, "master-key", randomBytes(12))
        val kdfParams = V1.encodeKdfParams(V1.ARGON, salt)
        // Done with the plaintext key material — wipe the local copies before the network round-trip. The
        // deviceFactor is store-owned (the store may return its own retained buffer), so it is not wiped here.
        masterKey.fill(0)   // a fresh copy from vault.masterKey(), not the vault's own buffer
        kek.fill(0)

        val wrapId = withRefresh { addDeviceWrap(b64encode(wrappedKey), b64encode(kdfParams)) }
        try {
            deviceSecurity.commitOnlyPassword(email, wrapId)
        } catch (e: Exception) {
            runCatching { withRefresh { deleteWrap(wrapId) } }   // rollback — no orphan
            throw VeyqError.TrustFailed
        }
    }

    /**
     * Untrust this device: delete this device's DEVICE wrap server-side (best-effort, idempotent) and drop
     * the local key + wrap id. Other devices keep their own wraps; the floor is never removable.
     */
    fun untrustThisDevice(email: String) {
        deviceSecurity.wrapId(email)?.let { id -> runCatching { withRefresh { deleteWrap(id) } } }
        deviceSecurity.clear(email)
    }

    // --- Passwordless levels (cr-0022, L2 biometric / L3 no-login; uc.account.{biometric,auto}-unlock) ---
    // The biometric flow + the random key K live in :app (BiometricPrompt is Activity-bound + async); these
    // take/return K as plain bytes and never touch the platform gate.

    /** The device's current unlock level (no biometric prompt — read from the meta). */
    fun currentLevel(email: String): DeviceLevel? = deviceSecurity.currentLevel(email)

    /**
     * Set this device to a **passwordless** level (L2/L3): wrap the already-unlocked Master Key under a
     * caller-supplied random key `K` (`symWrap(K, masterKey)`, stored method=DEVICE — no contract change),
     * and persist `{level, wrapId, current refresh token}`. **Replaces** any prior convenience wrap. `:app`
     * stores `K` itself (biometric-gated for L2, ungated for L3). Rolls back the server wrap on a commit
     * failure. Needs an unlocked vault + a live session (the persisted token resumes it without a password).
     */
    fun setPasswordlessLevel(email: String, level: DeviceLevel, k: ByteArray) {
        require(level == DeviceLevel.BIOMETRIC || level == DeviceLevel.NO_LOGIN) { "setPasswordlessLevel is L2/L3 only" }
        val masterKey = vault.masterKey() ?: throw VeyqError.UnlockUnavailable
        val refreshToken = session.refreshToken ?: throw VeyqError.NoSession

        deviceSecurity.wrapId(email)?.let { priorId -> runCatching { withRefresh { deleteWrap(priorId) } } }

        val wrappedKey = V1.symWrap(k, masterKey, "master-key", randomBytes(12))
        masterKey.fill(0)   // local copy from vault.masterKey(); K is caller-owned (:app stores it) — leave it
        val wrapId = withRefresh { addDeviceWrap(b64encode(wrappedKey), b64encode("v1/device-key".toByteArray())) }
        try {
            deviceSecurity.commitPasswordless(email, level, wrapId, tokenSeal(refreshToken, k))   // sealed, not clear
        } catch (e: Exception) {
            runCatching { withRefresh { deleteWrap(wrapId) } }   // rollback — no orphan
            throw VeyqError.TrustFailed
        }
        vault.holdDeviceKey(k)   // retain K so this session's rotations re-seal the token prompt-free
    }

    /**
     * Resume a persisted session for a passwordless device: read the stored refresh token, **unseal it with
     * K** (cr-0023 — the token is stored sealed, never in the clear; a legacy plaintext token unseal-fails
     * and is used as-is once, then re-sealed on rotation), install it, and rotate to a live session. `:app`
     * supplies K (decrypted via BiometricPrompt for L2 / read ungated for L3), so the one biometric prompt
     * already happened there. Returns `false` when there is no persisted token, or `false` (not throw) when
     * the token is dead — the caller falls back to the floor.
     */
    fun resumeSession(email: String, k: ByteArray): Boolean {
        val stored = deviceSecurity.refreshToken(email) ?: return false
        val token = tokenUnseal(stored, k) ?: stored   // lazy migration: a legacy plaintext token unseal-fails
        vault.holdDeviceKey(k)                          // so the rotation below re-seals prompt-free
        session.set("", token, account = email)
        return try {
            refresh()   // rotates to live tokens + re-seals the new refresh token under K
            true
        } catch (e: Exception) {
            vault.clear()
            false
        }
    }

    /**
     * Open the Master Key from this device's **K-wrap** (L2/L3) — `K` *is* the KEK, used directly (no
     * password, no Argon2id). `:app` supplies `k` (decrypted via BiometricPrompt for L2, read ungated for
     * L3). Returns `false` when unavailable (no passwordless level / no matching wrap → floor, **without**
     * self-wiping); throws [VeyqError.UnlockFailed] on an AEAD mismatch.
     */
    fun unlockWithKey(email: String, k: ByteArray): Boolean {
        val level = deviceSecurity.currentLevel(email)
        if (level != DeviceLevel.BIOMETRIC && level != DeviceLevel.NO_LOGIN) return false
        val wrapId = deviceSecurity.wrapId(email) ?: return false
        vault.holdDeviceKey(k)   // retain K for prompt-free rotation this session
        val material = withRefresh { fetchKeyMaterial() }
        val wrap = material.wraps.firstOrNull { it.id == wrapId && it.method == MasterKeyWrap.Method.DEVICE }
            ?: return false
        try {
            val masterKey = V1.symUnwrap(k, b64decode(wrap.wrappedKey), "master-key")
            val identityPrivate = V1.symUnwrap(masterKey, b64decode(material.wrappedIdentityPrivateKey), "identity-priv")
            vault.open(masterKey, identityPrivate)
        } catch (e: Exception) {
            throw VeyqError.UnlockFailed
        }
        return true
    }

    /**
     * App-launch auto-restore for a passwordless device (L2/L3) — the data-layer half of the single-prompt
     * launch flow (ADR 0011, docs/decisions/0011-uniform-passwordless-unlock-orchestration.md).
     * `:app` releases the device key [k] **once** (one biometric gesture for L2, an ungated read for L3) and
     * hands the **same** bytes here; this resumes the persisted session and opens the vault, and **neither
     * step prompts** — so the single prompt is an `:app` property, identical to Apple's `autoRestore`.
     * Returns `false` (caller falls back to the floor) when there is no passwordless record, the token is
     * dead, or the wrap is unusable.
     */
    fun autoRestore(email: String, k: ByteArray): Boolean {
        val level = deviceSecurity.currentLevel(email)
        if (level != DeviceLevel.BIOMETRIC && level != DeviceLevel.NO_LOGIN) return false
        if (!resumeSession(email, k)) return false
        return unlockWithKey(email, k)
    }

    // --- Personal scope (cr-0026, uc.spaces.personal-scope) ---

    /** The caller's scopes in the active tenant (each with the caller's own ciphertext grant). */
    fun listMyScopes(): List<ScopeSummary> = withRefresh { fetchScopes() }

    /**
     * Ensure this member has a PERSONAL scope in the active tenant — auto-birth, operator-blind: generate the
     * scope key, seal it to our **own** identity, and POST only the ciphertext grant. No-op if one already
     * exists. Returns whether a scope was created. Needs an unlocked vault (the identity bundle).
     */
    fun ensurePersonalScope(): Boolean {
        if (listMyScopes().any { it.kind == ScopeKind.PERSONAL }) return false
        val idBytes = vault.identityPrivateKey() ?: throw VeyqError.UnlockUnavailable
        val priv = V1.deserializeIdentityPrivate(idBytes)
        val scopeKey = randomBytes(32)
        try {
            val grant = b64encode(V1.asymWrap(V1.hybridPublic(priv), scopeKey, "scope-grant", randomBytes(12)))
            withRefresh { postPersonalScope(grant) }
            return true
        } finally {
            scopeKey.fill(0); priv.kemX.fill(0); priv.mlkemSeed.fill(0); priv.sign.fill(0)
        }
    }

    /**
     * Open the member's personal scope → the in-memory scope key (asymUnwrap our **own** grant with the
     * vaulted identity), or null when there is none / the vault is locked.
     */
    fun openPersonalScope(): ByteArray? {
        val idBytes = vault.identityPrivateKey() ?: return null
        val priv = V1.deserializeIdentityPrivate(idBytes)
        try {
            val personal = listMyScopes().firstOrNull { it.kind == ScopeKind.PERSONAL } ?: return null
            return V1.asymUnwrap(V1.hybridSecret(priv), V1.hybridPublic(priv), b64decode(personal.wrappedGrant), "scope-grant")
        } finally {
            priv.kemX.fill(0); priv.mlkemSeed.fill(0); priv.sign.fill(0)
        }
    }

    /** GET the caller's scopes. 401 → [VeyqError.SessionExpired] so [withRefresh] rotates-and-retries. */
    private fun fetchScopes(): List<ScopeSummary> = try {
        scopes.listMyScopes()
    } catch (e: ScopesClientException) {
        throw if (e.statusCode == 401) VeyqError.SessionExpired else VeyqError.UnexpectedStatus(e.statusCode)
    }

    /** POST the personal-scope grant (opaque ciphertext). 409 → already exists, 404 → no tenant context. */
    private fun postPersonalScope(grant: String) {
        try {
            scopes.createPersonalScope(CreatePersonalScopeRequest(wrappedGrant = grant))
        } catch (e: ScopesClientException) {
            throw when (e.statusCode) {
                409 -> VeyqError.PersonalScopeExists
                404 -> VeyqError.ScopeTenantUnavailable
                401 -> VeyqError.SessionExpired
                400 -> VeyqError.InvalidRequest(null)
                else -> VeyqError.UnexpectedStatus(e.statusCode)
            }
        }
    }

    /** POST a DEVICE wrap; returns its server id. 401 → [VeyqError.SessionExpired] (so [withRefresh] rotates
     *  and retries), 409 → [VeyqError.TooManyDevices], 400 → [VeyqError.InvalidRequest]. */
    private fun addDeviceWrap(wrappedKey: String, kdfParams: String): String = try {
        accountWraps.addAccountWrap(
            AddWrapRequest(method = AddWrapRequest.Method.DEVICE, wrappedKey = wrappedKey, kdfParams = kdfParams)
        ).id
    } catch (e: AccountClientException) {
        throw when (e.statusCode) {
            409 -> VeyqError.TooManyDevices
            401 -> VeyqError.SessionExpired
            400 -> VeyqError.InvalidRequest(null)
            else -> VeyqError.UnexpectedStatus(e.statusCode)
        }
    }

    /** DELETE a wrap by id (idempotent 204 server-side). 401 → [VeyqError.SessionExpired] for [withRefresh]. */
    private fun deleteWrap(id: String) {
        try {
            accountWraps.deleteAccountWrap(id)
        } catch (e: AccountClientException) {
            throw if (e.statusCode == 401) VeyqError.SessionExpired else VeyqError.UnexpectedStatus(e.statusCode)
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
        private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
    }
}
