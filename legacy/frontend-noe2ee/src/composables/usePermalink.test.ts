import { describe, expect, it } from 'vitest'
import { toPermalink } from './usePermalink'

describe('toPermalink', () => {
  it('normalizes text for path-safe names', () => {
    expect(toPermalink('  Schöne Grüße 2026! ')).toBe('schoene-gruesse-2026')
    expect(toPermalink('Crème brûlée')).toBe('creme-brulee')
    expect(toPermalink('---Already---Slug---')).toBe('already-slug')
  })

  it('limits permalink length', () => {
    expect(toPermalink('a'.repeat(200))).toHaveLength(120)
  })
})
