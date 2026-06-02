<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import type { LayerGroup, Map as LeafletMap } from 'leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import MediaLightbox from '../../../components/media/MediaLightbox.vue'
import ConfirmationDialog from '../../../shared/components/ConfirmationDialog.vue'
import { enableOsmTiles, hasOsmTileConsent } from '../../../shared/privacy/osmTiles'
import { useAuthState } from '../../../shared/state/useAuthState'
import { mediaPreviewUrl } from '../../../shared/utils/mediaUrls'
import {
  createRafResizeObserver,
  type RafResizeObserver
} from '../../../shared/utils/resizeObserver'
import type {
  MemoryMedia,
  MemoryTrack,
  MemoryTrackActivityType,
  UpdateMemoryMediaCapturedAtRequest,
  UpdateMemoryMediaHighlightRequest,
  UpdateMemoryTrackRequest,
  UploadMemoryTrackRequest
} from '../api'

const DEFAULT_OSM_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const MARKER_CLUSTER_DISTANCE_PX = 34
const TRACK_COLOR_SEED_HUE = 154
const TRACK_COLOR_CANDIDATES = 72

type MapMarkerGroup = {
  id: string
  media: MemoryMedia[]
  position: [number, number]
}

type TrackStyle = {
  color: string
  dashArray?: string
}

const props = defineProps<{
  tenantName: string
  media: MemoryMedia[]
  tracks: MemoryTrack[]
  canEditMetadata?: boolean
  canDeleteMedia?: boolean
  canEditTracks?: boolean
  updateCapturedAt?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaCapturedAtRequest
  ) => Promise<MemoryMedia>
  updateHighlight?: (
    mediaId: string,
    memoryId: string,
    request: UpdateMemoryMediaHighlightRequest
  ) => Promise<MemoryMedia>
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
  uploadTrack?: (request: UploadMemoryTrackRequest) => Promise<MemoryTrack>
  updateTrack?: (trackId: string, request: UpdateMemoryTrackRequest) => Promise<MemoryTrack>
  deleteTrack?: (trackId: string) => Promise<void>
}>()

const emit = defineEmits<{
  deleted: [media: MemoryMedia]
  changed: []
}>()

const { t } = useI18n()
const { authState } = useAuthState()
const userId = authState.user?.userId ?? null
const mapElement = ref<HTMLElement | null>(null)
const selectedMediaId = ref<string | null>(null)
const selectedMarkerGroupMediaIds = ref<string[] | null>(null)
const markerGroups = ref<MapMarkerGroup[]>([])
const mediaOverrides = ref<Record<string, MemoryMedia>>({})
const visibleTrackIds = ref<Set<string>>(new Set(props.tracks.map((track) => track.id)))
const selectedTrackId = ref<string | null>(props.tracks[0]?.id ?? null)
const uploadOpen = ref(false)
const editTrack = ref<MemoryTrack | null>(null)
const deleteCandidate = ref<MemoryTrack | null>(null)
const selectedFile = ref<File | null>(null)
const title = ref('')
const activityType = ref<MemoryTrackActivityType>('HIKING')
const saving = ref(false)
const error = ref('')
const osmTilesEnabled = ref(hasOsmTileConsent(userId))

let map: LeafletMap | null = null
let markerLayer: LayerGroup | null = null
let trackLayer: LayerGroup | null = null
let endpointLayer: LayerGroup | null = null
let tileLayer: L.TileLayer | null = null
let resizeObserver: RafResizeObserver | null = null

