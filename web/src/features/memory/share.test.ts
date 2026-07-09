// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  sharesApi: vi.fn(),
  publicSharesApi: vi.fn(),
  tokenApi: vi.fn(),
}))

import { createShare, resolveShare, createContributorIdentity, deleteSharePhoto, listSharesFor, reactivateShare, ShareUnavailableError } from './share'
import { unsealMeta } from './media'
import { sharesApi, publicSharesApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'
const CHAPTER_ID = '2a9b1d4a-6e8b-2c1f-2d3e-4a5b6c7c3f0e'

/** Seal a media's listed-metadata tier under a K_mem exactly as the media layer does (AAD `media-meta`). */
function sealMeta(key: Uint8Array, meta: object): string {
  return b64std(v1.symWrap(key, v1.utf8(JSON.stringify(meta)), 'media-meta', v1.randomBytes(12)))
}

/** Extract the base64url fragment key from a minted `/s/{id}#k=<key>` link. */
function keyFromLink(link: string): string {
  return /#k=([^&]+)/.exec(link)![1]
}

function mockCurator(createNode = vi.fn().mockResolvedValue({ shareId: 'share-1' })) {
  vi.mocked(sharesApi).mockReturnValue({ createShare: createNode } as unknown as ReturnType<typeof sharesApi>)
}
function mockGuest(resolve: unknown) {
  vi.mocked(publicSharesApi).mockReturnValue({
    resolveShare: vi.fn().mockResolvedValue(resolve),
  } as unknown as ReturnType<typeof publicSharesApi>)
}

beforeEach(() => vi.clearAllMocks())

describe('createShare (curator)', () => {
  it('wraps K_mem under a fresh K_share, seals the name, and returns a fragment-key link (operator-blind)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)

    const minted = await createShare(NODE_ID, kMem, 'Rom 2026')

    expect(minted.shareId).toBe('share-1')
    // the link carries the share id in the path and K_share ONLY in the #fragment
    expect(minted.link).toContain('/s/share-1#k=')
    const req = create.mock.calls[0][0].createShareRequest
    expect(req.nodeId).toBe(NODE_ID)
    expect(req.role).toBe('VIEWER')
    // the request carries opaque ciphertext only — no plaintext name, no key
    expect(req.wrap).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(req.sealedName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(JSON.stringify(req)).not.toContain('Rom 2026')
  })
})

describe('resolveShare (anonymous viewer)', () => {
  it('unwraps K_mem from the fragment key, decrypts the name, and keys each media to the right K_mem', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-1' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(NODE_ID, kMem, 'Rom 2026')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest

    // the media's listed metadata is sealed under the Memory's K_mem
    const wrappedMeta = sealMeta(kMem, { v: 1, w: 1600, h: 1200 })
    mockGuest({
      role: 'VIEWER',
      wrap,
      sealedName,
      nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY' }],
      media: [{ nodeId: NODE_ID, mediaId: 'm1', blobId: 'b1', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta }],
    })

    const resolved = await resolveShare('share-1', keyFromLink(minted.link))

    expect(resolved.memoryName).toBe('Rom 2026') // decrypted from sealedName with the fragment key
    expect(resolved.items).toHaveLength(1)
    // the viewer derived the correct K_mem — proof: the media's meta unseals to its geometry
    const meta = unsealMeta(resolved.items[0].memoryKey, resolved.items[0].item)
    expect(meta).toMatchObject({ w: 1600, h: 1200 })
  })

  it('derives a chapter K_mem down the subtree so a chapter media decrypts too (recursive K_mem)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-2' })
    mockCurator(create)
    const kMem = v1.randomBytes(32)
    const minted = await createShare(NODE_ID, kMem, 'Trip')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest

    // a chapter's K_mem is wrapped under the Memory's K_mem (AAD `content-key`)
    const chapterKey = v1.randomBytes(32)
    const wrappedContentKey = b64std(v1.symWrap(kMem, chapterKey, 'content-key', v1.randomBytes(12)))
    const chapterMeta = sealMeta(chapterKey, { v: 1, w: 800, h: 600 })
    mockGuest({
      role: 'VIEWER',
      wrap,
      sealedName,
      nodes: [
        { id: NODE_ID, parentId: null, kind: 'MEMORY' },
        { id: CHAPTER_ID, parentId: NODE_ID, kind: 'CHAPTER', wrappedContentKey },
      ],
      media: [{ nodeId: CHAPTER_ID, mediaId: 'm2', blobId: 'b2', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: chapterMeta }],
    })

    const resolved = await resolveShare('share-2', keyFromLink(minted.link))
    expect(resolved.items).toHaveLength(1)
    // the chapter media's meta unseals only if the viewer walked wrappedContentKey → the chapter's K_mem
    expect(unsealMeta(resolved.items[0].memoryKey, resolved.items[0].item)).toMatchObject({ w: 800, h: 600 })
  })

  it('rejects a wrong fragment key — the id alone is inert (E1)', async () => {
    const create = vi.fn().mockResolvedValue({ shareId: 'share-3' })
    mockCurator(create)
    const minted = await createShare(NODE_ID, v1.randomBytes(32), 'Rom')
    const { wrap, sealedName } = create.mock.calls[0][0].createShareRequest
    mockGuest({ role: 'VIEWER', wrap, sealedName, nodes: [{ id: NODE_ID, parentId: null, kind: 'MEMORY' }], media: [] })

    // a DIFFERENT (well-formed) 32-byte key can't open the wrap
    const wrongKey = 'A'.repeat(43) // 32 zero-ish bytes worth of base64url
    await expect(resolveShare('share-3', wrongKey)).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('maps a 404 (revoked/expired/unknown) to ShareUnavailableError', async () => {
    vi.mocked(publicSharesApi).mockReturnValue({
      resolveShare: vi.fn().mockRejectedValue(new Error('404')),
    } as unknown as ReturnType<typeof publicSharesApi>)
    await expect(resolveShare('nope', 'A'.repeat(43))).rejects.toBeInstanceOf(ShareUnavailableError)
  })
})

