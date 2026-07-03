import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  contentApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))

import {
  normalizeCoverCrop,
  normalizeMemoryCover,
  unsealMemoryCover,
  setMemoryCover,
  sameMemoryCover,
  coverImageStyle,
  FULL_FRAME,
  type MemoryCover,
} from './memoryCover'
import { contentApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const nodeKey = v1.randomBytes(32)

/** Seal an arbitrary node-meta blob under `nodeKey` as the RMW envelope would (AAD `node-meta`). */
function sealMeta(blob: object): string {
  return b64std(v1.symWrap(nodeKey, new TextEncoder().encode(JSON.stringify(blob)), 'node-meta', v1.randomBytes(12)))
}

beforeEach(() => vi.clearAllMocks())

describe('normalizeCoverCrop', () => {
  it('passes a valid on-image crop through unchanged', () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.3 }
    expect(normalizeCoverCrop(crop)).toEqual(crop)
  })

  it('falls back to the full frame for a malformed / absent crop (E4, never a throw)', () => {
    expect(normalizeCoverCrop(null)).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop('nope')).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop({})).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop({ x: NaN, y: 0.2, width: 0, height: -1 })).toEqual(FULL_FRAME)
  })

  it('clamps the extent to (0..1] and keeps the rect on-image (x ∈ [0, 1-width])', () => {
    // width>1 clamps to 1 (⇒ x pinned to 0); an over-far x is pulled back so x+width ≤ 1
    expect(normalizeCoverCrop({ x: 0.9, y: 0.9, width: 2, height: 2 })).toEqual(FULL_FRAME)
    expect(normalizeCoverCrop({ x: 0.9, y: 0.5, width: 0.5, height: 0.5 })).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 })
    // a negative origin clamps up to 0
    expect(normalizeCoverCrop({ x: -0.3, y: -0.1, width: 0.4, height: 0.4 })).toEqual({ x: 0, y: 0, width: 0.4, height: 0.4 })
  })
})

describe('normalizeMemoryCover', () => {
  it('requires a mediaId and normalizes the crop', () => {
    expect(normalizeMemoryCover({ mediaId: 'm1', crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 } })).toEqual({
      mediaId: 'm1',
      crop: { x: 0.1, y: 0.1, width: 0.5, height: 0.5 },
    })
    // missing/blank crop → full frame
    expect(normalizeMemoryCover({ mediaId: 'm1' })).toEqual({ mediaId: 'm1', crop: FULL_FRAME })
  })

  it('returns null for the "no cover" shapes', () => {
    expect(normalizeMemoryCover(null)).toBeNull()
    expect(normalizeMemoryCover({})).toBeNull()
    expect(normalizeMemoryCover({ mediaId: '' })).toBeNull()
    expect(normalizeMemoryCover({ mediaId: 42 })).toBeNull()
  })
})

describe('unsealMemoryCover', () => {
  it('round-trips a cover sealed under K_mem', () => {
    const cover: MemoryCover = { mediaId: 'm-7', crop: { x: 0.2, y: 0.1, width: 0.6, height: 0.34 } }
    const sealed = sealMeta({ v: 1, cover })
    expect(unsealMemoryCover(nodeKey, { wrappedMeta: sealed })).toEqual(cover)
  })

  it('is best-effort null for absent / wrong-key / malformed meta (E3/E4)', () => {
    expect(unsealMemoryCover(nodeKey, { wrappedMeta: null })).toBeNull()
    const sealed = sealMeta({ v: 1, cover: { mediaId: 'm-7', crop: FULL_FRAME } })
    expect(unsealMemoryCover(v1.randomBytes(32), { wrappedMeta: sealed })).toBeNull() // wrong key
    expect(unsealMemoryCover(nodeKey, { wrappedMeta: 'not-base64!!' })).toBeNull()
    expect(unsealMemoryCover(nodeKey, { wrappedMeta: sealMeta({ v: 1 }) })).toBeNull() // no cover sub-object
  })

  it('is operator-blind — the sealed blob carries no plaintext mediaId', () => {
    const sealed = sealMeta({ v: 1, cover: { mediaId: 'secret-media-id', crop: FULL_FRAME } })
    expect(new TextDecoder().decode(fromB64std(sealed))).not.toContain('secret-media-id')
  })
})

