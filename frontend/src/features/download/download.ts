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

/** Download + decrypt every original and pack them into a zip, streaming to disk where the browser allows it. */
export async function downloadMemoryZip(apiBase: string, session: UnlockedSession, active: ActiveSpace, memoryNode: MemoryNode, memoryName: string, withChapters: boolean): Promise<void> {
  const items: Item[] = []
  await gather(apiBase, session, active, memoryNode, '', withChapters, items)
  const filename = `${sanitize(memoryName)}.zip`
  const fetchOriginal = (it: Item) => downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, it.media.original)

  // Preferred: stream each entry straight to the chosen file via the File System Access API (Chromium), so peak
  // memory is ~one original instead of the whole archive (a large memory of full-res originals would OOM the tab).
  const picker = (window as unknown as { showSaveFilePicker?: SaveFilePicker }).showSaveFilePicker
  if (picker) {
    let handle: FileSystemFileHandleLike
    try {
      handle = await picker({ suggestedName: filename, types: [{ description: 'Zip archive', accept: { 'application/zip': ['.zip'] } }] })
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') return // user cancelled the save dialog
      return downloadInMemory(items, filename, fetchOriginal) // picker unavailable mid-call → fall back
    }
    const writable = await handle.createWritable()
    try {
      const zw = new ZipStreamWriter()
      // await each write so the stream applies backpressure — the next original isn't fetched/decrypted until
      // the previous entry has been flushed to disk, keeping peak memory at ~one file.
      for (const it of items) await writable.write(zw.file(it.name, await fetchOriginal(it)))
      await writable.write(zw.finish())
    } finally {
      await writable.close()
    }
    return
  }
  // Fallback (Firefox/Safari): build the zip in memory and trigger a normal download.
  await downloadInMemory(items, filename, fetchOriginal)
}

type SaveFilePicker = (opts: { suggestedName?: string; types?: unknown[] }) => Promise<FileSystemFileHandleLike>
type FileSystemFileHandleLike = { createWritable: () => Promise<{ write: (d: Uint8Array) => Promise<void>; close: () => Promise<void> }> }

async function downloadInMemory(items: Item[], filename: string, fetchOriginal: (it: Item) => Promise<Uint8Array>): Promise<void> {
  const files: { name: string; data: Uint8Array }[] = []
  for (const it of items) files.push({ name: it.name, data: await fetchOriginal(it) })
  const url = URL.createObjectURL(new Blob([zipStore(files) as BlobPart], { type: 'application/zip' }))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
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

/**
 * Incremental store-only ZIP writer. Feed one file at a time (`file()` returns its local header + stored
 * bytes to emit) and call `finish()` for the central directory + EOCD. Emitting per file lets the caller
 * stream each entry straight to disk and drop it, so peak memory stays ~one file rather than the whole zip.
 */
export class ZipStreamWriter {
  private enc = new TextEncoder()
  private central: Uint8Array[] = []
  private offset = 0
  private count = 0

  /** Local header + stored data for one file; record its central-directory entry. */
  file(name: string, data: Uint8Array): Uint8Array {
    const nameBytes = this.enc.encode(name)
    const crc = CRC(data)
    const lh = new Uint8Array(30 + nameBytes.length)
    const ldv = new DataView(lh.buffer)
    ldv.setUint32(0, 0x04034b50, true)
    ldv.setUint16(4, 20, true) // version needed
    ldv.setUint16(6, 0x0800, true) // flags: UTF-8 names
    ldv.setUint16(8, 0, true) // method: store
    ldv.setUint32(14, crc, true)
    ldv.setUint32(18, data.length, true) // comp size
    ldv.setUint32(22, data.length, true) // uncomp size
    ldv.setUint16(26, nameBytes.length, true)
    lh.set(nameBytes, 30)

    const ch = new Uint8Array(46 + nameBytes.length)
    const cdv = new DataView(ch.buffer)
    cdv.setUint32(0, 0x02014b50, true)
    cdv.setUint16(4, 20, true)
    cdv.setUint16(6, 20, true)
    cdv.setUint16(8, 0x0800, true)
    cdv.setUint16(10, 0, true)
    cdv.setUint32(16, crc, true)
    cdv.setUint32(20, data.length, true)
    cdv.setUint32(24, data.length, true)
    cdv.setUint16(28, nameBytes.length, true)
    cdv.setUint32(42, this.offset, true) // local header offset
    ch.set(nameBytes, 46)
    this.central.push(ch)
    this.offset += lh.length + data.length
    this.count++

    const out = new Uint8Array(lh.length + data.length)
    out.set(lh, 0)
    out.set(data, lh.length)
    return out
  }

  /** Central directory + End-Of-Central-Directory — emit after every file. */
  finish(): Uint8Array {
    const centralSize = this.central.reduce((n, c) => n + c.length, 0)
    const eocd = new Uint8Array(22)
    const edv = new DataView(eocd.buffer)
    edv.setUint32(0, 0x06054b50, true)
    edv.setUint16(8, this.count, true)
    edv.setUint16(10, this.count, true)
    edv.setUint32(12, centralSize, true)
    edv.setUint32(16, this.offset, true) // central dir offset
    const out = new Uint8Array(centralSize + 22)
    let p = 0
    for (const c of this.central) { out.set(c, p); p += c.length }
    out.set(eocd, p)
    return out
  }
}

/** In-memory store-only zip (the fallback path; same byte layout as the streaming writer). */
export function zipStore(files: { name: string; data: Uint8Array }[]): Uint8Array {
  const zw = new ZipStreamWriter()
  const parts = files.map((f) => zw.file(f.name, f.data))
  parts.push(zw.finish())
  const total = parts.reduce((n, p) => n + p.length, 0)
  const out = new Uint8Array(total)
  let p = 0
  for (const part of parts) { out.set(part, p); p += part.length }
  return out
}
