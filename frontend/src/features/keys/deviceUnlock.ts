// uc.keys.upsert-unlock-wrap (web) — enrol the current browser as a named DEVICE unlock method.
//
// After a successful login the master key is unlocked in memory. We wrap a copy of it under a
// per-browser device key and register it as a DEVICE master-key wrap, tagged with a user-chosen name and
// a stable per-browser credentialRef. This is what powers the "Devices" list in user settings (replacing
// the old passkey list): each enrolled browser is one removable DEVICE wrap.
//
// The device key is kept in localStorage. That is same-browser-only and good enough for listing/removing
// devices; hardening it into a non-extractable WebCrypto/IndexedDB key is a future improvement.
import { apiError } from '../../shared/apiError'
import * as v1 from '../../shared/crypto/v1'
import { b64url } from '../../shared/base64'
import type { UnlockedSession } from '../account/login/login'

const DEVICE_ID_KEY = 'veyq.deviceId'
const DEVICE_KEY_KEY = 'veyq.deviceKey'

export type KeyWrapSummary = {
  unlockMethod: 'PASSWORD' | 'PASSKEY' | 'RECOVERY' | 'DEVICE'
  credentialRef?: string
  name?: string
  createdAt?: string
}

/** List the caller's unlock wraps (metadata only). Drives the "Devices" list in user settings. */
export async function listMyKeyWraps(apiBase: string, sessionToken: string): Promise<KeyWrapSummary[]> {
  const res = await fetch(`${apiBase}/api/me/key-wraps`, { headers: { authorization: `Bearer ${sessionToken}` } })
  if (!res.ok) throw await apiError(res, 'key wraps')
  const body: { wraps: KeyWrapSummary[] } = await res.json()
  return body.wraps
}

function randomId(): string {
  return b64url(v1.randomBytes(16))
}

/** Stable identifier for this browser; created on first use. Used as the DEVICE wrap's credentialRef. */
export function deviceId(): string {
  let id = localStorage.getItem(DEVICE_ID_KEY)
  if (!id) {
    id = randomId()
    localStorage.setItem(DEVICE_ID_KEY, id)
  }
  return id
}

/** Per-browser 32-byte device key (the DEVICE wrap KEK); created on first use. */
function deviceKey(): Uint8Array {
  const stored = localStorage.getItem(DEVICE_KEY_KEY)
  if (stored) return fromStored(stored)
  const key = v1.randomBytes(32)
  localStorage.setItem(DEVICE_KEY_KEY, b64url(key))
  return key
}

function fromStored(s: string): Uint8Array {
  // small local decode to avoid importing fromB64url twice; b64url is symmetric with this
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function defaultDeviceName(): string {
  const ua = navigator.userAgent
  if (/iPhone|iPad|iPod/.test(ua)) return 'iOS'
  if (/Android/.test(ua)) return 'Android'
  if (/Macintosh/.test(ua)) return 'Mac'
  if (/Windows/.test(ua)) return 'Windows'
  return 'Browser'
}

/**
 * Register this browser as a named DEVICE unlock wrap. Best-effort: failures never block login (the
 * device list is a convenience, not required to use the app), so callers can fire-and-forget.
 */
export async function enrollDeviceUnlock(
  apiBase: string,
  session: UnlockedSession,
  name: string,
): Promise<void> {
  try {
    const wrap = v1.symWrap(deviceKey(), session.masterKey, 'master-key', v1.randomBytes(12))
    const masterKeyWrap = {
      unlockMethod: 'DEVICE',
      suiteId: 'v1',
      wrappedKey: b64url(wrap),
      credentialRef: deviceId(),
      name: name || defaultDeviceName(),
    }
    const res = await fetch(`${apiBase}/api/me/key-wraps/DEVICE`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
      body: JSON.stringify({ masterKeyWrap }),
    })
    if (!res.ok) throw await apiError(res, 'device enrol')
  } catch {
    // Non-fatal: the user is logged in regardless of whether the device wrap registered.
  }
}
