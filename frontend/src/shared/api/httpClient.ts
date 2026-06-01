import { clearAccessSession, hasExpiredAccessSession, rememberAccessSession } from './accessSession'

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

export class UploadCancelledError extends Error {
  constructor() {
    super('Upload cancelled')
    this.name = 'UploadCancelledError'
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
  if (shouldRefreshExpiredAccessSession(path)) {
    await refreshExpiredAccessSession()
  }
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

export type UploadProgress = {
  loaded: number
  total: number | null
}

export async function uploadFormDataJson<T>(
  path: string,
  body: FormData,
  onProgress?: (progress: UploadProgress) => void,
  signal?: AbortSignal
): Promise<T> {
  const url = `${API_BASE}${path}`
  if (shouldRefreshExpiredAccessSession(path)) {
    await refreshExpiredAccessSession()
  }
  let response = await requestUploadUrl('POST', url, body, undefined, onProgress, signal)

  if (response.status === 401 && shouldTryShareRefresh(path)) {
    try {
      await refreshShareLogin(publicShareId(path))
      response = await requestUploadUrl('POST', url, body, undefined, onProgress, signal)
    } catch (error) {
      if (error instanceof UploadCancelledError) throw error
      // Keep the original 401 semantics for callers when refresh is not possible.
    }
  } else if (response.status === 401 && shouldTryRefresh(path)) {
    try {
      await refreshLogin()
      response = await requestUploadUrl('POST', url, body, undefined, onProgress, signal)
    } catch (error) {
      if (error instanceof UploadCancelledError) throw error
      // Keep the original 401 semantics for callers when refresh is not possible.
    }
  }

  if (!response.ok) {
    throw await apiError(response)
  }

  return response.json()
}

export async function uploadBlob(
  path: string,
  body: Blob,
  contentType: string,
  onProgress?: (progress: UploadProgress) => void,
  signal?: AbortSignal
): Promise<void> {
  const url = `${API_BASE}${path}`
  if (shouldRefreshExpiredAccessSession(path)) {
    await refreshExpiredAccessSession()
  }
  let response = await requestUploadUrl('PUT', url, body, contentType, onProgress, signal)

  if (response.status === 401 && shouldTryShareRefresh(path)) {
    try {
      await refreshShareLogin(publicShareId(path))
      response = await requestUploadUrl('PUT', url, body, contentType, onProgress, signal)
    } catch (error) {
      if (error instanceof UploadCancelledError) throw error
      // Keep the original 401 semantics for callers when refresh is not possible.
    }
  } else if (response.status === 401 && shouldTryRefresh(path)) {
    try {
      await refreshLogin()
      response = await requestUploadUrl('PUT', url, body, contentType, onProgress, signal)
    } catch (error) {
      if (error instanceof UploadCancelledError) throw error
      // Keep the original 401 semantics for callers when refresh is not possible.
    }
  }

  if (!response.ok) {
    throw await apiError(response)
  }
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

function requestUploadUrl(
  method: 'POST' | 'PUT',
  url: string,
  body: FormData | Blob,
  contentType?: string,
  onProgress?: (progress: UploadProgress) => void,
  signal?: AbortSignal
): Promise<Response> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new UploadCancelledError())
      return
    }

    const request = new XMLHttpRequest()
    let abortedBySignal = false
    request.open(method, url)
    request.withCredentials = true
    request.responseType = 'text'
    if (contentType) {
      request.setRequestHeader('Content-Type', contentType)
    }

    request.upload.onprogress = (event) => {
      onProgress?.({
        loaded: event.loaded,
        total: event.lengthComputable ? event.total : null
      })
    }

    request.onload = () => {
      cleanup()
      try {
        resolve(
          new Response(responseBody(request), {
            status: request.status,
            statusText: request.statusText,
            headers: parseResponseHeaders(request.getAllResponseHeaders())
          })
        )
      } catch (error) {
        reject(error)
      }
    }

    const abortUpload = () => {
      abortedBySignal = true
      request.abort()
    }
    const cleanup = () => {
      signal?.removeEventListener('abort', abortUpload)
    }

    signal?.addEventListener('abort', abortUpload, { once: true })
    request.onerror = () => {
      cleanup()
      reject(new Error('Request failed'))
    }
    request.onabort = () => {
      cleanup()
      reject(abortedBySignal ? new UploadCancelledError() : new Error('Request aborted'))
    }
    request.send(body)
  })
}

function responseBody(request: XMLHttpRequest): BodyInit | null {
  return [204, 205, 304].includes(request.status) ? null : request.responseText
}

function parseResponseHeaders(rawHeaders: string): Headers {
  const headers = new Headers()
  rawHeaders
    .trim()
    .split(/[\r\n]+/)
    .filter(Boolean)
    .forEach((line) => {
      const separator = line.indexOf(':')
      if (separator <= 0) return
      headers.append(line.slice(0, separator).trim(), line.slice(separator + 1).trim())
    })
  return headers
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
      if (response.status !== 204) {
        const session = await response.json()
        rememberAccessSession(session)
      }
    })
    .catch((error) => {
      clearAccessSession()
      throw error
    })
    .finally(() => {
      refreshRequest = null
    })

  return refreshRequest
}

async function refreshExpiredAccessSession(): Promise<void> {
  try {
    await refreshLogin()
  } catch {
    // Keep the regular request/401 path when the refresh cookie is gone or invalid.
  }
}

function shouldRefreshExpiredAccessSession(path: string): boolean {
  return shouldTryRefresh(path) && hasExpiredAccessSession()
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
