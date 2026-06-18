// Per-device consent for loading external map tiles. The map shows decrypted GPS, and the tile request
// reveals the (coarse) location to a third party (OpenStreetMap on web/Android, Apple on iOS) — the one place
// plaintext-derived data would cross an origin boundary. So tiles load only after the user opts in, and the
// choice is stored LOCALLY (localStorage, per-origin) — never synced to the operator-blind server.
import { ref, watch } from 'vue'

const KEY = 'veyq.privacy.mapTiles'

/** Read the per-device map-tile consent; privacy-safe default of `false` when unset. */
export function readMapTilesConsent(): boolean { return localStorage.getItem(KEY) === 'true' }
/** Persist the per-device map-tile consent (per origin; never synced to the operator-blind server). */
export function setMapTilesConsent(on: boolean): void { localStorage.setItem(KEY, on ? 'true' : 'false') }

export const mapTilesConsent = ref(readMapTilesConsent())
watch(mapTilesConsent, setMapTilesConsent)
