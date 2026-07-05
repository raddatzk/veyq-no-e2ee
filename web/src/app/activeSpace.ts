// The active space the shell is showing (cr-0030/0075, uc.spaces.switch-spaces). Holds the opaque `scopeId` +
// the in-memory scope content key `K_SC` (the key bytes live OUTSIDE the reactive proxy — never expose key
// material to Vue reactivity). Which scope is active is driven by the URL's leading fragment token (ADR 0019):
// token-less → the shared **tenant** scope, `~me` → **personal**. Entering a scope opens its grant client-side
// (operator-blind: `K_SC` is derived on-device) and resets the per-scope tree + favorites.
import { reactive } from 'vue'
import { ensurePersonalScope, openScope, openScopeById, type OpenedScope } from '../features/spaces/scopes'
import { ScopeKind } from '../api/generated/scopes'
import { TENANT_SCOPE } from '../features/memory/location'
import { resetTree } from '../features/memory/treeStore'
import { clearFavorites } from './favoritesStore'
import { startGrantDeliveryPolling, stopGrantDeliveryPolling } from '../features/tasks/grantDeliveryPolling'

export type SpaceStatus = 'idle' | 'loading' | 'ready' | 'no-tenant' | 'error'

/** Reactive, non-sensitive view of the active space (the key bytes are held separately). */
export const activeSpace = reactive({
  status: 'idle' as SpaceStatus,
  scopeId: null as string | null,
  tenantId: null as string | null,
  kind: null as ScopeKind | null,
  // The token-less tenant home resolved to Personal because the caller holds no TENANT grant (ADR 0019, E1).
  tenantUnavailable: false,
})

// K_SC is held outside the reactive proxy on purpose — key material must not flow through Vue reactivity.
let scopeKey: Uint8Array | null = null

/** The active scope content key `K_SC`, or null when no space is open. The caller must not mutate it. */
export function activeScopeKey(): Uint8Array | null {
  return scopeKey
}

/**
 * Enter the scope selected by the URL's leading fragment `token` (`''` = tenant, `~me` = personal, `@<id>` =
 * group). Opens that scope's grant → holds `scopeId` + `K_SC`, then **resets the tree + favorites** so no stale
 * per-scope state bleeds across a switch. Sets `status` to `ready` on success, `no-tenant` when the active tenant
 * can't be resolved from the Host (404), or `error` otherwise. Requires an unlocked vault (the identity opens the
 * grant).
 *
 * **No client birth of the tenant scope** (ADR 0006): the token-less home is *open-only*. When the caller holds
 * no `TENANT` grant (tenant member-invite is unbuilt), it **falls back to Personal** and flags the tenant
 * unavailable (ADR 0019, en.spaces.tenant-scope E1) — never a `POST`. A `@<id>` group is opened by id against the
 * caller's own scope list (en.spaces.group-scope, cr-0032); an unopenable one falls back to Personal too. Only
 * Personal auto-births.
 */
export async function enterScope(token: string): Promise<void> {
  activeSpace.status = 'loading'
  try {
    let opened: OpenedScope | null = null
    if (token === TENANT_SCOPE) {
      opened = await openScope(ScopeKind.Tenant) // open-only — never mint the shared root
      activeSpace.tenantUnavailable = opened === null
    } else {
      activeSpace.tenantUnavailable = false
      if (token.startsWith('@')) opened = await openScopeById(token.slice(1)) // `@<id>` → open that group
    }
    if (!opened) {
      // Personal — the explicit `~me`, an unresolved/unopenable `@group`, or the no-grant tenant fallback.
      // Personal is the one birth path (idempotent: a no-op if one already exists).
      await ensurePersonalScope()
      opened = await openScope(ScopeKind.Personal)
    }
    if (!opened) {
      activeSpace.status = 'error'
      return
    }
    scopeKey?.fill(0) // zero the key we held for the scope we're leaving
    scopeKey = opened.key
    activeSpace.scopeId = opened.scopeId
    activeSpace.tenantId = opened.tenantId
    activeSpace.kind = opened.kind
    resetTree() // no stale tree/favorites bleed across scopes — the view reloads for the new scope
    clearFavorites()
    activeSpace.status = 'ready'
    // As a key-holder, keep delivering the scope key to any pending member (cr-0076/cr-0077, first-online-wins):
    // deliver on entry and then poll in the background so "awaiting access" closes without a re-entry (cr-0079).
    // A shared TENANT/GROUP scope may have grant-less members; Personal never does, so it just stops any poll.
    if (opened.kind !== ScopeKind.Personal) startGrantDeliveryPolling(opened.scopeId, activeScopeKey)
    else stopGrantDeliveryPolling()
  } catch (err) {
    activeSpace.status = isNoTenant(err) ? 'no-tenant' : 'error'
  }
}

/** Drop the active space + zero the held key (logout / re-lock). */
export function clearActiveSpace(): void {
  stopGrantDeliveryPolling()
  activeSpace.status = 'idle'
  activeSpace.scopeId = null
  activeSpace.tenantId = null
  activeSpace.kind = null
  activeSpace.tenantUnavailable = false
  scopeKey?.fill(0)
  scopeKey = null
  clearFavorites()
}

/** A 404 from the scope endpoints means no tenant claims this Host (`tenant_not_found`). */
function isNoTenant(err: unknown): boolean {
  return Boolean(err) && typeof err === 'object' && (err as { response?: { status?: number } }).response?.status === 404
}
