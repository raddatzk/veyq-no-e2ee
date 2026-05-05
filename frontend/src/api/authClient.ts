import { authApi, generatedCall } from './generatedClient'
import type { LoginSessionResponse } from './generated'

export type LoginResponse = LoginSessionResponse

export async function login(email: string, password: string): Promise<LoginResponse> {
  return generatedCall(authApi.login({ loginRequest: { email, password } }))
}

export async function logout(): Promise<void> {
  await generatedCall(authApi.logout())
}
