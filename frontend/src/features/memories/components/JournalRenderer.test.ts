import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { MemoryMedia } from '../api'
import JournalRenderer from './JournalRenderer.vue'

describe('JournalRenderer', () => {
  it('uses decoded plain text for media alt labels', () => {
    const wrapper = mount(JournalRenderer, {
      props: {
        markdown: '::media:media-1 Mom & Dad <em>at the beach</em> &amp; sunset',
        media: [media()],
        tracks: [],
        playlists: [],
        childMemories: []
      },
      global: {
        stubs: {
          MediaLightbox: true,
          TrackMap: true
        }
      }
    })

    expect(wrapper.find('img').attributes('alt')).toBe('Mom & Dad at the beach & sunset')
  })
})

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'First upload',
    uploader: { type: 'USER', displayName: 'Kevin' },
    createdAt: '2026-05-24T16:30:00Z',
    updatedAt: '2026-05-24T16:30:00Z',
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/media/media-1/content',
    thumbnailUrl: '/api/tenant/media/media-1/thumbnail',
    previewUrl: '/api/tenant/media/media-1/preview',
    posterUrl: null,
    capturedAt: null,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: null,
    imageHeight: null,
    gpsLatitude: null,
    gpsLongitude: null,
    highlight: false,
    ...overrides
  }
}
