<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Map as LeafletMap, Polyline } from 'leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { enableOsmTiles, hasOsmTileConsent } from '../../../shared/privacy/osmTiles'
import { useAuthState } from '../../../shared/state/useAuthState'
import { mediaPreviewUrl } from '../../../shared/utils/mediaUrls'
import { createRafResizeObserver, type RafResizeObserver } from '../../../shared/utils/resizeObserver'
import type { MemoryMedia, MemoryTrack } from '../api'

const DEFAULT_OSM_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const MARKER_CLUSTER_DISTANCE_PX = 34

type MapMarkerGroup = {
  id: string
  media: MemoryMedia[]
  position: [number, number]
}

const props = defineProps<{
  track?: MemoryTrack
  tracks?: MemoryTrack[]
  media?: MemoryMedia[]
}>()

const emit = defineEmits<{
  openMediaGroup: [media: MemoryMedia[]]
}>()

const { authState } = useAuthState()
const userId = authState.user?.userId ?? null
const mapElement = ref<HTMLElement | null>(null)
const osmTilesEnabled = ref(hasOsmTileConsent(userId))
const tileUrl = computed(() => (osmTilesEnabled.value ? DEFAULT_OSM_TILE_URL : ''))
const activeTracks = computed(() => {
  const tracks = props.tracks ?? (props.track ? [props.track] : [])
  return tracks.filter((track) => track.geometry.coordinates.length >= 2)
})
const trackPositions = computed(() =>
  activeTracks.value.map((track) => ({
    track,
    positions: track.geometry.coordinates.map(([longitude, latitude]) => [latitude, longitude] as [number, number])
  }))
)
const allPositions = computed<[number, number][]>(() =>
  trackPositions.value.flatMap((track) => track.positions)
)
const geotaggedMedia = computed(() =>
  (props.media ?? []).filter((media) => media.gpsLatitude !== null && media.gpsLongitude !== null)
)
const mapSignature = computed(
  () =>
    `${tileUrl.value}:${trackPositions.value
      .map(({ track, positions }) => `${track.id}:${positions.map((position) => position.join(',')).join('|')}`)
      .join(';')}:${geotaggedMedia.value
      .map((media) => `${media.id}:${media.gpsLatitude}:${media.gpsLongitude}:${media.thumbnailUrl}:${media.previewUrl}`)
      .join('|')}`
)

let map: LeafletMap | null = null
let routeLayers: Polyline[] = []
let markerLayers: L.Marker[] = []
let tileLayer: L.TileLayer | null = null
let resizeObserver: RafResizeObserver | null = null

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

  if (!mapElement.value || allPositions.value.length < 2) {
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
    map.on('zoomend moveend', syncMediaMarkers)
  }

  syncTileLayer()
  syncRouteLayer()
  syncMediaMarkers()
  fitTrackBounds()
  map.invalidateSize()
}

function destroyMap() {
  resizeObserver?.disconnect()
  resizeObserver = null
  routeLayers.forEach((layer) => layer.remove())
  routeLayers = []
  markerLayers.forEach((layer) => layer.remove())
  markerLayers = []
  tileLayer?.remove()
  tileLayer = null
  map?.off('zoomend moveend', syncMediaMarkers)
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
  routeLayers.forEach((layer) => layer.remove())
  routeLayers = trackPositions.value.map(({ positions }, index) =>
    L.polyline(positions, {
      color: trackColor(index),
      lineCap: 'round',
      lineJoin: 'round',
      opacity: 0.95,
      weight: 4
    }).addTo(map!)
  )
}

function syncMediaMarkers() {
  if (!map) return
  markerLayers.forEach((layer) => layer.remove())
  markerLayers = buildMarkerGroups().map((group) =>
    L.marker(group.position, {
      icon: createMediaMarkerIcon(group),
      title: mediaMarkerTitle(group)
    })
      .on('click', () => emit('openMediaGroup', group.media))
      .addTo(map!)
  )
}

function buildMarkerGroups(): MapMarkerGroup[] {
  if (!map) return []
  const groups: MapMarkerGroup[] = []

  geotaggedMedia.value.forEach((media) => {
    const position: [number, number] = [media.gpsLatitude!, media.gpsLongitude!]
    const point = map!.latLngToLayerPoint(position)
    const matchingGroup = groups.find((group) => {
      const groupPoint = map!.latLngToLayerPoint(group.position)
      return point.distanceTo(groupPoint) <= MARKER_CLUSTER_DISTANCE_PX
    })

    if (matchingGroup) {
      matchingGroup.media.push(media)
    } else {
      groups.push({
        id: media.id,
        media: [media],
        position
      })
    }
  })

  return groups.map((group) => ({
    ...group,
    id: group.media.map((media) => media.id).join('|')
  }))
}

function fitTrackBounds() {
  if (!map || allPositions.value.length < 2) return
  const bounds = L.latLngBounds(allPositions.value)
  map.fitBounds(bounds, { padding: [24, 24], maxZoom: 15 })
}

function createMediaMarkerIcon(group: MapMarkerGroup) {
  const media = group.media[0]
  const root = document.createElement('span')
  root.className = 'memory-media-map-marker memory-journal-track-map-marker'
  if (group.media.length > 1) root.classList.add('memory-media-map-marker-cluster')

  if (media.thumbnailUrl || media.previewUrl || media.mimeType.startsWith('image/')) {
    root.style.backgroundImage = `url("${mediaPreviewUrl(media)}")`
  } else {
    const icon = document.createElement('span')
    icon.className = 'pi pi-video'
    root.append(icon)
  }

  if (group.media.length > 1) {
    const badge = document.createElement('span')
    badge.className = 'memory-media-map-marker-badge'
    badge.textContent = String(group.media.length)
    root.append(badge)
  }

  return L.divIcon({
    className: 'memory-media-map-marker-wrapper',
    html: root,
    iconSize: [30, 38],
    iconAnchor: [15, 37]
  })
}

function mediaMarkerTitle(group: MapMarkerGroup): string {
  if (group.media.length === 1) return group.media[0].title || group.media[0].id
  return `${group.media.length} media`
}

function trackColor(index: number): string {
  const colors = [
    'var(--app-success)',
    'var(--app-accent)',
    'var(--app-info)',
    'var(--app-warning-text)',
    'var(--app-danger)'
  ]
  return colors[index % colors.length]
}

function attachResizeObserver(element: HTMLElement) {
  if (resizeObserver) return
  if (typeof ResizeObserver === 'undefined') return

  resizeObserver = createRafResizeObserver(() => {
    map?.invalidateSize()
    fitTrackBounds()
  })
  resizeObserver?.observe(element)
}

function allowOsmTiles() {
  enableOsmTiles(userId)
  osmTilesEnabled.value = true
}

function resetMapView() {
  fitTrackBounds()
}
</script>

<template>
  <div
    ref="mapElement"
    class="track-map"
    :class="{ 'track-map-without-tiles': !tileUrl }"
    role="img"
    :aria-label="activeTracks.map((track) => track.title).join(', ')"
  >
    <button type="button" class="track-map-reset" @click="resetMapView">
      {{ $t('map.resetView') }}
    </button>
    <div v-if="!tileUrl" class="track-map-consent">
      <span>{{ $t('map.externalTilesTitle') }}</span>
      <button type="button" @click="allowOsmTiles">{{ $t('map.externalTilesAllow') }}</button>
    </div>
  </div>
</template>
