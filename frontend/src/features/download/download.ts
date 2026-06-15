// uc.memory.download-zip (web) — export a memory's originals as a zip, fully client-side (the server only
// holds ciphertext). Each original blob is downloaded + decrypted, then packed into a store-only zip (the
// media are already compressed). "Mit Kapiteln" walks the memory's CHAPTER descendants too.
import type { UnlockedSession } from '../account/login/login'
import type { ActiveSpace } from '../home/home'
import { listChildren } from '../memory/browse/browse'
import { decodeNode, type MemoryNode } from '../memory/createTree/memoryTree'
import { downloadBlob, listMedia, type MediaItem } from '../uploads/media/media'

type Item = { media: MediaItem; name: string }

const EXT: Record<string, string> = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/gif': 'gif', 'image/webp': 'webp', 'video/mp4': 'mp4', 'video/quicktime': 'mov' }
const extFor = (mime: string) => EXT[mime] ?? 'bin'
const sanitize = (s: string) => s.replace(/[^\p{L}\p{N}\-_. ]/gu, '_').trim() || 'memory'

async function gather(apiBase: string, session: UnlockedSession, active: ActiveSpace, node: MemoryNode, prefix: string, withChapters: boolean, out: Item[]) {
  const media = await listMedia(apiBase, session, active.scope.scopeId, node.id)
  media.forEach((m, i) => out.push({ media: m, name: `${prefix}${String(i + 1).padStart(3, '0')}.${extFor(m.mimeType)}` }))
  if (withChapters) {
    const children = await listChildren(apiBase, session, active.scope.scopeId, node.id)
    for (const c of children.filter((c) => c.type === 'CHAPTER')) {
      await gather(apiBase, session, active, c, `${prefix}${sanitize(decodeNode(active.contentKey, c).name)}/`, true, out)
    }
  }
}

/** How many originals a download would contain (drives the modal's counts). */
export async function countMedia(apiBase: string, session: UnlockedSession, active: ActiveSpace, memoryNode: MemoryNode, withChapters: boolean): Promise<number> {
  const out: Item[] = []
  await gather(apiBase, session, active, memoryNode, '', withChapters, out)
  return out.length
}

/** Download + decrypt every original and pack them into a zip, then trigger a browser download. */
export async function downloadMemoryZip(apiBase: string, session: UnlockedSession, active: ActiveSpace, memoryNode: MemoryNode, memoryName: string, withChapters: boolean): Promise<void> {
  const items: Item[] = []
  await gather(apiBase, session, active, memoryNode, '', withChapters, items)
  const files: { name: string; data: Uint8Array }[] = []
  for (const it of items) {
    files.push({ name: it.name, data: await downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, it.media.original) })
  }
  const zip = zipStore(files)
  const url = URL.createObjectURL(new Blob([zip as BlobPart], { type: 'application/zip' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `${sanitize(memoryName)}.zip`
  a.click()
  URL.revokeObjectURL(url)
}

// --- minimal store-only ZIP writer ------------------------------------------------------------------------
const CRC = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return (b: Uint8Array) => {
    let c = 0xffffffff
    for (let i = 0; i < b.length; i++) c = t[(c ^ b[i]) & 0xff] ^ (c >>> 8)
    return (c ^ 0xffffffff) >>> 0
  }
})()

export function zipStore(files: { name: string; data: Uint8Array }[]): Uint8Array {
  const enc = new TextEncoder()
  const locals: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const f of files) {
    const name = enc.encode(f.name)
    const crc = CRC(f.data)
    const lh = new Uint8Array(30 + name.length)
    const ldv = new DataView(lh.buffer)
    ldv.setUint32(0, 0x04034b50, true)
    ldv.setUint16(4, 20, true) // version needed
    ldv.setUint16(6, 0x0800, true) // flags: UTF-8 names
    ldv.setUint16(8, 0, true) // method: store
    ldv.setUint32(14, crc, true)
    ldv.setUint32(18, f.data.length, true) // comp size
    ldv.setUint32(22, f.data.length, true) // uncomp size
    ldv.setUint16(26, name.length, true)
    lh.set(name, 30)
    locals.push(lh, f.data)

    const ch = new Uint8Array(46 + name.length)
    const cdv = new DataView(ch.buffer)
    cdv.setUint32(0, 0x02014b50, true)
    cdv.setUint16(4, 20, true)
    cdv.setUint16(6, 20, true)
    cdv.setUint16(8, 0x0800, true)
    cdv.setUint16(10, 0, true)
    cdv.setUint32(16, crc, true)
    cdv.setUint32(20, f.data.length, true)
    cdv.setUint32(24, f.data.length, true)
    cdv.setUint16(28, name.length, true)
    cdv.setUint32(42, offset, true) // local header offset
    ch.set(name, 46)
    central.push(ch)

    offset += lh.length + f.data.length
  }
  const centralSize = central.reduce((n, c) => n + c.length, 0)
  const eocd = new Uint8Array(22)
  const edv = new DataView(eocd.buffer)
  edv.setUint32(0, 0x06054b50, true)
  edv.setUint16(8, files.length, true)
  edv.setUint16(10, files.length, true)
  edv.setUint32(12, centralSize, true)
  edv.setUint32(16, offset, true) // central dir offset
  const parts = [...locals, ...central, eocd]
  const total = parts.reduce((n, p) => n + p.length, 0)
  const out = new Uint8Array(total)
  let p = 0
  for (const part of parts) { out.set(part, p); p += part.length }
  return out
}
