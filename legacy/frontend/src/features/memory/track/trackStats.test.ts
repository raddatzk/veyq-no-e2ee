import { describe, expect, it } from 'vitest'
import { formatDistance, formatDuration, formatElevation, trackStats } from './trackStats'

describe('trackStats', () => {
  it('computes distance from lat/lng (ascent/duration omitted without ele/time)', () => {
    const s = trackStats([{ lat: 0, lng: 0 }, { lat: 0, lng: 1 }]) // ~111 km along the equator
    expect(s.distanceM).toBeGreaterThan(110_000)
    expect(s.distanceM).toBeLessThan(112_000)
    expect(s.ascentM).toBeUndefined()
    expect(s.durationS).toBeUndefined()
  })

  it('sums ascent and descent when elevations are present', () => {
    const s = trackStats([
      { lat: 0, lng: 0, ele: 100 },
      { lat: 0, lng: 0.01, ele: 150 }, // +50
      { lat: 0, lng: 0.02, ele: 120 }, // -30
      { lat: 0, lng: 0.03, ele: 140 }, // +20
    ])
    expect(s.ascentM).toBe(70)
    expect(s.descentM).toBe(30)
  })

  it('derives duration from first/last timestamp', () => {
    const s = trackStats([
      { lat: 0, lng: 0, time: '2026-01-01T10:00:00Z' },
      { lat: 0, lng: 0.1, time: '2026-01-01T11:30:00Z' },
    ])
    expect(s.durationS).toBe(90 * 60)
  })

  it('formats distance, duration and elevation', () => {
    expect(formatDistance(850)).toBe('850 m')
    expect(formatDistance(12_340)).toBe('12.3 km')
    expect(formatDuration(45)).toBe('45 s')
    expect(formatDuration(23 * 60)).toBe('23 min')
    expect(formatDuration(3600 + 23 * 60)).toBe('1 h 23 min')
    expect(formatElevation({ distanceM: 1, ascentM: 120, descentM: 95 })).toBe('↑120 m ↓95 m')
    expect(formatElevation({ distanceM: 1 })).toBe('')
  })
})
