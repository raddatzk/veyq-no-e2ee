// A tiny, dependency-free EXIF GPS reader for JPEG bytes. We only need the location (and, opportunistically,
// the original capture time) so a memory's media can drop map pins — so this parses just the APP1/TIFF/GPS
// path, not the full EXIF spec. Returns nothing for non-JPEG, EXIF-less, or location-less images.

export type ExifGps = { lat?: number; lng?: number; takenAt?: string }

/** Parse GPS (and DateTimeOriginal) out of a JPEG's EXIF, or {} if absent/unreadable. */
export function readExifGps(bytes: Uint8Array): ExifGps {
  try {
    return parse(bytes)
  } catch {
    return {}
  }
}

function parse(bytes: Uint8Array): ExifGps {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return {} // not a JPEG (no SOI)
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)

  // Walk JPEG marker segments to find APP1 ("Exif\0\0").
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

function parseTiff(view: DataView, tiff: number): ExifGps {
  const le = view.getUint16(tiff) === 0x4949 // "II" little-endian, else "MM" big-endian
  const u16 = (o: number) => view.getUint16(o, le)
  const u32 = (o: number) => view.getUint32(o, le)

  const ifd0 = tiff + u32(tiff + 4)
  let gpsIfd = 0
  let takenAt: string | undefined
  for (const { tag, type, valueOffset } of entries(view, ifd0, le)) {
    if (tag === 0x8825) gpsIfd = tiff + u32(valueOffset) // GPS IFD pointer
    else if (tag === 0x0132 && type === 2) takenAt = ascii(view, tiff + u32(valueOffset)) // DateTime fallback
  }

  // DateTimeOriginal lives in the Exif sub-IFD (tag 0x8769 in IFD0).
  for (const { tag, valueOffset } of entries(view, ifd0, le)) {
    if (tag === 0x8769) {
      const exifIfd = tiff + u32(valueOffset)
      for (const e of entries(view, exifIfd, le)) if (e.tag === 0x9003 && e.type === 2) takenAt = ascii(view, tiff + u32(e.valueOffset))
    }
  }

  const out: ExifGps = { takenAt: exifDateToIso(takenAt) }
  if (!gpsIfd) return out

  let lat: number | undefined
  let lng: number | undefined
  let latRef = 'N'
  let lngRef = 'E'
  for (const { tag, type, count, valueOffset } of entries(view, gpsIfd, le)) {
    if (tag === 0x0001) latRef = ascii(view, valueOffset) // GPSLatitudeRef (inline 1-byte ASCII)
    else if (tag === 0x0003) lngRef = ascii(view, valueOffset) // GPSLongitudeRef
    else if (tag === 0x0002 && type === 5 && count === 3) lat = dms(view, tiff + u32(valueOffset), le)
    else if (tag === 0x0004 && type === 5 && count === 3) lng = dms(view, tiff + u32(valueOffset), le)
  }
  if (lat !== undefined) out.lat = latRef.startsWith('S') ? -lat : lat
  if (lng !== undefined) out.lng = lngRef.startsWith('W') ? -lng : lng
  return out
}

type Entry = { tag: number; type: number; count: number; valueOffset: number }

/** Yield each directory entry of an IFD (tag, type, count, and the offset of its value field). */
function entries(view: DataView, ifd: number, le: boolean): Entry[] {
  const count = view.getUint16(ifd, le)
  const out: Entry[] = []
  for (let i = 0; i < count; i++) {
    const e = ifd + 2 + i * 12
    out.push({ tag: view.getUint16(e, le), type: view.getUint16(e + 2, le), count: view.getUint32(e + 4, le), valueOffset: e + 8 })
  }
  return out
}

/** Three rationals (degrees, minutes, seconds) → signed-magnitude decimal degrees. */
function dms(view: DataView, at: number, le: boolean): number {
  const rat = (o: number) => view.getUint32(o, le) / (view.getUint32(o + 4, le) || 1)
  return rat(at) + rat(at + 8) / 60 + rat(at + 16) / 3600
}

function ascii(view: DataView, at: number): string {
  let s = ''
  for (let i = 0; i < 32; i++) {
    const c = view.getUint8(at + i)
    if (c === 0) break
    s += String.fromCharCode(c)
  }
  return s
}

/** EXIF "YYYY:MM:DD HH:MM:SS" → ISO 8601, or undefined when absent/malformed. */
function exifDateToIso(s?: string): string | undefined {
  const m = s && /^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})/.exec(s)
  if (!m) return undefined
  return `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}Z`
}
