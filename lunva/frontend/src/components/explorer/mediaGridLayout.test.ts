import { describe, expect, it } from 'vitest'
import { calculateJustifiedMediaLayout } from './mediaGridLayout'

const desktop = {
  containerWidth: 1000,
  targetHeight: 260,
  gap: 8
}

describe('media grid layout', () => {
  it('fills a completed row exactly', () => {
    const layout = calculateJustifiedMediaLayout({
      items: ['a', 'b', 'c', 'd'].map((id) => ({ id, aspectRatio: 1 })),
      ...desktop
    })

    expect(layout.tiles).toHaveLength(4)
    expect(layout.tiles.every((tile) => !tile.widow)).toBe(true)
    expect(layout.tiles[0].renderedHeight).toBeCloseTo((1000 - 24) / 4)
    expect(layout.tiles.at(-1)?.left).toBeCloseTo(756)
    expect(layout.tiles.at(-1)?.renderedWidth).toBeCloseTo(244)
    expect(layout.height).toBeCloseTo((1000 - 24) / 4)
  })

  it('uses greedy row breaks like the justifiedgrid example', () => {
    const layout = calculateJustifiedMediaLayout({
      items: Array.from({ length: 10 }, (_, index) => ({
        id: String(index),
        aspectRatio: 1
      })),
      ...desktop
    })

    expect(layout.tiles.filter((tile) => tile.top === 0)).toHaveLength(4)
    expect(layout.tiles.filter((tile) => tile.top > 0 && !tile.widow)).toHaveLength(4)
    expect(layout.tiles.filter((tile) => tile.widow)).toHaveLength(2)
  })

  it('lays out the final incomplete row at target height without justification', () => {
    const layout = calculateJustifiedMediaLayout({
      items: [
        { id: 'a', aspectRatio: 1 },
        { id: 'b', aspectRatio: 1 }
      ],
      ...desktop
    })

    expect(layout.tiles).toHaveLength(2)
    expect(layout.tiles.every((tile) => tile.widow)).toBe(true)
    expect(layout.tiles[0].renderedHeight).toBe(260)
    expect(layout.tiles[0].renderedWidth).toBe(260)
    expect(layout.tiles[1].left).toBe(268)
    expect(layout.height).toBe(260)
  })

  it('can suppress widows while media is still streaming', () => {
    const layout = calculateJustifiedMediaLayout({
      items: [
        { id: 'a', aspectRatio: 1 },
        { id: 'b', aspectRatio: 1 }
      ],
      showWidows: false,
      ...desktop
    })

    expect(layout.tiles).toHaveLength(0)
    expect(layout.height).toBe(0)
  })

  it('uses fallback aspect ratios deterministically before dimensions are known', () => {
    const layout = calculateJustifiedMediaLayout({
      items: [{ id: 'a' }, { id: 'b' }],
      containerWidth: 804,
      targetHeight: 260,
      gap: 8
    })

    expect(layout.tiles).toHaveLength(2)
    expect(layout.tiles[0].renderedWidth).toBeCloseTo((4 / 3) * 260)
    expect(layout.tiles[1].renderedWidth).toBeCloseTo((4 / 3) * 260)
    expect(layout.height).toBe(260)
  })

  it('returns an empty layout without items or width', () => {
    expect(
      calculateJustifiedMediaLayout({
        items: [{ id: 'a', aspectRatio: 1 }],
        containerWidth: 0,
        targetHeight: 260,
        gap: 8
      })
    ).toEqual({ tiles: [], height: 0 })
  })
})
