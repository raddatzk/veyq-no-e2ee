// uc.account.remember-this-device (web "off" tier, cr-0055). The session-scoped vault: the unlocked Master Key
// is parked in `sessionStorage` for the browser session, so a page reload re-opens the vault with no prompt,
// and it is cleared when the browser closes — paired with the session refresh cookie (ADR 0004 addendum). This
// is the conventional web-session experience for "not remembered" sign-ins.
//
// Trade-off (ADR 0005 §23): within the open browser session the vault is reconstructable WITHOUT the password.
// `sessionStorage` can't hold a non-extractable WebCrypto key, so the Master Key sits here as raw bytes — but
// that is no worse than the already-unlocked in-memory vault an XSS payload could read while it runs, and it is
// cleared on browser close. Operator-blind is untouched (nothing here is server-side). The persistent "on" tier
// keeps a non-extractable device key in IndexedDB instead (deviceKeyWrap.ts), never raw bytes.
import { b64std, fromB64std } from '../../../shared/base64'

const KEY = 'veyq.session-vault'

/** The remembered-for-this-session vault: the account email + the Master Key as standard base64. */
type StoredVault = { email: string; masterKey: string }

export function isSessionVaultSupported(): boolean {
  try {
    return typeof sessionStorage !== 'undefined'
  } catch {
    return false // some privacy modes throw on access
  }
}

/** Park the unlocked Master Key for the browser session (best-effort — a storage failure just means the device
 *  re-asks the password on the next reload). */
export function cacheSessionVault(email: string, masterKey: Uint8Array): void {
  try {
    const v: StoredVault = { email: email.trim().toLowerCase(), masterKey: b64std(masterKey) }
    sessionStorage.setItem(KEY, JSON.stringify(v))
  } catch {
    /* storage unavailable / full — best-effort */
  }
}

/** Read the session vault (email + Master Key), or null when absent/unreadable. */
export function loadSessionVault(): { email: string; masterKey: Uint8Array } | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const v = JSON.parse(raw) as StoredVault
    if (!v?.email || !v?.masterKey) return null
    return { email: v.email, masterKey: fromB64std(v.masterKey) }
  } catch {
    return null
  }
}

/** Drop the session vault (logout, switching to the persistent tier, or a no-session boot). */
export function clearSessionVault(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}
