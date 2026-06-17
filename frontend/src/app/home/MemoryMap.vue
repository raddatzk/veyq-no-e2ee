<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { t } from '../../i18n/i18n'
import { mapTilesConsent } from '../../features/account/privacy/mapConsent'

const props = defineProps<{
  points: { id: string; lat: number; lng: number }[]
  tracks?: { id: string; name: string; points: { lat: number; lng: number }[] }[]
}>()
const emit = defineEmits<{ select: [id: string] }>()

const el = ref<HTMLElement | null>(null)
let map: L.Map | null = null
let layer: L.LayerGroup | null = null

// Each track gets a distinct colour (cycled) so overlapping routes are tellable apart, with start/end dots.
const TRACK_COLORS = ['#9b8cff', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#ec4899']

function render() {
  if (!map) return
  layer?.remove()
  layer = L.layerGroup().addTo(map)
  const latlngs: L.LatLngTuple[] = []
  ;(props.tracks ?? []).forEach((t, i) => {
    const color = TRACK_COLORS[i % TRACK_COLORS.length]
    const line = t.points.map((p) => [p.lat, p.lng] as L.LatLngTuple)
    if (line.length > 1) {
      L.polyline(line, { color, weight: 4, opacity: 0.85 }).bindTooltip(t.name).addTo(layer!)
      // start (green ring) + end (red ring) endpoint markers
      L.circleMarker(line[0], { radius: 5, color: '#16a34a', weight: 3, fillColor: color, fillOpacity: 1 }).bindTooltip(`${t.name} — Start`).addTo(layer!)
      L.circleMarker(line[line.length - 1], { radius: 5, color: '#dc2626', weight: 3, fillColor: color, fillOpacity: 1 }).bindTooltip(`${t.name} — Ende`).addTo(layer!)
    }
    latlngs.push(...line)
  })
  for (const p of props.points) {
    latlngs.push([p.lat, p.lng])
    L.circleMarker([p.lat, p.lng], { radius: 7, color: '#fff', weight: 2, fillColor: '#9b8cff', fillOpacity: 1 })
      .on('click', () => emit('select', p.id))
      .addTo(layer)
  }
  if (latlngs.length === 1) map.setView(latlngs[0], 13)
  else if (latlngs.length > 1) map.fitBounds(L.latLngBounds(latlngs).pad(0.2))
}

// The Leaflet map + tile layer are created ONLY after consent — so no tile request (and thus no location
// disclosure to OpenStreetMap) happens until the user opts in. referrerPolicy keeps our URL out of the Referer.
function initMap() {
  if (map || !el.value) return
  map = L.map(el.value, { attributionControl: true }).setView([20, 0], 2)
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap',
    referrerPolicy: 'no-referrer',
  }).addTo(map)
  render()
}
function teardown() { map?.remove(); map = null; layer = null }
function enable() { mapTilesConsent.value = true }

onMounted(async () => { if (mapTilesConsent.value) { await nextTick(); initMap() } })
watch(mapTilesConsent, async (on) => { if (on) { await nextTick(); initMap() } else teardown() })
watch([() => props.points, () => props.tracks], () => { if (map) render() }, { deep: true })
onBeforeUnmount(teardown)
</script>

<template>
  <div v-if="mapTilesConsent" ref="el" class="memory-map"></div>
  <div v-else class="map-consent">
    <p class="map-consent-text">{{ t('map.consentText') }}</p>
    <button class="btn btn-primary" type="button" @click="enable">{{ t('map.consentEnable') }}</button>
  </div>
</template>

<style scoped>
.map-consent {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  min-height: 240px;
  padding: 2rem;
  text-align: center;
  border: 1px dashed var(--app-border, #3a3a3a);
  border-radius: 12px;
}
.map-consent-text { max-width: 32rem; color: var(--app-muted, #999); }
</style>
