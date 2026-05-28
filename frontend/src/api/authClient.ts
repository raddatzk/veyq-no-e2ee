import { authApi, generatedCall, usersApi } from './generatedClient'
import type { PasskeyLoginDomain, PasskeyLinkResponse, UserSummary } from './generated'
import { createPasskeyCredential, getPasskeyCredential } from '../shared/utils/passkeys'

export type LoginResponse = UserSummary

export async function login(email: string, password: string): Promise<LoginResponse> {
  return generatedCall(authApi.login({ loginRequest: { email, password } }))
}

export async function loginWithPasskey(): Promise<LoginResponse> {
  const options = await generatedCall(authApi.passkeyLoginOptions())
  const credential = await getPasskeyCredential(options)
  return generatedCall(authApi.passkeyLogin({ passkeyLoginRequest: credential }))
}

export async function passkeyLoginDomains(): Promise<PasskeyLoginDomain[]> {
  const response = await generatedCall(authApi.passkeyLoginDomains())
  return response.domains
}

export async function createPasskeyLink(
  targetOrigin: string,
  redirectPath: string
): Promise<PasskeyLinkResponse> {
  return generatedCall(
    authApi.createPasskeyLink({
      createPasskeyLinkRequest: {
        targetOrigin,
        redirectPath
      }
    })
  )
}

export async function completePasskeyLink(token: string): Promise<LoginResponse> {
  const grant = await generatedCall(
    authApi.exchangePasskeyLinkCode({ exchangePasskeyLinkCodeRequest: { code: token } })
  )
  const options = await generatedCall(
    authApi.createPasskeyLinkOptions({ passkeyLinkOptionsRequest: { grant: grant.grant } })
  )
  const credential = await createPasskeyCredential(options)
  return generatedCall(
    authApi.completePasskeyLink({
      completePasskeyLinkRequest: {
        grant: grant.grant,
        credential
      }
    })
  )
}

export async function forgotPassword(email: string): Promise<void> {
  await generatedCall(usersApi.forgotPassword({ forgotPasswordRequest: { email } }))
}

export async function forgotPasskey(email: string): Promise<void> {
  await generatedCall(usersApi.forgotPasskey({ forgotPasswordRequest: { email } }))
}

export async function resetPassword(userId: number, token: string, password: string): Promise<void> {
  await generatedCall(usersApi.resetMyPassword({ userId, resetPasswordRequest: { token, password } }))
}

export async function resetPasskey(userId: number, token: string): Promise<LoginResponse> {
  const options = await generatedCall(
    usersApi.createResetPasskeyOptions({ userId, passkeyResetOptionsRequest: { token } })
  )
  const credential = await createPasskeyCredential(options)
  return generatedCall(
    usersApi.resetMyPasskey({
      userId,
      resetPasskeyRequest: {
        token,
        credential
      }
    })
  )
}

export async function logout(): Promise<void> {
  await generatedCall(authApi.logout())
}
