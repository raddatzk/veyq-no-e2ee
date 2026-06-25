// uc.account.login (web) — operator-blind challenge-response login on the Model B data layer. The password
// never leaves the device: the client derives the Ed25519 auth keypair (ADR 0003), signs the server nonce,
// and stores only the opaque session tokens it gets back. Auth ≠ unlock — this gates a session, not content.
import * as v1 from '../../../shared/crypto/v1'
import { b64std, fromB64std } from '../../../shared/base64'
import { loginApi, sessionApi, tokenApi } from '../../../api/client'
import { ResponseError } from '../../../api/generated/auth'
import { clearTokens, session, setTokens } from '../../../app/session'

/**
 * Log in with email + password. Requests a challenge, derives the auth keypair with the instance salt the
 * challenge carries, signs the nonce, and stores the issued session. Throws on failure — a wrong password
 * or unknown account both surface as the same generic 401 (non-enumerable).
 */
export async function login(email: string, password: string): Promise<void> {
  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email } })
  const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), email)
  const auth = v1.deriveAuthKeypair(password, authSalt)
  const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
  const result = await loginApi().verifyLogin({
    loginVerifyRequest: { email, signature: b64std(signature) },
  })
  setTokens(result.accessToken, result.refreshToken, result.family.id)
}

/** Rotate the session tokens (silent refresh). Throws if the refresh is rejected. */
export async function refreshSession(): Promise<void> {
  const refreshToken = session.refreshToken
  if (refreshToken === null) throw new Error('no_session')
  const pair = await tokenApi().refreshSession({ refreshRequest: { refreshToken } })
  setTokens(pair.accessToken, pair.refreshToken)
}

/** Revoke the current session family and drop local tokens. Idempotent. */
export async function logout(): Promise<void> {
  try {
    await sessionApi().logout()
  } finally {
    clearTokens()
  }
}

/**
 * Run an authed call, transparently refreshing once on a 401 and retrying — the retry-on-401→refresh wiring
 * future authed resource calls use. (This CR has only the idempotent logout; the helper is the pattern.)
 */
export async function withRefresh<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (e) {
    if (e instanceof ResponseError && e.response.status === 401 && session.refreshToken !== null) {
      await refreshSession()
      return await call()
    }
    throw e
  }
}
