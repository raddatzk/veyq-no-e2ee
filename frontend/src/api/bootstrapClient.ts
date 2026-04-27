import type { TenantSummary, UserSummary } from '../authState'
import { callApi } from './httpClient'

export type BootstrapResponse = {
  adminRequired: boolean
  tenantAvailable: boolean
  user: UserSummary | null
  tenants: TenantSummary[]
}

export async function bootstrap(): Promise<BootstrapResponse> {
  return callApi('/api/bootstrap')
}
