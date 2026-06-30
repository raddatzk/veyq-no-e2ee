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
      memory: { favorite: 'Favorite', unfavorite: 'Remove favorite', rename: 'Rename', delete: 'Delete' },
      media: {
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
    global: { plugins: [i18n] },
  })
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllGlobals())

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
