import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MediaLightbox from './MediaLightbox.vue'
import type { MemoryMedia } from '../../features/memories/api'

describe('MediaLightbox', () => {
  const originalFullscreenEnabled = Object.getOwnPropertyDescriptor(document, 'fullscreenEnabled')
  const originalFullscreenElement = Object.getOwnPropertyDescriptor(document, 'fullscreenElement')
  const originalDevicePixelRatio = Object.getOwnPropertyDescriptor(window, 'devicePixelRatio')
  const originalInnerWidth = Object.getOwnPropertyDescriptor(window, 'innerWidth')
  const originalInnerHeight = Object.getOwnPropertyDescriptor(window, 'innerHeight')
  const requestFullscreen = vi.fn()
  const fetchMock = vi.fn()

  beforeEach(() => {
    requestFullscreen.mockResolvedValue(undefined)
    fetchMock.mockResolvedValue(new Response('', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    setViewport(1200, 800, 1)
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
    if (originalDevicePixelRatio) Object.defineProperty(window, 'devicePixelRatio', originalDevicePixelRatio)
    if (originalInnerWidth) Object.defineProperty(window, 'innerWidth', originalInnerWidth)
    if (originalInnerHeight) Object.defineProperty(window, 'innerHeight', originalInnerHeight)
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows the preview by default and uses it fullscreen when it covers the capped DPR target', async () => {
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
    expect(wrapper.find('.media-original-fullscreen img').attributes('src')).toBe('/api/tenant/media/media-1/preview')
    expect(wrapper.find('.media-lightbox-stage img').attributes('src')).toBe('/api/tenant/media/media-1/preview')
  })

  it('hides highlighting and shows a shortcut hint when highlighting is not allowed', async () => {
    vi.useFakeTimers()
    const updateHighlight = vi.fn()
    const wrapper = mount(MediaLightbox, {
      props: {
        tenantName: 'tenant',
        media: media(),
        canShowPrevious: false,
        canShowNext: false,
        canHighlight: false,
        updateHighlight
      },
      global: {
        stubs: {
          RouterLink: true
        }
      }
    })

    expect(wrapper.find('.lightbox-star-button').exists()).toBe(false)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f' }))
    await flushPromises()

    expect(updateHighlight).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Du kannst nur eigene Medien als Highlight markieren.')

    vi.advanceTimersByTime(3000)
    await flushPromises()

    expect(wrapper.text()).not.toContain('Du kannst nur eigene Medien als Highlight markieren.')
    vi.useRealTimers()
  })

  it('uses the original fullscreen when the preview is too small for DPR capped at 2', async () => {
    setViewport(1600, 1000, 3)
    const wrapper = mount(MediaLightbox, {
      props: {
        tenantName: 'tenant',
        media: media({ imageWidth: 5000, imageHeight: 3200 }),
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

    await wrapper.get('button[aria-label="Original im Vollbild anzeigen"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.media-original-fullscreen img').attributes('src')).toBe('/api/tenant/media/media-1/content')
  })

  it('keeps fullscreen open and emits navigation from fullscreen controls', async () => {
    setViewport(1600, 1000, 2)
    const wrapper = mount(MediaLightbox, {
      props: {
        tenantName: 'tenant',
        media: media({ imageWidth: 5000, imageHeight: 3200 }),
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
    await wrapper.setProps({ media: media({ id: 'media-2', contentUrl: '/api/tenant/media/media-2/content', imageWidth: 5000, imageHeight: 3200 }) })
    await flushPromises()

    expect(wrapper.emitted('next')).toHaveLength(1)
    expect(wrapper.find('.media-original-fullscreen').exists()).toBe(true)
    expect(wrapper.find('.media-original-fullscreen img').attributes('src')).toBe('/api/tenant/media/media-2/content')
  })

  it('emits navigation and close from keyboard shortcuts', async () => {
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

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()

    expect(wrapper.emitted('next')).toHaveLength(1)
    expect(wrapper.emitted('previous')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('does not emit unavailable keyboard navigation', async () => {
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

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    await flushPromises()

    expect(wrapper.emitted('next')).toBeUndefined()
    expect(wrapper.emitted('previous')).toBeUndefined()
  })

  it('preloads current previous two and next five media with adaptive image and video poster sources', async () => {
    setViewport(1600, 1000, 2)
    const mediaItems = Array.from({ length: 10 }, (_, index) =>
      media({
        id: `media-${index}`,
        contentUrl: `/api/tenant/media/media-${index}/content`,
        previewUrl: `/api/tenant/media/media-${index}/preview`,
        thumbnailUrl: `/api/tenant/media/media-${index}/thumbnail`,
        imageWidth: 5000,
        imageHeight: 3200
      })
    )
    mediaItems[4] = media({
      id: 'media-4',
      mimeType: 'video/mp4',
      contentUrl: '/api/tenant/media/media-4/content',
      thumbnailUrl: '/api/tenant/media/media-4/thumbnail',
      previewUrl: '/api/tenant/media/media-4/preview',
      posterUrl: '/api/tenant/media/media-4/poster',
      imageWidth: null,
      imageHeight: null
    })

    mount(MediaLightbox, {
      props: {
        tenantName: 'tenant',
        media: mediaItems[3],
        mediaItems,
        selectedIndex: 3,
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

    const urls = fetchMock.mock.calls.map((call) => call[0])
    expect(urls).toEqual(
      expect.arrayContaining([
        '/api/tenant/media/media-1/content',
        '/api/tenant/media/media-2/content',
        '/api/tenant/media/media-3/content',
        '/api/tenant/media/media-4/poster',
        '/api/tenant/media/media-5/content',
        '/api/tenant/media/media-6/content',
        '/api/tenant/media/media-7/content',
        '/api/tenant/media/media-8/content'
      ])
    )
    expect(urls).not.toContain('/api/tenant/media/media-0/content')
    expect(urls).not.toContain('/api/tenant/media/media-4/content')
    expect(urls).not.toContain('/api/tenant/media/media-9/content')
  })

  it('aborts pending preloads when the lightbox unmounts', async () => {
    const signals: AbortSignal[] = []
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.signal) signals.push(init.signal)
      return new Promise(() => undefined)
    })

    const wrapper = mount(MediaLightbox, {
      props: {
        tenantName: 'tenant',
        media: media({ imageWidth: 5000, imageHeight: 3200 }),
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

    wrapper.unmount()

    expect(signals.length).toBeGreaterThan(0)
    expect(signals.every((signal) => signal.aborted)).toBe(true)
  })
})

function setViewport(width: number, height: number, dpr: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: height })
  Object.defineProperty(window, 'devicePixelRatio', { configurable: true, value: dpr })
}

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
