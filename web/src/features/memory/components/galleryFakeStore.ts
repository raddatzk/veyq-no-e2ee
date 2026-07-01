// Test kit for the gallery component tests (cr-0049). A tiny in-process object store that — unlike the
// single-media fake in media.test.ts — **accumulates** several media per node, so MemoryDetail/MediaLightbox
// can list a real multi-item grid and round-trip each tier (original + derivatives) through a real v1
// seal/open under a K_blob wrapped by K_mem. Operator-blind: the "store" only ever holds ciphertext.
// Not a test file (no test cases); imported by the *.test.ts that mount the components.
import { vi } from 'vitest'

type Session = { wrappedBlobKey: string; chunkCount: number }
type MediaRecord = {
  mediaId: string
  originalUploadId: string
  derivatives: { kind: string; uploadId: string }[]
  wrappedExif: string | null
}

export function galleryFakeStore() {
  const objects = new Map<string, Uint8Array>()
  const sessions = new Map<string, Session>()
  const media: MediaRecord[] = []
  let upCounter = 0
  let mediaCounter = 0

  const blobOf = (uploadId: string) => {
    const s = sessions.get(uploadId)!
    return { blobId: uploadId, chunkCount: s.chunkCount, byteSize: 0, wrappedBlobKey: s.wrappedBlobKey }
  }
  const getsFor = (uploadId: string) =>
    Array.from({ length: sessions.get(uploadId)!.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` }))

  const api = {
    startBlobUploads: vi.fn(async ({ blobUploadsBatchRequest }: any) => ({
      results: blobUploadsBatchRequest.items.map((item: any) => {
        const uploadId = `up-${upCounter++}`
        sessions.set(uploadId, { wrappedBlobKey: item.wrappedBlobKey, chunkCount: item.chunkCount })
        return {
          uploadId,
          presignedPuts: Array.from({ length: item.chunkCount }, (_, i) => ({ index: i, url: `mem://${uploadId}/${i}` })),
          error: null,
        }
      }),
    })),
    completeMedia: vi.fn(async ({ mediaBatchRequest }: any) => ({
      results: mediaBatchRequest.items.map((item: any) => {
        const mediaId = `media-${mediaCounter++}`
        media.push({
          mediaId,
          originalUploadId: item.originalUploadId,
          derivatives: item.derivatives ?? [],
          wrappedExif: item.wrappedExif ?? null,
        })
        return { blobId: `blob-${item.originalUploadId}`, mediaId, error: null }
      }),
    })),
    listNodeMedia: vi.fn(async () =>
      media.map((m) => ({
        mediaId: m.mediaId,
        ...blobOf(m.originalUploadId),
        derivatives: m.derivatives.map((d) => ({ kind: d.kind, ...blobOf(d.uploadId) })),
        // wrappedExif is deliberately NOT listed — it is lazy via getMediaMeta (cr-0064)
      })),
    ),
    // Tier-2 EXIF, echoed only through the meta endpoint (kept out of the list) — operator-blind ciphertext.
    getMediaMeta: vi.fn(async ({ mediaId }: any) => ({
      wrappedExif: media.find((m) => m.mediaId === mediaId)?.wrappedExif ?? null,
    })),
    // Overwrite a media's sealed EXIF verbatim (cr-0065) — e.g. a corrected capture time.
    updateMediaMeta: vi.fn(async ({ mediaId, mediaMetaUpdate }: any) => {
      const rec = media.find((m) => m.mediaId === mediaId)
      if (rec) rec.wrappedExif = mediaMetaUpdate.wrappedExif ?? null
      return { wrappedExif: mediaMetaUpdate.wrappedExif ?? null }
    }),
    presignBlobDownloads: vi.fn(async ({ blobDownloadsBatchRequest }: any) => ({
      results: blobDownloadsBatchRequest.items.map((it: any) => ({ presignedGets: getsFor(it.blobId), error: null })),
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

  return { api, fetchStub, objects, media }
}
