import { describe, it, expect } from 'vitest'
import { sha1HexUpper, breachCountFromRange } from './pwned'

describe('pwned (HIBP k-anonymity)', () => {
  it('SHA-1 hex (uppercase) matches the known vector for "password"', async () => {
    expect(await sha1HexUpper('password')).toBe('5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8')
  })

  it('finds the matching suffix (after the 5-char prefix) and parses its count', () => {
    const hash = '5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8'
    const body =
      '0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n' +
      '1E4C9B93F3F0682250B6CF8331B7EE68FD8:9999999\r\n' + // suffix of "password"
      'FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:3'
    expect(breachCountFromRange(hash, body)).toBe(9999999)
  })

  it('returns 0 when the suffix is absent (password not breached / padding only)', () => {
    expect(breachCountFromRange('5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8', '0018A45C4D1DEF81644B54AB7F969B88D65:1')).toBe(0)
  })

  it('matches case-insensitively (HIBP returns uppercase, but be lenient)', () => {
    const hash = 'ABCDE'.padEnd(40, 'F') // suffix = 35× "F"
    expect(breachCountFromRange(hash, hash.slice(5).toLowerCase() + ':42')).toBe(42)
  })
})
