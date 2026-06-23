// uc.admin.create-tenant-invite-owner (web) — provision a tenant shell + owner invite, and claim
// ownership by minting the tenant scope keypair and granting it to oneself (server stays blind).
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import { b64url, fromB64url } from '../../../shared/base64'

export type TenantSummary = { id: string; name: string; displayName: string; domain?: string; logoUrl?: string; status: string }
export type OwnerInvite = { inviteId: string; bootstrapSecret: string; expiresAt: string }
export type TenantOwnerInvite = { tenant: TenantSummary; invite: OwnerInvite }
export type MyScopeGrant = {
  scopeId: string
  scopePublicKey: { suiteId: string; kemPublicKey: string }
  wrappedScopePrivateKey: { suiteId: string; bytes: string }
}

const json = (token: string) => ({ 'content-type': 'application/json', authorization: `Bearer ${token}` })

async function post<T>(apiBase: string, path: string, token: string, body?: unknown): Promise<T> {
  const res = await fetch(`${apiBase}${path}`, { method: 'POST', headers: json(token), body: body ? JSON.stringify(body) : undefined })
  if (!res.ok) throw await apiError(res, `POST ${path}`)
  return res.json()
}

export const createTenant = (apiBase: string, token: string, name: string, displayName: string, domain?: string) =>
  post<TenantSummary>(apiBase, '/api/tenants', token, { name, displayName, domain })

export const issueOwnerInvite = (apiBase: string, token: string, tenantId: string, email = '') =>
  post<OwnerInvite>(apiBase, `/api/tenants/${tenantId}/owner-invite`, token, { email })

/** A URL-safe tenant handle from a display name (the server keys tenants by it). */
function slugify(name: string): string {
  return name.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'space'
}

/**
 * Provision only the tenant shell and owner invite. This deliberately does not claim ownership: the invited
 * owner becomes a member and key-holder only after opening the invite link and confirming it.
 */
export async function createTenantOwnerInvite(apiBase: string, token: string, displayName: string, domain: string, ownerEmail: string): Promise<TenantOwnerInvite> {
  const base = slugify(displayName)
  for (let attempt = 0; attempt < 25; attempt++) {
    const handle = attempt === 0 ? base : `${base}-${attempt + 1}`
    try {
      const tenant = await createTenant(apiBase, token, handle, displayName.trim(), domain.trim())
      const invite = await issueOwnerInvite(apiBase, token, tenant.id, ownerEmail.trim())
      return { tenant, invite }
    } catch (e) {
      const msg = String(e)
      if (!msg.includes('409') && !msg.toLowerCase().includes('exists')) throw e
    }
  }
  throw new Error('could not create tenant (handle conflict)')
}

export async function uploadTenantLogo(apiBase: string, token: string, tenantId: string, logo: ScaledTenantLogo): Promise<TenantSummary> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/logo`, {
    method: 'PUT',
    headers: json(token),
    body: JSON.stringify({ contentType: logo.contentType, bytes: logo.bytes }),
  })
  if (!res.ok) throw await apiError(res, 'upload tenant logo')
  return res.json()
}

export async function clearTenantLogo(apiBase: string, token: string, tenantId: string): Promise<TenantSummary> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/logo`, { method: 'DELETE', headers: { authorization: `Bearer ${token}` } })
  if (!res.ok) throw await apiError(res, 'clear tenant logo')
  return res.json()
}

export type ScaledTenantLogo = { contentType: 'image/png' | 'image/jpeg' | 'image/webp'; bytes: string; previewUrl: string }

export async function scaleTenantLogo(file: File, maxSide = 512, maxBytes = 512 * 1024): Promise<ScaledTenantLogo> {
  if (!file.type.startsWith('image/')) throw new Error('Selected file is not an image')
  const bitmap = await createImageBitmap(file)
  try {
    let side = maxSide
    const png = await encodeScaledBitmap(bitmap, side, 'image/png')
    if (png.blob.size <= maxBytes) return toScaledTenantLogo(png.blob, 'image/png')

    for (const quality of [0.9, 0.82, 0.74, 0.66]) {
      const jpg = await encodeScaledBitmap(bitmap, side, 'image/jpeg', quality)
      if (jpg.blob.size <= maxBytes) return toScaledTenantLogo(jpg.blob, 'image/jpeg')
    }

    while (side > 128) {
      side = Math.max(128, Math.floor(side * 0.75))
      const jpg = await encodeScaledBitmap(bitmap, side, 'image/jpeg', 0.78)
      if (jpg.blob.size <= maxBytes) return toScaledTenantLogo(jpg.blob, 'image/jpeg')
    }
  } finally {
    bitmap.close()
  }
  throw new Error('Logo image is too large')
}

async function encodeScaledBitmap(bitmap: ImageBitmap, maxSide: number, contentType: ScaledTenantLogo['contentType'], quality?: number): Promise<{ blob: Blob }> {
  const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Could not scale logo')
  if (contentType === 'image/jpeg') {
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, width, height)
  }
  ctx.drawImage(bitmap, 0, 0, width, height)
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not encode logo'))), contentType, quality))
  return { blob }
}

async function toScaledTenantLogo(blob: Blob, contentType: ScaledTenantLogo['contentType']): Promise<ScaledTenantLogo> {
  const bytes = await blobToBase64(blob)
  return { contentType, bytes, previewUrl: URL.createObjectURL(blob) }
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error ?? new Error('Could not read logo'))
    reader.onload = () => resolve(String(reader.result).split(',')[1] ?? '')
    reader.readAsDataURL(blob)
  })
}

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
