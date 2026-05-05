import type { TenantSummary, UserSummary } from '../authState'
import { frontendApi, generatedCall } from './generatedClient'

export type EntrypointSummary = {
  serverId: string
  user: UserSummary | null
  tenants: TenantSummary[]
}

export async function bootstrap(): Promise<EntrypointSummary> {
  return generatedCall(frontendApi.entrypoint())
}
