import { afterEach, describe, expect, it } from 'vitest'
import {
  OSM_TILE_CONSENT_KEY,
  disableOsmTiles,
  enableOsmTiles,
  hasOsmTileConsent
} from './osmTiles'

describe('browser privacy consent helpers', () => {
  afterEach(() => {
    window.localStorage.clear()
  })

  it('stores OpenStreetMap tile consent only in local storage', () => {
    expect(hasOsmTileConsent()).toBe(false)

    enableOsmTiles()

    expect(window.localStorage.getItem(OSM_TILE_CONSENT_KEY)).toBe('true')
    expect(hasOsmTileConsent()).toBe(true)

    disableOsmTiles()

    expect(hasOsmTileConsent()).toBe(false)
  })

  it('stores OpenStreetMap tile consent separately per user when scoped', () => {
    enableOsmTiles(7)
    enableOsmTiles(9)

    expect(window.localStorage.getItem(OSM_TILE_CONSENT_KEY)).toBeNull()
    expect(window.localStorage.getItem('veyq:user:7:osm-tiles-enabled')).toBe('true')
    expect(window.localStorage.getItem('veyq:user:9:osm-tiles-enabled')).toBe('true')
    expect(hasOsmTileConsent(7)).toBe(true)
    expect(hasOsmTileConsent(9)).toBe(true)
    expect(hasOsmTileConsent()).toBe(false)

    disableOsmTiles(7)

    expect(hasOsmTileConsent(7)).toBe(false)
    expect(hasOsmTileConsent(9)).toBe(true)
  })
})
