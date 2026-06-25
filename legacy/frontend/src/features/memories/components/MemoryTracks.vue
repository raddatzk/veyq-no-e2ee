<script setup lang="ts">
// A memory's GPX routes on a Leaflet map (E2EE). Track points are decrypted by getTracks; map tiles load
// only after per-device consent (mapConsent), since a tile request reveals coarse location to a third party.
// GPX files are parsed client-side and stored sealed under the scope content key.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { getTracks, createTrack, type Track } from '../../memory/track/track'
import { parseGpx } from '../../memory/track/gpx'
import { mapTilesConsent } from '../../account/privacy/mapConsent'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'

const OSM_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const { t } = useI18n()

const mapEl = ref<HTMLElement | null>(null)
const tracks = ref<Track[]>([])
const error = ref('')
let map: L.Map | null = null
let tileLayer: L.TileLayer | null = null
let lines: L.Polyline[] = []

onMounted(async () => {
  await load()
  initMap()
})
onBeforeUnmount(() => { map?.remove(); map = null })

watch(mapTilesConsent, syncTiles)

async function load() {
  error.value = ''
  try {
    tracks.value = await getTracks(session.apiBase, requireSession(), props.active, props.nodeId)
  } catch {
    error.value = t('tracks.loadFailed')
  }
}

function initMap() {
  if (!mapEl.value || map) return
  map = L.map(mapEl.value, { attributionControl: true })
  syncTiles()
  draw()
}

function syncTiles() {
  if (!map) return
  tileLayer?.remove()
  tileLayer = null
  if (mapTilesConsent.value) {
    tileLayer = L.tileLayer(OSM_TILE_URL, { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map)
  }
}

function draw() {
  if (!map) return
  lines.forEach((l) => l.remove())
  lines = []
  const all: L.LatLngExpression[] = []
  for (const track of tracks.value) {
    const pts = track.points.map((p) => [p.lat, p.lng] as L.LatLngExpression)
    if (pts.length === 0) continue
    lines.push(L.polyline(pts, { color: '#5b4df5', weight: 4 }).addTo(map))
    all.push(...pts)
  }
  if (all.length > 0) map.fitBounds(L.latLngBounds(all), { padding: [24, 24], maxZoom: 15 })
  else map.setView([0, 0], 2)
}

async function onGpx(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  error.value = ''
  try {
    const route = parseGpx(await file.text())
    await createTrack(session.apiBase, requireSession(), props.active, props.nodeId, route)
    await load()
    draw()
  } catch {
    error.value = t('tracks.addFailed')
  }
}
</script>

<template>
  <section class="memory-tracks">
    <div class="settings-section-header tracks-header">
      <h2>{{ t('tracks.title') }}</h2>
      <label class="kit-file-trigger">
        <span class="p-button p-component p-button-secondary p-button-text"><i class="pi pi-map" />&nbsp;{{ t('tracks.addGpx') }}</span>
        <input class="kit-file-input" type="file" accept=".gpx,application/gpx+xml,application/xml,text/xml" @change="onGpx" />
      </label>
    </div>

    <p v-if="tracks.length === 0 && !error" class="tracks-empty">{{ t('tracks.empty') }}</p>

    <div v-show="tracks.length > 0" class="tracks-map-wrap">
      <div ref="mapEl" class="tracks-map" />
      <button v-if="!mapTilesConsent" type="button" class="tracks-consent" @click="mapTilesConsent = true">
        {{ t('privacy.osmProviderTitle') }}
      </button>
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
  </section>
</template>

<style scoped>
.memory-tracks { margin: 1.5rem 0; }
.tracks-header { display: flex; align-items: center; justify-content: space-between; }
.tracks-empty { opacity: 0.55; font-size: 0.9rem; }
.tracks-map-wrap { position: relative; }
.tracks-map { height: 360px; border-radius: 10px; overflow: hidden; background: var(--p-content-border-color, rgba(127,127,127,0.12)); }
.tracks-consent {
  position: absolute; bottom: 0.75rem; left: 50%; transform: translateX(-50%); z-index: 500;
  padding: 0.5rem 0.9rem; border-radius: 999px; border: none; cursor: pointer;
  background: var(--p-primary-color, #5b4df5); color: #fff; font-size: 0.85rem;
}
</style>
