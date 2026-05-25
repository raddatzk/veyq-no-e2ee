import type { LoginResponse } from './authClient'
import { generatedCall, usersApi } from './generatedClient'
import { createPasskeyCredential } from '../shared/utils/passkeys'

export async function acceptInvitation(
  userId: number,
  token: string,
  password?: string,
  displayName?: string
): Promise<LoginResponse> {
  return generatedCall(
    usersApi.acceptInvitation({
      userId,
      acceptInvitationRequest: { displayName, password, token }
    })
  )
}

export async function acceptInvitationWithPasskey(
  userId: number,
  token: string,
  displayName: string,
  passkeyDisplayName: string
): Promise<LoginResponse> {
  const options = await generatedCall(
    usersApi.createInvitationPasskeyOptions({
      userId,
      passkeyInvitationOptionsRequest: { token }
    })
  )
  const credential = await createPasskeyCredential(options)
  return generatedCall(
    usersApi.acceptInvitationWithPasskey({
      userId,
      acceptPasskeyInvitationRequest: { displayName, passkeyDisplayName, token, credential }
    })
  )
}
