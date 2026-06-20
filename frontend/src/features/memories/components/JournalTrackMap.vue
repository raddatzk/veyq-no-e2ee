<script setup lang="ts">
// A presentational mini-map for ::track journal embeds: it renders the already-decrypted route polyline(s)
// passed in by the journal body. Unlike MemoryMap.vue it owns no data fetching or upload — it is purely a
// read-only view of points the parent already holds, so it can sit inline inside the rendered journal. OSM
// tiles only load once the per-device map-tiles consent is granted (the same gate MemoryMap uses).
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type { Track } from '../../../features/memory/track/track'
import { mapTilesConsent } from '../../../features/account/privacy/mapConsent'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ tracks: Track[] }>()

const mapEl = ref<HTMLElement | null>(null)
let map: L.Map | null = null
let layer: L.LayerGroup | null = null

function ensureMap() {
  if (map || !mapEl.value) return
  map = L.map(mapEl.value).setView([20, 0], 2)
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '© OpenStreetMap', maxZoom: 19 }).addTo(map)
  layer = L.layerGroup().addTo(map)
  map.invalidateSize()
}

function render() {
  if (!map || !layer) return
  layer.clearLayers()
  const all: L.LatLngExpression[] = []
  for (const tr of props.tracks) {
    const ll = tr.points.map((p) => [p.lat, p.lng] as L.LatLngExpression)
    if (!ll.length) continue
    L.polyline(ll, { color: '#34d399', weight: 3 }).addTo(layer)
    all.push(...ll)
  }
  if (all.length) map.fitBounds(L.latLngBounds(all).pad(0.2))
}

watch(
  [() => mapTilesConsent.value, () => props.tracks],
  async () => {
    if (!mapTilesConsent.value) {
      if (map) { map.remove(); map = null; layer = null }
      return
    }
    await nextTick()
    ensureMap()
    render()
  },
  { immediate: true },
)

onBeforeUnmount(() => { if (map) { map.remove(); map = null; layer = null } })
</script>

<template>
  <div class="journal-track-map">
    <div v-if="!mapTilesConsent" class="journal-map-consent">{{ t('map.consentText') }}</div>
    <div v-else ref="mapEl" class="journal-map-canvas"></div>
  </div>
</template>

<style scoped>
.journal-track-map { width: 100%; }
.journal-map-consent { padding: 1rem; font-size: 0.85rem; opacity: 0.7; }
.journal-map-canvas { height: min(40vh, 320px); }
</style>
