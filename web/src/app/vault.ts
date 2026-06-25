// uc.account.unlock — the in-memory vault holding the unlocked Master Key + identity private key. This is
// the *unlocked* half of the account and lives ONLY here: never in `session.ts` (auth ≠ unlock — those gate
// a session, this opens content), never persisted, cleared on sign-out and gone on reload. Ephemeral by
// default: a fresh app session re-derives it (re-unlock), so we don't keep it anywhere durable.
//
// The raw key bytes are kept in a plain (non-reactive) holder so Vue's deep proxy never wraps a Uint8Array
// the crypto layer will use; the UI observes only the reactive `unlocked` flag.
import { reactive } from 'vue'
import type { Identity } from '../shared/crypto/v1'

const keys = {
  masterKey: null as Uint8Array | null,
  identityPrivate: null as Identity['priv'] | null,
}

/** Reactive unlock flag for the UI — never holds key material. */
export const vaultState = reactive({ unlocked: false })

/** Hold the freshly opened Master Key + identity private key for this working session. */
export function setUnlocked(masterKey: Uint8Array, identityPrivate: Identity['priv']): void {
  keys.masterKey = masterKey
  keys.identityPrivate = identityPrivate
  vaultState.unlocked = true
}

/** Drop all key material (sign-out / re-lock). Zeroes the key bytes before releasing the references, so
 *  the Master Key / identity private key don't linger in the heap until GC (best-effort: JS can't guarantee
 *  no copy was made, but it shortens the window for a heap snapshot to recover a key after sign-out). */
export function clearVault(): void {
  keys.masterKey?.fill(0)
  keys.identityPrivate?.kemX.fill(0)
  keys.identityPrivate?.mlkemSeed.fill(0)
  keys.identityPrivate?.sign.fill(0)
  keys.masterKey = null
  keys.identityPrivate = null
  vaultState.unlocked = false
}

export function isUnlocked(): boolean {
  return vaultState.unlocked
}

/** The unlocked Master Key, or null while locked. Callers must not persist it. */
export function masterKey(): Uint8Array | null {
  return keys.masterKey
}

/** The unlocked identity private key, or null while locked. */
export function identityPrivate(): Identity['priv'] | null {
  return keys.identityPrivate
}
