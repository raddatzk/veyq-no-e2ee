// uc.account.login (web) — operator-blind challenge-response login on the Model B data layer. The password
// never leaves the device: the client derives the Ed25519 auth keypair (ADR 0003), signs the server nonce,
// and stores only the opaque session tokens it gets back. Auth ≠ unlock — this gates a session, not content.
import * as v1 from '../../../shared/crypto/v1'
import { deriveAuthKeypairAsync } from '../../../shared/crypto/argonWorker'
import { b64std, fromB64std } from '../../../shared/base64'
import { loginApi, sessionApi, tokenApi } from '../../../api/client'
import { clearTokens, session, setTokens } from '../../../app/session'
import { clearVault } from '../../../app/vault'
import { setAccount, clearAccount } from '../../../app/account'
import { clearMasterKeyCache } from '../rememberMe/vaultCache'

/**
 * Log in with email + password. Requests a challenge, derives the auth keypair with the instance salt the
 * challenge carries, signs the nonce, and stores the issued session. Throws on failure — a wrong password
 * or unknown account both surface as the same generic 401 (non-enumerable).
 */
export async function login(email: string, password: string, remember = false): Promise<void> {
  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email } })
  const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), email)
  const auth = await deriveAuthKeypairAsync(password, authSalt)
  const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
  const result = await loginApi().verifyLogin({
    loginVerifyRequest: { email, signature: b64std(signature), remember },
  })
  // remember (cr-0052) → the refresh token rides the httpOnly cookie (absent from the body), so keep none in
  // memory and refresh via the cookie. Otherwise store the body token (in-memory, dies on reload).
  setTokens(result.accessToken, result.refreshToken ?? null, result.family.id)
  setAccount(email)
}

/** Rotate the session tokens (silent refresh). Throws if the refresh is rejected — and on rejection drops
 *  the dead tokens so the session lands in a clean logged-out state instead of looping 401→refresh→throw on
 *  every subsequent authed call. */
export async function refreshSession(): Promise<void> {
  try {
    // refreshToken null → the httpOnly cookie carries it (remember me): send an empty body and the browser
    // attaches the cookie. Otherwise send the in-memory body token. A boot with neither yields a 400/401 → the
    // catch drops to a clean logged-out state. The new token comes back in the body (in-memory path) or a fresh
    // cookie (remember path, body token absent → keep null).
    const pair = await tokenApi().refreshSession({
      refreshRequest: { refreshToken: session.refreshToken ?? undefined },
    })
    setTokens(pair.accessToken, pair.refreshToken ?? null)
  } catch (e) {
    clearTokens()
    throw e
  }
}

/** Revoke the current session family, drop local tokens, and re-lock the vault. Idempotent. Centralising
 *  the vault clear here means every sign-out path (this view, a future silent logout on refresh failure, a
 *  route guard) drops the unlocked keys — auth and unlock can never drift out of sync. */
export async function logout(): Promise<void> {
  try {
    await sessionApi().logout()
  } finally {
    clearTokens()
    clearVault()
    clearAccount()
    await clearMasterKeyCache().catch(() => {}) // drop the remembered vault (cr-0052)
  }
}

/**
 * Is this a 401 from a generated client? Duck-typed on purpose: each generated client package
 * (`generated/auth`, `generated/account`, …) defines its OWN `ResponseError` class, so
 * `instanceof ResponseError` from one package is false for an error thrown by another. We match the shared
 * shape (`name === 'ResponseError'` + `response.status`) instead, so the refresh-retry works for every
 * authed client, not just the auth one.
 */
function isUnauthorized(e: unknown): boolean {
  return (
    typeof e === 'object' &&
    e !== null &&
    (e as { name?: unknown }).name === 'ResponseError' &&
    (e as { response?: { status?: unknown } }).response?.status === 401
  )
}

/**
 * Run an authed call, transparently refreshing once on a 401 and retrying — the retry-on-401→refresh wiring
 * authed resource calls (e.g. unlock's key-material fetch) use.
 */
export async function withRefresh<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (e) {
    if (isUnauthorized(e)) {
      // Refresh via the in-memory token OR the httpOnly cookie (we can't see the cookie from JS, so always
      // attempt) and retry once; refreshSession throws (→ propagates as logged-out) when there is no session.
      await refreshSession()
      return await call()
    }
    throw e
  }
}
