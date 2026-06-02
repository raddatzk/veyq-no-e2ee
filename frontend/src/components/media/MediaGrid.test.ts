import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import MediaGrid from './MediaGrid.vue'
import type { MemoryMedia, MemoryMediaBucket } from '../../features/memories/api'

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

  it('disables lightbox highlighting when a media bucket is missing from the provided bucket list', async () => {
    const wrapper = mount(MediaGrid, {
      props: {
        tenantName: 'tenant',
        media: [media({ bucketId: 'missing-bucket' })],
        mediaBuckets: [bucket({ id: 'known-bucket', canManage: true })],
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
    await wrapper.find('.media-grid-tile').trigger('click')

    expect(wrapper.findComponent({ name: 'MediaLightbox' }).props('canHighlight')).toBe(false)
  })

  it('keeps the opened lightbox media stable when new media is inserted before it', async () => {
    const wrapper = mount(MediaGrid, {
      props: {
        tenantName: 'tenant',
        media: [
          media({ id: 'media-current', title: 'Current' }),
          media({ id: 'media-next', title: 'Next' })
        ],
        streaming: false
      },
      global: {
        stubs: {
          Button: true,
          Dialog: true,
          MediaLightbox: {
            props: ['media', 'selectedIndex'],
            template: '<div data-testid="lightbox">{{ media.id }}:{{ selectedIndex }}</div>'
          },
          ProgressSpinner: true
        }
      }
    })

    await flushPromises()
    await wrapper.find('.media-grid-tile').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="lightbox"]').text()).toBe('media-current:0')

    await wrapper.setProps({
      media: [
        media({ id: 'media-before', title: 'Before' }),
        media({ id: 'media-current', title: 'Current' }),
        media({ id: 'media-next', title: 'Next' })
      ]
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="lightbox"]').text()).toBe('media-current:1')
  })

  it('removes a deleted highlighted media from the highlighted filter view', async () => {
    const wrapper = mount(MediaGrid, {
      props: {
        tenantName: 'tenant',
        media: [
          media({ id: 'media-highlight', title: 'Highlight', highlight: true }),
          media({ id: 'media-regular', title: 'Regular', highlight: false })
        ],
        streaming: false
      },
      global: {
        stubs: {
          Button: true,
          Dialog: true,
          MediaLightbox: true,
          ProgressSpinner: true,
          SelectButton: {
            props: ['modelValue'],
            emits: ['update:modelValue'],
            template: '<button type="button" @click="$emit(\'update:modelValue\', \'highlighted\')">Highlights</button>'
          }
        }
      }
    })

    await flushPromises()
    await wrapper.find('.media-grid-filter-toggle button').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(1)

    await wrapper.setProps({
      media: [media({ id: 'media-regular', title: 'Regular', highlight: false })]
    })
    await flushPromises()

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(0)
  })
})

function media(overrides: Partial<MemoryMedia> = {}): MemoryMedia {
  return {
    id: 'media-1',
    memoryId: 'mem-1',
    title: 'Photo',
    uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    capturedAt: null,
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 12,
    contentUrl: '/api/tenant/media/media-1/content',
    thumbnailUrl: '/api/tenant/media/media-1/thumbnail',
    previewUrl: '/api/tenant/media/media-1/preview',
    posterUrl: null,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: 1200,
    imageHeight: 800,
    gpsLatitude: null,
    gpsLongitude: null,
    highlight: false,
    ...overrides
  }
}

function bucket(overrides: Partial<MemoryMediaBucket> = {}): MemoryMediaBucket {
  return {
    id: 'bucket-1',
    memoryId: 'mem-1',
    parentBucketId: undefined,
    displayName: 'Bucket',
    primary: false,
    uploader: { type: 'USER', displayName: 'Kevin', userId: 1 },
    canManage: true,
    mediaCount: 1,
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}
