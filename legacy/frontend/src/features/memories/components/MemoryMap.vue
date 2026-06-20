<script setup lang="ts">
// A memory's map: geotagged media (coords decrypted from sealedMeta — no thumbnails fetched) as markers +
// GPX tracks as polylines. External OSM tiles reveal coarse location to a third party, so they load ONLY
// after per-device consent (mapTilesConsent). GPX import parses + seals the route client-side.
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import Button from 'primevue/button'
import { createTrack, getTracks, type Track } from '../../../features/memory/track/track'
import { trackStats, formatDistance, formatDuration, formatElevation } from '../../../features/memory/track/trackStats'
import { parseGpx } from '../../../features/memory/track/gpx'
import { decodeMediaMeta, listMedia } from '../../../features/uploads/media/media'
import { mapTilesConsent } from '../../../features/account/privacy/mapConsent'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()

const mapEl = ref<HTMLElement | null>(null)
const error = ref('')
const importing = ref(false)
const hasGeo = ref(false)
// Per-track distance/elevation/duration, computed client-side from the decrypted points (server is blind).
const trackSummaries = ref<Array<{ name: string; distance: string; elevation: string; duration: string }>>([])
let map: L.Map | null = null
let layer: L.LayerGroup | null = null

function ensureMap() {
  if (map || !mapEl.value) return
  map = L.map(mapEl.value).setView([20, 0], 2)
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { attribution: '© OpenStreetMap', maxZoom: 19 }).addTo(map)
  layer = L.layerGroup().addTo(map)
  map.invalidateSize()
}

async function render() {
  if (!map || !layer) return
  layer.clearLayers()
  trackSummaries.value = []
  const all: L.LatLngExpression[] = []
  try {
    const media = await listMedia(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.nodeId)
    for (const m of media) {
      const meta = decodeMediaMeta(props.active.contentKey, m)
      if (meta.lat == null || meta.lng == null) continue
      L.circleMarker([meta.lat, meta.lng], { radius: 6, color: '#6366f1', fillColor: '#6366f1', fillOpacity: 0.85, weight: 1 }).addTo(layer)
      all.push([meta.lat, meta.lng])
    }
    const tracks: Track[] = await getTracks(session.apiBase, session.unlocked!, props.active, props.nodeId)
    const summaries: typeof trackSummaries.value = []
    for (const tr of tracks) {
      const ll = tr.points.map((p) => [p.lat, p.lng] as L.LatLngExpression)
      if (!ll.length) continue
      L.polyline(ll, { color: '#34d399', weight: 3 }).addTo(layer)
      all.push(...ll)
      const s = trackStats(tr.points)
      summaries.push({
        name: tr.name,
        distance: formatDistance(s.distanceM),
        elevation: formatElevation(s),
        duration: s.durationS ? formatDuration(s.durationS) : '',
      })
    }
    trackSummaries.value = summaries
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
  hasGeo.value = all.length > 0
  if (all.length) map.fitBounds(L.latLngBounds(all).pad(0.2))
}

async function importGpx(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  error.value = ''
  try {
    const route = parseGpx(await file.text())
    await createTrack(session.apiBase, session.unlocked!, props.active, props.nodeId, route)
    await render()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    importing.value = false
  }
}

watch(
  [() => mapTilesConsent.value, () => props.nodeId, () => props.active.scope.scopeId],
  async () => {
    if (!mapTilesConsent.value) {
      if (map) { map.remove(); map = null; layer = null }
      return
    }
    await nextTick()
    ensureMap()
    await render()
  },
  { immediate: true },
)

onBeforeUnmount(() => { if (map) { map.remove(); map = null; layer = null } })
</script>

<template>
  <div class="memory-map">
    <p v-if="error" class="map-error">{{ error }}</p>

    <div v-if="!mapTilesConsent" class="map-consent">
      <p>{{ t('map.consentText') }}</p>
      <Button type="button" :label="t('map.consentEnable')" icon="pi pi-map" @click="mapTilesConsent = true" />
    </div>

    <template v-else>
      <div class="map-toolbar">
        <label class="upload-trigger">
          <span class="p-button p-component p-button-secondary p-button-outlined">
            <span class="pi pi-upload" />
            <span class="p-button-label">{{ importing ? t('track.importing') : t('track.import') }}</span>
          </span>
          <input type="file" accept=".gpx,application/gpx+xml,application/xml,text/xml" class="upload-input" :disabled="importing" @change="importGpx" />
        </label>
      </div>
      <div ref="mapEl" class="map-container"></div>
      <ul v-if="trackSummaries.length" class="track-stats">
        <li v-for="(s, i) in trackSummaries" :key="i">
          <span class="track-name">{{ s.name }}</span>
          <span class="track-metrics">
            <span>{{ s.distance }}</span>
            <span v-if="s.elevation">{{ s.elevation }}</span>
            <span v-if="s.duration">{{ s.duration }}</span>
          </span>
        </li>
      </ul>
      <p v-if="!hasGeo" class="empty-hint">{{ t('map.empty') }}</p>
    </template>
  </div>
</template>

<style scoped>
.memory-map { margin: 0.5rem 0 1rem; }
.map-error { color: var(--p-red-400, #f87171); }
.map-consent { padding: 1.5rem; border: 1px dashed var(--p-content-border-color, rgba(127,127,127,0.3)); border-radius: 12px; text-align: center; }
.map-consent p { opacity: 0.8; margin: 0 0 0.75rem; }
.map-toolbar { margin-bottom: 0.5rem; }
.upload-trigger { cursor: pointer; }
.upload-input { display: none; }
.map-container { height: 440px; border-radius: 12px; overflow: hidden; }
.track-stats { list-style: none; margin: 0.75rem 0 0; padding: 0; display: flex; flex-direction: column; gap: 0.4rem; }
.track-stats li { display: flex; justify-content: space-between; align-items: baseline; gap: 1rem; padding: 0.4rem 0.6rem; border-radius: 8px; background: var(--p-content-background, rgba(127,127,127,0.06)); border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.2)); }
.track-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.track-metrics { display: flex; gap: 0.9rem; opacity: 0.85; white-space: nowrap; font-variant-numeric: tabular-nums; }
.empty-hint { opacity: 0.6; margin-top: 0.5rem; }
</style>
