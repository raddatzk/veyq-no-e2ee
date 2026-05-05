import { generatedCall, tenantsApi } from './generatedClient'
import type { TenantSummary } from './generated'

export type { TenantSummary }

export async function tenants(): Promise<TenantSummary[]> {
  const response = await generatedCall(tenantsApi.myTenants())
  return response.tenants
}

export async function createTenant(name: string, displayName: string, domain: string): Promise<TenantSummary> {
  return generatedCall(
    tenantsApi.createTenant({ createTenantRequest: { name, displayName, domain } })
  )
}
