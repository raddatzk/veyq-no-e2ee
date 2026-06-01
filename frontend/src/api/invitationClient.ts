import { userFromSession, type LoginResponse } from './authClient'
import { generatedCall, usersApi } from './generatedClient'
import type { InvitationMetadataResponse } from './generated'
import { createPasskeyCredential } from '../shared/utils/passkeys'

export async function invitationMetadata(userId: number, token: string): Promise<InvitationMetadataResponse> {
  return generatedCall(
    usersApi.invitationMetadata({
      userId,
      invitationMetadataRequest: { token }
    })
  )
}

export async function acceptInvitation(
  userId: number,
  token: string,
  password?: string,
  displayName?: string
): Promise<LoginResponse> {
  return userFromSession(
    await generatedCall(
      usersApi.acceptInvitation({
        userId,
        acceptInvitationRequest: { displayName, password, token }
      })
    )
  )
}

export async function acceptInvitationWithPasskey(
  userId: number,
  token: string,
  displayName: string
): Promise<LoginResponse> {
  const options = await generatedCall(
    usersApi.createInvitationPasskeyOptions({
      userId,
      passkeyInvitationOptionsRequest: { token }
    })
  )
  const credential = await createPasskeyCredential(options)
  return userFromSession(
    await generatedCall(
      usersApi.acceptInvitationWithPasskey({
        userId,
        acceptPasskeyInvitationRequest: { displayName, token, credential }
      })
    )
  )
}
