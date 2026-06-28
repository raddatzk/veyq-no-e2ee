import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../../api/client', () => ({
  mediaApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import { addMedia, listMedia, openMedia, CHUNK_SIZE, type MediaItem } from './media'
import { mediaApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'
import { fromB64std } from '../../shared/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'

/**
 * A tiny in-process object store: presigned PUT/GET URLs are `mem://<n>`, a stubbed `fetch` stores PUT bodies
 * and replays them on GET. Mirrors the real direct-to-store transport without a network or MinIO, so the test
 * exercises real v1 chunk seal/open + K_blob wrap + the batch control plane end-to-end (operator-blind: the
 * "store" only ever holds ciphertext).
 */
function fakeStore() {
  const objects = new Map<string, Uint8Array>()
  let counter = 0
  // capture what the control plane saw, for assertions
  const captured = { startReq: undefined as any, completeReq: undefined as any, chunkCount: 0, wrappedBlobKey: '' }

  const api = {
    startBlobUploads: vi.fn(async ({ blobUploadsBatchRequest }: any) => {
      captured.startReq = blobUploadsBatchRequest.items[0]
      captured.chunkCount = captured.startReq.chunkCount
      captured.wrappedBlobKey = captured.startReq.wrappedBlobKey
      const presignedPuts = Array.from({ length: captured.chunkCount }, (_, i) => ({ index: i, url: `mem://${counter}/${i}` }))
      counter++
      return { results: [{ uploadId: 'up-1', presignedPuts, error: null }] }
    }),
    completeMedia: vi.fn(async ({ mediaBatchRequest }: any) => {
      captured.completeReq = mediaBatchRequest.items[0]
      return { results: [{ blobId: 'blob-1', mediaId: 'media-1', error: null }] }
    }),
    listNodeMedia: vi.fn(async () => [
      { mediaId: 'media-1', blobId: 'blob-1', chunkCount: captured.chunkCount, byteSize: 0, wrappedBlobKey: captured.wrappedBlobKey },
    ]),
    presignBlobDownloads: vi.fn(async () => ({
      results: [{ presignedGets: Array.from({ length: captured.chunkCount }, (_, i) => ({ index: i, url: `mem://0/${i}` })), error: null }],
    })),
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

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllGlobals())

describe('addMedia + openMedia', () => {
  it('round-trips bytes through chunked AEAD under a K_blob wrapped by K_mem (operator-blind)', async () => {
    const store = fakeStore()
    vi.mocked(mediaApi).mockReturnValue(store.api as unknown as ReturnType<typeof mediaApi>)
    vi.stubGlobal('fetch', store.fetchStub)

    const memoryKey = v1.randomBytes(32)
    // 600 KiB ⇒ 3 chunks at CHUNK_SIZE = 256 KiB (deterministic fill; getRandomValues caps at 64 KiB/call)
    const data = new Uint8Array(600 * 1024)
    for (let i = 0; i < data.length; i++) data[i] = (i * 31 + 7) & 0xff

    const { blobId, mediaId } = await addMedia(SCOPE_ID, NODE_ID, memoryKey, data)
    expect(blobId).toBe('blob-1')
    expect(mediaId).toBe('media-1')

    // control plane: declared 3 chunks; complete attached to the node
    expect(store.captured.chunkCount).toBe(Math.ceil(data.length / CHUNK_SIZE))
    expect(store.captured.completeReq).toEqual({ uploadId: 'up-1', nodeId: NODE_ID })

    // operator-blind: the stored objects are ciphertext (each frame = nonce+ct+tag, never the plaintext chunk)
    const stored = [...store.objects.values()]
    expect(stored).toHaveLength(store.captured.chunkCount)
    expect(stored[0]).not.toEqual(data.subarray(0, CHUNK_SIZE))
    expect(stored[0].length).toBe(CHUNK_SIZE + 12 + 16)

    // the wrap opens back to a real 32-byte K_blob under K_mem with the 'blob-key' context
    const blobKey = v1.symUnwrap(memoryKey, fromB64std(store.captured.wrappedBlobKey), 'blob-key')
    expect(blobKey.length).toBe(32)

    // read-back round-trips to the original plaintext
    const [item] = await listMedia(SCOPE_ID, NODE_ID)
    const opened = await openMedia(SCOPE_ID, memoryKey, item as MediaItem)
    expect(Array.from(opened)).toEqual(Array.from(data))
  })

  it('throws store_unavailable when the store cannot presign (no transport)', async () => {
    vi.mocked(mediaApi).mockReturnValue({
      startBlobUploads: vi.fn(async () => ({ results: [{ uploadId: 'up-1', presignedPuts: null, error: null }] })),
    } as unknown as ReturnType<typeof mediaApi>)

    await expect(addMedia(SCOPE_ID, NODE_ID, v1.randomBytes(32), v1.randomBytes(10))).rejects.toThrow('store_unavailable')
  })
})
