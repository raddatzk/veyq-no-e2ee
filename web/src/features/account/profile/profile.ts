// Per-tenant member identity (web, cr-0083 / ADR 0023): the caller's `username` (plaintext handle) + their real
// name (first + last) **sealed under K_tenant**. Operator-blind: the name is AEAD-sealed on-device (same
// `symWrap` as favorites/node-meta) and only the ciphertext blob crosses the API. The username is a plaintext
// per-tenant handle the server enforces unique on.
import * as v1 from '../../../shared/crypto/v1'
import { b64std, fromB64std } from '../../../shared/base64'
import { accountProfileApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import { openScope } from '../../spaces/scopes'
import { ScopeKind } from '../../../api/generated/scopes'
import type { MemberProfile } from '../../../api/generated/account'

export type { MemberProfile } from '../../../api/generated/account'

/** AAD/context binding for the sealed real-name blob (like `favorites` for the favorites blob). */
const NAME_CTX = 'member-name'
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** A member's real name (decrypted, on-device only). */
export interface MemberName {
  firstName: string
  lastName: string
}

/** The caller's profile in the active tenant (Host-resolved): the plaintext handle + the sealed name blob. */
export function getMyProfile(): Promise<MemberProfile> {
  return withRefresh(() => accountProfileApi().getMyProfile())
}

/** Update the caller's username, sealed name, and/or the account-global group-add consent preference (cr-0094) —
 *  each may be omitted to leave it unchanged. */
export function updateMyProfile(update: {
  username?: string
  sealedName?: string
  requireGroupAddConsent?: boolean
}): Promise<MemberProfile> {
  return withRefresh(() => accountProfileApi().updateMyProfile({ profileUpdateRequest: update }))
}

/** Seal a first/last name under `K_tenant` → opaque base64 blob the server stores verbatim. */
export function sealName(kTenant: Uint8Array, name: MemberName): string {
  const json = encoder.encode(JSON.stringify({ firstName: name.firstName.trim(), lastName: name.lastName.trim() }))
  return b64std(v1.symWrap(kTenant, json, NAME_CTX, v1.randomBytes(12)))
}

/** Decrypt a sealed-name blob with `K_tenant` → the name, or null if absent/unreadable (wrong key, corrupt). */
export function unsealName(kTenant: Uint8Array, sealedName: string | null | undefined): MemberName | null {
  if (!sealedName) return null
  try {
    const obj = JSON.parse(decoder.decode(v1.symUnwrap(kTenant, fromB64std(sealedName), NAME_CTX)))
    if (typeof obj?.firstName === 'string' && typeof obj?.lastName === 'string') {
      return { firstName: obj.firstName, lastName: obj.lastName }
    }
  } catch {
    // wrong key / corrupt / not-yet-sealed — treated as "no readable name"
  }
  return null
}

/**
 * Open the **active tenant's** scope key `K_tenant` on-device (the caller's tenant grant), independent of which
 * space is currently active — the profile panel + directory need it to seal/unseal names even from Personal.
 * The caller owns the returned key transiently and must not mutate it; null if not a granted tenant member.
 */
export async function openTenantKey(): Promise<Uint8Array | null> {
  return (await openScope(ScopeKind.Tenant))?.key ?? null
}
