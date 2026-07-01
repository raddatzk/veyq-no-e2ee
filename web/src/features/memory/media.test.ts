import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../api/client', () => ({
  mediaApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

// exifr does the real image parse in the browser; here we drive it so the seal/round-trip is deterministic.
// Default: no EXIF (parse → undefined), so the non-EXIF paths behave exactly as a source without metadata.
vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

import {
  addMedia,
  listMedia,
  openBlob,
  openOriginal,
  gridRef,
  lightboxRef,
  downloadRef,
  scaleToMaxEdge,
  unsealLayout,
  setMediaCapturedAt,
  effectiveCapturedAt,
  CHUNK_SIZE,
  TIER_SPECS,
  type MediaDeriver,
  type MediaItem,
  type MediaExif,
} from './media'
import exifr from 'exifr'
import { mediaApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'
import { fromB64std } from '../../shared/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

/**
 * A tiny in-process object store that handles **several blobs per add-media** (original + derivatives). Each
 * upload session gets its own `mem://<uploadId>/<chunk>` keyspace; the fake replays PUT bodies on the matching
 * GET, so a real v1 seal/open + K_blob wrap round-trips per tier. `listNodeMedia` reconstructs the role-tagged
 * MediaSummary from what `completeMedia` saw (blobId == uploadId in this fake). Operator-blind: the "store"
 * only ever holds ciphertext.
 */
function fakeStore() {
  const objects = new Map<string, Uint8Array>()
  const sessions = new Map<string, { wrappedBlobKey: string; chunkCount: number }>()
  let upCounter = 0
  const captured = { startItems: [] as any[], completeItem: undefined as any, updatedExif: undefined as any }

  const blobOf = (uploadId: string) => {
    const s = sessions.get(uploadId)!
    return { blobId: uploadId, chunkCount: s.chunkCount, byteSize: 0, wrappedBlobKey: s.wrappedBlobKey }
  }

  const api = {
    startBlobUploads: vi.fn(async ({ blobUploadsBatchRequest }: any) => {
      const results = blobUploadsBatchRequest.items.map((item: any) => {
        const uploadId = `up-${upCounter++}`
        sessions.set(uploadId, { wrappedBlobKey: item.wrappedBlobKey, chunkCount: item.chunkCount })
        captured.startItems.push(item)
        const presignedPuts = Array.from({ length: item.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` }))
        return { uploadId, presignedPuts, error: null }
      })
      return { results }
    }),
    completeMedia: vi.fn(async ({ mediaBatchRequest }: any) => {
      const item = mediaBatchRequest.items[0]
      captured.completeItem = item
      return { results: [{ blobId: `blob-${item.originalUploadId}`, mediaId: 'media-1', error: null }] }
    }),
    listNodeMedia: vi.fn(async () => {
      const item = captured.completeItem
      return [
        {
          mediaId: 'media-1',
          ...blobOf(item.originalUploadId),
          derivatives: (item.derivatives ?? []).map((d: any) => ({ kind: d.kind, ...blobOf(d.uploadId) })),
          wrappedLayout: item.wrappedLayout ?? null, // opaque ciphertext, echoed verbatim (cr-0059/cr-0064)
          // wrappedExif is deliberately NOT echoed by listNodeMedia — it is lazy via getMediaMeta (cr-0064)
        },
      ]
    }),
    updateMediaMeta: vi.fn(async ({ mediaMetaUpdate }: any) => {
      captured.updatedExif = mediaMetaUpdate.wrappedExif // opaque ciphertext (or null to clear)
      return { wrappedExif: mediaMetaUpdate.wrappedExif ?? null }
    }),
    presignBlobDownloads: vi.fn(async ({ blobDownloadsBatchRequest }: any) => {
      const uploadId = blobDownloadsBatchRequest.items[0].blobId // == uploadId in this fake
      const s = sessions.get(uploadId)!
      return {
        results: [{ presignedGets: Array.from({ length: s.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` })), error: null }],
      }
    }),
  }

  const fetchStub = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') {
      objects.set(url, new Uint8Array(init.body as Uint8Array))
      return { ok: true, status: 200 } as Response
    }
    const body = objects.get(url)
    if (!body) return { ok: false, status: 404 } as Response
    return { ok: true, status: 200, arrayBuffer: async () => body.buffer } as unknown as Response
  })

  return { api, fetchStub, objects, captured }
}

