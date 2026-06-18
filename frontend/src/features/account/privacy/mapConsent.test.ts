import { beforeEach, describe, expect, it } from 'vitest'
import { readMapTilesConsent, setMapTilesConsent } from './mapConsent'

// uc.memory.consent-map-tiles (web): a per-device opt-in before a map loads external tiles — the one
// place plaintext-derived (coarse) location crosses an origin boundary. Stored locally, never synced.
describe('map tile consent', () => {
  beforeEach(() => localStorage.clear())

  it('defaults to no consent when unset', () => {
    expect(readMapTilesConsent()).toBe(false)
  })

  it('persists opt-in and opt-out per origin', () => {
    setMapTilesConsent(true)
    expect(readMapTilesConsent()).toBe(true)
    setMapTilesConsent(false)
    expect(readMapTilesConsent()).toBe(false)
  })
})
