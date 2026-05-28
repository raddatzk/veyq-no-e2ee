import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import MediaGrid from './MediaGrid.vue'
import type { MemoryMedia } from '../../features/memories/api'

class ResizeObserverStub {
  constructor(private readonly callback: ResizeObserverCallback) {}

  observe(target: Element) {
    this.callback([{ contentRect: { width: 640 } } as ResizeObserverEntry], this as unknown as ResizeObserver)
    Object.defineProperty(target, 'clientWidth', { value: 640, configurable: true })
  }

  disconnect() {}
}

describe('MediaGrid', () => {
  const originalResizeObserver = globalThis.ResizeObserver

  beforeEach(() => {
    globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver
  })

  afterEach(() => {
    globalThis.ResizeObserver = originalResizeObserver
  })

  it('renders thumbnails in the grid instead of originals', async () => {
    const wrapper = mount(MediaGrid, {
      props: {
        tenantName: 'tenant',
        media: [media()],
        streaming: false
      },
      global: {
        stubs: {
          Button: true,
          Dialog: true,
          MediaLightbox: true,
          ProgressSpinner: true
        }
      }
    })

    await flushPromises()

    const image = wrapper.find('.media-grid-tile img')
    expect(image.attributes('src')).toBe('/api/tenant/media/media-1/thumbnail')
    expect(image.attributes('src')).not.toBe('/api/tenant/media/media-1/content')
  })
})

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'Photo',
    uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
    createdAt: '2026-05-24T10:00:00Z',
    capturedAt: null,
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/media/media-1/content',
    thumbnailUrl: '/api/tenant/media/media-1/thumbnail',
    previewUrl: '/api/tenant/media/media-1/preview',
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: 1200,
    imageHeight: 800,
    gpsLatitude: null,
    gpsLongitude: null,
    ...overrides
  }
}
