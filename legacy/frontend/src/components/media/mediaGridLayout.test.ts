import { describe, expect, it } from 'vitest'
import { calculateJustifiedMediaLayout, galleryDisplayAspectRatio } from './mediaGridLayout'

describe('galleryDisplayAspectRatio', () => {
  it('bounds extreme aspect ratios for gallery thumbnails', () => {
    expect(galleryDisplayAspectRatio(16, 9)).toBe(4 / 3)
    expect(galleryDisplayAspectRatio(9, 16)).toBe(3 / 4)
  })

  it('keeps normal aspect ratios and falls back when dimensions are missing', () => {
    expect(galleryDisplayAspectRatio(4, 3)).toBe(4 / 3)
    expect(galleryDisplayAspectRatio(3, 4)).toBe(3 / 4)
    expect(galleryDisplayAspectRatio(1, 1)).toBe(1)
    expect(galleryDisplayAspectRatio(null, 9)).toBe(4 / 3)
  })
})

describe('calculateJustifiedMediaLayout', () => {
  it('keeps portrait and landscape media in visibly different tile shapes', () => {
    const layout = calculateJustifiedMediaLayout({
      items: [
        { id: 'portrait', aspectRatio: 3 / 4 },
        { id: 'landscape', aspectRatio: 16 / 9 }
      ],
      containerWidth: 600,
      targetHeight: 200,
      gap: 8,
      showWidows: true
    })

    const portrait = layout.tiles.find((tile) => tile.id === 'portrait')
    const landscape = layout.tiles.find((tile) => tile.id === 'landscape')

    expect(portrait).toBeDefined()
    expect(landscape).toBeDefined()
    expect(portrait!.renderedWidth).toBeLessThan(portrait!.renderedHeight)
    expect(landscape!.renderedWidth).toBeGreaterThan(landscape!.renderedHeight)
  })
})
