// App-wide reactive session state for the E2EE backend. Replaces the legacy cookie-auth `authState`:
// authentication is now a Bearer `sessionToken` held only in memory (never a cookie), alongside the
// unlocked master/identity keys recovered locally at login. The active space (open scope + content key)
// is the per-context crypto handle every content operation threads through.
//
// Vue's reactive() leaves TypedArrays unproxied (they are an INVALID proxy target), so the Uint8Array
// key material inside UnlockedSession / ActiveSpace is returned raw to crypto calls — the same pattern
// the greenfield web app relies on. Theme stays owned by the legacy useThemeState system.
import { reactive } from 'vue'
import type { UnlockedSession } from '../features/account/login/login'
import type { ActiveSpace } from '../features/home/home'

export const session = reactive({
  // same-origin: fetch hits `/api/...` on the instance that served this page (vite proxies to the backend)
  apiBase: '',
  unlocked: null as UnlockedSession | null,
  activeSpace: null as ActiveSpace | null,
})

/** True once the master key is unlocked — the gate the app shell uses to show content vs the auth screens. */
export function isUnlocked(): boolean {
  return session.unlocked !== null
}

/** Set (or clear) the unlocked session. Clearing also drops the active space (its keys are session-scoped). */
export function setUnlocked(unlocked: UnlockedSession | null): void {
  session.unlocked = unlocked
  if (!unlocked) session.activeSpace = null
}

/** Set (or clear) the active space (open scope + content key). */
export function setActiveSpace(space: ActiveSpace | null): void {
  session.activeSpace = space
}

/** Convenience accessor that asserts an unlocked session — throws if called before login. */
export function requireSession(): UnlockedSession {
  if (!session.unlocked) throw new Error('no unlocked session')
  return session.unlocked
}

/** Convenience accessor that asserts an active space — throws if called before a space is entered. */
export function requireActiveSpace(): ActiveSpace {
  if (!session.activeSpace) throw new Error('no active space')
  return session.activeSpace
}
