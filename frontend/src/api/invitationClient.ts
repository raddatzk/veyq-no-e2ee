import type { LoginResponse } from './authClient'
import { generatedCall, usersApi } from './generatedClient'

export async function acceptInvitation(
  userId: number,
  token: string,
  password: string
): Promise<LoginResponse> {
  return generatedCall(
    usersApi.acceptInvitation({
      userId,
      acceptInvitationRequest: { password, token }
    })
  )
}