/** A stub deriver: returns a tiny "JPEG" tagged by the requested maxEdge so each tier's bytes are distinct. */
function stubDeriver(): MediaDeriver & { images: number; posters: number } {
  const d = {
    images: 0,
    posters: 0,
    deriveImage: vi.fn(async (_blob: Blob, { maxEdge }: { maxEdge: number; quality: number }) => {
      d.images++
      return new Uint8Array([0xff, 0xd8, maxEdge & 0xff, (maxEdge >> 8) & 0xff])
    }),
    deriveVideoPoster: vi.fn(async () => {
      d.posters++
      return new Uint8Array([0xff, 0xd8, 0x07, 0x09])
    }),
  }
  return d
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllGlobals())

describe('scaleToMaxEdge (longest-edge target math)', () => {
  it('scales the longest edge to the cap, preserving aspect ratio', () => {
    expect(scaleToMaxEdge(4000, 2000, 400)).toEqual({ width: 400, height: 200 }) // landscape
    expect(scaleToMaxEdge(2000, 4000, 400)).toEqual({ width: 200, height: 400 }) // portrait
    expect(scaleToMaxEdge(1000, 1000, 400)).toEqual({ width: 400, height: 400 }) // square
  })

  it('never upscales a source already within the cap', () => {
    expect(scaleToMaxEdge(300, 150, 400)).toEqual({ width: 300, height: 150 })
    expect(scaleToMaxEdge(400, 400, 400)).toEqual({ width: 400, height: 400 })
  })

  it('rounds to whole pixels and keeps each side ≥ 1px', () => {
    expect(scaleToMaxEdge(2048, 3, 400)).toEqual({ width: 400, height: 1 })
    expect(scaleToMaxEdge(0, 0, 400)).toEqual({ width: 0, height: 0 })
  })
})

describe('addMedia — derivative generation + role/batch assembly', () => {
  it('an image uploads original + thumbnail + preview in one batch, role-tagged', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    const memoryKey = v1.randomBytes(32)
    const data = new Uint8Array(10).fill(7)
    const { blobId, mediaId } = await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: data, mediaType: 'image/png' }, deriver)

    expect(mediaId).toBe('media-1')
    expect(blobId).toBe('blob-up-0') // original is upload index 0

    // three blobs declared in one start batch (original + 2 derivatives)
    expect(store.captured.startItems).toHaveLength(3)
    // the deriver produced two downscales at the pinned tier specs; no video poster
    expect(deriver.images).toBe(2)
    expect(deriver.posters).toBe(0)
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(1, expect.anything(), TIER_SPECS.THUMBNAIL)
    expect(deriver.deriveImage).toHaveBeenNthCalledWith(2, expect.anything(), TIER_SPECS.PREVIEW)

    // complete attached one role-tagged media: original + [THUMBNAIL, PREVIEW]
    expect(store.captured.completeItem.nodeId).toBe(NODE_ID)
    expect(store.captured.completeItem.originalUploadId).toBe('up-0')
    expect(store.captured.completeItem.derivatives).toEqual([
      { kind: 'THUMBNAIL', uploadId: 'up-1' },
      { kind: 'PREVIEW', uploadId: 'up-2' },
    ])

    // each tier has its OWN K_blob wrap (per-blob keys, never shared)
    const wraps = store.captured.startItems.map((i) => i.wrappedBlobKey)
    expect(new Set(wraps).size).toBe(3)
  })

  it('a video derives a single poster and uses it for both the thumbnail and preview tier', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(20).fill(3), mediaType: 'video/mp4' }, deriver)

    expect(deriver.posters).toBe(1) // decoded once
    expect(deriver.images).toBe(0)
    expect(store.captured.completeItem.derivatives.map((d: any) => d.kind)).toEqual(['THUMBNAIL', 'PREVIEW'])

    // both derivative tiers open back to the SAME poster bytes
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const thumb = await openBlob(SCOPE_ID, memoryKey, gridRef(item))
    const preview = await openBlob(SCOPE_ID, memoryKey, lightboxRef(item))
    expect(Array.from(thumb)).toEqual([0xff, 0xd8, 0x07, 0x09])
    expect(Array.from(preview)).toEqual(Array.from(thumb))
  })

  it('a non-image/non-video attaches the original only (no derivatives)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()

    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/pdf' }, deriver)

    expect(deriver.images + deriver.posters).toBe(0)
    expect(store.captured.startItems).toHaveLength(1)
    expect(store.captured.completeItem.derivatives).toEqual([])
  })

  it('throws store_unavailable when the store cannot presign (no transport)', async () => {
    vi.mocked(mediaApi).mockReturnValue({
      startBlobUploads: vi.fn(async () => ({ results: [{ uploadId: 'up-1', presignedPuts: null, error: null }] })),
    } as unknown as ReturnType<typeof mediaApi>)

    await expect(
      addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1]), mediaType: 'application/pdf' }, stubDeriver()),
    ).rejects.toThrow('store_unavailable')
  })
})

