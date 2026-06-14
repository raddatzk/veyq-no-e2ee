// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { openPersonalScope, getOrCreateContentKey } from '../../content/scopeContent'
import { createNode } from '../../memory/createTree/memoryTree'
import * as v1 from '../../../shared/crypto/v1'
import { chunkBytes, createUpload, putChunk, getProgress, resumeUpload, downloadResumable } from './resumableUpload'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('resumable-upload E2E (web -> live backend): interrupt + resume a chunked media', () => {
  it('uploads some chunks, resumes the rest, finalizes, and downloads byte-identical', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', pass)
    const a = await login(apiBase, 'admin@example.com', pass, emergencyKit.secretKey)
    const scope = await openPersonalScope(apiBase, a)
    const ck = await getOrCreateContentKey(apiBase, a, scope)
    const memory = await createNode(apiBase, a, scope.scopeId, ck, { type: 'MEMORY', name: 'Italien 2026', slug: 'italien-2026', orderIndex: 0 })

    // a 35-byte "video" split into 10-byte chunks → 4 chunks
    const video = Uint8Array.from({ length: 35 }, (_, i) => (i * 7 + 3) & 0xff)
    const chunks = chunkBytes(video, 10)
    expect(chunks.length).toBe(4)

    const sess = await createUpload(apiBase, a, scope.scopeId, memory.id, { totalChunks: chunks.length, mimeType: 'video/mp4', byteSize: video.length, takenAt: '2026-01-01T10:00:00Z' })

    // upload only the first two chunks, then "the connection drops"
    await putChunk(apiBase, a, sess.uploadId, 0, chunks.length, ck, chunks[0])
    await putChunk(apiBase, a, sess.uploadId, 1, chunks.length, ck, chunks[1])
    const mid = await getProgress(apiBase, a, sess.uploadId)
    expect(mid.status).toBe('OPEN')
    expect(mid.receivedChunks.sort()).toEqual([0, 1])

    // resume: sends only the missing chunks (2, 3), then finalizes
    const done = await resumeUpload(apiBase, a, sess.uploadId, ck, chunks)
    expect(done.status).toBe('COMPLETED')

    // the finalized media downloads + decrypts frame-by-frame, byte-identical to the original
    const recovered = await downloadResumable(apiBase, a, sess.uploadId, ck, chunks.length)
    expect(Array.from(recovered)).toEqual(Array.from(video))

    // anti-reorder: a frame sealed at index 0 must NOT open at index 1 (the AAD binds the position)
    const frame0 = v1.sealFrame(ck, 0, chunks.length, chunks[0], v1.randomBytes(12))
    expect(() => v1.openFrame(ck, 1, chunks.length, frame0)).toThrow()
  })
})
