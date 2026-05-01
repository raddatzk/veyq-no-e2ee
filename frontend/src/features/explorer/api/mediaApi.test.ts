import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamExplorerMediaMetadata } from './mediaApi'
import type { ExplorerMedia } from './types'

function media(id: string): ExplorerMedia {
  return {
    id,
    title: id,
    albumPath: '/album',
    ownerUserId: 1,
    uploaderDisplayName: 'Kevin',
    createdAt: '2026-01-01T00:00:00Z',
    capturedAt: null,
    capturedAtOverride: false,
    mimeType: 'image/jpeg',
    sizeBytes: 1000,
    contentUrl: `/api/media/${id}/content`,
    cameraMake: null,
    cameraModel: null,
    lensModel: null,
    imageWidth: 1000,
    imageHeight: 1000,
    gpsLatitude: null,
    gpsLongitude: null
  }
}

function ndjson(items: ExplorerMedia[]) {
  return `${items.map((item) => JSON.stringify(item)).join('\n')}\n`
}

describe('mediaApi metadata streaming', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('streams media metadata from NDJSON responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(ndjson([media('a'), media('b')]), { status: 200 }))
    )
    const batches: ExplorerMedia[][] = []

    await streamExplorerMediaMetadata('tenant', '/album', (items) => batches.push(items), {
      batchSize: 10
    })

    expect(batches).toHaveLength(1)
    expect(batches[0].map((item) => item.id)).toEqual(['a', 'b'])
    expect(fetch).toHaveBeenCalledWith(
      '/api/tenant/media?path=%2Falbum',
      expect.objectContaining({
        credentials: 'include',
        headers: expect.objectContaining({ Accept: 'application/x-ndjson' })
      })
    )
  })

  it('publishes metadata in configured batches and flushes the final remainder', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(ndjson([media('a'), media('b'), media('c')]), { status: 200 }))
    )
    const batches: ExplorerMedia[][] = []

    await streamExplorerMediaMetadata('tenant', '/album', (items) => batches.push(items), {
      batchSize: 2
    })

    expect(batches.map((batch) => batch.map((item) => item.id))).toEqual([['a', 'b'], ['c']])
  })

  it('does not publish stale metadata after the stream was aborted', async () => {
    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(ndjson([media('a')]), { status: 200 }))
    )
    const onBatch = vi.fn()

    await streamExplorerMediaMetadata('tenant', '/album', onBatch, {
      signal: controller.signal
    })

    expect(onBatch).not.toHaveBeenCalled()
  })
})
