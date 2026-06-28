import { generatedCall, setupApi } from './generatedClient'
import type { AdminSetupRequest, SetupStatusSummary } from './generated'
import { createPasskeyCredential } from '../shared/utils/passkeys'
import { userFromSession } from './authClient'

export type SetupStatusResponse = SetupStatusSummary
export type { AdminSetupRequest }

export async function setupStatus(): Promise<SetupStatusResponse> {
  return generatedCall(setupApi.status())
}

export async function setupAdmin(request: AdminSetupRequest): Promise<void> {
  await generatedCall(setupApi.createAdmin({ adminSetupRequest: request }))
}

export async function setupAdminWithPasskey(email: string, displayName: string) {
  const options = await generatedCall(
    setupApi.createAdminPasskeyOptions({
      adminPasskeySetupOptionsRequest: { email, displayName }
    })
  )
  const credential = await createPasskeyCredential(options)
  return userFromSession(
    await generatedCall(
      setupApi.createAdminWithPasskey({
        adminPasskeySetupRequest: { email, displayName, credential }
      })
    )
  )
}
