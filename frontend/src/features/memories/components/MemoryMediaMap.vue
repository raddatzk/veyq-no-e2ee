<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LayerGroup, Map as LeafletMap } from 'leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import MediaLightbox from '../../../components/media/MediaLightbox.vue'
import { API_BASE } from '../../../shared/api/httpClient'
import { enableOsmTiles, hasOsmTileConsent } from '../../../shared/privacy/osmTiles'
import { useAuthState } from '../../../shared/state/useAuthState'
import type { MemoryMedia, UpdateMemoryMediaMetadataRequest } from '../api'

const DEFAULT_OSM_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const MARKER_CLUSTER_DISTANCE_PX = 34

type MapMarkerGroup = {
  id: string
  media: MemoryMedia[]
  position: [number, number]
}

const props = defineProps<{
  tenantName: string
  media: MemoryMedia[]
  canEditMetadata?: boolean
  canDeleteMedia?: boolean
  updateMetadata?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaMetadataRequest
  ) => Promise<MemoryMedia>
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
}>()
const emit = defineEmits<{
  deleted: [media: MemoryMedia]
}>()

const { t } = useI18n()
const { authState } = useAuthState()
const userId = authState.user?.userId ?? null
const mapElement = ref<HTMLElement | null>(null)
const selectedMediaId = ref<string | null>(null)
const selectedMarkerGroupId = ref<string | null>(null)
const selectedMarkerGroupMediaIds = ref<string[] | null>(null)
const markerGroups = ref<MapMarkerGroup[]>([])
const mediaOverrides = ref<Record<string, MemoryMedia>>({})
let map: LeafletMap | null = null
let markerLayer: LayerGroup | null = null
let tileLayer: L.TileLayer | null = null
let resizeObserver: ResizeObserver | null = null
let resizeFrame: number | null = null

const osmTilesEnabled = ref(hasOsmTileConsent(userId))
const tileUrl = computed(() => (osmTilesEnabled.value ? DEFAULT_OSM_TILE_URL : ''))
const allMedia = computed(() => props.media.map((media) => mediaOverrides.value[media.id] || media))
const geotaggedMedia = computed(() =>
  allMedia.value.filter((media) => media.gpsLatitude !== null && media.gpsLongitude !== null)
)
const selectedMediaList = computed(() => {
  if (!selectedMarkerGroupMediaIds.value) return geotaggedMedia.value
  const mediaById = new Map(geotaggedMedia.value.map((media) => [media.id, media]))
  const groupMedia = selectedMarkerGroupMediaIds.value
    .map((mediaId) => mediaById.get(mediaId))
    .filter((media): media is MemoryMedia => Boolean(media))
  return groupMedia.length ? groupMedia : geotaggedMedia.value
})
const selectedIndex = computed(() =>
  selectedMediaId.value
    ? selectedMediaList.value.findIndex((media) => media.id === selectedMediaId.value)
    : -1
)
const selectedMedia = computed(() =>
  selectedIndex.value >= 0 ? selectedMediaList.value[selectedIndex.value] : null
)
const canShowPrevious = computed(() => selectedIndex.value > 0)
const canShowNext = computed(
  () => selectedIndex.value >= 0 && selectedIndex.value < selectedMediaList.value.length - 1
)
const mapSignature = computed(() =>
  geotaggedMedia.value
    .map((media) => `${media.id}:${media.gpsLatitude}:${media.gpsLongitude}:${media.thumbnailUrl}:${media.previewUrl}`)
    .join('|')
)

onMounted(() => {
  void renderMap()
})

watch(
  [mapElement, mapSignature, tileUrl],
  () => {
    void renderMap()
  },
  { flush: 'post' }
)

watch(
  () => props.media.map((media) => media.id).join('|'),
  () => {
    const currentIds = new Set(props.media.map((media) => media.id))
    mediaOverrides.value = Object.fromEntries(
      Object.entries(mediaOverrides.value).filter(([mediaId]) => currentIds.has(mediaId))
    )
    if (selectedMediaId.value && !currentIds.has(selectedMediaId.value)) closeLightbox()
  }
)

watch(selectedMedia, (media) => {
  if (media) {
    window.addEventListener('keydown', handleKeydown)
  } else {
    window.removeEventListener('keydown', handleKeydown)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  destroyMap()
})

async function renderMap() {
  await nextTick()

  if (!mapElement.value || geotaggedMedia.value.length === 0 || !tileUrl.value) {
    destroyMap()
    return
  }

  attachResizeObserver(mapElement.value)

  if (!map) {
    map = L.map(mapElement.value, {
      scrollWheelZoom: true,
      touchZoom: true,
      zoomControl: true
    })
    markerLayer = L.layerGroup().addTo(map)
    map.on('zoomend moveend', renderMarkerGroups)
  }

  syncTileLayer()
  fitMediaBounds()
  renderMarkerGroups()
  map.invalidateSize()
}

function destroyMap() {
  if (resizeFrame !== null) window.cancelAnimationFrame(resizeFrame)
  resizeFrame = null
  resizeObserver?.disconnect()
  resizeObserver = null
  tileLayer?.remove()
  tileLayer = null
  markerLayer?.clearLayers()
  markerLayer = null
  markerGroups.value = []
  map?.off('zoomend moveend', renderMarkerGroups)
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
      fitMediaBounds()
      renderMarkerGroups()
    })
  })
  resizeObserver.observe(element)
}

