export const API_BASE = import.meta.env.VITE_API_BASE || ''

let refreshRequest: Promise<void> | null = null

export class SetupRequiredError extends Error {
  constructor() {
    super('Setup required')
    this.name = 'SetupRequiredError'
  }
}

export async function callApi<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await apiFetch(path, options)

  if (!response.ok) {
    throw await apiError(response)
  }

  if (response.status === 204) return null as T
  return response.json()
}

export async function apiFetch(path: string, options: RequestInit = {}): Promise<Response> {
  return apiFetchUrl(`${API_BASE}${path}`, options)
}

export async function apiFetchUrl(url: string, options: RequestInit = {}): Promise<Response> {
  const path = pathFromUrl(url)
  let response = await requestUrl(url, options)

  if (response.status === 401 && shouldTryRefresh(path)) {
    try {
      await refreshLogin()
      response = await requestUrl(url, options)
    } catch {
      // Keep the original 401 semantics for callers when refresh is not possible.
    }
  }

  return response
}

export async function apiError(response: Response): Promise<Error> {
  const body = await response.json().catch(() => ({ error: 'Request failed' }))
  if (response.status === 503 && body.error === 'Setup required') {
    return new SetupRequiredError()
  }
  return new Error(body.error || 'Request failed')
}

async function requestUrl(url: string, options: RequestInit = {}): Promise<Response> {
  const headers =
    options.body instanceof FormData
      ? { ...(options.headers || {}) }
      : {
          'Content-Type': 'application/json',
          ...(options.headers || {})
        }

  return fetch(url, {
    credentials: 'include',
    ...options,
    headers
  })
}

async function refreshLogin(): Promise<void> {
  refreshRequest ||= requestUrl(`${API_BASE}/api/auth/refresh`, { method: 'POST' })
    .then(async (response) => {
      if (!response.ok) {
        throw await apiError(response)
      }
    })
    .finally(() => {
      refreshRequest = null
    })

  return refreshRequest
}

function pathFromUrl(url: string): string {
  if (API_BASE && url.startsWith(API_BASE)) return url.slice(API_BASE.length) || '/'
  if (url.startsWith('/')) return url
  return new URL(url).pathname
}

function shouldTryRefresh(path: string): boolean {
  return (
    path !== '/api/auth/login' &&
    path !== '/api/auth/logout' &&
    path !== '/api/auth/refresh' &&
    path !== '/api/setup' &&
    path !== '/api/setup/admin'
  )
}
