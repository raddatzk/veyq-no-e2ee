// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { parseGpx } from './gpx'

const GPX = `<?xml version="1.0"?>
<gpx version="1.1">
  <metadata><name>Ignored meta</name></metadata>
  <trk>
    <name>Colosseum Loop</name>
    <trkseg>
      <trkpt lat="41.8902" lon="12.4922"><ele>20</ele></trkpt>
      <trkpt lat="41.8910" lon="12.4930"></trkpt>
    </trkseg>
  </trk>
</gpx>`

describe('parseGpx', () => {
  it('extracts the track name and points', () => {
    const t = parseGpx(GPX)
    expect(t.name).toBe('Colosseum Loop')
    expect(t.points).toHaveLength(2)
    expect(t.points[0]).toEqual({ lat: 41.8902, lng: 12.4922 })
  })

  it('falls back to route points + a generic name', () => {
    const t = parseGpx(`<gpx><rte><rtept lat="1.5" lon="2.5"/></rte></gpx>`)
    expect(t.name).toBe('Track')
    expect(t.points).toEqual([{ lat: 1.5, lng: 2.5 }])
  })

  it('throws on malformed XML or empty tracks', () => {
    expect(() => parseGpx('not xml <')).toThrow()
    expect(() => parseGpx('<gpx></gpx>')).toThrow()
  })
})
