import { authApi, generatedCall, usersApi } from './generatedClient'
import type { UserSummary } from './generated'
import { getPasskeyCredential } from '../shared/utils/passkeys'

export type LoginResponse = UserSummary

export async function login(email: string, password: string): Promise<LoginResponse> {
  return generatedCall(authApi.login({ loginRequest: { email, password } }))
}

export async function loginWithPasskey(): Promise<LoginResponse> {
  const options = await generatedCall(authApi.passkeyLoginOptions())
  const credential = await getPasskeyCredential(options)
  return generatedCall(authApi.passkeyLogin({ passkeyLoginRequest: credential }))
}

export async function forgotPassword(email: string): Promise<void> {
  await generatedCall(usersApi.forgotPassword({ forgotPasswordRequest: { email } }))
}

export async function resetPassword(userId: number, token: string, password: string): Promise<void> {
  await generatedCall(usersApi.resetMyPassword({ userId, resetPasswordRequest: { token, password } }))
}

export async function logout(): Promise<void> {
  await generatedCall(authApi.logout())
}
