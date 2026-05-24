export const API_BASE = import.meta.env.VITE_API_BASE || ''

let refreshRequest: Promise<void> | null = null
const shareRefreshRequests = new Map<string, Promise<void>>()

export class SetupRequiredError extends Error {
  constructor() {
    super('Setup required')
    this.name = 'SetupRequiredError'
  }
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly fallbackMessage: string,
    readonly body: unknown
  ) {
    super(fallbackMessage)
    this.name = 'ApiError'
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

  if (response.status === 401 && shouldTryShareRefresh(path)) {
    const shareId = publicShareId(path)
    try {
      await refreshShareLogin(shareId)
      response = await requestUrl(url, options)
    } catch {
      // Keep the original 401 semantics for callers when refresh is not possible.
    }
  } else if (response.status === 401 && shouldTryRefresh(path)) {
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
  const body = await response.json().catch(() => null)
  const code = errorCode(body, response.status)
  const message = errorMessage(body)
  if (response.status === 503 && code === 'SETUP_REQUIRED') {
    return new SetupRequiredError()
  }
  return new ApiError(response.status, code, message, body)
}

function errorCode(body: unknown, status: number): string {
  if (isRecord(body) && typeof body.code === 'string' && body.code) return body.code
  return legacyCode(body, status)
}

function errorMessage(body: unknown): string {
  if (isRecord(body) && typeof body.message === 'string' && body.message) return body.message
  if (isRecord(body) && typeof body.error === 'string' && body.error) return body.error
  return 'Request failed'
}

function legacyCode(body: unknown, status: number): string {
  if (isRecord(body) && body.error === 'Setup required') return 'SETUP_REQUIRED'
  if (status === 400) return 'BAD_REQUEST'
  if (status === 401) return 'UNAUTHORIZED'
  if (status === 403) return 'FORBIDDEN'
  if (status === 404) return 'NOT_FOUND'
  if (status === 409) return 'CONFLICT'
  if (status === 413) return 'UPLOAD_TOO_LARGE'
  if (status === 416) return 'RANGE_NOT_SATISFIABLE'
  if (status === 429) return 'RATE_LIMITED'
  if (status === 503) return 'SERVICE_UNAVAILABLE'
  if (status >= 500) return 'INTERNAL_SERVER_ERROR'
  return 'REQUEST_FAILED'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
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

async function refreshShareLogin(shareId: string): Promise<void> {
  const existing = shareRefreshRequests.get(shareId)
  if (existing) return existing

  const request = requestUrl(`${API_BASE}/api/public-shares/${encodeURIComponent(shareId)}/refresh`, {
    method: 'POST'
  })
    .then(async (response) => {
      if (!response.ok) {
        throw await apiError(response)
      }
    })
    .finally(() => {
      shareRefreshRequests.delete(shareId)
    })

  shareRefreshRequests.set(shareId, request)
  return request
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

function publicShareId(path: string): string {
  return path.match(/^\/api\/public-shares\/([^/]+)/)?.[1] || ''
}

function shouldTryShareRefresh(path: string): boolean {
  return (
    path.startsWith('/api/public-shares/') &&
    !path.endsWith('/login') &&
    !path.endsWith('/logout') &&
    !path.endsWith('/refresh') &&
    publicShareId(path) !== ''
  )
}

function shouldTryRefresh(path: string): boolean {
  return (
    !path.startsWith('/api/public-shares/') &&
    path !== '/api/auth/login' &&
    path !== '/api/auth/logout' &&
    path !== '/api/auth/refresh' &&
    path !== '/api/setup' &&
    path !== '/api/setup/admin'
  )
}
