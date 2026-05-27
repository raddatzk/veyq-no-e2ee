export const OSM_TILE_CONSENT_KEY = 'veyq:osm-tiles-enabled'

export function hasOsmTileConsent(userId?: number | null): boolean {
  return getStorage()?.getItem(scopedConsentKey(userId)) === 'true'
}

export function enableOsmTiles(userId?: number | null) {
  getStorage()?.setItem(scopedConsentKey(userId), 'true')
}

export function disableOsmTiles(userId?: number | null) {
  getStorage()?.removeItem(scopedConsentKey(userId))
}

function scopedConsentKey(userId?: number | null): string {
  return userId ? `veyq:user:${userId}:${OSM_TILE_CONSENT_KEY.replace(/^veyq:/, '')}` : OSM_TILE_CONSENT_KEY
}

function getStorage(): Storage | null {
  return typeof window === 'undefined' ? null : window.localStorage
}
