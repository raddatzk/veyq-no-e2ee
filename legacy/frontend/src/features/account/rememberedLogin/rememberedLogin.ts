// "Login merken" stores the credentials locally so the next login can reuse them without a browser credential prompt.
// This is intentionally same-browser only; clearing site data or using Settings > forget removes it.
export type RememberedLogin = { email: string; password: string; secretKey: string }

const LS_KEY = 'veyq.rememberedLogin'
const LEGACY_DEVICE_UNLOCK_KEY = 'veyq.deviceUnlock'

function isRememberedLogin(value: unknown): value is RememberedLogin {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<RememberedLogin>
  return typeof candidate.email === 'string'
    && typeof candidate.password === 'string'
    && typeof candidate.secretKey === 'string'
}

export function rememberedLoginSupported(): boolean {
  try {
    const key = 'veyq.storageProbe'
    localStorage.setItem(key, '1')
    localStorage.removeItem(key)
    return true
  } catch {
    return false
  }
}

export function readRememberedLogin(): RememberedLogin | null {
  try {
    const parsed = JSON.parse(localStorage.getItem(LS_KEY) ?? 'null')
    return isRememberedLogin(parsed) ? parsed : null
  } catch {
    return null
  }
}

export function rememberedLoginEnabled(): boolean {
  return readRememberedLogin() !== null
}

export function saveRememberedLogin(creds: RememberedLogin): void {
  localStorage.setItem(LS_KEY, JSON.stringify(creds))
}

export function clearRememberedLogin(): void {
  try {
    localStorage.removeItem(LS_KEY)
  } catch {
    /* storage unavailable */
  }
  clearLegacyDeviceUnlockLogin()
}

export function clearLegacyDeviceUnlockLogin(): void {
  try {
    localStorage.removeItem(LEGACY_DEVICE_UNLOCK_KEY)
  } catch {
    /* storage unavailable */
  }
}
