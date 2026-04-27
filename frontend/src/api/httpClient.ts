const API_BASE = import.meta.env.VITE_API_BASE || ''

let refreshRequest: Promise<void> | null = null

export class SetupRequiredError extends Error {
  constructor() {
    super('Setup required')
    this.name = 'SetupRequiredError'
  }
}

export async function callApi<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response = await request(path, options)

  if (response.status === 401 && shouldTryRefresh(path)) {
    try {
      await refreshLogin()
      response = await request(path, options)
    } catch {
      // Keep the original 401 semantics for callers when refresh is not possible.
    }
  }

  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: 'Request failed' }))
    if (response.status === 503 && body.error === 'Setup required') {
      throw new SetupRequiredError()
    }
    throw new Error(body.error || 'Request failed')
  }

  if (response.status === 204) return null as T
  return response.json()
}

async function request(path: string, options: RequestInit = {}): Promise<Response> {
  return fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  })
}

async function refreshLogin(): Promise<void> {
  refreshRequest ||= request('/api/auth/refresh', { method: 'POST' })
    .then(async (response) => {
      if (!response.ok) {
        const body = await response.json().catch(() => ({ error: 'Request failed' }))
        throw new Error(body.error || 'Request failed')
      }
    })
    .finally(() => {
      refreshRequest = null
    })

  return refreshRequest
}

function shouldTryRefresh(path: string): boolean {
  return path !== '/api/auth/login' &&
    path !== '/api/auth/logout' &&
    path !== '/api/auth/refresh' &&
    path !== '/api/setup' &&
    path !== '/api/setup/admin'
}
