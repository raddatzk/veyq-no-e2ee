// Device unlock — "remember me on this device" behind biometrics. After a normal login, the credentials
// needed to re-login ({email, password, secretKey}) are AEAD-sealed under a device key and stored locally;
// next time, a biometric/passkey gesture yields the device key, the vault opens, and we re-login silently.
// Auth stays password-based on the server — the device just supplies the credentials behind the biometric.
// The device key never leaves the device; on web it comes from a passkey's PRF, on mobile from Keychain/Keystore.
import * as v1 from '../../../shared/crypto/v1'

const EMPTY = new Uint8Array(0)

export type DeviceCredentials = { email: string; password: string; secretKey: string }

/** Seal the login credentials under the device key (nonce || AEAD). */
export function sealVault(deviceKey: Uint8Array, creds: DeviceCredentials): Uint8Array {
  const nonce = v1.randomBytes(12)
  return v1.concat(nonce, v1.aeadSeal(deviceKey, nonce, v1.utf8(JSON.stringify(creds)), EMPTY))
}

/** Open a sealed vault with the device key (throws if the key is wrong — e.g. a different passkey). */
export function openVault(deviceKey: Uint8Array, blob: Uint8Array): DeviceCredentials {
  return JSON.parse(new TextDecoder().decode(v1.aeadOpen(deviceKey, blob.slice(0, 12), blob.slice(12), EMPTY)))
}
