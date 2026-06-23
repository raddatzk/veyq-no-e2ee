// Space roster + active-space management for the home app. Wraps the greenfield home/space slices:
// list every space the user can enter, and enter (open scope + content key) the one a route selects.
import { ref } from 'vue'
import { enterSpace, loadSpaces, type ActiveSpace, type SpaceEntry } from '../features/home/home'
import { matchSpace, type HomeRoute } from '../features/home/homeRoute'
import { requireSession, session, setActiveSpace } from './session'
import { useAuthState } from '../shared/state/useAuthState'

export const spaces = ref<SpaceEntry[]>([])
let loaded = false

export class NoSpaceAvailableError extends Error {
  constructor() {
    super('no space available')
  }
}

/** Load (once) every space the user can enter, decrypting group names. */
export async function ensureSpaces(): Promise<SpaceEntry[]> {
  if (!loaded) {
    spaces.value = await loadSpaces(session.apiBase, requireSession())
    useAuthState().setTenants(
      spaces.value
        .filter((space) => space.kind === 'TENANT')
        .map((space) => ({
          id: space.tenantId ?? '',
          name: space.name ?? '',
          displayName: space.displayName,
          domain: space.domain,
          logoUrl: space.logoUrl,
          role: space.role
        }))
        .filter((tenant) => tenant.id && tenant.name)
    )
    loaded = true
  }
  return spaces.value
}

/** Drop the cached roster (after creating/joining/leaving a space). */
export function invalidateSpaces(): void {
  loaded = false
}

/** Force a refresh of the shared roster ref (after creating/joining/leaving) so live views update. */
export async function reloadSpaces(): Promise<SpaceEntry[]> {
  loaded = false
  return ensureSpaces()
}

/** Resolve a parsed route to a space, defaulting to the personal scope, and enter it (open scope + key). */
export async function ensureActiveSpaceForRoute(route: HomeRoute | null): Promise<ActiveSpace> {
  const all = await ensureSpaces()
  const entry =
    (route ? matchSpace(all, route, window.location.host) : undefined) ??
    all.find((s) => s.kind === 'PERSONAL') ??
    all[0]
  if (!entry) {
    setActiveSpace(null)
    throw new NoSpaceAvailableError()
  }
  if (session.activeSpace?.scope.scopeId !== entry.scopeId) {
    setActiveSpace(await enterSpace(session.apiBase, requireSession(), entry))
  }
  return session.activeSpace!
}
