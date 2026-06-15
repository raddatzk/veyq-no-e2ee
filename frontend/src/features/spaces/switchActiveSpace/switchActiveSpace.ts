// uc.spaces.switch-active-space (web) — the unified space switcher: list every space the caller can enter
// (personal + groups) and switch the active context into any of them. Switching fetches no secrets — it
// opens the chosen scope with the wrap the client already holds. Content ops reuse the content slice.
import type { UnlockedSession } from '../../account/login/login'
import { openPersonalScope, openScopeById, openTenantScope, type OpenScope } from '../../content/scopeContent'

export type SpaceEntry = {
  kind: 'PERSONAL' | 'TENANT' | 'GROUP'
  tenantId?: string
  name?: string
  displayName: string
  role?: string
  // GROUP only — the group name sealed under its content key (the client decrypts it into displayName).
  sealedName?: { suiteId: string; bytes: string }
  // TENANT only — set when the tenant is scheduled for deletion; the client shows a warning + cancel.
  scheduledDeletionAt?: string
  scopeId: string
  scopePublicKey: { suiteId: string; kemPublicKey: string }
}

/** List every space the caller can enter (personal + every group they hold the scope key for). */
export async function listMySpaces(apiBase: string, session: UnlockedSession): Promise<SpaceEntry[]> {
  const res = await fetch(`${apiBase}/api/me/spaces`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw new Error(`list spaces failed: ${res.status} ${await res.text()}`)
  return (await res.json()).spaces
}

/** Switch into a space: open its scope with the already-held wrap (no secrets fetched). */
export async function openSpace(apiBase: string, session: UnlockedSession, entry: SpaceEntry): Promise<OpenScope> {
  if (entry.kind === 'PERSONAL') return openPersonalScope(apiBase, session)
  // A group is opened by scope id (no tenant-keyed grant path); a tenant-wide space by its tenant id.
  if (entry.kind === 'GROUP') return openScopeById(apiBase, session, entry.scopeId)
  if (!entry.tenantId) throw new Error('tenant space entry is missing its tenantId')
  return openTenantScope(apiBase, session, entry.tenantId)
}
