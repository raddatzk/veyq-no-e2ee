// uc.spaces.personal-scope (web) — birth + open a member's per-tenant PERSONAL scope. Operator-blind: the
// scope key is generated on-device and sealed to the member's own identity (asymWrap, the hybrid KEM); only
// the ciphertext grant reaches the server. The active tenant is resolved server-side from the Host.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { scopesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { identityPrivate } from '../../app/vault'
import { ScopeKind, type ScopeSummary, type MyTenant } from '../../api/generated/scopes'

/** The AAD/KDF context binding a scope-key wrap (ADR 0009/0020). Shared with the grant-delivery worker, which
 *  wraps `K_SC` to a *foreign* identity under the very same context (cr-0076). */
export const SCOPE_GRANT = 'scope-grant'

/** The caller's scopes in the active tenant (each with their own grant ciphertext). */
export async function listMyScopes(): Promise<ScopeSummary[]> {
  return withRefresh(() => scopesApi().listMyScopes())
}

/** Every tenant the caller belongs to — the sidebar space switcher (Host-independent). */
export async function listMyTenants(): Promise<MyTenant[]> {
  return withRefresh(() => scopesApi().listMyTenants())
}

/**
 * Ensure this member has a PERSONAL scope in the active tenant — auto-birth, operator-blind: generate the
 * scope key, seal it to our **own** identity public key, and POST only the ciphertext grant. No-op if one
 * already exists. Requires an unlocked vault (the identity bundle). Returns whether a scope was created.
 */
export async function ensurePersonalScope(): Promise<boolean> {
  if ((await listMyScopes()).some((s) => s.kind === ScopeKind.Personal)) return false
  const priv = identityPrivate()
  if (!priv) throw new Error('vault is locked — cannot birth a personal scope')
  const scopeKey = v1.randomBytes(32)
  try {
    const grant = b64std(
      v1.asymWrap(v1.hybridPublic(priv), scopeKey, SCOPE_GRANT, { nonce: v1.randomBytes(12) }),
    )
    await withRefresh(() => scopesApi().createPersonalScope({ createPersonalScopeRequest: { wrappedGrant: grant } }))
    return true
  } finally {
    scopeKey.fill(0)
    zero(priv)
  }
}

/** An opened scope: its opaque identity + the in-memory scope content key `K_SC` (owned transiently by the caller). */
export type OpenedScope = { scopeId: string; tenantId: string; kind: ScopeKind; key: Uint8Array }

/**
 * Open the caller's scope of the given `kind` → its identity + in-memory scope key (asymUnwrap our **own** grant
 * with the vaulted identity), or null if there is no such scope / the vault is locked. This is **open-only** —
 * it never mints a scope (the tenant scope is born cold at activation, ADR 0006; only `ensurePersonalScope`
 * births, and personal-only). The caller owns the returned key transiently.
 */
export async function openScope(kind: ScopeKind): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const scope = (await listMyScopes()).find((s) => s.kind === kind)
    if (!scope) return null
    const key = v1.asymUnwrap(
      v1.hybridSecret(priv),
      v1.hybridPublic(priv),
      fromB64std(scope.wrappedGrant),
      SCOPE_GRANT,
    )
    return { scopeId: scope.id, tenantId: scope.tenantId, kind: scope.kind, key }
  } finally {
    zero(priv)
  }
}

/** Open the member's personal scope → just its key (thin wrapper over {@link openScope}; used by e2e harnesses). */
export async function openPersonalScope(): Promise<Uint8Array | null> {
  return (await openScope(ScopeKind.Personal))?.key ?? null
}

/** Best-effort wipe of the transient identity-private copy the vault handed us. */
function zero(priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): void {
  priv.kemX.fill(0)
  priv.mlkemSeed.fill(0)
  priv.sign.fill(0)
}
