// uc.account.recover-keys (web) — orchestrates the passkey + vault: enable it after a login, then unlock
// silently next time. The sealed vault + the passkey credential id live in localStorage; the device key
// (passkey PRF) is never stored, so the vault is useless without the passkey gesture.
import { getPasskeyDeviceKey, passkeySupported, registerPasskey } from './passkey'
import { openVault, sealVault, type DeviceCredentials } from './deviceVault'
import { b64url, fromB64url } from '../../../shared/base64'

const LS_KEY = 'veyq.deviceUnlock'

type Stored = { credentialId: string; vault: string } // vault = base64url(sealed blob)

const read = (): Stored | null => { try { return JSON.parse(localStorage.getItem(LS_KEY) ?? 'null') } catch { return null } }

/** Whether this device can offer passkey unlock (browser supports passkeys). */
export const deviceUnlockSupported = passkeySupported

/** Whether device unlock has been set up on this device for some account. */
export function deviceUnlockEnabled(): boolean {
  return read() !== null
}

/** Set up device unlock: create a passkey and seal the login credentials under its device key. */
export async function enableDeviceUnlock(creds: DeviceCredentials): Promise<void> {
  const { credentialId, deviceKey } = await registerPasskey(creds.email)
  const stored: Stored = { credentialId, vault: b64url(sealVault(deviceKey, creds)) }
  localStorage.setItem(LS_KEY, JSON.stringify(stored))
}

/** Unlock: prompt the passkey, derive the device key, and open the stored credentials. */
export async function unlockWithDevice(): Promise<DeviceCredentials> {
  const stored = read()
  if (!stored) throw new Error('device unlock not set up')
  const deviceKey = await getPasskeyDeviceKey(stored.credentialId)
  return openVault(deviceKey, fromB64url(stored.vault))
}

/** Forget the device unlock on this device. */
export function disableDeviceUnlock(): void {
  localStorage.removeItem(LS_KEY)
}
