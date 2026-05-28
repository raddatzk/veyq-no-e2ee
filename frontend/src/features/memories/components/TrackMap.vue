<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Map as LeafletMap, Polyline } from 'leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { enableOsmTiles, hasOsmTileConsent } from '../../../shared/privacy/osmTiles'
import { useAuthState } from '../../../shared/state/useAuthState'
import type { MemoryTrack } from '../api'

const DEFAULT_OSM_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'

const props = defineProps<{
  track: MemoryTrack
}>()

const { authState } = useAuthState()
const userId = authState.user?.userId ?? null
const mapElement = ref<HTMLElement | null>(null)
const osmTilesEnabled = ref(hasOsmTileConsent(userId))
const tileUrl = computed(() => (osmTilesEnabled.value ? DEFAULT_OSM_TILE_URL : ''))
const positions = computed<[number, number][]>(() =>
  props.track.geometry.coordinates.map(([longitude, latitude]) => [latitude, longitude])
)
const mapSignature = computed(
  () => `${props.track.id}:${tileUrl.value}:${positions.value.map((position) => position.join(',')).join('|')}`
)

let map: LeafletMap | null = null
let routeLayer: Polyline | null = null
let tileLayer: L.TileLayer | null = null
let resizeObserver: ResizeObserver | null = null
let resizeFrame: number | null = null

onMounted(() => {
  void renderMap()
})

watch(
  [mapElement, mapSignature],
  () => {
    void renderMap()
  },
  { flush: 'post' }
)

onBeforeUnmount(() => {
  destroyMap()
})

async function renderMap() {
  await nextTick()

  if (!mapElement.value || positions.value.length < 2) {
    destroyMap()
    return
  }

  attachResizeObserver(mapElement.value)

  if (!map) {
    map = L.map(mapElement.value, {
      attributionControl: true,
      scrollWheelZoom: true,
      touchZoom: true,
      zoomControl: true
    })
  }

  syncTileLayer()
  syncRouteLayer()
  fitTrackBounds()
  map.invalidateSize()
}

function destroyMap() {
  if (resizeFrame !== null) window.cancelAnimationFrame(resizeFrame)
  resizeFrame = null
  resizeObserver?.disconnect()
  resizeObserver = null
  routeLayer?.remove()
  routeLayer = null
  tileLayer?.remove()
  tileLayer = null
  map?.remove()
  map = null
}

function syncTileLayer() {
  if (!map) return
  tileLayer?.remove()
  tileLayer = null

  if (!tileUrl.value) return
  tileLayer = L.tileLayer(tileUrl.value, {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19
  }).addTo(map)
}

function syncRouteLayer() {
  if (!map) return
  routeLayer?.remove()
  routeLayer = L.polyline(positions.value, {
    color: 'var(--app-success)',
    lineCap: 'round',
    lineJoin: 'round',
    opacity: 0.95,
    weight: 4
  }).addTo(map)
}

function fitTrackBounds() {
  if (!map || positions.value.length < 2) return
  const bounds = L.latLngBounds(positions.value)
  map.fitBounds(bounds, { padding: [24, 24], maxZoom: 15 })
}

function attachResizeObserver(element: HTMLElement) {
  if (resizeObserver) return
  if (typeof ResizeObserver === 'undefined') return

  resizeObserver = new ResizeObserver((entries) => {
    const entry = entries[0]
    if (!entry || entry.contentRect.width <= 0 || entry.contentRect.height <= 0) return
    if (resizeFrame !== null) window.cancelAnimationFrame(resizeFrame)
    resizeFrame = window.requestAnimationFrame(() => {
      resizeFrame = null
      map?.invalidateSize()
      fitTrackBounds()
    })
  })
  resizeObserver.observe(element)
}

function allowOsmTiles() {
  enableOsmTiles(userId)
  osmTilesEnabled.value = true
}
</script>

<template>
  <div
    ref="mapElement"
    class="track-map"
    :class="{ 'track-map-without-tiles': !tileUrl }"
    role="img"
    :aria-label="track.title"
  >
    <div v-if="!tileUrl" class="track-map-consent">
      <span>{{ $t('map.externalTilesTitle') }}</span>
      <button type="button" @click="allowOsmTiles">{{ $t('map.externalTilesAllow') }}</button>
    </div>
  </div>
</template>
