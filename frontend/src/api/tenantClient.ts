import { generatedCall, tenantsApi } from './generatedClient'
import type { TenantCreationResponse, TenantSummary } from './generated'

export type { TenantCreationResponse, TenantSummary }

export async function tenants(): Promise<TenantSummary[]> {
  const response = await generatedCall(tenantsApi.myTenants())
  return response.tenants
}

export async function createTenant(
  name: string,
  displayName: string,
  domain: string,
  ownerEmail: string
): Promise<TenantCreationResponse> {
  return generatedCall(
    tenantsApi.createTenant({ createTenantRequest: { name, displayName, domain, ownerEmail } })
  )
}

export async function deleteTenant(tenantName: string): Promise<void> {
  return generatedCall(tenantsApi.deleteTenant({ tenantName }))
}
