import { describe, expect, it } from 'vitest'
import { zipStore } from './download'

// Parse a store-only zip back via its End-Of-Central-Directory + local headers, proving it is well-formed
// and the stored bytes round-trip.
function extract(zip: Uint8Array): Record<string, Uint8Array> {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength)
  expect(dv.getUint32(zip.length - 22, true)).toBe(0x06054b50) // EOCD magic
  const count = dv.getUint16(zip.length - 22 + 10, true)
  const out: Record<string, Uint8Array> = {}
  let o = 0
  for (let i = 0; i < count; i++) {
    expect(dv.getUint32(o, true)).toBe(0x04034b50) // local header magic
    const size = dv.getUint32(o + 18, true)
    const nameLen = dv.getUint16(o + 26, true)
    const extraLen = dv.getUint16(o + 28, true)
    const name = new TextDecoder().decode(zip.subarray(o + 30, o + 30 + nameLen))
    const start = o + 30 + nameLen + extraLen
    out[name] = zip.subarray(start, start + size)
    o = start + size
  }
  return out
}

describe('store-only zip writer', () => {
  it('packs files that round-trip byte-for-byte', () => {
    const files = [
      { name: '001.png', data: Uint8Array.from([1, 2, 3, 4, 5]) },
      { name: 'tag-1/001.jpg', data: Uint8Array.from({ length: 300 }, (_, i) => (i * 7) & 0xff) },
    ]
    const out = extract(zipStore(files))
    expect(Object.keys(out).sort()).toEqual(['001.png', 'tag-1/001.jpg'])
    expect(Array.from(out['001.png'])).toEqual(Array.from(files[0].data))
    expect(Array.from(out['tag-1/001.jpg'])).toEqual(Array.from(files[1].data))
  })

  it('produces a valid empty archive', () => {
    const zip = zipStore([])
    expect(new DataView(zip.buffer).getUint32(zip.length - 22, true)).toBe(0x06054b50)
  })
})
