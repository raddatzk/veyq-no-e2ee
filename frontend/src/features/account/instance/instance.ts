// uc.account.connect (web) — the instance is the same-origin host that served the page, so all the
// client needs is its setup status: whether first-run setup is still pending, and the advertised suites.
export type SetupStatus = { setupRequired: boolean; suites: string[] }

/** Fetch the instance's setup status (drives the setup vs login branch on boot). */
export async function getSetupStatus(apiBase: string): Promise<SetupStatus> {
  const res = await fetch(`${apiBase}/api/setup`, { headers: { accept: 'application/json' } })
  if (!res.ok) throw new Error(`setup status failed: ${res.status} ${await res.text()}`)
  return res.json()
}
