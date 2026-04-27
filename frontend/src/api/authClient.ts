import type { TenantSummary } from '../authState'

const API_BASE = import.meta.env.VITE_API_BASE || ''

export type LoginResponse = {
  userId: number
  email: string
  displayName: string
}

export type MeResponse = LoginResponse

export type SetupStatusResponse = {
  adminRequired: boolean
  tenantAvailable: boolean
}

export type AdminSetupRequest = {
  password: string
  displayName: string
  email: string
}

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

export async function login(email: string, password: string): Promise<LoginResponse> {
  return call('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  })
}

export async function me(): Promise<MeResponse> {
  return call('/api/me')
}

export async function setupStatus(): Promise<SetupStatusResponse> {
  return call('/api/setup')
}

export async function setupAdmin(request: AdminSetupRequest): Promise<void> {
  await call('/api/setup/admin', {
    method: 'POST',
    body: JSON.stringify(request)
  })
}

export async function tenants(): Promise<TenantSummary[]> {
  return call('/api/tenants')
}

export async function createTenant(slug: string, name: string): Promise<TenantSummary> {
  return call('/api/tenants', {
    method: 'POST',
    body: JSON.stringify({ slug, name })
  })
}

export async function logout(): Promise<void> {
  await call('/api/auth/logout', { method: 'POST' })
}
