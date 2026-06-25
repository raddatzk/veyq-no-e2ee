import { describe, expect, it } from 'vitest'
import {
  mediaContentOrPreviewUrl,
  mediaContentUrl,
  mediaImageDisplayUrl,
  mediaPosterUrl,
  mediaPreviewOnlyUrl,
  mediaPreviewUrl,
  mediaThumbnailUrl,
  type MediaUrlSource
} from './mediaUrls'

const media = (overrides: Partial<MediaUrlSource> = {}): MediaUrlSource => ({
  contentUrl: '/api/tenant/memory-media/media-1/content',
  thumbnailUrl: null,
  previewUrl: null,
  posterUrl: null,
  mimeType: 'image/jpeg',
  ...overrides
})

describe('media URL helpers', () => {
  it('prefers thumbnails before previews and originals for gallery previews', () => {
    expect(mediaPreviewUrl(media({
      thumbnailUrl: '/api/tenant/memory-media/media-1/thumbnail',
      previewUrl: '/api/tenant/memory-media/media-1/preview'
    }))).toBe('/api/tenant/memory-media/media-1/thumbnail')
    expect(mediaPreviewUrl(media({ previewUrl: '/api/tenant/memory-media/media-1/preview' })))
      .toBe('/api/tenant/memory-media/media-1/preview')
    expect(mediaPreviewUrl(media())).toBe('/api/tenant/memory-media/media-1/content')
  })

  it('keeps content, preview-only, poster and image display variants explicit', () => {
    const item = media({
      previewUrl: '/api/tenant/memory-media/media-1/preview',
      posterUrl: '/api/tenant/memory-media/media-1/poster'
    })

    expect(mediaContentUrl(item)).toBe('/api/tenant/memory-media/media-1/content')
    expect(mediaContentOrPreviewUrl(item)).toBe('/api/tenant/memory-media/media-1/content')
    expect(mediaPreviewOnlyUrl(item)).toBe('/api/tenant/memory-media/media-1/preview')
    expect(mediaThumbnailUrl({ ...item, thumbnailUrl: '/api/tenant/memory-media/media-1/thumbnail' }))
      .toBe('/api/tenant/memory-media/media-1/thumbnail')
    expect(mediaPosterUrl(item)).toBe('/api/tenant/memory-media/media-1/poster')
    expect(mediaImageDisplayUrl(item)).toBe('/api/tenant/memory-media/media-1/preview')
    expect(mediaImageDisplayUrl({ ...item, mimeType: 'video/mp4' })).toBe('/api/tenant/memory-media/media-1/content')
  })
})
