import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import ExplorerMediaGrid from './ExplorerMediaGrid.vue'
import type { ExplorerMedia } from '../../api/explorerClient'

const progressSpinnerStub = { template: '<span class="small-spinner" />' }
const lightboxStub = {
  props: ['media', 'canShowPrevious', 'canShowNext'],
  template:
    '<div class="media-lightbox">{{ media.id }} {{ canShowPrevious }} {{ canShowNext }}</div>'
}

let observedWidth = 1000

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
    previewUrl: '/api/media/media-1/preview',
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

async function settleLayout() {
  await nextTick()
  await nextTick()
  await nextTick()
}

describe('ExplorerMediaGrid', () => {
  beforeEach(() => {
    observedWidth = 1000
    vi.stubGlobal('ResizeObserver', ResizeObserverMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders absolute positioned tiles with a fixed gallery height', async () => {
    const wrapper = mountGrid(
      Array.from({ length: 4 }, (_, index) =>
        createMedia({
          id: `media-${index}`,
          imageWidth: 1000,
          imageHeight: 1000
        })
      )
    )

    await settleLayout()

    const grid = wrapper.find('.media-grid')
    const tiles = wrapper.findAll('.media-grid-tile')

    expect(grid.attributes('style')).toContain('height: 244px')
    expect(tiles).toHaveLength(4)
    expect(tiles[0].attributes('style')).toContain('transform: translate(0px, 0px)')
    expect(tiles[0].attributes('style')).toContain('width: 244px')
    expect(tiles[0].attributes('style')).toContain('height: 244px')
    expect(tiles[3].attributes('style')).toContain('transform: translate(756px, 0px)')
  })

  it('requests media after layout and reveals each tile after its own load event', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'a', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'b', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'c', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'd', imageWidth: 1000, imageHeight: 1000 })
    ])

    await nextTick()

    expect(wrapper.find('.media-grid-tile-placeholder').exists()).toBe(true)
    expect(wrapper.find('img').exists()).toBe(false)

    await settleLayout()

    expect(wrapper.findAll('img')).toHaveLength(4)
    expect(wrapper.findAll('.media-grid-tile-placeholder')).toHaveLength(4)

    await wrapper.findAll('img')[0].trigger('load')

    expect(wrapper.findAll('.media-grid-tile-placeholder')).toHaveLength(3)
    expect(wrapper.findAll('img')[0].classes()).toContain('media-grid-tile-media-loaded')
  })

  it('uses backend dimensions before image load events', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'wide', imageWidth: 1600, imageHeight: 900 }),
      createMedia({ id: 'square', imageWidth: 900, imageHeight: 900 }),
      createMedia({ id: 'portrait', imageWidth: 900, imageHeight: 1350 })
    ])

    await settleLayout()

    const tiles = wrapper.findAll('.media-grid-tile')
    expect(tiles).toHaveLength(3)
    expect(tiles[0].attributes('style')).toContain('width: 462.222')
    expect(tiles[1].attributes('style')).toContain('width: 260')
    expect(tiles[2].attributes('style')).toContain('width: 173.333')
  })

  it('renders a fallback layout while dimensions are unknown', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'a', imageWidth: null, imageHeight: null }),
      createMedia({ id: 'b', imageWidth: null, imageHeight: null })
    ])

    await settleLayout()

    const tiles = wrapper.findAll('.media-grid-tile')
    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(false)
    expect(tiles).toHaveLength(2)
    expect(tiles[0].attributes('style')).toContain('width: 346.666')
    expect(tiles[1].attributes('style')).toContain('width: 346.666')
  })

  it('keeps media and videos in the same absolute tile model', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'image', mimeType: 'image/jpeg', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'video', mimeType: 'video/mp4', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'file', mimeType: 'application/octet-stream', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'video-raw', mimeType: 'video/mp4', previewUrl: null, imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'image-2', mimeType: 'image/jpeg', imageWidth: 1000, imageHeight: 1000 })
    ])

    await settleLayout()

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(5)
    expect(wrapper.findAll('img')).toHaveLength(3)
    expect(wrapper.findAll('video')).toHaveLength(1)
    expect(wrapper.find('.media-grid-icon').exists()).toBe(true)
  })

  it('renders videos with preview urls as preview images first', async () => {
    const wrapper = mountGrid([
      createMedia({
        id: 'video',
        mimeType: 'video/mp4',
        contentUrl: '/api/media/video/content',
        previewUrl: '/api/media/video/preview',
        imageWidth: 1000,
        imageHeight: 1000
      })
    ])

    await settleLayout()

    const image = wrapper.find('img')
    expect(image.exists()).toBe(true)
    expect(image.attributes('src')).toBe('/api/media/video/preview')
    expect(wrapper.find('video').exists()).toBe(false)
  })

  it('opens the lightbox using tile order', async () => {
    const wrapper = mountGrid([
      createMedia({ id: 'a', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'b', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'c', imageWidth: 1000, imageHeight: 1000 }),
      createMedia({ id: 'd', imageWidth: 1000, imageHeight: 1000 })
    ])

    await settleLayout()
    await wrapper.findAll('.media-grid-tile')[1].trigger('click')

    expect(wrapper.find('.media-lightbox').text()).toContain('b true true')
  })

  it('does not show the streaming loader below an already rendered grid', async () => {
    const wrapper = mountGrid(
      Array.from({ length: 4 }, (_, index) =>
        createMedia({
          id: `media-${index}`,
          imageWidth: 1000,
          imageHeight: 1000
        })
      )
    )

    await settleLayout()
    expect(wrapper.find('.media-grid-tile').exists()).toBe(true)

    await wrapper.setProps({ streaming: true })
    await settleLayout()

    expect(wrapper.find('.media-grid-tile').exists()).toBe(true)
    expect(wrapper.find('.explorer-loading').exists()).toBe(false)
  })

  it('waits for the initial media stream before rendering a partial first row', async () => {
    const wrapper = mountGrid(
      [createMedia({ id: 'first', imageWidth: 1000, imageHeight: 1000 })],
      true
    )

    await settleLayout()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(true)
    expect(wrapper.find('.media-grid-tile').exists()).toBe(false)
    expect(wrapper.text()).toContain('Medien werden geladen...')

    await wrapper.setProps({ streaming: false })
    await settleLayout()

    expect(wrapper.find('.media-grid-placeholder').exists()).toBe(false)
    expect(wrapper.find('.media-grid-tile').exists()).toBe(true)
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

    await settleLayout()

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(4)

    await wrapper.setProps({ streaming: false })
    await settleLayout()

    expect(wrapper.findAll('.media-grid-tile')).toHaveLength(6)
  })

  it('shows the streaming loader while the media stream has not yielded items', async () => {
    const wrapper = mountGrid([], true)

    await nextTick()

    expect(wrapper.find('.explorer-loading').exists()).toBe(true)
    expect(wrapper.text()).toContain('Medien werden geladen...')
  })
})