describe('addMedia — progress, abort, best-effort derivation (cr-0058)', () => {
  it('reports progress 0 → 1 (monotonic) over the ciphertext chunks', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const seen: number[] = []

    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array(20).fill(1), mediaType: 'image/png' }, stubDeriver(), {
      onProgress: (f) => seen.push(f),
    })

    expect(seen[0]).toBe(0)
    expect(seen[seen.length - 1]).toBe(1)
    expect(seen).toEqual([...seen].sort((a, b) => a - b)) // non-decreasing
  })

  it('a pre-aborted signal throws AbortError and never attaches the media', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const ac = new AbortController()
    ac.abort()

    await expect(
      addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1]), mediaType: 'image/png' }, stubDeriver(), {
        signal: ac.signal,
      }),
    ).rejects.toMatchObject({ name: 'AbortError' })
    expect(store.api.completeMedia).not.toHaveBeenCalled()
  })

  it('uploads original-only (no derivatives) when derivation throws — e.g. an undecodable HEIC', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.deriveImage = vi.fn(async () => {
      throw new Error('decode_failed') // browser can't decode this codec (HEIC off Safari)
    })

    const { mediaId } = await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1, 2, 3]), mediaType: 'image/heic' }, deriver)

    expect(mediaId).toBe('media-1')
    expect(store.captured.startItems).toHaveLength(1) // original only — no derivative blobs
    expect(store.captured.completeItem.derivatives).toEqual([])
  })
})

