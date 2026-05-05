import { authApi, generatedCall } from './generatedClient'
import type { UserSummary } from './generated'

export type LoginResponse = UserSummary

export async function login(email: string, password: string): Promise<LoginResponse> {
  return generatedCall(authApi.login({ loginRequest: { email, password } }))
}

export async function logout(): Promise<void> {
  await generatedCall(authApi.logout())
}
