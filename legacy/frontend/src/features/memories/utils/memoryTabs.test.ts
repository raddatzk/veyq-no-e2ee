import { describe, expect, it } from 'vitest'
import { memoryTabFromHash, memoryTabToHash } from './memoryTabs'

describe('memoryTabs', () => {
  it('falls back to gallery for empty and unknown hashes', () => {
    expect(memoryTabFromHash('')).toBe('gallery')
    expect(memoryTabFromHash('#unknown')).toBe('gallery')
    expect(memoryTabFromHash('#context')).toBe('gallery')
    expect(memoryTabFromHash('#tracks')).toBe('gallery')
    expect(memoryTabFromHash('#buckets')).toBe('gallery')
  })

  it('parses supported tab hashes', () => {
    expect(memoryTabFromHash('#journal')).toBe('journal')
    expect(memoryTabFromHash('#map')).toBe('map')
    expect(memoryTabFromHash('#playlists')).toBe('playlists')
    expect(memoryTabFromHash('#contributions')).toBe('contributions')
  })

  it('serializes tabs to route hashes', () => {
    expect(memoryTabToHash('gallery')).toBe('')
    expect(memoryTabToHash('journal')).toBe('#journal')
    expect(memoryTabToHash('map')).toBe('#map')
  })
})
