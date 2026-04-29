import type { TenantSummary, UserSummary } from '../authState'
import { callApi } from './httpClient'

export type EntrypointSummary = {
  serverId: string
  user: UserSummary | null
  tenants: TenantSummary[]
}

export async function bootstrap(): Promise<EntrypointSummary> {
  return callApi('/api/frontend/bootstrap')
}
