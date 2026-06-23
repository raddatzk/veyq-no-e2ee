// App boot sequence (uc.platform.app-shell): before any content loads, discover the instance's state and
// route accordingly. A fresh instance lands on first-run setup; an already-set-up instance on login; an
// unreachable instance surfaces a clear error state (bootState.unreachable) rather than a blank/broken screen.
// Extracted from main.ts so the boot branches are unit-testable.
import { reactive } from 'vue'
import { useAuthState } from '../shared/state/useAuthState'
import { useServerState } from '../shared/state/useServerState'
import { initializeTheme } from '../shared/state/useThemeState'
import { getSetupStatus } from '../features/account/instance/instance'
import { fetchCurrentUser, refreshSession } from '../features/account/login/login'
import {
  clearSessionUnlock,
  hasSessionUnlock,
  readRememberedLogin,
  readRememberedUnlock,
  readSessionUnlock
} from '../features/account/rememberedLogin/rememberedLogin'
import { session, setUnlocked } from './session'

/** Boot-time shell state. `unreachable` is set when the instance can't be reached on boot. */
export const bootState = reactive({
  unreachable: false,
})

async function tryRememberedSession(): Promise<void> {
  const hasRememberedUnlock = Boolean(readRememberedLogin()?.unlock)
  if (!hasSessionUnlock() && !hasRememberedUnlock) return
  const tabSession = readSessionUnlock()
  if (tabSession) {
    try {
      const user = await fetchCurrentUser(session.apiBase, tabSession.sessionToken)
      setUnlocked({ ...tabSession, user })
      return
    } catch {
      clearSessionUnlock()
    }
  }
  try {
    const refreshed = await refreshSession(session.apiBase)
    const remembered = readSessionUnlock(refreshed.sessionToken) ?? readRememberedUnlock(refreshed.sessionToken)
    if (!remembered) return
    const user = await fetchCurrentUser(session.apiBase, refreshed.sessionToken)
    setUnlocked({ ...remembered, user, sessionToken: refreshed.sessionToken })
  } catch {
    clearSessionUnlock()
  }
}

/**
 * Discover the instance state and prepare the app shell. Returns nothing; the result is reflected in the
 * shared server/auth state and `bootState`, which the router + App shell read to land on the right view.
 */
export async function boot(): Promise<void> {
  initializeTheme()
  const { finishLoading } = useAuthState()
  const { setSetupCompleted } = useServerState()
  bootState.unreachable = false

  try {
    const status = await getSetupStatus(session.apiBase)
    setSetupCompleted(!status.setupRequired)
    if (!status.setupRequired) await tryRememberedSession()
  } catch {
    // The instance is unreachable — show a clear error state instead of forcing setup or a blank screen.
    bootState.unreachable = true
  } finally {
    finishLoading()
  }
}
