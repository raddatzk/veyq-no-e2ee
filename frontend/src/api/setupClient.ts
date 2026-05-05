import { generatedCall, setupApi } from './generatedClient'
import type { AdminSetupRequest, SetupStatusSummary } from './generated'

export type SetupStatusResponse = SetupStatusSummary
export type { AdminSetupRequest }

export async function setupStatus(): Promise<SetupStatusResponse> {
  return generatedCall(setupApi.status())
}

export async function setupAdmin(request: AdminSetupRequest): Promise<void> {
  await generatedCall(setupApi.createAdmin({ adminSetupRequest: request }))
}
