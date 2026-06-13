// uc.admin.create-tenant-invite-owner (web) — provision a tenant shell + owner invite, and claim
// ownership by minting the tenant scope keypair and granting it to oneself (server stays blind).
import * as v1 from '../../../shared/crypto/v1'

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  return Uint8Array.from(bin, (c) => c.charCodeAt(0))
}

export type TenantSummary = { id: string; name: string; displayName: string; domain?: string; status: string }
export type OwnerInvite = { inviteId: string; bootstrapSecret: string; expiresAt: string }
export type MyScopeGrant = {
  scopePublicKey: { suiteId: string; kemPublicKey: string }
  wrappedScopePrivateKey: { suiteId: string; bytes: string }
}

const json = (token: string) => ({ 'content-type': 'application/json', authorization: `Bearer ${token}` })

async function post<T>(apiBase: string, path: string, token: string, body?: unknown): Promise<T> {
  const res = await fetch(`${apiBase}${path}`, { method: 'POST', headers: json(token), body: body ? JSON.stringify(body) : undefined })
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status} ${await res.text()}`)
  return res.json()
}

export const createTenant = (apiBase: string, token: string, name: string, displayName: string, domain?: string) =>
  post<TenantSummary>(apiBase, '/api/tenants', token, { name, displayName, domain })

export const issueOwnerInvite = (apiBase: string, token: string, tenantId: string) =>
  post<OwnerInvite>(apiBase, `/api/tenants/${tenantId}/owner-invite`, token)

export async function getMyScopeGrant(apiBase: string, token: string, tenantId: string): Promise<MyScopeGrant> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/scope`, { headers: { authorization: `Bearer ${token}` } })
  if (!res.ok) throw new Error(`scope fetch failed: ${res.status}`)
  return res.json()
}

/**
 * Claim ownership of an empty tenant. Mints the tenant scope keypair and wraps its private key to the
 * claimer's identity public key (a self scope grant) — the server only ever sees ciphertext + the
 * scope public key. The identity public key is the hybrid KEM key (kemX || kemM) from the key bag.
 */
export async function claimTenantOwnership(
  apiBase: string,
  token: string,
  inviteId: string,
  bootstrapSecret: string,
  identityKemPublicKeyB64: string,
): Promise<TenantSummary> {
  const idKem = fromB64url(identityKemPublicKeyB64)
  const pkX = idKem.slice(0, 32)
  const pkM = idKem.slice(32)

  const scope = v1.generateIdentity() // reuse the hybrid-KEM bundle for the scope (sign field unused)
  const scopePublic = v1.concat(scope.pub.kemX, scope.pub.kemM)
  const scopePrivate = v1.serializeIdentityPrivate(scope)
  const grant = v1.asymWrap({ x: pkX, m: pkM }, scopePrivate, 'scope-grant', { nonce: v1.randomBytes(12) })

  return post<TenantSummary>(apiBase, `/api/tenant-invites/${inviteId}/claim`, token, {
    bootstrapSecret,
    scopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(scopePublic) },
    wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(grant) },
  })
}

/** Unwrap a scope grant with the holder's identity private + public key → the scope private bundle. */
export function openScopeGrant(
  grant: MyScopeGrant,
  identityPriv: v1.Identity['priv'],
  identityKemPublicKeyB64: string,
): v1.Identity['priv'] {
  const idKem = fromB64url(identityKemPublicKeyB64)
  const sk = v1.hybridSecret(identityPriv)
  const pk = { x: idKem.slice(0, 32), m: idKem.slice(32) }
  const scopePrivBytes = v1.asymUnwrap(sk, pk, fromB64url(grant.wrappedScopePrivateKey.bytes), 'scope-grant')
  return v1.deserializeIdentityPrivate(scopePrivBytes)
}
