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

/**
 * Log in with email + password. Requests a challenge, derives the auth keypair with the instance salt the
 * challenge carries, signs the nonce, and stores the issued session. Throws on failure — a wrong password
 * or unknown account both surface as the same generic 401 (non-enumerable).
 */
export async function login(email: string, password: string): Promise<void> {
  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email } })
  const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), email)
  const auth = await deriveAuthKeypairAsync(password, authSalt)
  const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
  const result = await loginApi().verifyLogin({
    loginVerifyRequest: { email, signature: b64std(signature) },
  })
  setTokens(result.accessToken, result.refreshToken, result.family.id)
  setAccount(email)
}

/** Rotate the session tokens (silent refresh). Throws if the refresh is rejected — and on rejection drops
 *  the dead tokens so the session lands in a clean logged-out state instead of looping 401→refresh→throw on
 *  every subsequent authed call. */
export async function refreshSession(): Promise<void> {
  const refreshToken = session.refreshToken
  if (refreshToken === null) throw new Error('no_session')
  try {
    const pair = await tokenApi().refreshSession({ refreshRequest: { refreshToken } })
    setTokens(pair.accessToken, pair.refreshToken)
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
    if (isUnauthorized(e) && session.refreshToken !== null) {
      await refreshSession()
      return await call()
    }
    throw e
  }
}
