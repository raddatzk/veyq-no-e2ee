import { describe, expect, it } from 'vitest'
import { isoDateToLocalDate, localDateToIsoDate } from './isoDate'

describe('ISO date helpers', () => {
  it('round-trips ISO dates through local Date instances without timezone shifts', () => {
    const date = isoDateToLocalDate('2026-05-08')

    expect(date).toBeInstanceOf(Date)
    expect(date?.getFullYear()).toBe(2026)
    expect(date?.getMonth()).toBe(4)
    expect(date?.getDate()).toBe(8)
    expect(localDateToIsoDate(date)).toBe('2026-05-08')
  })

  it('keeps empty or invalid values empty', () => {
    expect(isoDateToLocalDate('')).toBeNull()
    expect(isoDateToLocalDate('2026-02-31')).toBeNull()
    expect(localDateToIsoDate(null)).toBe('')
  })
})
