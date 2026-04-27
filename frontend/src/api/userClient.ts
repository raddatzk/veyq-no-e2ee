import type { UserSummary } from '../authState'
import { callApi } from './httpClient'

export async function me(): Promise<UserSummary> {
  return callApi('/api/me')
}
