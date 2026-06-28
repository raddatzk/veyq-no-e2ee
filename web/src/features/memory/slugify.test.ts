import { describe, it, expect } from 'vitest'
import { slugify } from './tree'

describe('slugify', () => {
  it('produces readable URL-safe slugs', () => {
    expect(slugify('Reisen 2026')).toBe('reisen-2026')
    expect(slugify('Rom!')).toBe('rom')
    expect(slugify('  spaced  out  ')).toBe('spaced-out')
  })

  it('folds German + strips diacritics so Latin names stay legible', () => {
    expect(slugify('Müller')).toBe('mueller')
    expect(slugify('Straße')).toBe('strasse')
    expect(slugify('Café')).toBe('cafe')
    expect(slugify('naïve')).toBe('naive')
  })

  it('keeps slugs to [a-z0-9-] — reserved URL chars never survive', () => {
    expect(slugify('A/B')).toBe('a-b')
    expect(slugify('a#b?c')).toBe('a-b-c')
    expect(slugify('--edge--')).toBe('edge')
  })

  it('is deterministic and non-empty even for names that reduce to nothing', () => {
    const a = slugify('🎉')
    expect(a).toMatch(/^n[a-z0-9]+$/)
    expect(slugify('🎉')).toBe(a) // same name → same slug (→ same blinded token)
    expect(slugify('日本')).not.toBe(slugify('中国')) // distinct names stay distinct
  })
})
