import { describe, expect, it } from 'vitest'
import { readExifGps } from './exifGps'

// Build a minimal little-endian JPEG/EXIF carrying a GPS location (48°8'30"N, 11°34'12"E) and a
// DateTimeOriginal, so we can unit-test the parser without a real photo.
function makeExifJpeg(): Uint8Array {
  const tiff = new Uint8Array(128)
  const dv = new DataView(tiff.buffer)
  const w16 = (o: number, v: number) => dv.setUint16(o, v, true)
  const w32 = (o: number, v: number) => dv.setUint32(o, v, true)
  const rat = (o: number, n: number) => { w32(o, n); w32(o + 4, 1) }

  // TIFF header (II) + IFD0 at offset 8
  tiff[0] = 0x49; tiff[1] = 0x49; w16(2, 0x2a); w32(4, 8)
  // IFD0: one entry → GPS IFD pointer (tag 0x8825) at offset 26
  w16(8, 1)
  w16(10, 0x8825); w16(12, 4); w32(14, 1); w32(18, 26)
  w32(22, 0)
  // GPS IFD at 26: 4 entries + rationals at 80/104
  w16(26, 4)
  w16(28, 0x0001); w16(30, 2); w32(32, 2); tiff[36] = 0x4e // GPSLatitudeRef "N"
  w16(40, 0x0002); w16(42, 5); w32(44, 3); w32(48, 80) // GPSLatitude
  w16(52, 0x0003); w16(54, 2); w32(56, 2); tiff[60] = 0x45 // GPSLongitudeRef "E"
  w16(64, 0x0004); w16(66, 5); w32(68, 3); w32(72, 104) // GPSLongitude
  w32(76, 0)
  rat(80, 48); rat(88, 8); rat(96, 30) // 48° 8' 30"
  rat(104, 11); rat(112, 34); rat(120, 12) // 11° 34' 12"

  const exif = new Uint8Array(6 + tiff.length)
  exif.set([0x45, 0x78, 0x69, 0x66, 0, 0]) // "Exif\0\0"
  exif.set(tiff, 6)
  const appLen = exif.length + 2
  const jpeg = new Uint8Array(2 + 2 + 2 + exif.length + 2)
  jpeg.set([0xff, 0xd8]) // SOI
  jpeg.set([0xff, 0xe1, (appLen >> 8) & 0xff, appLen & 0xff], 2) // APP1 + big-endian length
  jpeg.set(exif, 6)
  jpeg.set([0xff, 0xd9], 6 + exif.length) // EOI
  return jpeg
}

describe('readExifGps', () => {
  it('parses GPS lat/lng with hemisphere refs', () => {
    const { lat, lng } = readExifGps(makeExifJpeg())
    expect(lat).toBeCloseTo(48.141667, 5)
    expect(lng).toBeCloseTo(11.57, 5)
  })

  it('returns nothing for non-JPEG or EXIF-less bytes', () => {
    expect(readExifGps(new Uint8Array([1, 2, 3, 4]))).toEqual({})
    expect(readExifGps(new Uint8Array([0xff, 0xd8, 0xff, 0xd9]))).toEqual({})
  })
})
