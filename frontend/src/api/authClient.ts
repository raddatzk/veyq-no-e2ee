const API_BASE = import.meta.env.VITE_API_BASE || ''

export type LoginResponse = {
  userId: number
  username: string
  displayName: string
}

export type MeResponse = LoginResponse

async function call(path: string, options: RequestInit = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  })

  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: 'Request failed' }))
    throw new Error(body.error || 'Request failed')
  }

  if (response.status === 204) return null
  return response.json()
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  return call('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })
}

export async function me(): Promise<MeResponse> {
  return call('/api/auth/me')
}

export async function logout(): Promise<void> {
  await call('/api/auth/logout', { method: 'POST' })
}
