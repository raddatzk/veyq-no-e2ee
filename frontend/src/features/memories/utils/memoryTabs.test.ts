import { describe, expect, it } from 'vitest'
import { memoryTabFromHash, memoryTabToHash } from './memoryTabs'

describe('memoryTabs', () => {
  it('falls back to gallery for empty and unknown hashes', () => {
    expect(memoryTabFromHash('')).toBe('gallery')
    expect(memoryTabFromHash('#unknown')).toBe('gallery')
  })

  it('parses supported tab hashes', () => {
    expect(memoryTabFromHash('#journal')).toBe('journal')
    expect(memoryTabFromHash('#tracks')).toBe('tracks')
    expect(memoryTabFromHash('#playlists')).toBe('playlists')
  })

  it('serializes tabs to route hashes', () => {
    expect(memoryTabToHash('gallery')).toBe('')
    expect(memoryTabToHash('journal')).toBe('#journal')
  })
})