function fitMediaBounds() {
  if (!map || geotaggedMedia.value.length === 0) return
  const positions = geotaggedMedia.value.map(
    (media) => [media.gpsLatitude!, media.gpsLongitude!] as [number, number]
  )
  if (positions.length === 1) {
    map.setView(positions[0], 13)
  } else {
    const bounds = L.latLngBounds(positions)
    map.fitBounds(bounds, { padding: [36, 36], maxZoom: 15 })
  }
}

function renderMarkerGroups() {
  if (!map || !markerLayer) return
  markerGroups.value = buildMarkerGroups()
  markerLayer.clearLayers()

  markerGroups.value.forEach((group) => {
    const firstMedia = group.media[0]
    const marker = L.marker(group.position, {
      icon: createMarkerIcon(group),
      title: firstMedia.title || firstMedia.id
    })
    marker.on('click', () => openLightbox(firstMedia.id, group))
    marker.addTo(markerLayer!)
  })
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

function createMarkerIcon(group: MapMarkerGroup) {
  const media = group.media[0]
  const root = document.createElement('span')
  root.className = 'memory-media-map-marker'
  if (group.media.length > 1) root.classList.add('memory-media-map-marker-cluster')

  if (media.thumbnailUrl || media.previewUrl || media.mimeType.startsWith('image/')) {
    root.style.backgroundImage = `url("${API_BASE}${media.thumbnailUrl || media.previewUrl || media.contentUrl}")`
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

function openLightbox(mediaId: string, group?: MapMarkerGroup) {
  selectedMediaId.value = mediaId
  selectedMarkerGroupId.value = group?.id || null
  selectedMarkerGroupMediaIds.value = group ? group.media.map((media) => media.id) : null
}

function closeLightbox() {
  selectedMediaId.value = null
  selectedMarkerGroupId.value = null
  selectedMarkerGroupMediaIds.value = null
}

function showPrevious() {
  if (!canShowPrevious.value) return
  selectedMediaId.value = selectedMediaList.value[selectedIndex.value - 1]?.id || null
}

function showNext() {
  if (!canShowNext.value) return
  selectedMediaId.value = selectedMediaList.value[selectedIndex.value + 1]?.id || null
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    closeLightbox()
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    showPrevious()
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    showNext()
  }
}

function updateMedia(media: MemoryMedia) {
  mediaOverrides.value = { ...mediaOverrides.value, [media.id]: media }
}

function removeMedia(media: MemoryMedia) {
  mediaOverrides.value = Object.fromEntries(
    Object.entries(mediaOverrides.value).filter(([mediaId]) => mediaId !== media.id)
  )
  closeLightbox()
  emit('deleted', media)
}

function allowOsmTiles() {
  enableOsmTiles(userId)
  osmTilesEnabled.value = true
}
</script>

<template>
  <section class="memory-section">
    <div class="memory-section-header">
      <h2>{{ t('map.title') }}</h2>
      <span>{{ geotaggedMedia.length }}</span>
    </div>

    <div v-if="geotaggedMedia.length" class="memory-media-map-shell">
      <div
        ref="mapElement"
        class="memory-media-map-canvas"
        :class="{ 'memory-media-map-canvas-without-tiles': !tileUrl }"
        :aria-label="t('map.title')"
      >
        <div v-if="!tileUrl" class="memory-media-map-consent">
          <div>
            <strong>{{ t('map.externalTilesTitle') }}</strong>
            <p>{{ t('map.externalTilesCopy') }}</p>
          </div>
          <button type="button" class="memory-media-map-consent-button" @click="allowOsmTiles">
            {{ t('map.externalTilesAllow') }}
          </button>
        </div>
      </div>
    </div>

    <div v-else class="memory-empty memory-empty-action">
      <span class="pi pi-map-marker" />
      <span>{{ t('map.empty') }}</span>
      <small>{{ t('map.emptyAction') }}</small>
    </div>

    <MediaLightbox
      v-if="selectedMedia"
      :tenant-name="tenantName"
      :media="selectedMedia"
      :can-show-previous="canShowPrevious"
      :can-show-next="canShowNext"
      :can-edit-metadata="canEditMetadata"
      :can-delete="canDeleteMedia"
      :update-metadata="updateMetadata"
      :delete-media="deleteMedia"
      @close="closeLightbox"
      @previous="showPrevious"
      @next="showNext"
      @updated="updateMedia"
      @deleted="removeMedia"
    />
  </section>
</template>
