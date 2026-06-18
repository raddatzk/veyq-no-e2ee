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
let layer: L.LayerGroup | null = null        // static: tracks
let markerLayer: L.LayerGroup | null = null  // media pins, re-clustered on zoom

// Each track gets a distinct colour (cycled) so overlapping routes are tellable apart, with start/end dots.
const TRACK_COLORS = ['#9b8cff', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#ec4899']
// Media pins within this many screen pixels (at the current zoom) collapse into one cluster marker (Legacy: 34).
const MARKER_CLUSTER_DISTANCE_PX = 34

type Cluster = { lat: number; lng: number; ids: string[] }

// Group geotagged media by on-screen proximity at the current zoom (so clusters split as you zoom in).
function clusterPoints(): Cluster[] {
  const groups: Cluster[] = []
  for (const p of props.points) {
    const pt = map!.latLngToLayerPoint([p.lat, p.lng])
    const g = groups.find((g) => map!.latLngToLayerPoint([g.lat, g.lng]).distanceTo(pt) <= MARKER_CLUSTER_DISTANCE_PX)
    if (g) g.ids.push(p.id)
    else groups.push({ lat: p.lat, lng: p.lng, ids: [p.id] })
  }
  return groups
}

// (Re)draw just the media markers from the current-zoom clustering. Single → tap opens it; cluster → tap zooms in.
function renderMarkers() {
  if (!map) return
  markerLayer?.remove()
  markerLayer = L.layerGroup().addTo(map)
  for (const c of clusterPoints()) {
    if (c.ids.length === 1) {
      L.circleMarker([c.lat, c.lng], { radius: 7, color: '#fff', weight: 2, fillColor: '#9b8cff', fillOpacity: 1 })
        .on('click', () => emit('select', c.ids[0]))
        .addTo(markerLayer!)
    } else {
      const icon = L.divIcon({ className: 'map-cluster-wrap', html: `<span class="map-cluster">${c.ids.length}</span>`, iconSize: [30, 30], iconAnchor: [15, 15] })
      L.marker([c.lat, c.lng], { icon })
        .on('click', () => map!.setView([c.lat, c.lng], Math.min(19, map!.getZoom() + 2)))
        .addTo(markerLayer!)
    }
  }
}

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
  props.points.forEach((p) => latlngs.push([p.lat, p.lng]))
  if (latlngs.length === 1) map.setView(latlngs[0], 13)
  else if (latlngs.length > 1) map.fitBounds(L.latLngBounds(latlngs).pad(0.2))
  renderMarkers()
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
  map.on('zoomend', renderMarkers)  // re-cluster as the pixel distances between pins change
  render()
}
function teardown() { map?.remove(); map = null; layer = null; markerLayer = null }
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
  min-height: min(60vh, 520px);
  padding: 2rem;
  text-align: center;
  background: var(--app-surface);
  border: 1px dashed var(--app-line);
  border-radius: var(--app-radius);
}
.map-consent-text { max-width: 32rem; color: var(--app-muted, #999); }
</style>

<style>
/* Cluster marker: a violet count badge standing in for several overlapping media pins. */
.map-cluster {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #9b8cff;
  color: #fff;
  font-size: 0.8rem;
  font-weight: 700;
  border: 2px solid #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.4);
}
</style>
