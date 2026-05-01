import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import ExplorerMediaCard from './ExplorerMediaCard.vue'
import ExplorerMediaGrid from './ExplorerMediaGrid.vue'
import type { ExplorerMedia } from '../../api/explorerClient'

const progressSpinnerStub = { template: '<span class="small-spinner" />' }
const lightboxStub = { template: '<div class="media-lightbox" />' }

let observedWidth = 1000
let animationFrameCallbacks: FrameRequestCallback[] = []

class ResizeObserverMock {
  private readonly callback: ResizeObserverCallback

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback
  }

  observe(target: Element) {
    this.callback(
      [
        {
          target,
          contentRect: { width: observedWidth }
        } as ResizeObserverEntry
      ],
      this as unknown as ResizeObserver
    )
  }

  disconnect() {}
}

function runAnimationFrames() {
  const callbacks = animationFrameCallbacks
  animationFrameCallbacks = []
  callbacks.forEach((callback, index) => callback(index))
}

function createMedia(overrides: Partial<ExplorerMedia> = {}): ExplorerMedia {
  return {
    id: 'media-1',
    title: 'Beach',
    albumPath: '/trip',
    ownerUserId: 1,
    uploaderDisplayName: 'Kevin',
    createdAt: '2026-01-01T00:00:00Z',
    capturedAt: null,
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 1234,
    contentUrl: '/api/media/media-1/content',
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: null,
    imageHeight: null,
    gpsLatitude: null,
    gpsLongitude: null,
    ...overrides
  }
}

function mountGrid(media: ExplorerMedia[], streaming = false) {
  return mount(ExplorerMediaGrid, {
    props: {
      tenantName: 'tenant',
      media,
      streaming
    },
    global: {
      stubs: {
        ProgressSpinner: progressSpinnerStub,
        ExplorerMediaLightbox: lightboxStub
      }
    }
  })
}

describe('ExplorerMediaGrid', () => {
  beforeEach(() => {
    observedWidth = 1000
    animationFrameCallbacks = []
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      animationFrameCallbacks.push(callback)
      return animationFrameCallbacks.length
    })
    vi.stubGlobal('cancelAnimationFrame', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses backend image dimensions before media load events', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'wide', imageWidth: 1600, imageHeight: 900 }),
      createMedia({ id: 'square', imageWidth: 900, imageHeight: 900 }),
      createMedia({ id: 'portrait', imageWidth: 900, imageHeight: 1350 })
    ])

    await nextTick()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(false)
    expect(wrapper.find('.media-grid-row').exists()).toBe(true)
    expect(wrapper.find('.media-grid-row-center').exists()).toBe(true)
    expect(wrapper.find('.media-ratio-probes').exists()).toBe(false)

    const cards = wrapper.findAllComponents(ExplorerMediaCard)
    expect(cards).toHaveLength(3)
    expect(cards[0].props('width')).toBeGreaterThan(cards[1].props('width') as number)
    expect(cards[2].props('width')).toBeLessThan(cards[1].props('width') as number)
  })

  it('sets justified row alignment when the row fills the container', async () => {
    const wrapper = mountGrid(
      Array.from({ length: 5 }, (_, index) =>
        createMedia({
          id: `media-${index}`,
          imageWidth: 1000,
          imageHeight: 1000
        })
      )
    )

    await nextTick()

    expect(wrapper.find('.media-grid-row-justify').exists()).toBe(true)
    expect(wrapper.find('.media-grid-row-center').exists()).toBe(false)
  })

  it('eagerly loads media once a visible row is published', async () => {
    const wrapper = mountGrid(
      Array.from({ length: 5 }, (_, index) =>
        createMedia({
          id: `media-${index}`,
          imageWidth: 1000,
          imageHeight: 1000
        })
      )
    )

    await nextTick()

    expect(wrapper.findAllComponents(ExplorerMediaCard).map((card) => card.props('loading'))).toEqual([
      'eager',
      'eager',
      'eager',
      'eager',
      'eager'
    ])
  })

  it('does not render a fallback justified layout while ratios are unknown', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'a', imageWidth: null, imageHeight: null }),
      createMedia({ id: 'b', imageWidth: null, imageHeight: null })
    ])

    await nextTick()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(true)
    expect(wrapper.find('.media-grid-row').exists()).toBe(false)
    expect(wrapper.find('.media-ratio-probes').exists()).toBe(true)
  })

  it('does not show the streaming loader below an already rendered grid', async () => {
    const wrapper = mountGrid(
      Array.from({ length: 5 }, (_, index) =>
        createMedia({
          id: `media-${index}`,
          imageWidth: 1000,
          imageHeight: 1000
        })
      )
    )

    await nextTick()

    expect(wrapper.find('.media-grid-row').exists()).toBe(true)

    await wrapper.setProps({ streaming: true })
    await nextTick()

    expect(wrapper.find('.media-grid-row').exists()).toBe(true)
    expect(wrapper.find('.explorer-loading').exists()).toBe(false)
  })

  it('waits for the initial media stream before rendering a partial first row', async () => {
    const wrapper = mountGrid(
      [createMedia({ id: 'first', imageWidth: 1000, imageHeight: 1000 })],
      true
    )

    await nextTick()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(true)
    expect(wrapper.find('.media-grid-row').exists()).toBe(false)
    expect(wrapper.text()).toContain('Medien werden geladen...')

    await wrapper.setProps({ streaming: false })
    await nextTick()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(false)
    expect(wrapper.find('.media-grid-row').exists()).toBe(true)
  })

  it('publishes completed rows while keeping the streaming widow row buffered', async () => {
    const wrapper = mountGrid(
      Array.from({ length: 6 }, (_, index) =>
        createMedia({
          id: `media-${index}`,
          imageWidth: 1000,
          imageHeight: 1000
        })
      ),
      true
    )

    await nextTick()

    expect(wrapper.findAll('.media-grid-row')).toHaveLength(1)
    expect(wrapper.find('.media-grid-row-justify').exists()).toBe(true)
    expect(wrapper.findAllComponents(ExplorerMediaCard)).toHaveLength(5)

    await wrapper.setProps({ streaming: false })
    await nextTick()

    expect(wrapper.findAll('.media-grid-row')).toHaveLength(2)
    expect(wrapper.findAllComponents(ExplorerMediaCard)).toHaveLength(6)
    expect(wrapper.find('.media-grid-row-center').exists()).toBe(true)
  })

  it('shows the streaming loader while the media stream has not yielded items', async () => {
    const wrapper = mountGrid([], true)

    await nextTick()

    expect(wrapper.find('.explorer-loading').exists()).toBe(true)
    expect(wrapper.text()).toContain('Medien werden geladen...')
  })

  it('applies browser aspect ratio updates in one animation frame', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'a', imageWidth: null, imageHeight: null }),
      createMedia({ id: 'b', imageWidth: null, imageHeight: null })
    ])
    await nextTick()

    const probeCards = wrapper.findAllComponents(ExplorerMediaCard)
    await probeCards[0].vm.$emit('aspectRatio', 16 / 9)
    await probeCards[1].vm.$emit('aspectRatio', 1)
    await nextTick()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(true)
    expect(wrapper.find('.media-grid-row').exists()).toBe(false)

    runAnimationFrames()
    await nextTick()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(false)
    expect(wrapper.find('.media-grid-row').exists()).toBe(true)
    expect(wrapper.findAllComponents(ExplorerMediaCard)).toHaveLength(2)
  })
})
