import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/httpClient'
import { getErrorMessage } from './errors'

describe('getErrorMessage', () => {
  it('localizes known API error codes', () => {
    const error = new ApiError(400, 'MEMORY_COVER_CROP_INVALID', 'Cover crop is invalid', {})

    expect(getErrorMessage(error, 'Fallback')).toBe('Der gewählte Cover-Ausschnitt ist ungültig.')
  })

  it('uses API fallback messages for unknown API error codes', () => {
    const error = new ApiError(400, 'UNKNOWN_CODE', 'Unknown fallback', {})

    expect(getErrorMessage(error, 'Fallback')).toBe('Unknown fallback')
  })

  it('keeps regular error messages unchanged', () => {
    expect(getErrorMessage(new Error('Plain error'), 'Fallback')).toBe('Plain error')
  })
})