describe('setMemoryCover', () => {
  it('re-seals under K_mem and PATCHes wrappedMeta via updateNode', async () => {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)

    const cover: MemoryCover = { mediaId: 'm-3', crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.28 } }
    const sealed = await setMemoryCover(SCOPE_ID, 'node-9', nodeKey, cover, null)

    const req = updateNode.mock.calls[0][0]
    expect(req.scopeId).toBe(SCOPE_ID)
    expect(req.nodeId).toBe('node-9')
    expect(req.updateNodeRequest.wrappedMeta).toBe(sealed)
    expect(unsealMemoryCover(nodeKey, { wrappedMeta: sealed })).toEqual(cover)
  })

  it('read-modify-write: setting a cover preserves a sibling date + location', async () => {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)

    const date = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }
    const location = { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed' }
    const withSiblings = sealMeta({ v: 1, date, location })

    const cover: MemoryCover = { mediaId: 'm-3', crop: FULL_FRAME }
    const sealed = await setMemoryCover(SCOPE_ID, 'node-9', nodeKey, cover, withSiblings)

    const blob = JSON.parse(new TextDecoder().decode(v1.symUnwrap(nodeKey, fromB64std(sealed), 'node-meta')))
    expect(blob.cover).toEqual(cover)
    expect(blob.date).toEqual(date)
    expect(blob.location).toEqual(location)
  })

  it('read-modify-write: removing a cover (null) drops it but keeps the date + location', async () => {
    const updateNode = vi.fn().mockResolvedValue(undefined)
    vi.mocked(contentApi).mockReturnValue({ updateNode } as unknown as ReturnType<typeof contentApi>)

    const date = { mode: 'single', from: '2026-05-01', to: '2026-05-01' }
    const location = { name: 'Teneriffa', nameSource: 'manual', derivedMode: 'fixed' }
    const withCover = sealMeta({ v: 1, date, location, cover: { mediaId: 'm-3', crop: FULL_FRAME } })

    const sealed = await setMemoryCover(SCOPE_ID, 'node-9', nodeKey, null, withCover)

    const blob = JSON.parse(new TextDecoder().decode(v1.symUnwrap(nodeKey, fromB64std(sealed), 'node-meta')))
    expect(blob.cover).toBeUndefined()
    expect(unsealMemoryCover(nodeKey, { wrappedMeta: sealed })).toBeNull()
    expect(blob.date).toEqual(date)
    expect(blob.location).toEqual(location)
  })
})

describe('sameMemoryCover', () => {
  it('compares mediaId + all four crop fields', () => {
    const a: MemoryCover = { mediaId: 'm1', crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.3 } }
    expect(sameMemoryCover(a, { mediaId: 'm1', crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.3 } })).toBe(true)
    expect(sameMemoryCover(a, { mediaId: 'm2', crop: a.crop })).toBe(false)
    expect(sameMemoryCover(a, { mediaId: 'm1', crop: { ...a.crop, x: 0.15 } })).toBe(false)
    expect(sameMemoryCover(null, null)).toBe(true)
    expect(sameMemoryCover(a, null)).toBe(false)
  })
})

describe('coverImageStyle', () => {
  it('no crop ⇒ a plain full-frame object-fit cover', () => {
    expect(coverImageStyle(null)).toEqual({ width: '100%', height: '100%', left: '0', top: '0', objectFit: 'cover' })
  })

  it('the full frame ⇒ 100% at the origin (object-fit fill)', () => {
    expect(coverImageStyle(FULL_FRAME)).toEqual({ width: '100%', height: '100%', left: '0%', top: '0%', objectFit: 'fill' })
  })

  it('a real crop ⇒ the inverse-scale + offset formula', () => {
    // show the 0.8×0.6 region at (0.1, 0.2): image is 125%×166.6…%, shifted by -12.5%×-33.3…%
    expect(coverImageStyle({ x: 0.1, y: 0.2, width: 0.8, height: 0.6 })).toEqual({
      width: `${100 / 0.8}%`,
      height: `${100 / 0.6}%`,
      left: `${(-0.1 / 0.8) * 100}%`,
      top: `${(-0.2 / 0.6) * 100}%`,
      objectFit: 'fill',
    })
  })
})
