import type { UserSummary } from '../authState'
import { generatedCall, usersApi } from './generatedClient'

export async function me(): Promise<UserSummary> {
  return generatedCall(usersApi.me())
}
