import type { TenantSummary } from '../authState'
import { callApi } from './httpClient'

type TenantsResponse = {
  tenants: TenantSummary[]
}

export async function tenants(): Promise<TenantSummary[]> {
  const response = await callApi<TenantsResponse>('/api/tenants')
  return response.tenants
}

export async function createTenant(name: string, displayName: string): Promise<TenantSummary> {
  return callApi('/api/tenants', {
    method: 'POST',
    body: JSON.stringify({ name, displayName })
  })
}
