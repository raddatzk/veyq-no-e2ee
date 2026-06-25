// A tiny, dependency-free EXIF reader for JPEG bytes. We parse the APP1/TIFF path (IFD0 + Exif sub-IFD + GPS
// IFD): location + capture time for map pins, plus a broad set of generic camera tags for the lightbox info
// panel. All of it is sealed client-side into sealedMeta (operator-blind) — the server never sees any of it.
// Returns {} for non-JPEG / EXIF-less images; individual fields are best-effort.

export type ExifData = {
  lat?: number
  lng?: number
  takenAt?: string
  cameraMake?: string
  cameraModel?: string
  lens?: string
  width?: number
  height?: number
  // Generic decoded tags (label → human string), e.g. "Aperture" → "f/2.8". Shown verbatim in the info panel.
  exif?: Record<string, string>
}

/** Parse EXIF (GPS, capture time, camera tags) out of a JPEG, or {} if absent/unreadable. */
export function readExifGps(bytes: Uint8Array): ExifData {
  try {
    return parse(bytes)
  } catch {
    return {}
  }
}

function parse(bytes: Uint8Array): ExifData {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return {} // not a JPEG (no SOI)
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let offset = 2
  while (offset + 4 <= bytes.length) {
    if (view.getUint8(offset) !== 0xff) break
    const marker = view.getUint8(offset + 1)
    const size = view.getUint16(offset + 2)
    if (marker === 0xda) break // start of scan — no metadata past here
    if (marker === 0xe1 && size >= 8) {
      const exifStart = offset + 4
      if (bytes[exifStart] === 0x45 && bytes[exifStart + 1] === 0x78) return parseTiff(view, exifStart + 6) // "Ex"
    }
    offset += 2 + size
  }
  return {}
}

// type → byte size of one component (EXIF TIFF types)
const TYPE_SIZE: Record<number, number> = { 1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8 }

function parseTiff(view: DataView, tiff: number): ExifData {
  const le = view.getUint16(tiff) === 0x4949 // "II" little-endian, else "MM" big-endian
  const u32 = (o: number) => view.getUint32(o, le)
  const out: ExifData = {}
  const exif: Record<string, string> = {}

  const ifd0 = tiff + u32(tiff + 4)
  let gpsIfd = 0
  let exifIfd = 0
  let takenAt: string | undefined

  collectKnown(view, ifd0, tiff, le, out, exif)
  for (const { tag, valueOffset } of entries(view, ifd0, le)) {
    if (tag === 0x8825) gpsIfd = tiff + u32(valueOffset) // GPS IFD pointer
    else if (tag === 0x8769) exifIfd = tiff + u32(valueOffset) // Exif sub-IFD pointer
    else if (tag === 0x0132) takenAt = readString(view, tiff, findEntry(view, ifd0, le, 0x0132)!, le) // DateTime fallback
  }
  if (exifIfd) {
    collectKnown(view, exifIfd, tiff, le, out, exif)
    const dto = findEntry(view, exifIfd, le, 0x9003)
    if (dto) takenAt = readString(view, tiff, dto, le)
  }
  out.takenAt = exifDateToIso(takenAt)

  if (gpsIfd) readGps(view, gpsIfd, tiff, le, out, exif)
  if (Object.keys(exif).length) out.exif = exif
  return out
}

// tag → [label, formatter] for the generic info panel; canonical fields are pulled out separately below.
const TAGS: Record<number, [string, (v: number[] | string) => string]> = {
  0x0131: ['Software', (v) => String(v)],
  0x0112: ['Orientation', (v) => String((v as number[])[0] ?? v)],
  0x829a: ['Exposure', (v) => exposure((v as number[])[0])],
  0x829d: ['Aperture', (v) => `f/${trim((v as number[])[0])}`],
  0x8827: ['ISO', (v) => String((v as number[])[0] ?? v)],
  0x920a: ['Focal length', (v) => `${trim((v as number[])[0])} mm`],
  0xa405: ['Focal length (35mm)', (v) => `${(v as number[])[0]} mm`],
  0x9209: ['Flash', (v) => ((v as number[])[0] & 1 ? 'On' : 'Off')],
  0xa433: ['Lens make', (v) => String(v)],
  0xa434: ['Lens model', (v) => String(v)],
}

/** Decode a curated set of known tags from an IFD into canonical fields + the generic map. */
function collectKnown(view: DataView, ifd: number, tiff: number, le: boolean, out: ExifData, exif: Record<string, string>) {
  for (const e of entries(view, ifd, le)) {
    if (e.tag === 0x010f) out.cameraMake = readString(view, tiff, e, le).trim() || undefined
    else if (e.tag === 0x0110) out.cameraModel = readString(view, tiff, e, le).trim() || undefined
    else if (e.tag === 0xa434) out.lens = readString(view, tiff, e, le).trim() || undefined
    else if (e.tag === 0xa002) out.width = readNums(view, tiff, e, le)[0]
    else if (e.tag === 0xa003) out.height = readNums(view, tiff, e, le)[0]
    const known = TAGS[e.tag]
    if (known) {
      const raw = e.type === 2 ? readString(view, tiff, e, le).trim() : readNums(view, tiff, e, le)
      const s = (raw as string).length === 0 ? '' : known[1](raw)
      if (s) exif[known[0]] = s
    }
  }
}

