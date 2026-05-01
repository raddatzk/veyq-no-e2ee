import { describe, expect, it } from 'vitest'
import { buildJustifiedMediaRows } from './mediaGridLayout'

describe('media grid layout', () => {
  it('subtracts fixed gaps and fills completed rows', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a', 'b', 'c', 'd', 'e'],
      aspectRatios: { a: 1, b: 1, c: 1, d: 1, e: 1 },
      containerWidth: 1000,
      targetHeight: 200,
      gap: 4
    })

    expect(rows).toHaveLength(1)
    expect(rows[0].height).toBeCloseTo((1000 - 16) / 5)
    expect(rows[0].width).toBeCloseTo(1000)
  })

  it('justifies the last row', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a', 'b'],
      aspectRatios: { a: 2, b: 2 },
      containerWidth: 1000,
      targetHeight: 200,
      gap: 4
    })

    expect(rows).toHaveLength(1)
    expect(rows[0].height).toBeCloseTo((1000 - 4) / 4)
    expect(rows[0].width).toBeCloseTo(1000)
  })

  it('uses a fallback aspect ratio before media dimensions are known', () => {
    const rows = buildJustifiedMediaRows({
      ids: ['a', 'b'],
      aspectRatios: {},
      containerWidth: 804,
      targetHeight: 300,
      gap: 4
    })

    expect(rows).toHaveLength(1)
    expect(rows[0].height).toBeCloseTo(300)
    expect(rows[0].width).toBeCloseTo(804)
  })
})
