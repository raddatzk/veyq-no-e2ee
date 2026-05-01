import { describe, expect, it } from 'vitest'
import { buildJustifiedMediaRows, targetRowHeightTolerance } from './mediaGridLayout'

const desktop = {
  targetHeight: 210,
  minHeight: 170,
  maxHeight: 210,
  gap: 4
}

describe('media grid layout', () => {
  it('fills a single row exactly when a justified solution is valid', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a', 'b', 'c', 'd', 'e'],
      aspectRatios: { a: 1, b: 1, c: 1, d: 1, e: 1 },
      containerWidth: 1000,
      ...desktop
    })

    expect(rows).toHaveLength(1)
    expect(rows[0].align).toBe('justify')
    expect(rows[0].height).toBeCloseTo((1000 - 16) / 5)
    expect(rows[0].width).toBeCloseTo(1000)
  })

  it('chooses global row breaks instead of greedy first-row filling', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'],
      aspectRatios: {
        a: 1,
        b: 1,
        c: 1,
        d: 1,
        e: 1,
        f: 1,
        g: 1,
        h: 1,
        i: 1,
        j: 1
      },
      containerWidth: 1000,
      ...desktop
    })

    expect(rows).toHaveLength(2)
    expect(rows[0].items).toHaveLength(5)
    expect(rows[1].items).toHaveLength(5)
    rows.forEach((row) => {
      expect(row.align).toBe('justify')
      expect(row.height).toBeCloseTo((1000 - 16) / 5)
      expect(row.width).toBeCloseTo(1000)
    })
  })

  it('keeps desktop rows inside the 170-210px height range', () => {
    const ids = Array.from({ length: 24 }, (_, index) => String(index))
    const rows = buildJustifiedMediaRows({
      ids,
      aspectRatios: Object.fromEntries(ids.map((id) => [id, 1])),
      containerWidth: 2400,
      ...desktop
    })

    expect(rows.length).toBeGreaterThan(1)
    rows.forEach((row) => {
      expect(row.height).toBeGreaterThanOrEqual(170)
      expect(row.height).toBeLessThanOrEqual(210)
    })
  })

  it('derives the Flickr target row height tolerance from configured bounds', () => {
    expect(targetRowHeightTolerance(desktop)).toBeCloseTo(40 / 210)
  })

  it('prefers the 210px target height for centered rows', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a'],
      aspectRatios: { a: 4 },
      containerWidth: 1000,
      ...desktop
    })

    expect(rows).toHaveLength(1)
    expect(rows[0].align).toBe('center')
    expect(rows[0].height).toBe(210)
    expect(rows[0].width).toBeCloseTo(840)
  })

  it('centers a row when justification would make it too tall', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a'],
      aspectRatios: { a: 0.5 },
      containerWidth: 1000,
      ...desktop
    })

    expect(rows).toHaveLength(1)
    expect(rows[0].align).toBe('center')
    expect(rows[0].height).toBe(210)
    expect(rows[0].width).toBeCloseTo(105)
  })

  it('isolates very wide items instead of forcing a flat multi-item row', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['wide', 'a', 'b', 'c', 'd'],
      aspectRatios: { wide: 5, a: 1, b: 1, c: 1, d: 1 },
      containerWidth: 1000,
      ...desktop
    })

    expect(rows[0].items.map((item) => item.id)).toEqual(['wide'])
    expect(rows[0].height).toBeGreaterThanOrEqual(170)
    expect(rows[0].height).toBeLessThanOrEqual(210)
    rows.forEach((row) => {
      expect(row.height).toBeGreaterThanOrEqual(170)
    })
  })

  it('keeps item order when wide rows are split', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a', 'b', 'c'],
      aspectRatios: { a: 4, b: 4, c: 4 },
      containerWidth: 1000,
      ...desktop
    })

    expect(rows).toHaveLength(3)
    expect(rows.map((row) => row.align)).toEqual(['justify', 'justify', 'center'])
    expect(rows[1].items.map((item) => item.id)).toEqual(['b'])
    expect(rows.flatMap((row) => row.items.map((item) => item.id))).toEqual(['a', 'b', 'c'])
  })

  it('uses fallback aspect ratios deterministically before media dimensions are known', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a', 'b'],
      aspectRatios: {},
      containerWidth: 804,
      ...desktop
    })

    expect(rows).toHaveLength(1)
    expect(rows[0].align).toBe('center')
    expect(rows[0].height).toBe(210)
    expect(rows[0].width).toBeCloseTo(2 * (4 / 3) * 210 + 4)
  })
})
