// uc.spaces.personal-scope (web) — birth + open a member's per-tenant PERSONAL scope. Operator-blind: the
// scope key is generated on-device and sealed to the member's own identity (asymWrap, the hybrid KEM); only
// the ciphertext grant reaches the server. The active tenant is resolved server-side from the Host.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { scopesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { identityPrivate } from '../../app/vault'
import { sealName, openName } from '../memory/tree'
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

/**
 * Create a named GROUP scope in the active tenant (en.spaces.group-scope, cr-0032) — operator-blind: generate a
 * random `K_SC`, **seal the name under it**, seal `K_SC` to our **own** identity (the self-grant), and POST only
 * the two ciphertexts. The server mints the scope + our OWNER self-membership and never sees the key or the name.
 * Requires an unlocked vault. Returns the new scope id (so the caller can switch into `#@<id>`).
 */
export async function createGroup(name: string): Promise<string> {
  const priv = identityPrivate()
  if (!priv) throw new Error('vault is locked — cannot create a group')
  const scopeKey = v1.randomBytes(32)
  try {
    const encryptedName = sealName(scopeKey, name)
    const grant = b64std(v1.asymWrap(v1.hybridPublic(priv), scopeKey, SCOPE_GRANT, { nonce: v1.randomBytes(12) }))
    const created = await withRefresh(() =>
      scopesApi().createGroup({ createGroupRequest: { encryptedName, wrappedGrant: grant } }),
    )
    return created.scopeId
  } finally {
    scopeKey.fill(0)
    zero(priv)
  }
}

/** A group the member holds — the switcher row: opaque id + tenant + the **decrypted** (client-side) name. */
export type GroupSpace = { scopeId: string; tenantId: string; name: string }

/**
 * The member's groups in the active tenant with their names decrypted on-device (en.spaces.group-scope). Each
 * group's name is sealed under **its own** `K_SC`, so we open every group's grant to decrypt its name — the
 * server only ever returned the ciphertext name + ciphertext grant. A group whose grant/name won't open (E2, a
 * rotated/foreign grant) is dropped from the switcher rather than crashing it.
 */
export async function listMyGroups(): Promise<GroupSpace[]> {
  const priv = identityPrivate()
  if (!priv) return []
  try {
    return (await listMyScopes())
      .filter((s) => s.kind === ScopeKind.Group)
      .flatMap((g) => {
        let key: Uint8Array | null = null
        try {
          key = unwrapGrant(g, priv)
          const name = g.encryptedName ? openName(key, g.encryptedName) : ''
          return [{ scopeId: g.id, tenantId: g.tenantId, name }]
        } catch {
          return []
        } finally {
          key?.fill(0)
        }
      })
  } finally {
    zero(priv)
  }
}

/** An opened scope: its opaque identity + the in-memory scope content key `K_SC` (owned transiently by the caller). */
export type OpenedScope = { scopeId: string; tenantId: string; kind: ScopeKind; key: Uint8Array }

/** asymUnwrap our **own** grant on a scope row with the vaulted identity → the in-memory `K_SC`. Caller owns it. */
function unwrapGrant(scope: ScopeSummary, priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): Uint8Array {
  return v1.asymUnwrap(v1.hybridSecret(priv), v1.hybridPublic(priv), fromB64std(scope.wrappedGrant), SCOPE_GRANT)
}

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
    return { scopeId: scope.id, tenantId: scope.tenantId, kind: scope.kind, key: unwrapGrant(scope, priv) }
  } finally {
    zero(priv)
  }
}

/**
 * Open a specific scope by its opaque id → its identity + in-memory scope key, or null if the caller holds no
 * such scope / the vault is locked / the grant won't open. Used to enter a group from the `#@<id>` fragment
 * (en.spaces.group-scope, cr-0032): the id is resolved against the caller's **own** scope list, client-side.
 */
export async function openScopeById(scopeId: string): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const scope = (await listMyScopes()).find((s) => s.id === scopeId)
    if (!scope) return null
    return { scopeId: scope.id, tenantId: scope.tenantId, kind: scope.kind, key: unwrapGrant(scope, priv) }
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