describe('addMedia — E2EE-sealed layout + EXIF tiers (cr-0059/cr-0064)', () => {
  const CAPTURED_AT = Date.UTC(2021, 4, 1, 10, 0, 0) // 2021-05-01T10:00:00Z

  afterEach(() => {
    vi.mocked(exifr.parse).mockReset()
    vi.mocked(exifr.parse).mockResolvedValue(undefined as never)
  })

  it('seals the layout tier {v,w,h} (geometry only) under K_mem and round-trips it through listMedia', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 1920, h: 1080 }))
    vi.mocked(exifr.parse).mockResolvedValue({ DateTimeOriginal: new Date(CAPTURED_AT), Make: 'Canon' } as never)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(10).fill(7), mediaType: 'image/png' }, deriver)

    // the wire carries only opaque ciphertext — never the plaintext dimensions
    const sealed = store.captured.completeItem.wrappedLayout
    expect(typeof sealed).toBe('string')
    expect(sealed).not.toContain('1920')

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const layout = unsealLayout(memoryKey, item)
    expect(layout).toEqual({ v: 1, w: 1920, h: 1080 }) // geometry only — capture time lives in the EXIF tier
    // a wrong K_mem can't open it (bound to the Memory key)
    expect(unsealLayout(v1.randomBytes(32), item)).toBeNull()
  })

  it('seals the full EXIF tier under K_mem — and listMedia never carries it (lazy via getMediaMeta)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 800, h: 600 }))
    vi.mocked(exifr.parse).mockResolvedValue({
      DateTimeOriginal: new Date(CAPTURED_AT),
      Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm', FNumber: 1.8, ISO: 200, latitude: 48.137, longitude: 11.575,
    } as never)

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(10).fill(7), mediaType: 'image/jpeg' }, deriver)

    // the EXIF tier is sealed opaque — plaintext camera/coords never hit the wire
    const sealedExif = store.captured.completeItem.wrappedExif
    expect(typeof sealedExif).toBe('string')
    expect(sealedExif).not.toContain('Canon')
    expect(sealedExif).not.toContain('48.137')
    // unseal it directly (the lightbox does this after getMediaMeta) — every field round-trips
    const exif = JSON.parse(new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(sealedExif), 'media-exif')))
    expect(exif).toMatchObject({ v: 1, make: 'Canon', model: 'EOS R', lens: 'RF 50mm', fNumber: 1.8, iso: 200, gpsLat: 48.137, gpsLng: 11.575, capturedAt: CAPTURED_AT })

    // listMedia stays constant-small: it echoes the layout tier but NOT the EXIF tier
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(item.wrappedLayout).toBeDefined()
    expect((item as Record<string, unknown>).wrappedExif).toBeUndefined()
  })

  it('tolerates a no-EXIF source — layout still seals from dims, EXIF tier is null', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => ({ w: 640, h: 480 }))
    // exifr.parse → undefined (default mock): the source carries no readable EXIF

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'image/png' }, deriver)

    expect(store.captured.completeItem.wrappedExif ?? null).toBeNull()
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealLayout(memoryKey, item)).toEqual({ v: 1, w: 640, h: 480 })
  })

  it('seals no layout when the source is undecodable (probe returns null) → unseal falls back to null', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const deriver = stubDeriver()
    deriver.probeDimensions = vi.fn(async () => null) // e.g. an empty MIME / non-image

    const memoryKey = v1.randomBytes(32)
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/pdf' }, deriver)

    expect(store.captured.completeItem.wrappedLayout ?? null).toBeNull()
    expect(store.captured.completeItem.wrappedExif ?? null).toBeNull()
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(unsealLayout(memoryKey, item)).toBeNull()
  })

  it('a deriver with no probeDimensions seals no layout (best-effort, optional)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    await addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), { bytes: new Uint8Array([1]), mediaType: 'image/png' }, stubDeriver())
    expect(store.captured.completeItem.wrappedLayout ?? null).toBeNull()
  })
})

describe('setMediaCapturedAt — sealed capturedAt override (cr-0065)', () => {
  const ORIG = Date.UTC(2021, 4, 1, 10, 0, 0)
  const CORRECTED = Date.UTC(2019, 7, 15, 8, 30, 0)
  const MEDIA_ID = 'media-1'

  function decodeExif(memoryKey: Uint8Array, sealed: string): MediaExif {
    return JSON.parse(new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(sealed), 'media-exif')))
  }

  it('re-seals the bundle with the override, keeping the original capturedAt (operator-blind)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)
    const exif: MediaExif = { v: 1, capturedAt: ORIG, make: 'Canon' }

    const updated = await setMediaCapturedAt(SCOPE_ID, MEDIA_ID, memoryKey, exif, CORRECTED)

    // the wire carries only opaque ciphertext — never the plaintext date
    const sealed = store.captured.updatedExif
    expect(typeof sealed).toBe('string')
    expect(sealed).not.toContain('2019')
    // the sealed bundle keeps the original + adds the override
    expect(decodeExif(memoryKey, sealed)).toEqual({ v: 1, capturedAt: ORIG, make: 'Canon', capturedAtOverride: CORRECTED })
    // effective time now prefers the correction
    expect(effectiveCapturedAt(updated)).toBe(CORRECTED)
    // a wrong K_mem can't open it
    expect(() => decodeExif(v1.randomBytes(32), sealed)).toThrow()
  })

  it('clears the override (null) back to the original, keeping the rest of the bundle', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)
    const exif: MediaExif = { v: 1, capturedAt: ORIG, make: 'Canon', capturedAtOverride: CORRECTED }

    const updated = await setMediaCapturedAt(SCOPE_ID, MEDIA_ID, memoryKey, exif, null)

    expect(decodeExif(memoryKey, store.captured.updatedExif)).toEqual({ v: 1, capturedAt: ORIG, make: 'Canon' })
    expect(effectiveCapturedAt(updated)).toBe(ORIG) // falls back to the extracted original
  })

  it('clearing the only field drops the whole EXIF tier to null', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)
    const exif: MediaExif = { v: 1, capturedAtOverride: CORRECTED } // a media that only ever had a manual date

    const updated = await setMediaCapturedAt(SCOPE_ID, MEDIA_ID, memoryKey, exif, null)

    expect(store.captured.updatedExif).toBeNull() // nothing left to seal → the tier is cleared
    expect(updated).toBeNull()
  })

  it('sets a date on a media with no EXIF (fresh bundle)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    const memoryKey = v1.randomBytes(32)

    const updated = await setMediaCapturedAt(SCOPE_ID, MEDIA_ID, memoryKey, null, CORRECTED)

    expect(decodeExif(memoryKey, store.captured.updatedExif)).toEqual({ v: 1, capturedAtOverride: CORRECTED })
    expect(effectiveCapturedAt(updated)).toBe(CORRECTED)
  })
})

