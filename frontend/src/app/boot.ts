// App boot sequence (uc.platform.app-shell): before any content loads, discover the instance's state and
// route accordingly. A fresh instance lands on first-run setup; an already-set-up instance on login; an
// unreachable instance surfaces a clear error state (bootState.unreachable) rather than a blank/broken screen.
// A remembered login (the E2EE replacement for a refresh token) is replayed here so a reload lands the user
// straight in the app. Extracted from main.ts so the boot branches are unit-testable.
import { reactive } from 'vue'
import { useAuthState } from '../shared/state/useAuthState'
import { useServerState } from '../shared/state/useServerState'
import { initializeTheme } from '../shared/state/useThemeState'
import { getSetupStatus } from '../features/account/instance/instance'
import { login } from '../features/account/login/login'
import { clearRememberedLogin, readRememberedLogin } from '../features/account/rememberedLogin/rememberedLogin'
import { session, setUnlocked } from './session'

/** Boot-time shell state. `unreachable` is set when the instance can't be reached on boot. */
export const bootState = reactive({
  unreachable: false,
})

// Re-login from stored credentials (email + password + Secret Key) — the E2EE replacement for a refresh
// token. A fresh login also re-derives the master/identity keys, which a token refresh never could.
async function tryRememberedLogin(): Promise<void> {
  const creds = readRememberedLogin()
  if (!creds) return
  try {
    setUnlocked(await login(session.apiBase, creds.email, creds.password, creds.secretKey))
  } catch {
    clearRememberedLogin()
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
    // A set-up instance may have a remembered login → replay it so a reload lands straight in the app.
    if (!status.setupRequired) await tryRememberedLogin()
  } catch {
    // The instance is unreachable — show a clear error state instead of forcing setup or a blank screen.
    bootState.unreachable = true
  } finally {
    finishLoading()
  }
}
