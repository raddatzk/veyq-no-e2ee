import { apiError } from '../../../shared/apiError'
// uc.platform.app-shell (web) — the instance is the same-origin host that served the page, so all the
// client needs is its setup status: whether first-run setup is still pending, the advertised suites, plus
// optional operator metadata (a support URL that drives a dismissible banner, and an advisory version).
export type SetupStatus = { setupRequired: boolean; suites: string[]; supportUrl?: string; serverVersion?: string }

/** Fetch the instance's setup status (drives the setup vs login branch on boot). */
export async function getSetupStatus(apiBase: string): Promise<SetupStatus> {
  const res = await fetch(`${apiBase}/api/setup`, { headers: { accept: 'application/json' } })
  if (!res.ok) throw await apiError(res, 'setup status')
  return res.json()
}
