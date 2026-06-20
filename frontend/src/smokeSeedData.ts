import { deflateSync } from 'node:zlib'
import type { UnlockedSession } from './features/account/login/login'
import { getOrCreateContentKey, openPersonalScope } from './features/content/scopeContent'
import { createNode } from './features/memory/createTree/memoryTree'
import { saveJournalMarkdown } from './features/memory/journalLive/journalPersistence'
import { createPlaylist } from './features/memory/playlist/playlist'
import { createTrack } from './features/memory/track/track'
import { uploadMedia } from './features/uploads/media/media'
import type { ActiveSpace } from './features/home/home'

export const smokeMemoryName = 'Italien 2026'

const CRC = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return (buf: Uint8Array) => {
    let c = 0xffffffff
    for (let i = 0; i < buf.length; i++) c = t[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
    return (c ^ 0xffffffff) >>> 0
  }
})()

function chunk(type: string, data: Uint8Array): Uint8Array {
  const typeBytes = new TextEncoder().encode(type)
  const body = new Uint8Array(typeBytes.length + data.length)
  body.set(typeBytes); body.set(data, typeBytes.length)
  const out = new Uint8Array(8 + data.length + 4)
  const dv = new DataView(out.buffer)
  dv.setUint32(0, data.length)
  out.set(body, 4)
  dv.setUint32(4 + body.length, CRC(body))
  return out
}

/** A minimal solid-color RGB PNG of the given size. */
function makePng(w: number, h: number, rgb: [number, number, number]): Uint8Array {
  const raw = new Uint8Array(h * (1 + w * 3))
  let o = 0
  for (let y = 0; y < h; y++) {
    raw[o++] = 0
    for (let x = 0; x < w; x++) { raw[o++] = rgb[0]; raw[o++] = rgb[1]; raw[o++] = rgb[2] }
  }
  const ihdr = new Uint8Array(13)
  const dv = new DataView(ihdr.buffer)
  dv.setUint32(0, w); dv.setUint32(4, h)
  ihdr[8] = 8; ihdr[9] = 2
  const idat = new Uint8Array(deflateSync(raw))
  const sig = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])
  const parts = [sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', new Uint8Array(0))]
  const total = parts.reduce((n, p) => n + p.length, 0)
  const png = new Uint8Array(total)
  let p = 0
  for (const part of parts) { png.set(part, p); p += part.length }
  return png
}

export async function seedSmokeContent(apiBase: string, session: UnlockedSession): Promise<{ memory: string }> {
  const scope = await openPersonalScope(apiBase, session)
  const ck = await getOrCreateContentKey(apiBase, session, scope)
  const active = {
    entry: { kind: 'PERSONAL', displayName: 'Personal', scopeId: scope.scopeId, scopePublicKey: { suiteId: 'v1', kemPublicKey: '' } },
    scope,
    contentKey: ck,
  } as ActiveSpace

  await createNode(apiBase, session, scope.scopeId, ck, { type: 'FOLDER', name: 'Urlaube', slug: 'urlaube', orderIndex: 0 })
  const memory = await createNode(apiBase, session, scope.scopeId, ck, { type: 'MEMORY', name: smokeMemoryName, slug: 'italien-2026', orderIndex: 1 })
  await createNode(apiBase, session, scope.scopeId, ck, { type: 'CHAPTER', name: 'Tag 1', slug: 'tag-1', orderIndex: 0, parentId: memory.id })

  const mediaIds: string[] = []
  const dims: [number, number][] = [[800, 600], [600, 800], [800, 800], [800, 640], [640, 800], [800, 600], [720, 800], [800, 560], [800, 600]]
  const colors: [number, number, number][] = [[155, 140, 255], [45, 212, 191], [248, 113, 113], [103, 232, 249], [250, 204, 21], [91, 77, 245]]
  for (let i = 0; i < dims.length; i++) {
    const [w, h] = dims[i]
    const rgb = colors[i % colors.length]
    const media = await uploadMedia(apiBase, session, scope.scopeId, ck, memory.id, {
      bytes: makePng(w, h, rgb),
      thumb: makePng(Math.round(w / 2), Math.round(h / 2), rgb),
      takenAt: new Date(Date.UTC(2026, 0, i + 1)).toISOString(),
      mimeType: 'image/png',
      lat: 45.4408 + i * 0.01,
      lng: 12.3155 + i * 0.01,
      width: w,
      height: h,
    })
    mediaIds.push(media.id)
  }

  await createTrack(apiBase, session, active, memory.id, {
    name: 'Venedig Spaziergang',
    points: [
      { lat: 45.4408, lng: 12.3155, time: '2026-01-01T09:00:00Z' },
      { lat: 45.4441, lng: 12.3267, time: '2026-01-01T09:20:00Z' },
      { lat: 45.4372, lng: 12.3358, time: '2026-01-01T09:45:00Z' },
    ],
  })
  await createPlaylist(apiBase, session, active, memory.id, 'https://open.spotify.com/playlist/37i9dQZF1DX4UtSsGT1Sbe', 'Italien Reise', 'Soundtrack fuer die Memory', true)
  await saveJournalMarkdown(apiBase, session, scope.scopeId, memory.id, ck, `# ${smokeMemoryName}\n\nErster importierter Smoke-Journaltext.\n\n::media:${mediaIds.slice(0, 3).join(',')} Highlights`)

  return { memory: smokeMemoryName }
}
