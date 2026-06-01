import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  SetupRequiredError,
  UploadCancelledError,
  apiError,
  apiFetch,
  uploadBlob,
  uploadFormDataJson
} from './httpClient'
import { rememberAccessSession } from './accessSession'

class MockXMLHttpRequest {
  static instances: MockXMLHttpRequest[] = []

  upload = {
    onprogress: null as ((event: ProgressEvent) => void) | null
  }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  responseText = ''
  responseType = ''
  status = 200
  statusText = 'OK'
  withCredentials = false
  method = ''
  url = ''
  body: FormData | Blob | null = null
  headers = new Map<string, string>()
  abort = vi.fn(() => {
    this.onabort?.()
  })

  constructor() {
    MockXMLHttpRequest.instances.push(this)
  }

  open(method: string, url: string) {
    this.method = method
    this.url = url
  }

  setRequestHeader(name: string, value: string) {
    this.headers.set(name, value)
  }

  send(body: FormData | Blob) {
    this.body = body
  }

  getAllResponseHeaders() {
    return 'content-type: application/json'
  }
}

describe('apiError', () => {
  it('parses stable error response codes', async () => {
    const error = await apiError(
      new Response(JSON.stringify({ code: 'MEMORY_COVER_CROP_INVALID', message: 'Cover crop is invalid' }), {
        status: 400
      })
    )

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('MEMORY_COVER_CROP_INVALID')
    expect(error.message).toBe('Cover crop is invalid')
  })

  it('accepts legacy error response bodies during migration', async () => {
    const error = await apiError(
      new Response(JSON.stringify({ error: 'Too many requests' }), {
        status: 429
      })
    )

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('RATE_LIMITED')
    expect(error.message).toBe('Too many requests')
  })

  it('falls back for non-json error responses', async () => {
    const error = await apiError(new Response('nope', { status: 500 }))

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('INTERNAL_SERVER_ERROR')
    expect(error.message).toBe('Request failed')
  })

  it('maps setup required responses to the setup error type', async () => {
    const error = await apiError(
      new Response(JSON.stringify({ code: 'SETUP_REQUIRED', message: 'Setup required' }), {
        status: 503
      })
    )

    expect(error).toBeInstanceOf(SetupRequiredError)
  })
})

describe('apiFetch', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    localStorage.clear()
  })

  it('refreshes before a protected request when the stored access session is expired', async () => {
    rememberAccessSession({ accessTokenExpiresAt: '2026-05-29T10:00:00.000Z' })
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-29T10:01:00.000Z'))
    const fetch = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        user: userSummary(),
        accessTokenExpiresAt: '2026-05-29T10:16:00.000Z'
      })))
      .mockResolvedValueOnce(new Response(JSON.stringify([{ id: 1 }])))
    vi.stubGlobal('fetch', fetch)

    const response = await apiFetch('/api/tenants')

    expect(response.status).toBe(200)
    expect(fetch).toHaveBeenNthCalledWith(1, '/api/auth/refresh', expect.objectContaining({ method: 'POST' }))
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/tenants', expect.any(Object))
    expect(localStorage.getItem('veyq.accessTokenExpiresAt')).toBe('2026-05-29T10:16:00.000Z')
    vi.useRealTimers()
  })

  it('deduplicates concurrent refreshes for expired access sessions', async () => {
    rememberAccessSession({ accessTokenExpiresAt: '2026-05-29T10:00:00.000Z' })
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-29T10:01:00.000Z'))
    let resolveRefresh!: (response: Response) => void
    const refresh = new Promise<Response>((resolve) => {
      resolveRefresh = resolve
    })
    const fetch = vi.fn((url: string) => {
      if (url === '/api/auth/refresh') return refresh
      return Promise.resolve(new Response(JSON.stringify({ ok: true })))
    })
    vi.stubGlobal('fetch', fetch)

    const first = apiFetch('/api/tenants')
    const second = apiFetch('/api/users/me')
    await Promise.resolve()
    resolveRefresh(new Response(JSON.stringify({
      user: userSummary(),
      accessTokenExpiresAt: '2026-05-29T10:16:00.000Z'
    })))

    await Promise.all([first, second])

    expect(fetch.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1)
    expect(fetch).toHaveBeenCalledWith('/api/tenants', expect.any(Object))
    expect(fetch).toHaveBeenCalledWith('/api/users/me', expect.any(Object))
    vi.useRealTimers()
  })

  it('falls back to the original request and clears the marker when refresh fails', async () => {
    rememberAccessSession({ accessTokenExpiresAt: '2026-05-29T10:00:00.000Z' })
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-05-29T10:01:00.000Z'))
    const fetch = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'Authentication required' }), {
        status: 401
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: 'Authentication required' }), {
        status: 401
      }))
    vi.stubGlobal('fetch', fetch)

    const response = await apiFetch('/api/tenants')

    expect(response.status).toBe(401)
    expect(fetch).toHaveBeenNthCalledWith(1, '/api/auth/refresh', expect.objectContaining({ method: 'POST' }))
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/tenants', expect.any(Object))
    expect(localStorage.getItem('veyq.accessTokenExpiresAt')).toBeNull()
    vi.useRealTimers()
  })
})

