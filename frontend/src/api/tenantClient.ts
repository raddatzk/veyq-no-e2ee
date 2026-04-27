const API_BASE = import.meta.env.VITE_API_BASE || ''

export type TenantGroup = {
  id: number
  name: string
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

export async function groups(tenantName: string): Promise<TenantGroup[]> {
  return call(`/api/${tenantName}/groups`)
}

export async function createGroup(tenantName: string, name: string): Promise<TenantGroup> {
  return call(`/api/${tenantName}/groups`, {
    method: 'POST',
    body: JSON.stringify({ name })
  })
}

export async function media(tenantName: string): Promise<unknown[]> {
  return call(`/api/${tenantName}/media`)
}
