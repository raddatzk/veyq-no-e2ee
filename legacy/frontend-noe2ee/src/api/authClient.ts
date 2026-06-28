import { authApi, generatedCall, usersApi } from './generatedClient'
import type { LoginSession, MobileSessionCodeResponse, PasskeyLoginDomain, PasskeyLinkResponse, UserSummary } from './generated'
import { createPasskeyCredential, getPasskeyCredential } from '../shared/utils/passkeys'
import { clearAccessSession, rememberAccessSession } from '../shared/api/accessSession'

export type LoginResponse = UserSummary

export async function login(email: string, password: string, totpCode?: string): Promise<LoginResponse> {
  return userFromSession(await generatedCall(authApi.login({ loginRequest: { email, password, totpCode } })))
}

export async function loginWithPasskey(): Promise<LoginResponse> {
  const options = await generatedCall(authApi.passkeyLoginOptions())
  const credential = await getPasskeyCredential(options)
  return userFromSession(await generatedCall(authApi.passkeyLogin({ passkeyLoginRequest: credential })))
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

export async function createMobileSessionCode(
  redirectUri: string,
  state?: string
): Promise<MobileSessionCodeResponse> {
  return generatedCall(
    authApi.createMobileSessionCode({
      mobileSessionCodeRequest: {
        redirectUri,
        state
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
  const session = await generatedCall(
    authApi.completePasskeyLink({
      completePasskeyLinkRequest: {
        grant: grant.grant,
        credential
      }
    })
  )
  return userFromSession(session)
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
  const session = await generatedCall(
    usersApi.resetMyPasskey({
      userId,
      resetPasskeyRequest: {
        token,
        credential
      }
    })
  )
  return userFromSession(session)
}

export async function logout(): Promise<void> {
  try {
    await generatedCall(authApi.logout())
  } finally {
    clearAccessSession()
  }
}

export function userFromSession(session: LoginSession): UserSummary {
  rememberAccessSession(session)
  return session.user
}