describe('uploadFormDataJson', () => {
  beforeEach(() => {
    MockXMLHttpRequest.instances = []
    vi.stubGlobal('XMLHttpRequest', MockXMLHttpRequest)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts multipart form data with cookies and returns parsed JSON', async () => {
    const body = new FormData()
    const file = new File(['image'], 'photo.jpg', { type: 'image/jpeg' })
    body.append('file', file)

    const request = uploadFormDataJson<{ id: string }>('/api/tenant/memories/mem-1/media', body)
    const xhr = MockXMLHttpRequest.instances[0]
    xhr.responseText = JSON.stringify({ id: 'media-1' })
    xhr.onload?.()

    await expect(request).resolves.toEqual({ id: 'media-1' })
    expect(xhr.method).toBe('POST')
    expect(xhr.url).toBe('/api/tenant/memories/mem-1/media')
    expect(xhr.withCredentials).toBe(true)
    expect(xhr.body).toBe(body)
  })

  it('maps JSON error responses through ApiError', async () => {
    const request = uploadFormDataJson('/api/tenant/memories/mem-1/media', new FormData())
    const xhr = MockXMLHttpRequest.instances[0]
    xhr.status = 413
    xhr.statusText = 'Payload Too Large'
    xhr.responseText = JSON.stringify({ code: 'UPLOAD_TOO_LARGE', message: 'Too large' })
    xhr.onload?.()

    await expect(request).rejects.toMatchObject({
      name: 'ApiError',
      status: 413,
      code: 'UPLOAD_TOO_LARGE',
      message: 'Too large'
    })
  })

  it('emits upload progress callbacks', async () => {
    const onProgress = vi.fn()
    const request = uploadFormDataJson('/api/tenant/memories/mem-1/media', new FormData(), onProgress)
    const xhr = MockXMLHttpRequest.instances[0]

    xhr.upload.onprogress?.({ loaded: 25, total: 100, lengthComputable: true } as ProgressEvent)
    xhr.responseText = JSON.stringify({ id: 'media-1' })
    xhr.onload?.()

    await request
    expect(onProgress).toHaveBeenCalledWith({ loaded: 25, total: 100 })
  })

  it('aborts uploads through AbortSignal with a cancellation error', async () => {
    const controller = new AbortController()
    const removeListener = vi.spyOn(controller.signal, 'removeEventListener')
    const request = uploadFormDataJson('/api/tenant/memories/mem-1/media', new FormData(), undefined, controller.signal)
    const xhr = MockXMLHttpRequest.instances[0]

    controller.abort()

    expect(xhr.abort).toHaveBeenCalled()
    await expect(request).rejects.toMatchObject({ name: 'UploadCancelledError' })
    expect(removeListener).toHaveBeenCalledWith('abort', expect.any(Function))
  })

  it('keeps cancellation when an upload is aborted during auth refresh', async () => {
    const controller = new AbortController()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))))
    const request = uploadBlob(
      '/api/tenant/memories/mem-1/media/uploads/upl-1/chunks/0',
      new Blob(['chunk']),
      'application/octet-stream',
      undefined,
      controller.signal
    )
    const xhr = MockXMLHttpRequest.instances[0]
    xhr.status = 401
    xhr.responseText = JSON.stringify({ code: 'UNAUTHORIZED', message: 'Unauthorized' })
    xhr.onload?.()

    controller.abort()

    await expect(request).rejects.toBeInstanceOf(UploadCancelledError)
    expect(MockXMLHttpRequest.instances).toHaveLength(1)
  })

  it('uploads binary chunks with content type, cookies and progress', async () => {
    const onProgress = vi.fn()
    const chunk = new Blob(['chunk'], { type: 'application/octet-stream' })
    const request = uploadBlob(
      '/api/tenant/memories/mem-1/media/uploads/upl-1/chunks/0',
      chunk,
      'application/octet-stream',
      onProgress
    )
    const xhr = MockXMLHttpRequest.instances[0]

    xhr.upload.onprogress?.({ loaded: 5, total: 5, lengthComputable: true } as ProgressEvent)
    xhr.status = 204
    xhr.onload?.()

    await request
    expect(xhr.method).toBe('PUT')
    expect(xhr.withCredentials).toBe(true)
    expect(xhr.body).toBe(chunk)
    expect(xhr.headers.get('Content-Type')).toBe('application/octet-stream')
    expect(onProgress).toHaveBeenCalledWith({ loaded: 5, total: 5 })
  })
})

function userSummary() {
  return {
    userId: 1,
    email: 'kevin@example.test',
    displayName: 'Kevin',
    permalink: 'kevin',
    serverRole: 'ServerOwner',
    passkeyCount: 1,
    hasPassword: true
  }
}
