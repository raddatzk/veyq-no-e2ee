import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MediaLightbox from './MediaLightbox.vue'
import type { MemoryMedia } from '../../features/memories/api'

describe('MediaLightbox', () => {
  const originalFullscreenEnabled = Object.getOwnPropertyDescriptor(document, 'fullscreenEnabled')
  const originalFullscreenElement = Object.getOwnPropertyDescriptor(document, 'fullscreenElement')
  const requestFullscreen = vi.fn()

  beforeEach(() => {
    requestFullscreen.mockResolvedValue(undefined)
    Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, value: true })
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: null })
    HTMLElement.prototype.requestFullscreen = requestFullscreen
    Object.defineProperty(document.documentElement, 'requestFullscreen', {
      configurable: true,
      value: requestFullscreen
    })
  })

  afterEach(() => {
    if (originalFullscreenEnabled) Object.defineProperty(document, 'fullscreenEnabled', originalFullscreenEnabled)
    if (originalFullscreenElement) Object.defineProperty(document, 'fullscreenElement', originalFullscreenElement)
    vi.restoreAllMocks()
  })

  it('shows the preview by default and opens the original only for fullscreen', async () => {
    const wrapper = mount(MediaLightbox, {
      props: {
        tenantName: 'tenant',
        media: media(),
        canShowPrevious: false,
        canShowNext: false
      },
      global: {
        stubs: {
          RouterLink: true
        }
      }
    })
    await flushPromises()

    const stageImage = wrapper.find('.media-lightbox-stage img')
    expect(stageImage.attributes('src')).toBe('/api/tenant/media/media-1/preview')
    expect(wrapper.find('a[download]').attributes('href')).toBe('/api/tenant/media/media-1/content')

    await wrapper.get('button[aria-label="Original im Vollbild anzeigen"]').trigger('click')
    await flushPromises()

    expect(requestFullscreen).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.media-original-fullscreen img').attributes('src')).toBe('/api/tenant/media/media-1/content')
    expect(wrapper.find('.media-lightbox-stage img').attributes('src')).toBe('/api/tenant/media/media-1/preview')
  })

  it('keeps fullscreen open and emits navigation from fullscreen controls', async () => {
    const wrapper = mount(MediaLightbox, {
      props: {
        tenantName: 'tenant',
        media: media(),
        canShowPrevious: true,
        canShowNext: true
      },
      global: {
        stubs: {
          RouterLink: true
        }
      }
    })
    await flushPromises()

    await wrapper.get('button[aria-label="Original im Vollbild anzeigen"]').trigger('click')
    await flushPromises()
    await wrapper.get('.media-original-fullscreen .lightbox-nav-next').trigger('click')
    await wrapper.setProps({ media: media({ id: 'media-2', contentUrl: '/api/tenant/media/media-2/content' }) })
    await flushPromises()

    expect(wrapper.emitted('next')).toHaveLength(1)
    expect(wrapper.find('.media-original-fullscreen').exists()).toBe(true)
    expect(wrapper.find('.media-original-fullscreen img').attributes('src')).toBe('/api/tenant/media/media-2/content')
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
