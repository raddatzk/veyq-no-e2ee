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
import MediaLightbox from './MediaLightbox.vue'
import { addMedia, listMedia, type MediaDeriver, type MediaItem } from '../media'
import { mediaApi } from '../../../api/client'
import * as v1 from '../../../shared/crypto/v1'
import { galleryFakeStore } from './galleryFakeStore'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      media: {
        itemPosition: '{index} of {count}',
        download: 'Download original',
        close: 'Close',
        previous: 'Previous',
        next: 'Next',
        loading: 'Loading…',
        loadError: 'This item couldn’t be loaded.',
      },
    },
  },
})

function stubDeriver(): MediaDeriver {
  return {
    deriveImage: vi.fn(async (_b: Blob, { maxEdge }: { maxEdge: number; quality: number }) =>
      new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff])),
    deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 0x07, 0x09])),
  }
}

/** Seed two image media and return the listed items + the live fake store. */
async function seedTwo(memoryKey: Uint8Array) {
  const store = galleryFakeStore()
  vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
  vi.stubGlobal('fetch', store.fetchStub)
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(1), mediaType: 'image/png' }, stubDeriver())
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(8).fill(2), mediaType: 'image/jpeg' }, stubDeriver())
  const items = await listMedia(SCOPE_ID, NODE_ID)
  return { store, items }
}

function mountLightbox(memoryKey: Uint8Array, items: MediaItem[], startIndex = 0) {
  return mount(MediaLightbox, {
    props: { scopeId: SCOPE_ID, memoryKey, items, startIndex, memoryName: 'Rom 2026' },
    global: { plugins: [i18n] },
  })
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('MediaLightbox (preview, prev/next, download)', () => {
  it('fetches the PREVIEW tier (not the original) for the stage and shows it', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    store.api.presignBlobDownloads.mockClear()

    const wrapper = mountLightbox(memoryKey, items)
    await flushPromises()

    // media 0: original=up-0, THUMBNAIL=up-1, PREVIEW=up-2 → the lightbox opens the PREVIEW, never the original
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toContain('up-2')
    expect(presigned).not.toContain('up-0')
    expect(wrapper.find('.media-lightbox-stage img').attributes('src')).toBeTruthy()
  })

  it('pages prev/next through the grid order, fetching the next item’s preview, and clamps at the ends', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    // at the first item: previous is disabled (clamp), next enabled
    expect(wrapper.find('.lightbox-nav-previous').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeUndefined()

    store.api.presignBlobDownloads.mockClear()
    await wrapper.find('.lightbox-nav-next').trigger('click')
    await flushPromises()

    // media 1: PREVIEW=up-5 → next paged to it; now next is disabled at the end
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toContain('up-5')
    expect(wrapper.find('.media-lightbox-title small').text()).toBe('2 of 2')
    expect(wrapper.find('.lightbox-nav-next').attributes('disabled')).toBeDefined()
  })

  it('revokes the preview object URL on navigation and on close', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)
    const revoke = vi.spyOn(URL, 'revokeObjectURL')

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    await wrapper.find('.lightbox-nav-next').trigger('click')
    await flushPromises()
    expect(revoke).toHaveBeenCalled() // the first preview URL is freed before the next loads

    revoke.mockClear()
    wrapper.unmount()
    expect(revoke).toHaveBeenCalled() // the last preview URL is freed on unmount
  })

  it('closes on the close button and on Escape', async () => {
    const memoryKey = v1.randomBytes(32)
    const { items } = await seedTwo(memoryKey)

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()

    await wrapper.find('[aria-label="Close"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  it('download fetches the ORIGINAL and saves it with the derived name', async () => {
    const memoryKey = v1.randomBytes(32)
    const { store, items } = await seedTwo(memoryKey)
    const anchors: HTMLAnchorElement[] = []
    const realCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      const el = realCreate(tag)
      if (tag === 'a') anchors.push(el as HTMLAnchorElement)
      return el
    })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})

    const wrapper = mountLightbox(memoryKey, items, 0)
    await flushPromises()
    store.api.presignBlobDownloads.mockClear()

    await wrapper.find('[aria-label="Download original"]').trigger('click')
    await flushPromises()

    // the original (up-0), never a derivative, is fetched for download
    const presigned = store.api.presignBlobDownloads.mock.calls.flatMap(
      (c: any) => c[0].blobDownloadsBatchRequest.items.map((it: any) => it.blobId),
    )
    expect(presigned).toEqual(['up-0'])
    // the original decrypts to the stub's 8 bytes filled with 1 → not a known signature → .bin
    expect(anchors.at(-1)!.getAttribute('download')).toBe('Rom 2026 1.bin')
  })
})
