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

/** Accept as an existing (signed-in) user: activates the tenant with the cold-born grant. */
export function acceptInvite(bootstrapSecret: string, grant: string): Promise<TenantActivated> {
  return tenantsApi().acceptTenantInvite({ acceptInviteRequest: { bootstrapSecret, grant } })
}

/** Register a new owner account cold and accept in one step (public — the email comes from the invite). */
export function registerAndAccept(
  bootstrapSecret: string,
  displayName: string,
  material: AccountMaterial,
  grant: string,
): Promise<TenantActivatedWithUser> {
  return tenantsApi().registerAndAcceptTenantInvite({
    registerAndAcceptRequest: {
      bootstrapSecret,
      account: {
        displayName,
        suiteId: material.suiteId,
        identityPublicKey: material.identityPublicKey,
        wrappedIdentityPrivateKey: material.wrappedIdentityPrivateKey,
        authPublicKey: material.authPublicKey,
        masterKeyWraps: material.masterKeyWraps,
        recoveryMasterKeyTag: material.recoveryMasterKeyTag,
      },
      grant,
    },
  })
}

/**
 * Cold-birth the TENANT scope: a fresh scope root key sealed (asymWrap) to the owner's **own** identity
 * public key — the first grant, opaque ciphertext to the server. The scope key itself never leaves the device.
 */
export function scopeGrant(identityPublic: HybridPublic): string {
  const scopeRootKey = v1.randomBytes(32)
  return b64std(v1.asymWrap(identityPublic, scopeRootKey, 'scope-grant', { nonce: v1.randomBytes(12) }))
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
