// App boot sequence (uc.platform.app-shell): before any content loads, discover the instance's state and
// route accordingly. A fresh instance lands on first-run setup; an already-set-up instance on the (stubbed)
// login; an unreachable instance surfaces a clear error state rather than a blank/broken screen.
//
// Trimmed for this CR: only the setup-status branch exists. The remembered-session / unlock machinery from
// the legacy boot arrives with the login CR.
import { getSetupStatus } from '../features/account/instance/instance'
import { useServerState } from '../shared/state/useServerState'

/**
 * Discover the instance state and prepare the app shell. The result is reflected in the shared server
 * state, which the router + App shell read to land on the right view.
 */
export async function boot(): Promise<void> {
  const { setSetupCompleted, setUnreachable } = useServerState()
  setUnreachable(false)
  try {
    const status = await getSetupStatus()
    setSetupCompleted(!status.setupRequired)
  } catch {
    // The instance is unreachable — show a clear error state instead of forcing setup or a blank screen.
    setUnreachable(true)
  }
}
