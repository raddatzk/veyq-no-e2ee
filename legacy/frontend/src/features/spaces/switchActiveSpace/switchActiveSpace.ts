// uc.spaces.switch-active-space (web) — the unified space switcher: list every enterable PERSONAL,
// TENANT_ROOT and GROUP_ROOT space and switch the active context into any of them. Switching fetches no
// secrets — it opens the chosen scope with the wrap the client already holds. Content ops reuse the content slice.
import { apiError } from '../../../shared/apiError'
import { authFetch } from '../../../shared/api/sessionFetch'
import type { UnlockedSession } from '../../account/login/login'
import {
  openPersonalScopeById,
  openScopeById,
  type OpenScope
} from '../../content/scopeContent'

export type SpaceEntry = {
  kind: 'PERSONAL' | 'TENANT' | 'GROUP'
  tenantId?: string
  groupId?: string
  name?: string
  // TENANT/GROUP — the tenant's domain (the host that resolves to it); used for routing + cross-host switch.
  domain?: string
  // TENANT/GROUP — optional tenant branding image URL shown in the space switcher.
  logoUrl?: string
  displayName: string
  role?: string
  // GROUP only — the group name sealed under its content key (the client decrypts it into displayName).
  sealedName?: { suiteId: string; bytes: string }
  // TENANT only — set when the tenant is scheduled for deletion; the client shows a warning + cancel.
  scheduledDeletionAt?: string
  scopeId: string
  scopePublicKey: { suiteId: string; kemPublicKey: string }
}

/** List every PERSONAL, TENANT_ROOT and GROUP_ROOT space the caller can enter. */
export async function listMySpaces(
  apiBase: string,
  session: UnlockedSession
): Promise<SpaceEntry[]> {
  const res = await authFetch(apiBase, session, '/api/spaces')
  if (!res.ok) throw await apiError(res, 'list spaces')
  return (await res.json()).spaces
}

/** Switch into a space: open its scope with the already-held wrap (no secrets fetched). */
export async function openSpace(
  apiBase: string,
  session: UnlockedSession,
  entry: SpaceEntry
): Promise<OpenScope> {
  if (entry.kind === 'PERSONAL') return openPersonalScopeById(apiBase, session, entry.scopeId)
  // Every space is opened by scope id; groupId is only for group-management APIs.
  if (entry.kind === 'GROUP') return openScopeById(apiBase, session, entry.scopeId)
  return openScopeById(apiBase, session, entry.scopeId)
}
