// uc.admin.create-tenant (web, Slice A / cr-0009) — Server-Owner tenant administration on the
// operator-blind data layer. Everything here is operational metadata + a hashed invite; there is NO content
// key (the cold TENANT-scope birth that activates a tenant is Slice B). Thin facade over the generated
// tenants client: views depend on this typed surface, never on the generator runtime.
import { tenantsApi } from '../../../api/client'
import { ResponseError } from '../../../api/generated/tenants'
import type {
  InvitePreview,
  TenantActivated,
  TenantActivatedWithUser,
  TenantCreated,
  TenantInviteState,
  TenantSummary,
} from '../../../api/generated/tenants'
import * as v1 from '../../../shared/crypto/v1'
import type { HybridPublic } from '../../../shared/crypto/v1'
import { b64std } from '../../../shared/base64'
import type { AccountMaterial } from '../../account/register/accountBirth'

export type { TenantSummary, TenantInviteState, TenantCreated, InvitePreview } from '../../../api/generated/tenants'

/** Create a PENDING tenant and mint an owner invite. */
export function createTenant(tenantName: string, domain: string, ownerEmail: string): Promise<TenantCreated> {
  return tenantsApi().createTenant({ createTenantRequest: { tenantName, domain, ownerEmail } })
}

/** List all tenants (Server-Owner only). */
export function listTenants(): Promise<TenantSummary[]> {
  return tenantsApi().listTenants()
}

/** Re-mint and resend a pending tenant's invite to the same owner email. */
export function resendInvite(tenantId: string): Promise<TenantInviteState> {
  return tenantsApi().resendTenantInvite({ tenantId })
}

/** Re-bind a pending tenant's invite to a different owner email. */
export function reassignInvite(tenantId: string, ownerEmail: string): Promise<TenantInviteState> {
  return tenantsApi().reassignTenantInvite({ tenantId, reassignOwnerRequest: { ownerEmail } })
}

/** Revoke a pending tenant. */
export function revokeTenant(tenantId: string): Promise<void> {
  return tenantsApi().revokeTenant({ tenantId })
}

// --- Slice B (cr-0010): invite acceptance / activation ---

/** Preview an invite from its bootstrap secret (public — no session). */
export function previewInvite(bootstrapSecret: string): Promise<InvitePreview> {
  return tenantsApi().previewTenantInvite({ acceptPreviewRequest: { bootstrapSecret } })
}

/** Accept as an existing (signed-in) user, choosing a `username` for this tenant (ADR 0023). An OWNER invite
 *  carries the cold-born grant; a MEMBER invite omits it (grant-less; a key-holder delivers the key, cr-0076). */
export function acceptInvite(
  bootstrapSecret: string,
  username: string,
  grant?: string,
  sealedName?: string,
): Promise<TenantActivated> {
  return tenantsApi().acceptTenantInvite({ acceptInviteRequest: { bootstrapSecret, username, grant, sealedName } })
}

/** Register a new account cold and accept in one step (public — the email comes from the invite). `username` is
 *  the caller's per-tenant handle; `sealedName` is the OPTIONAL real name sealed under `K_tenant` at join
 *  (owner cold-birth only — a grant-less member seals it later via the profile op, ADR 0023). `grant` is set for
 *  an OWNER invite (cold-births the TENANT scope) and omitted for a MEMBER invite (grant-less join). */
export function registerAndAccept(
  bootstrapSecret: string,
  username: string,
  material: AccountMaterial,
  grant?: string,
  sealedName?: string,
): Promise<TenantActivatedWithUser> {
  return tenantsApi().registerAndAcceptTenantInvite({
    registerAndAcceptRequest: {
      bootstrapSecret,
      username,
      account: {
        suiteId: material.suiteId,
        identityPublicKey: material.identityPublicKey,
        wrappedIdentityPrivateKey: material.wrappedIdentityPrivateKey,
        authPublicKey: material.authPublicKey,
        masterKeyWraps: material.masterKeyWraps,
        recoveryMasterKeyTag: material.recoveryMasterKeyTag,
      },
      grant,
      sealedName,
    },
  })
}

/**
 * Cold-birth the TENANT scope: mint a fresh scope root key (`K_tenant`), and return **both** the first grant
 * (the key `asymWrap`'d to the owner's own identity public key — opaque ciphertext to the server) **and the key
 * itself**, so the owner can immediately seal their real name under it at join (cr-0106). The key never leaves
 * the device; only the sealed grant + (optional) sealed name cross the API.
 */
export function bornTenantScope(identityPublic: HybridPublic): { grant: string; scopeRootKey: Uint8Array } {
  const scopeRootKey = v1.randomBytes(32)
  const grant = b64std(v1.asymWrap(identityPublic, scopeRootKey, 'scope-grant', { nonce: v1.randomBytes(12) }))
  return { grant, scopeRootKey }
}

/** Just the grant from a fresh tenant-scope birth (for callers that don't seal a name). */
export function scopeGrant(identityPublic: HybridPublic): string {
  return bornTenantScope(identityPublic).grant
}

/**
 * Decode a failed tenant call into `{ status, code }` for UI mapping (401 → re-login, 403 → not owner,
 * 409 → domain_taken / tenant_not_pending). Returns status 0 for a non-HTTP failure (e.g. network).
 */
export async function tenantError(e: unknown): Promise<{ status: number; code: string | null }> {
  if (e instanceof ResponseError) {
    let code: string | null = null
    try {
      code = ((await e.response.clone().json()) as { code?: string })?.code ?? null
    } catch {
      // no / non-JSON body — leave code null
    }
    return { status: e.response.status, code }
  }
  return { status: 0, code: null }
}
