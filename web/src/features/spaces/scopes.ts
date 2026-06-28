// uc.spaces.personal-scope (web) — birth + open a member's per-tenant PERSONAL scope. Operator-blind: the
// scope key is generated on-device and sealed to the member's own identity (asymWrap, the hybrid KEM); only
// the ciphertext grant reaches the server. The active tenant is resolved server-side from the Host.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { scopesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { identityPrivate } from '../../app/vault'
import { ScopeKind, type ScopeSummary, type MyTenant } from '../../api/generated/scopes'

const SCOPE_GRANT = 'scope-grant'

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
 * Open the member's personal scope → the in-memory scope key (asymUnwrap our own grant with the vaulted
 * identity), or null if there is none / the vault is locked. The caller owns the returned key transiently.
 */
export async function openPersonalScope(): Promise<Uint8Array | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    if (!personal) return null
    return v1.asymUnwrap(
      v1.hybridSecret(priv),
      v1.hybridPublic(priv),
      fromB64std(personal.wrappedGrant),
      SCOPE_GRANT,
    )
  } finally {
    zero(priv)
  }
}

/** Best-effort wipe of the transient identity-private copy the vault handed us. */
function zero(priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): void {
  priv.kemX.fill(0)
  priv.mlkemSeed.fill(0)
  priv.sign.fill(0)
}