describe('read-back — operator-blind round-trip + tier selection', () => {
  it('round-trips the original through chunked AEAD under a K_blob wrapped by K_mem', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    // 600 KiB ⇒ 3 chunks at CHUNK_SIZE = 256 KiB (deterministic fill)
    const data = new Uint8Array(600 * 1024)
    for (let i = 0; i < data.length; i++) data[i] = (i * 31 + 7) & 0xff

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: data, mediaType: 'image/jpeg' }, stubDeriver())

    // operator-blind: the first stored object is ciphertext (nonce+ct+tag), never the plaintext chunk
    const original = store.objects.get('mem://up-0/0')!
    expect(original).not.toEqual(data.subarray(0, CHUNK_SIZE))
    expect(original.length).toBe(CHUNK_SIZE + 12 + 16)

    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    expect(item.original.chunkCount).toBe(Math.ceil(data.length / CHUNK_SIZE))
    const opened = await openOriginal(SCOPE_ID, memoryKey, item)
    expect(Array.from(opened)).toEqual(Array.from(data))
  })

  it('picks the right tier: grid=thumbnail, lightbox=preview, download=original', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array(5).fill(9), mediaType: 'image/webp' }, stubDeriver())
    const [item] = await listMedia(SCOPE_ID, NODE_ID)

    expect(downloadRef(item).blobId).toBe('up-0')
    expect(gridRef(item).blobId).toBe('up-1') // THUMBNAIL
    expect(lightboxRef(item).blobId).toBe('up-2') // PREVIEW

    // the thumbnail tier opens to the 400px-tagged stub bytes, the preview to the 2048px-tagged ones
    const grid = await openBlob(SCOPE_ID, memoryKey, gridRef(item))
    const light = await openBlob(SCOPE_ID, memoryKey, lightboxRef(item))
    expect(Array.from(grid)).toEqual([0xff, 0xd8, 400 & 0xff, 400 >> 8])
    expect(Array.from(light)).toEqual([0xff, 0xd8, 2048 & 0xff, 2048 >> 8])
  })

  it('falls back to the original when a media has no derivatives', async () => {
    const item: MediaItem = {
      mediaId: 'm',
      original: { blobId: 'orig', chunkCount: 1, byteSize: 0, wrappedBlobKey: 'd3JhcA==' },
      derivatives: [],
    }
    expect(gridRef(item).blobId).toBe('orig')
    expect(lightboxRef(item).blobId).toBe('orig')
    expect(downloadRef(item).blobId).toBe('orig')
  })

  it('the original wrap opens back to a real 32-byte K_blob under K_mem', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)
    const memoryKey = v1.randomBytes(32)

    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { bytes: new Uint8Array([1]), mediaType: 'application/pdf' }, stubDeriver())
    const wrap = store.captured.startItems[0].wrappedBlobKey
    const blobKey = v1.symUnwrap(memoryKey, fromB64std(wrap), 'blob-key')
    expect(blobKey.length).toBe(32)
  })
})
