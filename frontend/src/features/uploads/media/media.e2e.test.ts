// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { openPersonalScope, getOrCreateContentKey } from '../../content/scopeContent'
import { createNode } from '../../memory/createTree/memoryTree'
import * as v1 from '../../../shared/crypto/v1'
import { chunkBytes, startBlob, putChunk, getProgress, uploadBlob, downloadBlob, uploadMedia, listMedia } from './media'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('unified media E2E (web -> live backend): one upload/download path for small + large + thumbnail', () => {
  it('uploads a 1-chunk photo, a multi-chunk video, dedups, resumes, and lists a gallery', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', pass)
    const a = await login(apiBase, 'admin@example.com', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, a)
    const ck = await getOrCreateContentKey(apiBase, a, scope)
    const memory = await createNode(apiBase, a, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0 })

    // a small photo (fits one frame) + its thumbnail, both through the SAME upload path
    const photo = Uint8Array.from({ length: 200 }, (_, i) => (i * 13 + 1) & 0xff)
    const thumb = Uint8Array.from({ length: 40 }, (_, i) => (i * 5 + 2) & 0xff)
    const m1 = await uploadMedia(apiBase, a, scope.scopeId, ck, memory.id, { bytes: photo, thumb, takenAt: '2026-03-15T12:00:00Z', mimeType: 'image/jpeg' })
    expect(m1.original.totalChunks).toBe(1)
    expect(m1.derivatives.map((d) => d.kind)).toEqual(['THUMBNAIL'])

    // a large "video" split into many frames — same uploadMedia call, chunked transparently
    const chunkSize = 10
    const video = Uint8Array.from({ length: 35 }, (_, i) => (i * 7 + 3) & 0xff)
    const m2 = await uploadMedia(apiBase, a, scope.scopeId, ck, memory.id, { bytes: video, takenAt: '2026-01-01T08:00:00Z', mimeType: 'video/mp4' }, chunkSize)
    expect(m2.original.totalChunks).toBe(4)
    expect(m2.derivatives.length).toBe(0)

    // both download byte-identical through the SAME frame path (small + large alike)
    expect(Array.from(await downloadBlob(apiBase, a, scope.scopeId, ck, m1.original))).toEqual(Array.from(photo))
    expect(Array.from(await downloadBlob(apiBase, a, scope.scopeId, ck, m2.original))).toEqual(Array.from(video))

    // dedup: re-uploading identical bytes skips the frames and returns the existing blob (READY)
    const ticket = await startBlob(apiBase, a, scope.scopeId, { dedupToken: v1Dedup(ck, photo), totalChunks: 1, byteSize: photo.length })
    expect(ticket.status).toBe('READY')
    expect(ticket.blobId).toBe(m1.original.blobId)

    // resume: open a fresh upload, send only chunk 0, then uploadBlob fills the gap and finalizes
    const partial = Uint8Array.from({ length: 25 }, (_, i) => (i * 3 + 9) & 0xff)
    const pchunks = chunkBytes(partial, chunkSize)
    expect(pchunks.length).toBe(3)
    const t = await startBlob(apiBase, a, scope.scopeId, { dedupToken: v1Dedup(ck, partial), totalChunks: pchunks.length, byteSize: partial.length })
    await putChunk(apiBase, a, scope.scopeId, t.uploadId!, 0, pchunks.length, ck, pchunks[0])
    expect((await getProgress(apiBase, a, scope.scopeId, t.uploadId!)).receivedChunks.sort()).toEqual([0])
    const resumed = await uploadBlob(apiBase, a, scope.scopeId, ck, partial, chunkSize) // resumes the same content
    expect(Array.from(await downloadBlob(apiBase, a, scope.scopeId, ck, resumed))).toEqual(Array.from(partial))

    // the gallery lists both media in upload order (photo then video; the client sorts by decrypted takenAt)
    const gallery = await listMedia(apiBase, a, scope.scopeId, memory.id)
    expect(gallery.map((g) => g.id)).toEqual([m1.id, m2.id])
    expect(gallery[0].derivatives[0].kind).toBe('THUMBNAIL')

    // anti-reorder still holds: a frame sealed at index 0 must NOT open at index 1 (AAD binds position)
    const frame0 = v1.sealFrame(ck, 0, pchunks.length, pchunks[0], v1.randomBytes(12))
    expect(() => v1.openFrame(ck, 1, pchunks.length, frame0)).toThrow()

    // media tiers: a photo uploaded with BOTH a thumbnail (grid) and a preview (lightbox) stores three
    // distinct blobs; the full-res original is only ever pulled on an explicit download.
    const photo3 = Uint8Array.from({ length: 150 }, (_, i) => (i * 17 + 4) & 0xff)
    const thumb3 = Uint8Array.from({ length: 30 }, (_, i) => (i * 3 + 1) & 0xff)
    const preview3 = Uint8Array.from({ length: 80 }, (_, i) => (i * 9 + 6) & 0xff)
    const m3 = await uploadMedia(apiBase, a, scope.scopeId, ck, memory.id, { bytes: photo3, thumb: thumb3, preview: preview3, takenAt: '2026-02-01T09:00:00Z', mimeType: 'image/jpeg' })
    expect(m3.derivatives.map((d) => d.kind).sort()).toEqual(['PREVIEW', 'THUMBNAIL'])
    const previewRef = m3.derivatives.find((d) => d.kind === 'PREVIEW')!.blob
    const thumbRef = m3.derivatives.find((d) => d.kind === 'THUMBNAIL')!.blob
    expect(new Set([m3.original.blobId, thumbRef.blobId, previewRef.blobId]).size).toBe(3) // three distinct blobs
    expect(Array.from(await downloadBlob(apiBase, a, scope.scopeId, ck, previewRef))).toEqual(Array.from(preview3))
    expect(Array.from(await downloadBlob(apiBase, a, scope.scopeId, ck, thumbRef))).toEqual(Array.from(thumb3))
  })
})

function v1Dedup(ck: Uint8Array, content: Uint8Array): string {
  const t = v1.dedupToken(ck, content)
  let s = ''
  for (let i = 0; i < t.length; i++) s += String.fromCharCode(t[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
