import { callApi } from './httpClient'

export type LoginResponse = {
  userId: number
  email: string
  displayName: string
  serverRole: 'SERVER_OWNER' | 'SERVER_MEMBER'
}

export async function login(email: string, password: string): Promise<LoginResponse> {
  return callApi('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  })
}

export async function logout(): Promise<void> {
  await callApi('/api/auth/logout', { method: 'POST' })
}
