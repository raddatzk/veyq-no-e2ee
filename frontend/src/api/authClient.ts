import { authApi, generatedCall } from './generatedClient'
import type { LoginResponse } from './generated/auth'

export type { LoginResponse }

export async function login(email: string, password: string): Promise<LoginResponse> {
  return generatedCall(authApi.login({ loginRequest: { email, password } }))
}

export async function logout(): Promise<void> {
  await generatedCall(authApi.logout())
}
