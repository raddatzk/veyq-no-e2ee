// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../../api/client', () => ({
  mediaApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import MemoryDetail from './MemoryDetail.vue'
import { addMedia, type MediaDeriver } from '../media'
import { mediaApi } from '../../../api/client'
import * as v1 from '../../../shared/crypto/v1'
import { NodeKind } from '../../../api/generated/content'
import type { TreeNode } from '../tree'
import { galleryFakeStore } from './galleryFakeStore'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      memory: { favorite: 'Favorite', unfavorite: 'Remove favorite', rename: 'Rename', delete: 'Delete', addChapter: 'Add chapter', chapters: 'Chapters', gallery: 'Gallery', noChapters: 'No chapters yet.' },
      media: {
        upload: 'Upload',
        gallery: 'Memory gallery',
        itemCount: 'No items | {count} item | {count} items',
        openItem: 'Open item {index}',
        empty: 'No photos in this Memory yet.',
        unavailable: 'This Memory can’t be opened right now.',
        loading: 'Loading…',
        itemPosition: '{index} of {count}',
        download: 'Download original',
        close: 'Close',
        previous: 'Previous',
        next: 'Next',
        loadError: 'This item couldn’t be loaded.',
      },
    },
  },
})

const node = (name: string): TreeNode => ({
  id: NODE_ID, kind: NodeKind.Memory, name, parentId: null, orderIndex: 0, wrappedContentKey: null,
})

/** A stub deriver: a tiny "JPEG" tagged by the requested maxEdge so each tier's bytes are distinct. */
function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) =>
      new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff])),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 0x07, 0x09])),
  }
}

function mountDetail(memoryKey: Uint8Array, props: Partial<Record<string, unknown>> = {}) {
  return mount(MemoryDetail, {
    props: { node: node('Rom 2026'), scopeId: SCOPE_ID, memoryKey, favorite: false, ...props },
    global: { plugins: [i18n, PrimeVue], directives: { tooltip: {} } },
  })
}

// happy-dom does no layout, so the justified grid (cr-0059) can't measure its container; stub a width + a
// no-op ResizeObserver so the layout produces tiles (the layout math itself is covered in mediaGridLayout.test).
beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('MemoryDetail (gallery grid + header)', () => {
  it('lists the Memory media → renders the decrypted title, item count and a thumbnail tile per item', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(2), mediaType: 'image/jpeg' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.gallery-title').text()).toBe('Rom 2026')
    expect(wrapper.find('.gallery-count').text()).toBe('2 items')
    // two derivable items → two <img> thumbnail tiles, each backed by a decrypted object URL
    const imgs = wrapper.findAll('.gallery-tile img')
    expect(imgs).toHaveLength(2)
    expect(imgs[0].attributes('src')).toBeTruthy()
    // operator-blind: each thumbnail came back through a presigned GET + on-device decrypt
    expect(store.api.listNodeMedia).toHaveBeenCalledWith({ scopeId: SCOPE_ID, nodeId: NODE_ID })
    expect(store.api.presignBlobDownloads).toHaveBeenCalled()
  })

  it('shows the empty state for a Memory with no media', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const wrapper = mountDetail(v1.randomBytes(32))
    await flushPromises()

    expect(wrapper.find('.veyq-empty').text()).toBe('No photos in this Memory yet.')
    expect(wrapper.findAll('.gallery-tile')).toHaveLength(0)
  })

  it('renders a non-image/video item (E1) as an icon tile, fetching no thumbnail for it', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/pdf' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.findAll('.gallery-tile')).toHaveLength(1)
    expect(wrapper.find('.gallery-tile img').exists()).toBe(false)
    expect(wrapper.find('.gallery-tile .media-grid-icon').exists()).toBe(true)
    // a non-derivable item is never fetched (no preview/thumbnail tier exists)
    expect(store.api.presignBlobDownloads).not.toHaveBeenCalled()
  })

  it('shows the unavailable state when K_mem is absent (E2: locked / not openable)', async () => {
    const wrapper = mountDetail(null as unknown as Uint8Array, { memoryKey: null })
    await flushPromises()
    expect(wrapper.find('.veyq-empty').text()).toBe('This Memory can’t be opened right now.')
  })

  it('renders the Memory’s chapters and emits open/create (cr-0060)', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const chapters: TreeNode[] = [
      { id: 'c1', kind: NodeKind.Chapter, name: 'Day 1', parentId: NODE_ID, orderIndex: 0, wrappedContentKey: 'd3JhcA==' },
    ]

    const wrapper = mountDetail(v1.randomBytes(32), { chapters })
    await flushPromises()

    // switch to the Chapters tab (the gallery is the default tab)
    const chaptersTab = wrapper.findAll('[role="tab"]').find((t) => t.text().includes('Chapters'))
    expect(chaptersTab).toBeTruthy()
    await chaptersTab!.trigger('click')
    await flushPromises()

    // the chapter shows as a MemoryCard (not a chip, cr-0062), open-only (no favorite pin)
    const card = wrapper.find('.memory-card')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('Day 1')
    expect(card.find('.memory-card-pin').exists()).toBe(false) // favoritable=false
    // clicking the card asks the host to open that chapter
    await card.find('.memory-card-main').trigger('click')
    expect(wrapper.emitted('openChapter')?.[0]?.[0]).toMatchObject({ id: 'c1' })

    // the create card asks the host to start creation
    await wrapper.find('.memory-create-card').trigger('click')
    expect(wrapper.emitted('createChapter')).toBeTruthy()
  })

  it('opens the lightbox when a tile is clicked', async () => {
    const store = galleryFakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())

    const wrapper = mountDetail(memoryKey)
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.media-lightbox').exists()).toBe(false)
    await wrapper.find('.gallery-tile').trigger('click')
    await flushPromises()
    expect(wrapper.find('.media-lightbox').exists()).toBe(true)
  })
})
