import { describe, it, expect, vi } from 'vitest'

// pinCover pulls in media, which imports the generated API client — stub it (no network in a pure selection test).
vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
  mediaApi: vi.fn(),
}))

import { pickCoverMedia } from './pinCover'
import type { MediaItem } from './media'
import * as v1 from '../../shared/crypto/v1'
import { b64std } from '../../shared/base64'

const nodeKey = v1.randomBytes(32)

/** A MediaItem with an optional THUMBNAIL derivative and optional sealed GPS meta (mirrors media.ts's seal). */
function item(id: string, opts: { derivable?: boolean; gps?: { lat: number; lng: number } } = {}): MediaItem {
  const it: MediaItem = {
    mediaId: id,
    original: { blobId: 'o', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k' },
    derivatives: opts.derivable === false ? [] : [{ kind: 'THUMBNAIL', ref: { blobId: 't', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k' } }],
  }
  if (opts.gps) {
    const bytes = new TextEncoder().encode(JSON.stringify({ v: 1, w: 100, h: 100, gpsLat: opts.gps.lat, gpsLng: opts.gps.lng }))
    it.wrappedMeta = b64std(v1.symWrap(nodeKey, bytes, 'media-meta', v1.randomBytes(12)))
  }
  return it
}

const id = (m: MediaItem | null) => m?.mediaId ?? null

describe('pickCoverMedia', () => {
  it('returns null when no item is derivable', () => {
    expect(pickCoverMedia([item('a', { derivable: false })], nodeKey)).toBeNull()
    expect(pickCoverMedia([], nodeKey)).toBeNull()
  })

  it('without a target, returns the first derivable item', () => {
    expect(id(pickCoverMedia([item('a', { derivable: false }), item('b'), item('c')], nodeKey))).toBe('b')
  })

  it('with a target, returns the geotagged item nearest to it', () => {
    const near = { lat: 50, lng: 7 }
    const items = [
      item('far', { gps: { lat: 10, lng: 10 } }),
      item('close', { gps: { lat: 50.01, lng: 7.01 } }),
      item('mid', { gps: { lat: 52, lng: 9 } }),
    ]
    expect(id(pickCoverMedia(items, nodeKey, near))).toBe('close')
  })

  it('with a target but no geotagged media, falls back to the first derivable item', () => {
    const items = [item('a'), item('b')] // no GPS meta on either
    expect(id(pickCoverMedia(items, nodeKey, { lat: 50, lng: 7 }))).toBe('a')
  })
})
