// Per-device consent for loading external map tiles. The map shows decrypted GPS, and the tile request
// reveals the (coarse) location to a third party (OpenStreetMap on web/Android, Apple on iOS) — the one place
// plaintext-derived data would cross an origin boundary. So tiles load only after the user opts in, and the
// choice is stored LOCALLY (localStorage, per-origin) — never synced to the operator-blind server.
import { ref, watch } from 'vue'

const KEY = 'veyq.privacy.mapTiles'
export const mapTilesConsent = ref(localStorage.getItem(KEY) === 'true')
watch(mapTilesConsent, (on) => localStorage.setItem(KEY, on ? 'true' : 'false'))
