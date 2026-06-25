import { describe, expect, it } from 'vitest'
import { calculateJustifiedLayout, galleryDisplayAspectRatio } from './justifiedLayout'

const items = (ars: number[]) => ars.map((ar, i) => ({ id: `t${i}`, aspectRatio: ar }))

describe('justified gallery layout', () => {
  it('justifies every full row flush to the container width', () => {
    // many 1:1 tiles, container 1000, target 200 → target AR = 5, so rows break at 5 squares
    const { tiles } = calculateJustifiedLayout({ items: items(Array(10).fill(1)), containerWidth: 1000, targetHeight: 200, gap: 10 })
    // first full row: 5 tiles; its right edge (last.left + last.width) must equal the container width
    const firstRow = tiles.filter((t) => t.top === 0)
    expect(firstRow.length).toBe(5)
    const last = firstRow[firstRow.length - 1]
    expect(Math.round(last.left + last.renderedWidth)).toBe(1000)
    // none in a justified row is a widow, and all share one height
    expect(firstRow.every((t) => !t.widow)).toBe(true)
    expect(new Set(firstRow.map((t) => Math.round(t.renderedHeight))).size).toBe(1)
  })

  it('keeps the trailing incomplete row as a widow at target height', () => {
    // 6 squares, width 1000, target 200 → 5 fill row one, 1 widow on row two
    const { tiles } = calculateJustifiedLayout({ items: items(Array(6).fill(1)), containerWidth: 1000, targetHeight: 200, gap: 10 })
    const widows = tiles.filter((t) => t.widow)
    expect(widows.length).toBe(1)
    expect(widows[0].renderedHeight).toBe(200)
    expect(widows[0].renderedWidth).toBeCloseTo(200, 0) // 1:1 at target height
  })

  it('preserves per-tile aspect ratio within a justified row (wide tiles get more width)', () => {
    const { tiles } = calculateJustifiedLayout({ items: items([2, 1, 1]), containerWidth: 800, targetHeight: 200, gap: 0 })
    const row = tiles.filter((t) => t.top === 0)
    expect(row).toHaveLength(3)
    // the 2:1 tile is twice as wide as each 1:1 tile (same row height)
    expect(row[0].renderedWidth).toBeCloseTo(row[1].renderedWidth * 2, 0)
  })

  it('returns empty for no items or zero container', () => {
    expect(calculateJustifiedLayout({ items: [], containerWidth: 1000, targetHeight: 200, gap: 8 }).tiles).toEqual([])
    expect(calculateJustifiedLayout({ items: items([1]), containerWidth: 0, targetHeight: 200, gap: 8 }).tiles).toEqual([])
  })

  it('clamps display aspect ratio into [3/4, 4/3] with a 4/3 fallback', () => {
    expect(galleryDisplayAspectRatio(4000, 1000)).toBe(4 / 3) // ultra-wide clamped
    expect(galleryDisplayAspectRatio(1000, 4000)).toBe(3 / 4) // ultra-tall clamped
    expect(galleryDisplayAspectRatio(0, 0)).toBe(4 / 3) // invalid → fallback
    expect(galleryDisplayAspectRatio(1000, 1000)).toBe(1) // square passes through
  })
})
