// The active space the shell is showing (cr-0030, uc.memory.browse). This slice = the member's PERSONAL
// scope. Holds the opaque `scopeId` + the in-memory scope content key `K_SC` (the key bytes live OUTSIDE the
// reactive proxy — never expose key material to Vue reactivity). Populated after unlock: auto-birth the
// personal scope if absent, then open it. Operator-blind: `K_SC` is derived on-device from our own grant.
import { reactive } from 'vue'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../features/spaces/scopes'
import { ScopeKind } from '../api/generated/scopes'

export type SpaceStatus = 'idle' | 'loading' | 'ready' | 'no-tenant' | 'error'

/** Reactive, non-sensitive view of the active space (the key bytes are held separately). */
export const activeSpace = reactive({
  status: 'idle' as SpaceStatus,
  scopeId: null as string | null,
  tenantId: null as string | null,
})

// K_SC is held outside the reactive proxy on purpose — key material must not flow through Vue reactivity.
let scopeKey: Uint8Array | null = null

/** The active scope content key `K_SC`, or null when no space is open. The caller must not mutate it. */
export function activeScopeKey(): Uint8Array | null {
  return scopeKey
}

/**
 * Enter the member's personal space: auto-birth it if absent (idempotent), then open it → hold `scopeId` +
 * `K_SC`. Sets `status` to `ready` on success, `no-tenant` when the active tenant can't be resolved from the
 * Host (404), or `error` otherwise. Requires an unlocked vault (the identity bundle seals/opens the grant).
 */
export async function enterPersonalSpace(): Promise<void> {
  activeSpace.status = 'loading'
  try {
    await ensurePersonalScope() // idempotent: a no-op if one already exists
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    const key = personal ? await openPersonalScope() : null
    if (!personal || !key) {
      activeSpace.status = 'error'
      return
    }
    activeSpace.scopeId = personal.id
    activeSpace.tenantId = personal.tenantId
    scopeKey = key
    activeSpace.status = 'ready'
  } catch (err) {
    activeSpace.status = isNoTenant(err) ? 'no-tenant' : 'error'
  }
}

/** Drop the active space + zero the held key (logout / re-lock). */
export function clearActiveSpace(): void {
  activeSpace.status = 'idle'
  activeSpace.scopeId = null
  activeSpace.tenantId = null
  scopeKey?.fill(0)
  scopeKey = null
}

/** A 404 from the scope endpoints means no tenant claims this Host (`tenant_not_found`). */
function isNoTenant(err: unknown): boolean {
  return Boolean(err) && typeof err === 'object' && (err as { response?: { status?: number } }).response?.status === 404
}
