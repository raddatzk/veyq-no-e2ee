// uc.account.unlock (web) — open the account Master Key on-device from the PASSWORD wrap using the password
// AND the Secret Key (two-secret key derivation, ADR 0003/0005). Operator-blind: the client fetches only
// ciphertext, derives the Unlock KEK locally, and never sends the password, the Secret Key, the KEK, or any
// unwrapped key. Auth ≠ unlock — this needs an authenticated session (the fetch carries the bearer token).
import * as v1 from '../../../shared/crypto/v1'
import { fromB64std } from '../../../shared/base64'
import { accountApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import { setUnlocked } from '../../../app/vault'
import { MasterKeyWrapMethodEnum } from '../../../api/generated/account'

/**
 * Unlock the Master Key with the password + Secret Key. Fetches the caller's key material (silently
 * refreshing the session on a 401), selects the PASSWORD wrap, derives the Unlock KEK from its KDF params,
 * AEAD-unwraps the Master Key, then unwraps the identity private key with it, and stores both in the
 * in-memory vault. The KEK and the inputs are dropped when this returns; nothing is persisted.
 *
 * Throws on a wrong password / Secret Key (the AEAD tag fails to verify) or a missing PASSWORD wrap — both a
 * generic unlock failure with the server uninvolved; the caller surfaces one indistinguishable message.
 */
export async function unlockMasterKey(password: string, secretKeyB64: string): Promise<void> {
  const material = await withRefresh(() => accountApi().getAccountKeyMaterial())

  const passwordWrap = material.wraps.find((w) => w.method === MasterKeyWrapMethodEnum.Password)
  // E2 — no PASSWORD wrap (or no KDF params to re-derive its KEK): this device/account can't unlock by
  // password; recovery / re-login is the path. Surfaced as the same generic failure.
  if (!passwordWrap?.kdfParams) throw new Error('unlock_unavailable')

  const secretKey = fromB64std(secretKeyB64.trim())
  const { params, salt } = v1.decodeKdfParams(fromB64std(passwordWrap.kdfParams))
  const kek = v1.kekPasswordSecret(secretKey, password, salt, params)

  // E1 — a wrong password or Secret Key yields a wrong KEK, so this AEAD-unwrap throws (tag mismatch). Same
  // for the identity unwrap if the Master Key is wrong. Both bubble up as the generic unlock failure.
  const masterKey = v1.symUnwrap(kek, fromB64std(passwordWrap.wrappedKey), 'master-key')
  const identityPrivate = v1.deserializeIdentityPrivate(
    v1.symUnwrap(masterKey, fromB64std(material.wrappedIdentityPrivateKey), 'identity-priv'),
  )

  setUnlocked(masterKey, identityPrivate)
}
