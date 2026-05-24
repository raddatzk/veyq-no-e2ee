import { describe, expect, it } from 'vitest'
import { ApiError, SetupRequiredError, apiError } from './httpClient'

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