function readGps(view: DataView, gpsIfd: number, tiff: number, le: boolean, out: ExifData, exif: Record<string, string>) {
  const u32 = (o: number) => view.getUint32(o, le)
  let lat: number | undefined
  let lng: number | undefined
  let latRef = 'N'
  let lngRef = 'E'
  let alt: number | undefined
  let altRef = 0 // 0 = above sea level, 1 = below
  for (const { tag, type, count, valueOffset } of entries(view, gpsIfd, le)) {
    if (tag === 0x0001) latRef = ascii(view, valueOffset, 2)
    else if (tag === 0x0003) lngRef = ascii(view, valueOffset, 2)
    else if (tag === 0x0002 && type === 5 && count === 3) lat = dms(view, tiff + u32(valueOffset), le)
    else if (tag === 0x0004 && type === 5 && count === 3) lng = dms(view, tiff + u32(valueOffset), le)
    else if (tag === 0x0005) altRef = view.getUint8(valueOffset) // GPSAltitudeRef (inline 1-byte)
    else if (tag === 0x0006 && type === 5) alt = view.getUint32(tiff + u32(valueOffset), le) / (view.getUint32(tiff + u32(valueOffset) + 4, le) || 1)
  }
  if (lat !== undefined) out.lat = latRef.startsWith('S') ? -lat : lat
  if (lng !== undefined) out.lng = lngRef.startsWith('W') ? -lng : lng
  if (alt !== undefined) exif.Altitude = `${trim(altRef === 1 ? -alt : alt)} m`
}

type Entry = { tag: number; type: number; count: number; valueOffset: number }

function entries(view: DataView, ifd: number, le: boolean): Entry[] {
  const count = view.getUint16(ifd, le)
  const out: Entry[] = []
  for (let i = 0; i < count; i++) {
    const e = ifd + 2 + i * 12
    out.push({ tag: view.getUint16(e, le), type: view.getUint16(e + 2, le), count: view.getUint32(e + 4, le), valueOffset: e + 8 })
  }
  return out
}

function findEntry(view: DataView, ifd: number, le: boolean, tag: number): Entry | undefined {
  return entries(view, ifd, le).find((e) => e.tag === tag)
}

/** Absolute offset of an entry's value bytes — inline (≤4 bytes) at the value field, else the pointed-to offset. */
function dataOffset(view: DataView, tiff: number, e: Entry, le: boolean): number {
  const size = (TYPE_SIZE[e.type] ?? 1) * e.count
  return size <= 4 ? e.valueOffset : tiff + view.getUint32(e.valueOffset, le)
}

function readString(view: DataView, tiff: number, e: Entry, le: boolean): string {
  return ascii(view, dataOffset(view, tiff, e, le), Math.min(e.count, 128))
}

function readNums(view: DataView, tiff: number, e: Entry, le: boolean): number[] {
  const at = dataOffset(view, tiff, e, le)
  const sz = TYPE_SIZE[e.type] ?? 1
  const out: number[] = []
  for (let i = 0; i < e.count; i++) {
    const o = at + i * sz
    if (e.type === 3) out.push(view.getUint16(o, le))
    else if (e.type === 4) out.push(view.getUint32(o, le))
    else if (e.type === 9) out.push(view.getInt32(o, le))
    else if (e.type === 5) out.push(view.getUint32(o, le) / (view.getUint32(o + 4, le) || 1))
    else if (e.type === 10) out.push(view.getInt32(o, le) / (view.getInt32(o + 4, le) || 1))
    else if (e.type === 1 || e.type === 7) out.push(view.getUint8(o))
  }
  return out
}

function dms(view: DataView, at: number, le: boolean): number {
  const rat = (o: number) => view.getUint32(o, le) / (view.getUint32(o + 4, le) || 1)
  return rat(at) + rat(at + 8) / 60 + rat(at + 16) / 3600
}

function ascii(view: DataView, at: number, max = 32): string {
  let s = ''
  for (let i = 0; i < max; i++) {
    if (at + i >= view.byteLength) break
    const c = view.getUint8(at + i)
    if (c === 0) break
    s += String.fromCharCode(c)
  }
  return s
}

/** "1/200 s" for sub-second exposures, else "2 s". */
function exposure(t: number): string {
  if (!t || t <= 0) return ''
  return t < 1 ? `1/${Math.round(1 / t)} s` : `${trim(t)} s`
}

/** Drop trailing-zero noise from a decoded rational (2.7999 → "2.8"). */
function trim(n: number): string {
  return String(Math.round(n * 100) / 100)
}

function exifDateToIso(s?: string): string | undefined {
  const m = s && /^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(s)
  if (!m) return undefined
  return `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}Z`
}
