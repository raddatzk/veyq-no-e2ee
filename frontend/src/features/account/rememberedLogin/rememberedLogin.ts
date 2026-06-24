// "Login merken" stores only the Emergency-Kit factor locally. The password is never persisted, so an
// expired or remotely invalidated session cannot silently re-authenticate without the user.
// The tab unlock stores key material in sessionStorage only so a normal reload keeps the E2EE session open,
// while closing the tab/browser drops it.
import * as v1 from '../../../shared/crypto/v1'
import { b64url, fromB64url } from '../../../shared/base64'
import type { UnlockedSession } from '../login/login'

export type RememberedLogin = { email: string; secretKey: string; unlock?: RememberedUnlock }
type RememberedUnlock = {
  user: UnlockedSession['user']
  identityPublicKey: UnlockedSession['identityPublicKey']
  identityPrivateKey: string
  masterKey: string
  rootSessionId?: string
}
type SessionUnlock = RememberedUnlock & { sessionToken?: string }

const LS_KEY = 'veyq.rememberedLogin'
const TAB_UNLOCK_KEY = 'veyq.sessionUnlock'
const LEGACY_DEVICE_UNLOCK_KEY = 'veyq.deviceUnlock'
const LEGACY_DEVICE_ID_KEY = 'veyq.deviceId'
const LEGACY_DEVICE_KEY_KEY = 'veyq.deviceKey'

function isRememberedLogin(value: unknown): value is RememberedLogin {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<RememberedLogin>
  return typeof candidate.email === 'string'
    && typeof candidate.secretKey === 'string'
}

function isRememberedUnlock(value: unknown): value is RememberedUnlock {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<RememberedUnlock>
  return Boolean(candidate.user)
    && Boolean(candidate.identityPublicKey)
    && typeof candidate.identityPrivateKey === 'string'
    && typeof candidate.masterKey === 'string'
}

function isSessionUnlock(value: unknown): value is SessionUnlock {
  return isRememberedUnlock(value)
}

function toRememberedUnlock(session: UnlockedSession): RememberedUnlock {
  return {
    user: session.user,
    identityPublicKey: session.identityPublicKey,
    identityPrivateKey: b64url(v1.concat(v1.lp(session.identity.kemX), v1.lp(session.identity.mlkemSeed), v1.lp(session.identity.sign))),
    masterKey: b64url(session.masterKey),
    rootSessionId: session.rootSessionId,
  }
}

function toSessionUnlock(session: UnlockedSession): SessionUnlock {
  return {
    ...toRememberedUnlock(session),
    sessionToken: session.sessionToken,
  }
}

function fromRememberedUnlock(unlock: RememberedUnlock, sessionToken: string): UnlockedSession | null {
  try {
    return {
      sessionToken,
      user: unlock.user,
      identity: v1.deserializeIdentityPrivate(fromB64url(unlock.identityPrivateKey)),
      identityPublicKey: unlock.identityPublicKey,
      masterKey: fromB64url(unlock.masterKey),
      rootSessionId: unlock.rootSessionId ?? '',
    }
  } catch {
    return null
  }
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

export function readRememberedUnlock(sessionToken: string): UnlockedSession | null {
  const remembered = readRememberedLogin()
  const unlock = remembered?.unlock
  if (!unlock) return null
  return fromRememberedUnlock(unlock, sessionToken)
}

export function hasSessionUnlock(): boolean {
  try {
    return sessionStorage.getItem(TAB_UNLOCK_KEY) !== null
  } catch {
    return false
  }
}

export function readSessionUnlock(sessionToken?: string): UnlockedSession | null {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(TAB_UNLOCK_KEY) ?? 'null')
    if (!isSessionUnlock(parsed)) return null
    const token = sessionToken ?? parsed.sessionToken
    return token ? fromRememberedUnlock(parsed, token) : null
  } catch {
    return null
  }
}

export function saveSessionUnlock(unlocked: UnlockedSession): void {
  try {
    sessionStorage.setItem(TAB_UNLOCK_KEY, JSON.stringify(toSessionUnlock(unlocked)))
  } catch {
    /* storage unavailable */
  }
}

export function clearSessionUnlock(): void {
  try {
    sessionStorage.removeItem(TAB_UNLOCK_KEY)
  } catch {
    /* storage unavailable */
  }
}

export function rememberedLoginEnabled(): boolean {
  return readRememberedLogin() !== null
}

export function saveRememberedLogin(creds: Pick<RememberedLogin, 'email' | 'secretKey'>, unlocked?: UnlockedSession): void {
  localStorage.setItem(LS_KEY, JSON.stringify({
    ...creds,
    unlock: unlocked ? toRememberedUnlock(unlocked) : undefined,
  }))
  clearLegacyDeviceUnlockLogin()
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
    localStorage.removeItem(LEGACY_DEVICE_ID_KEY)
    localStorage.removeItem(LEGACY_DEVICE_KEY_KEY)
  } catch {
    /* storage unavailable */
  }
}
