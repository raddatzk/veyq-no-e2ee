// uc.account.unlock (web) — open the account Master Key on-device. The floor path uses the password AND the
// Secret Key (2SKD); a trusted device (uc.account.trust-device) opens the DEVICE wrap with only the password.
// Operator-blind: the client fetches only ciphertext, derives the Unlock KEK locally, and never sends the
// password, the Secret Key, the KEK, or any unwrapped key. Auth ≠ unlock — the fetch carries the bearer token.
import * as v1 from '../../../shared/crypto/v1'
import { unlockKekAsync, deviceKekAsync } from '../../../shared/crypto/argonWorker'
import { fromB64std } from '../../../shared/base64'
import { accountApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import { setUnlocked } from '../../../app/vault'
import { MasterKeyWrapMethodEnum, type AccountKeyMaterial } from '../../../api/generated/account'
import * as device from '../trustDevice/deviceKey'

function fetchKeyMaterial(): Promise<AccountKeyMaterial> {
  return withRefresh(() => accountApi().getAccountKeyMaterial())
}

/** Unwrap the identity private key with the opened Master Key and store both in the in-memory vault. */
function openVault(material: AccountKeyMaterial, masterKey: Uint8Array): void {
  const identityPrivate = v1.deserializeIdentityPrivate(
    v1.symUnwrap(masterKey, fromB64std(material.wrappedIdentityPrivateKey), 'identity-priv'),
  )
  setUnlocked(masterKey, identityPrivate)
}

/**
 * Unlock with the password + Secret Key (PASSWORD floor). Fetches the caller's key material (refreshing the
 * session on a 401), derives the Unlock KEK, AEAD-unwraps the Master Key, then the identity private key.
 * Throws on a wrong password / Secret Key (AEAD tag mismatch) or a missing PASSWORD wrap — one generic
 * failure, server uninvolved.
 */
export async function unlockMasterKey(password: string, secretKeyB64: string): Promise<void> {
  const material = await fetchKeyMaterial()
  const passwordWrap = material.wraps.find((w) => w.method === MasterKeyWrapMethodEnum.Password)
  if (!passwordWrap?.kdfParams) throw new Error('unlock_unavailable') // E2 — no floor wrap
  const secretKey = fromB64std(secretKeyB64.trim())
  const { params, salt } = v1.decodeKdfParams(fromB64std(passwordWrap.kdfParams))
  const kek = await unlockKekAsync(secretKey, password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(passwordWrap.wrappedKey), 'master-key')
  openVault(material, masterKey)
}

/**
 * Unlock on a trusted device with only the password (DEVICE wrap). Returns **false** when this device can't
 * use the DEVICE method — no local device key, or the matching wrap is gone server-side (orphan trust,
 * cleared here) — so the caller falls back to the PASSWORD floor. **Throws** on a wrong password (AEAD tag
 * mismatch) → the same generic failure as the floor path.
 */
export async function unlockWithDevice(email: string, password: string): Promise<boolean> {
  const trust = await device.loadTrust(email)
  if (!trust) return false
  const material = await fetchKeyMaterial()
  const wrap = material.wraps.find(
    (w) => w.method === MasterKeyWrapMethodEnum.Device && w.kdfParams === trust.kdfParams,
  )
  if (!wrap?.kdfParams) {
    await device.clearTrust(email) // the server wrap is gone — drop the dangling local key
    return false
  }
  const deviceFactor = await device.deviceFactor(trust.cryptoKey)
  const { params, salt } = v1.decodeKdfParams(fromB64std(wrap.kdfParams))
  const kek = await deviceKekAsync(deviceFactor, password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(wrap.wrappedKey), 'master-key')
  openVault(material, masterKey)
  return true
}