describe('createContributorIdentity (cr-0101)', () => {
  it('seals the display name under K_mem (operator-blind) and returns the anonymous session', async () => {
    const create = vi.fn().mockResolvedValue({ identityId: 'id-1', contributorToken: 'tok-1' })
    vi.mocked(publicSharesApi).mockReturnValue({ createShareIdentity: create } as unknown as ReturnType<typeof publicSharesApi>)
    const kMem = v1.randomBytes(32)

    const session = await createContributorIdentity('share-1', kMem, 'Oma')

    expect(session).toEqual({ identityId: 'id-1', contributorToken: 'tok-1' })
    const req = create.mock.calls[0][0].createShareIdentityRequest
    // the name crosses the wire only as opaque ciphertext — never in clear
    expect(req.sealedDisplayName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(req.sealedDisplayName).not.toContain('Oma')
    // and it unwraps back under K_mem (proves the seal is correct + operator-blind)
    const opened = new TextDecoder().decode(v1.symUnwrap(kMem, fromB64std(req.sealedDisplayName), 'share-identity-name'))
    expect(opened).toBe('Oma')
  })
})

describe('shared-scope management (cr-0104)', () => {
  it('listSharesFor scopes by nodeId server-side and threads each row’s `mine` flag', async () => {
    const list = vi.fn().mockResolvedValue([
      { shareId: 's1', nodeId: NODE_ID, role: 'VIEWER', sealedName: 'x', enabled: true, mine: true, createdAt: '2026-07-07T12:00:00Z', expiresAt: null },
      { shareId: 's2', nodeId: NODE_ID, role: 'VIEWER', sealedName: 'x', enabled: false, mine: false, createdAt: '2026-07-06T12:00:00Z', expiresAt: null },
    ])
    vi.mocked(sharesApi).mockReturnValue({ listShares: list } as unknown as ReturnType<typeof sharesApi>)

    const rows = await listSharesFor(NODE_ID)

    expect(list).toHaveBeenCalledWith({ nodeId: NODE_ID }) // server-scoped to the Memory, not a client filter
    // co-members' shares come back too (mine=false), disabled ones included — newest first
    expect(rows.map((r) => [r.shareId, r.mine, r.enabled])).toEqual([
      ['s1', true, true],
      ['s2', false, false],
    ])
  })

  it('reactivateShare adopts a disabled share via the member-gated endpoint', async () => {
    const reactivate = vi.fn().mockResolvedValue(undefined)
    // NB: wire the mock to the local `reactivate` fn — a bare `{ reactivateShare }` shorthand would resolve to the
    // imported module function under test, so `sharesApi().reactivateShare` would recurse into it (stack overflow).
    vi.mocked(sharesApi).mockReturnValue({ reactivateShare: reactivate } as unknown as ReturnType<typeof sharesApi>)

    await reactivateShare('share-9')

    expect(reactivate).toHaveBeenCalledWith({ shareId: 'share-9' })
  })
})

describe('deleteSharePhoto (cr-0102 uploader delete-own)', () => {
  it('calls the token-gated delete endpoint with the contributor token in the header', async () => {
    const del = vi.fn().mockResolvedValue(undefined)
    vi.mocked(publicSharesApi).mockReturnValue({ deleteShareMedia: del } as unknown as ReturnType<typeof publicSharesApi>)

    await deleteSharePhoto('share-1', 'tok-1', 'media-9')

    expect(del).toHaveBeenCalledWith({ shareId: 'share-1', mediaId: 'media-9', xContributorToken: 'tok-1' })
  })
})