const tileUrl = computed(() => (osmTilesEnabled.value ? DEFAULT_OSM_TILE_URL : ''))
const allMedia = computed(() => props.media.map((media) => mediaOverrides.value[media.id] || media))
const geotaggedMedia = computed(() =>
  allMedia.value.filter((media) => media.gpsLatitude !== null && media.gpsLongitude !== null)
)
const trackStyles = computed(() => buildTrackStyles(props.tracks))
const visibleTracks = computed(() =>
  props.tracks.filter((track) => visibleTrackIds.value.has(track.id))
)
const hasMapContent = computed(
  () =>
    geotaggedMedia.value.length > 0 ||
    props.tracks.some((track) => trackPositions(track).length >= 2)
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
const activityOptions = computed(() =>
  ACTIVITY_TYPES.map((value) => ({ label: t(`track.activity.${value}`), value }))
)
const mapSignature = computed(() =>
  [
    tileUrl.value,
    geotaggedMedia.value
      .map(
        (media) =>
          `${media.id}:${media.gpsLatitude}:${media.gpsLongitude}:${media.thumbnailUrl}:${media.previewUrl}`
      )
      .join('|'),
    props.tracks
      .map(
        (track) =>
          `${track.id}:${visibleTrackIds.value.has(track.id)}:${track.geometry.coordinates.map((coordinate) => coordinate.join(',')).join(';')}`
      )
      .join('|')
  ].join('::')
)

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

watch(
  () => props.tracks.map((track) => track.id).join('|'),
  () => {
    const currentIds = new Set(props.tracks.map((track) => track.id))
    const nextVisibleIds = new Set(
      [...visibleTrackIds.value].filter((trackId) => currentIds.has(trackId))
    )
    props.tracks.forEach((track) => {
      if (!visibleTrackIds.value.has(track.id)) nextVisibleIds.add(track.id)
    })
    visibleTrackIds.value = nextVisibleIds
    if (selectedTrackId.value && !currentIds.has(selectedTrackId.value)) {
      selectedTrackId.value = props.tracks[0]?.id ?? null
    }
  }
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

watch(uploadOpen, (open) => {
  if (!open) resetForm()
})

watch(editTrack, (track) => {
  error.value = ''
  title.value = track?.title ?? ''
  activityType.value = track?.activityType ?? 'HIKING'
})

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

  if (!mapElement.value || !hasMapContent.value) {
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
    trackLayer = L.layerGroup().addTo(map)
    endpointLayer = L.layerGroup().addTo(map)
    map.on('zoomend moveend', renderMarkerGroups)
  }

  syncTileLayer()
  renderTracks()
  fitContextBounds()
  renderMarkerGroups()
  map.invalidateSize()
}

function destroyMap() {
  resizeObserver?.disconnect()
  resizeObserver = null
  tileLayer?.remove()
  tileLayer = null
  markerLayer?.clearLayers()
  markerLayer = null
  trackLayer?.clearLayers()
  trackLayer = null
  endpointLayer?.clearLayers()
  endpointLayer = null
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

function renderTracks() {
  if (!map || !trackLayer || !endpointLayer) return
  const routeLayer = trackLayer
  const routeEndpointLayer = endpointLayer
  routeLayer.clearLayers()
  routeEndpointLayer.clearLayers()

  visibleTracks.value.forEach((track) => {
    const positions = trackPositions(track)
    if (positions.length < 2) return
    const style = trackStyles.value.get(track.id) ?? { color: 'hsl(154 72% 38%)' }
    const selected = selectedTrackId.value === track.id
    L.polyline(positions, {
      color: style.color,
      dashArray: style.dashArray,
      lineCap: 'round',
      lineJoin: 'round',
      opacity: selected ? 1 : 0.82,
      weight: selected ? 6 : 4
    })
      .on('click', () => {
        void focusTrack(track.id)
      })
      .addTo(routeLayer)

    const first = positions[0]
    const last = positions[positions.length - 1]
    L.marker(first, {
      icon: createEndpointIcon(style.color, 'start', t('context.start')),
      title: `${track.title} ${t('context.start')}`
    }).addTo(routeEndpointLayer)
    L.marker(last, {
      icon: createEndpointIcon(style.color, 'end', t('context.end')),
      title: `${track.title} ${t('context.end')}`
    }).addTo(routeEndpointLayer)
  })
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

function fitContextBounds() {
  if (!map) return
  const positions = [
    ...geotaggedMedia.value.map(
      (media) => [media.gpsLatitude!, media.gpsLongitude!] as [number, number]
    ),
    ...visibleTracks.value.flatMap((track) => trackPositions(track))
  ]
  if (positions.length === 0) return
  if (positions.length === 1) {
    map.setView(positions[0], 13)
  } else {
    map.fitBounds(L.latLngBounds(positions), { padding: [36, 36], maxZoom: 15 })
  }
}

function fitTrackBounds(track: MemoryTrack) {
  if (!map) return
  const positions = trackPositions(track)
  if (positions.length === 0) return
  if (positions.length === 1) {
    map.setView(positions[0], 13)
  } else {
    map.fitBounds(L.latLngBounds(positions), { padding: [44, 44], maxZoom: 15 })
  }
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

function createEndpointIcon(color: string, kind: 'start' | 'end', title: string) {
  const root = document.createElement('span')
  root.className = `memory-context-track-endpoint memory-context-track-endpoint-${kind}`
  root.style.setProperty('--track-color', color)
  root.setAttribute('aria-label', title)

  const icon = document.createElement('span')
  icon.className = kind === 'start' ? 'pi pi-circle-fill' : 'pi pi-flag'
  root.append(icon)

  return L.divIcon({
    className: 'memory-context-track-endpoint-wrapper',
    html: root,
    iconSize: [24, 24],
    iconAnchor: [12, 12]
  })
}

function attachResizeObserver(element: HTMLElement) {
  if (resizeObserver) return
  if (typeof ResizeObserver === 'undefined') return

  resizeObserver = createRafResizeObserver(() => {
    map?.invalidateSize()
    fitContextBounds()
    renderMarkerGroups()
  })
  resizeObserver?.observe(element)
}

function toggleTrack(trackId: string) {
  const next = new Set(visibleTrackIds.value)
  if (next.has(trackId)) {
    next.delete(trackId)
    if (selectedTrackId.value === trackId) {
      selectedTrackId.value = props.tracks.find((track) => next.has(track.id))?.id ?? null
    }
  } else {
    next.add(trackId)
  }
  visibleTrackIds.value = next
  if (next.has(trackId)) void focusTrack(trackId)
}

async function focusTrack(trackId: string) {
  const track = props.tracks.find((candidate) => candidate.id === trackId)
  if (!track) return
  selectedTrackId.value = trackId
  if (!visibleTrackIds.value.has(trackId)) {
    visibleTrackIds.value = new Set([...visibleTrackIds.value, trackId])
  }
  await nextTick()
  if (!map) {
    await renderMap()
  } else {
    renderTracks()
  }
  fitTrackBounds(track)
}

function selectTrack(trackId: string) {
  void focusTrack(trackId)
}

function openLightbox(mediaId: string, group?: MapMarkerGroup) {
  selectedMediaId.value = mediaId
  selectedMarkerGroupMediaIds.value = group ? group.media.map((media) => media.id) : null
}

function closeLightbox() {
  selectedMediaId.value = null
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

function selectTrackFile(event: FileUploadSelectEvent) {
  selectedFile.value = event.files[0] ?? null
  if (selectedFile.value && !title.value.trim()) {
    title.value = selectedFile.value.name.replace(/\.gpx$/i, '')
  }
}

async function submitUpload() {
  if (!props.uploadTrack || !selectedFile.value) return
  saving.value = true
  error.value = ''
  try {
    await props.uploadTrack({
      file: selectedFile.value,
      title: title.value.trim(),
      activityType: activityType.value
    })
    uploadOpen.value = false
    emit('changed')
  } catch {
    error.value = t('track.saveFailed')
  } finally {
    saving.value = false
  }
}

async function submitEdit() {
  if (!props.updateTrack || !editTrack.value) return
  saving.value = true
  error.value = ''
  try {
    await props.updateTrack(editTrack.value.id, {
      title: title.value.trim(),
      activityType: activityType.value
    })
    editTrack.value = null
    emit('changed')
  } catch {
    error.value = t('track.saveFailed')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (saving.value || !props.deleteTrack || !deleteCandidate.value) return
  saving.value = true
  error.value = ''
  try {
    await props.deleteTrack(deleteCandidate.value.id)
    deleteCandidate.value = null
    emit('changed')
  } catch {
    error.value = t('track.deleteFailed')
  } finally {
    saving.value = false
  }
}

function resetForm() {
  selectedFile.value = null
  title.value = ''
  activityType.value = 'HIKING'
  saving.value = false
  error.value = ''
}

function allowOsmTiles() {
  enableOsmTiles(userId)
  osmTilesEnabled.value = true
}

function trackPositions(track: MemoryTrack): [number, number][] {
  return track.geometry.coordinates.map(([longitude, latitude]) => [latitude, longitude])
}

function buildTrackStyles(tracks: MemoryTrack[]): Map<string, TrackStyle> {
  const hues: number[] = []
  return new Map(
    tracks.map((track, index) => {
      const hue = nextDistinctHue(hues, index)
      hues.push(hue)
      return [
        track.id,
        {
          color: `hsl(${hue} 72% 38%)`,
          dashArray: dashArrayForActivity(track.activityType)
        }
      ]
    })
  )
}

function nextDistinctHue(existingHues: number[], index: number): number {
  if (existingHues.length === 0) return TRACK_COLOR_SEED_HUE
  let bestHue = TRACK_COLOR_SEED_HUE
  let bestScore = -1
  for (let candidate = 0; candidate < TRACK_COLOR_CANDIDATES; candidate += 1) {
    const hue =
      (TRACK_COLOR_SEED_HUE + Math.round(candidate * (360 / TRACK_COLOR_CANDIDATES))) % 360
    const distance = Math.min(...existingHues.map((existing) => hueDistance(hue, existing)))
    if (distance > bestScore) {
      bestHue = hue
      bestScore = distance
    }
  }
  return (bestHue + index) % 360
}

function hueDistance(a: number, b: number): number {
  const diff = Math.abs(a - b) % 360
  return Math.min(diff, 360 - diff)
}

function dashArrayForActivity(activity: MemoryTrackActivityType): string | undefined {
  if (activity === 'RUNNING') return '2 8'
  if (activity === 'CYCLING' || activity === 'ROAD_CYCLING' || activity === 'GRAVEL_CYCLING')
    return '10 7'
  if (activity === 'MOUNTAIN_BIKING') return '10 5 2 5'
  if (activity === 'SKIING') return '14 6 3 6'
  if (activity === 'WATER') return '3 9'
  return undefined
}

function formatDistance(meters: number): string {
  if (meters >= 1000)
    return `${(meters / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 })} km`
  return `${meters.toLocaleString()} m`
}

function formatElevation(meters?: number | null): string {
  if (meters === null || meters === undefined) return t('common.notAvailable')
  return `${meters.toLocaleString()} m`
}

function formatDuration(startedAt?: string | null, endedAt?: string | null): string {
  if (!startedAt || !endedAt) return t('common.notAvailable')
  const started = Date.parse(startedAt)
  const ended = Date.parse(endedAt)
  if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) {
    return t('common.notAvailable')
  }
  const totalMinutes = Math.round((ended - started) / 60_000)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours <= 0) return t('track.durationMinutes', { minutes })
  if (minutes === 0) return t('track.durationHours', { hours })
  return t('track.durationHoursMinutes', { hours, minutes })
}

const ACTIVITY_TYPES: MemoryTrackActivityType[] = [
  'HIKING',
  'WALKING',
  'RUNNING',
  'CYCLING',
  'MOUNTAIN_BIKING',
  'ROAD_CYCLING',
  'GRAVEL_CYCLING',
  'SKIING',
  'WATER',
  'OTHER'
]
</script>

<template>
  <section class="memory-context">
    <div class="memory-context-toolbar">
      <div>
        <h2>{{ t('memory.context') }}</h2>
        <p>{{ t('context.summary', { media: geotaggedMedia.length, tracks: tracks.length }) }}</p>
      </div>
      <Button
        v-if="canEditTracks"
        icon="pi pi-map"
        :label="t('track.upload')"
        size="small"
        @click="uploadOpen = true"
      />
    </div>

    <div v-if="hasMapContent" class="memory-context-layout">
      <div
        ref="mapElement"
        class="memory-context-map"
        :class="{ 'memory-context-map-without-tiles': !tileUrl }"
        :aria-label="t('memory.context')"
      >
        <div v-if="!tileUrl" class="memory-context-map-consent">
          <div>
            <strong>{{ t('map.externalTilesTitle') }}</strong>
            <p>{{ t('map.externalTilesCopy') }}</p>
          </div>
          <button type="button" class="memory-media-map-consent-button" @click="allowOsmTiles">
            {{ t('map.externalTilesAllow') }}
          </button>
        </div>
      </div>

      <aside class="memory-context-sidebar" :aria-label="t('track.title')">
        <div v-if="tracks.length === 0" class="memory-context-track-empty">
          <span>{{ t('track.empty') }}</span>
          <small>{{ t('track.emptyAction') }}</small>
        </div>
        <article
          v-for="track in tracks"
          :key="track.id"
          class="memory-context-track"
          :class="{ 'memory-context-track-selected': selectedTrackId === track.id }"
        >
          <div class="memory-context-track-header">
            <label class="memory-context-track-toggle">
              <input
                type="checkbox"
                :checked="visibleTrackIds.has(track.id)"
                :aria-label="
                  visibleTrackIds.has(track.id)
                    ? t('context.hideTrack', { title: track.title })
                    : t('context.showTrack', { title: track.title })
                "
                @change="toggleTrack(track.id)"
              />
              <span
                class="memory-context-track-swatch"
                :style="{ '--track-color': trackStyles.get(track.id)?.color }"
              />
            </label>
            <button type="button" class="memory-context-track-title" @click="selectTrack(track.id)">
              <span>{{ t(`track.activity.${track.activityType}`) }}</span>
              <strong>{{ track.title }}</strong>
            </button>
          </div>

          <dl class="memory-context-track-metrics">
            <div>
              <dt>{{ t('track.distance') }}</dt>
              <dd>{{ formatDistance(track.distanceMeters) }}</dd>
            </div>
            <div>
              <dt>{{ t('track.duration') }}</dt>
              <dd>{{ formatDuration(track.startedAt, track.endedAt) }}</dd>
            </div>
            <div>
              <dt>{{ t('track.ascent') }}</dt>
              <dd>{{ formatElevation(track.ascentMeters) }}</dd>
            </div>
            <div>
              <dt>{{ t('track.descent') }}</dt>
              <dd>{{ formatElevation(track.descentMeters) }}</dd>
            </div>
          </dl>

          <div class="memory-track-actions memory-context-track-actions">
            <a
              :href="track.contentUrl"
              class="p-button p-button-sm p-button-secondary p-button-outlined"
            >
              <span class="pi pi-download" />
              <span>{{ t('track.gpx') }}</span>
            </a>
            <Button
              v-if="canEditTracks"
              icon="pi pi-pencil"
              :aria-label="t('common.edit')"
              size="small"
              severity="secondary"
              outlined
              @click="editTrack = track"
            />
            <Button
              v-if="canEditTracks"
              icon="pi pi-trash"
              :aria-label="t('common.delete')"
              size="small"
              severity="danger"
              outlined
              @click="deleteCandidate = track"
            />
          </div>
        </article>
      </aside>
    </div>

    <div v-else class="veyq-empty veyq-empty-action">
      <span class="pi pi-map-marker" />
      <span>{{ t('context.empty') }}</span>
      <small>{{ t('context.emptyAction') }}</small>
      <Button
        v-if="canEditTracks"
        icon="pi pi-upload"
        :label="t('track.upload')"
        size="small"
        @click="uploadOpen = true"
      />
    </div>

    <Dialog
      v-model:visible="uploadOpen"
      modal
      :header="t('track.upload')"
      class="memory-track-dialog"
    >
      <div class="veyq-form memory-dialog-form">
        <p v-if="error" class="memory-form-error">{{ error }}</p>
        <FileUpload
          mode="basic"
          accept=".gpx,application/gpx+xml,application/xml,text/xml"
          custom-upload
          :choose-label="selectedFile?.name || t('track.chooseFile')"
          :disabled="saving"
          @select="selectTrackFile"
        >
          <template #filelabel />
        </FileUpload>
        <label>
          <span>{{ t('track.name') }}</span>
          <InputText v-model="title" :disabled="saving" />
        </label>
        <label>
          <span>{{ t('track.activityType') }}</span>
          <Select
            v-model="activityType"
            :options="activityOptions"
            option-label="label"
            option-value="value"
            :disabled="saving"
          />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            icon="pi pi-times"
            :label="t('common.cancel')"
            severity="secondary"
            outlined
            :disabled="saving"
            @click="uploadOpen = false"
          />
          <Button
            icon="pi pi-save"
            :label="t('common.save')"
            :loading="saving"
            :disabled="!selectedFile || !title.trim()"
            @click="submitUpload"
          />
        </div>
      </div>
    </Dialog>

    <Dialog
      :visible="Boolean(editTrack)"
      modal
      :header="t('track.edit')"
      class="memory-track-dialog"
      @update:visible="editTrack = null"
    >
      <div class="veyq-form memory-dialog-form">
        <p v-if="error" class="memory-form-error">{{ error }}</p>
        <label>
          <span>{{ t('track.name') }}</span>
          <InputText v-model="title" :disabled="saving" />
        </label>
        <label>
          <span>{{ t('track.activityType') }}</span>
          <Select
            v-model="activityType"
            :options="activityOptions"
            option-label="label"
            option-value="value"
            :disabled="saving"
          />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            icon="pi pi-times"
            :label="t('common.cancel')"
            severity="secondary"
            outlined
            :disabled="saving"
            @click="editTrack = null"
          />
          <Button
            icon="pi pi-save"
            :label="t('common.save')"
            :loading="saving"
            :disabled="!title.trim()"
            @click="submitEdit"
          />
        </div>
      </div>
    </Dialog>

    <ConfirmationDialog
      :visible="Boolean(deleteCandidate)"
      :title="t('track.delete')"
      :confirm-label="t('common.delete')"
      confirm-icon="pi pi-trash"
      confirm-severity="danger"
      dialog-class="memory-track-dialog"
      form-class="memory-dialog-form"
      :loading="saving"
      @update:visible="
        (nextVisible) => {
          if (!nextVisible) deleteCandidate = null
        }
      "
      @confirm="confirmDelete"
    >
      <template #default>
        <p>{{ t('track.deleteConfirm', { name: deleteCandidate?.title }) }}</p>
        <p v-if="error" class="memory-form-error">{{ error }}</p>
      </template>
    </ConfirmationDialog>

    <MediaLightbox
      v-if="selectedMedia"
      :tenant-name="tenantName"
      :media="selectedMedia"
      :media-items="selectedMediaList"
      :selected-index="selectedIndex"
      :can-show-previous="canShowPrevious"
      :can-show-next="canShowNext"
      :can-edit-metadata="canEditMetadata"
      :can-delete="canDeleteMedia"
      :update-captured-at="updateCapturedAt"
      :update-highlight="updateHighlight"
      :delete-media="deleteMedia"
      @close="closeLightbox"
      @previous="showPrevious"
      @next="showNext"
      @updated="updateMedia"
      @deleted="removeMedia"
    />
  </section>
</template>
