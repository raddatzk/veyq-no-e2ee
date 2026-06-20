import { describe, expect, it } from 'vitest'
import { dayKey, formatDateRange, formatDayLabel, groupByDay } from './dateBuckets'

describe('dateBuckets', () => {
  it('groups consecutive items by local day, preserving order', () => {
    const items = [
      { takenAt: '2026-01-01T08:00:00Z', id: 'a' },
      { takenAt: '2026-01-01T20:00:00Z', id: 'b' },
      { takenAt: '2026-03-15T12:00:00Z', id: 'c' },
    ]
    const buckets = groupByDay(items)
    expect(buckets).toHaveLength(2)
    expect(buckets[0].items.map((i) => i.id)).toEqual(['a', 'b'])
    expect(buckets[1].items.map((i) => i.id)).toEqual(['c'])
  })

  it('re-opens a bucket only for consecutive same-day runs', () => {
    const items = [
      { takenAt: '2026-01-01T08:00:00Z', id: 'a' },
      { takenAt: '2026-03-15T12:00:00Z', id: 'b' },
      { takenAt: '2026-01-01T09:00:00Z', id: 'c' },
    ]
    expect(groupByDay(items)).toHaveLength(3)
  })

  it('falls back to a placeholder key for invalid timestamps', () => {
    expect(dayKey('not-a-date')).toBe('—')
    expect(formatDayLabel('—', 'de')).toBe('—')
  })

  it('formats a single date and a range', () => {
    const single = [{ takenAt: '2026-06-13T10:00:00Z' }]
    expect(formatDateRange(single, 'de')).toBe(formatDayLabel(dayKey(single[0].takenAt), 'de'))
    const range = [{ takenAt: '2026-03-15T12:00:00Z' }, { takenAt: '2026-01-01T08:00:00Z' }]
    expect(formatDateRange(range, 'de')).toContain('–')
  })

  it('returns empty range for no items', () => {
    expect(formatDateRange([], 'en')).toBe('')
  })
})
