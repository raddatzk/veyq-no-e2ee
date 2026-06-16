// uc.account.reset-password (web) — recover a forgotten password with the Secret Key (Emergency Kit) AND
// email access. The email delivers a one-time token (the email factor); the Secret Key decrypts the RECOVERY
// master-key wrap (the crypto factor). The client unwraps the master key, re-wraps it under the new password
// and ships the new PASSWORD wrap + auth verifier. The server sees neither secret.
import * as v1 from '../../../shared/crypto/v1'

const ARGON = v1.ARGON
function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

type MasterKeyWrap = { unlockMethod: string; suiteId: string; wrappedKey: string; kdfParams?: string }
export type ResetMaterial = { email: string; recoveryWrap: MasterKeyWrap }

/** Ask the server to email a reset token. Always resolves (the server never reveals whether the account exists). */
export async function requestPasswordReset(apiBase: string, email: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/account/password-reset/request`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: email.trim() }),
  })
  if (!res.ok) throw new Error(`reset request failed: ${res.status} ${await res.text()}`)
}

/** Exchange the email token for the RECOVERY wrap (the account's master key wrapped under the Secret Key). */
export async function getResetMaterial(apiBase: string, token: string): Promise<ResetMaterial> {
  const res = await fetch(`${apiBase}/api/account/password-reset/material`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ token }),
  })
  if (!res.ok) throw new Error(`reset material failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/**
 * Complete the reset: unwrap the master key with the Secret Key (via the token-gated RECOVERY wrap), then
 * re-wrap it under the new password (PASSWORD wrap) and submit a fresh auth verifier. Returns the account
 * email so the caller can immediately log in with the new password + the Secret Key. Throws on a wrong
 * Secret Key (the RECOVERY wrap fails to open).
 */
export async function confirmPasswordReset(apiBase: string, token: string, secretKeyB64: string, newPassword: string): Promise<{ email: string }> {
  const material = await getResetMaterial(apiBase, token)
  const secretKey = fromB64url(secretKeyB64)

  // unwrap the master key with the Secret Key alone (argon2id(secretKey), salt+params from kdfParams)
  if (!material.recoveryWrap.kdfParams) throw new Error('recovery wrap has no kdf params')
  const { params, salt } = v1.decodeKdfParams(fromB64url(material.recoveryWrap.kdfParams))
  const recKek = v1.kekRecovery(secretKey, salt, params)
  const masterKey = v1.symUnwrap(recKek, fromB64url(material.recoveryWrap.wrappedKey), 'master-key')

  // re-wrap the master key under the NEW password (PASSWORD KEK still folds in the Secret Key — login stays
  // password + Secret Key) and mint a fresh server-side auth verifier for the new password.
  const newSalt = v1.randomBytes(16)
  const newPwWrap = v1.symWrap(v1.kekPasswordSecret(secretKey, newPassword, newSalt, ARGON), masterKey, 'master-key', v1.randomBytes(12))
  const authSalt = v1.randomBytes(16)
  const verifier = v1.kekRecovery(v1.utf8(newPassword), authSalt, ARGON)

  const res = await fetch(`${apiBase}/api/account/password-reset/confirm`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      token,
      masterKeyWrap: { unlockMethod: 'PASSWORD', suiteId: 'v1', wrappedKey: b64url(newPwWrap), kdfParams: b64url(v1.encodeKdfParams(ARGON, newSalt)) },
      authVerifier: { kind: 'ARGON2_PROOF', suiteId: 'v1', verifier: b64url(v1.concat(authSalt, verifier)) },
      // prove Secret-Key possession: reproduce the commitment the server stored for this master key
      recoveryAuthTag: b64url(v1.recoveryAuthTag(masterKey)),
    }),
  })
  if (!res.ok) throw new Error(`reset confirm failed: ${res.status} ${await res.text()}`)
  return { email: material.email }
}

/** Read a `#reset=<token>` reset token out of a URL fragment, or null. */
export function parseResetHash(hash: string): string | null {
  const h = hash.startsWith('#') ? hash.slice(1) : hash
  return new URLSearchParams(h).get('reset')
}
