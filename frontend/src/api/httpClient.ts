const API_BASE = import.meta.env.VITE_API_BASE || ''

export async function callApi<T>(path: string, options: RequestInit = {}): Promise<T> {
  const httpCall = async () => await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  })

  const response = await httpCall()

  if (response.status === 401) {
    await refreshLogin()
    response = await httpCall()
  }

  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: 'Request failed' }))
    throw new Error(body.error || 'Request failed')
  }

  if (response.status === 204) return null as T
  return response.json()
}

async function doCallApi<T>(path: string, options: RequestInit = {}): Promise<T> {
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

  if (response.status === 204) return null as T
  return response.json()
}

async function refreshLogin(): Promise<void> {
  await callApi('/api/auth/refresh', { method: 'POST' })
}
