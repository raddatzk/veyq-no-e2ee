<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ProgressSpinner from 'primevue/progressspinner'
import ExplorerMediaLightbox from './ExplorerMediaLightbox.vue'
import { calculateJustifiedMediaLayout } from './mediaGridLayout'
import type { MediaGridLayoutResult, MediaGridLayoutTile } from './mediaGridLayout'
import type { ExplorerMedia } from '../../api/explorerClient'
import { API_BASE } from '../../api/httpClient'

type GalleryItem = {
  id: string
  aspectRatio: number
  mediaUrl: string
  alt: string
  media: ExplorerMedia
}

type GalleryTile = MediaGridLayoutTile & GalleryItem

const props = defineProps<{
  tenantName: string
  media: ExplorerMedia[]
  streaming: boolean
}>()

const selectedIndex = ref<number | null>(null)
const mediaGrid = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
const layout = ref<MediaGridLayoutResult>({ tiles: [], height: 0 })
const shouldRequestMedia = ref(false)
const loadedTileKeys = ref<Set<string>>(new Set())
const mediaOverrides = ref<Record<string, ExplorerMedia>>({})
let mediaGridObserver: ResizeObserver | null = null
let layoutSequence = 0

const galleryItems = computed<GalleryItem[]>(() =>
  props.media.map((media) => ({
    id: media.id,
    aspectRatio: mediaAspectRatio(media),
    mediaUrl: `${API_BASE}${media.contentUrl}`,
    alt: media.title || media.id,
    media
  }))
)
const galleryItemById = computed(() => new Map(galleryItems.value.map((item) => [item.id, item])))
const targetTileHeight = computed(() => (containerWidth.value <= 720 ? 190 : 260))
const galleryTiles = computed<GalleryTile[]>(() =>
  layout.value.tiles
    .map((tile) => {
      const item = galleryItemById.value.get(tile.id)
      return item ? { ...item, ...tile } : null
    })
    .filter((tile): tile is GalleryTile => Boolean(tile))
)
const visibleMedia = computed(() =>
  galleryTiles.value.map((tile) => mediaOverrides.value[tile.id] || tile.media)
)
const selectedMedia = computed(() =>
  selectedIndex.value === null ? null : visibleMedia.value[selectedIndex.value] || null
)
const isLightboxOpen = computed(() => selectedMedia.value !== null)
const canShowPrevious = computed(() => selectedIndex.value !== null && selectedIndex.value > 0)
const canShowNext = computed(
  () => selectedIndex.value !== null && selectedIndex.value < visibleMedia.value.length - 1
)
const canRenderGallery = computed(() => galleryTiles.value.length > 0)
const layoutSignature = computed(() =>
  props.media
    .map((item) => `${item.id}:${item.imageWidth ?? ''}x${item.imageHeight ?? ''}`)
    .join('|')
)

onMounted(() => {
  attachMediaGridObserver()
})

watch(isLightboxOpen, (isOpen) => {
  if (isOpen) {
    window.addEventListener('keydown', handleKeydown)
  } else {
    window.removeEventListener('keydown', handleKeydown)
  }
})

watch(
  () => props.media.map((item) => item.id).join('|'),
  () => {
    if (!props.media.length) {
      selectedIndex.value = null
      mediaOverrides.value = {}
      return
    }

    const currentIds = new Set(props.media.map((item) => item.id))
    mediaOverrides.value = Object.fromEntries(
      Object.entries(mediaOverrides.value).filter(([mediaId]) => currentIds.has(mediaId))
    )
    loadedTileKeys.value = new Set(
      [...loadedTileKeys.value].filter((key) =>
        galleryItems.value.some((item) => tileLoadKey(item) === key)
      )
    )

    if (selectedIndex.value !== null && selectedIndex.value >= visibleMedia.value.length) closeLightbox()
  }
)

watch(
  [layoutSignature, containerWidth, targetTileHeight, () => props.streaming],
  () => {
    void recalculateLayout()
  },
  { flush: 'post' }
)

watch(mediaGrid, attachMediaGridObserver, { flush: 'post' })

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  mediaGridObserver?.disconnect()
})

function attachMediaGridObserver() {
  const element = mediaGrid.value
  if (!element) return

  mediaGridObserver?.disconnect()
  measureMediaGrid(element)

  if (typeof ResizeObserver === 'undefined') return
  mediaGridObserver = new ResizeObserver((entries) => {
    const width = entries[0]?.contentRect.width || 0
    containerWidth.value = Math.floor(width || measuredWidth(element))
  })
  mediaGridObserver.observe(element)
}

function measureMediaGrid(element: HTMLElement) {
  containerWidth.value = Math.floor(measuredWidth(element))
}

function measuredWidth(element: HTMLElement): number {
  return element.getBoundingClientRect().width || element.clientWidth || element.parentElement?.clientWidth || 0
}

async function recalculateLayout() {
  const sequence = ++layoutSequence
  shouldRequestMedia.value = false
  layout.value = calculateJustifiedMediaLayout({
    items: galleryItems.value,
    containerWidth: containerWidth.value,
    targetHeight: targetTileHeight.value,
    gap: 8,
    showWidows: !props.streaming
  })

  await nextTick()
  if (sequence === layoutSequence) {
    shouldRequestMedia.value = layout.value.tiles.length > 0
  }
}

function mediaAspectRatio(media: ExplorerMedia): number {
  if (!media.imageWidth || !media.imageHeight || media.imageWidth <= 0 || media.imageHeight <= 0) {
    return 4 / 3
  }
  return media.imageWidth / media.imageHeight
}

function isImage(media: ExplorerMedia): boolean {
  return Boolean(media.mimeType?.startsWith('image/'))
}

function isVideo(media: ExplorerMedia): boolean {
  return Boolean(media.mimeType?.startsWith('video/'))
}

function tileLoadKey(tile: Pick<GalleryTile, 'id' | 'mediaUrl'>): string {
  return `${tile.id}:${tile.mediaUrl}`
}

function isTileLoaded(tile: GalleryTile): boolean {
  return loadedTileKeys.value.has(tileLoadKey(tile))
}

function markTileLoaded(tile: GalleryTile) {
  const key = tileLoadKey(tile)
  if (loadedTileKeys.value.has(key)) return
  loadedTileKeys.value = new Set([...loadedTileKeys.value, key])
}

function openLightbox(index: number) {
  selectedIndex.value = index
}

function updateMedia(media: ExplorerMedia) {
  mediaOverrides.value = { ...mediaOverrides.value, [media.id]: media }
}

function closeLightbox() {
  selectedIndex.value = null
}

function showPrevious() {
  if (canShowPrevious.value && selectedIndex.value !== null) selectedIndex.value -= 1
}

function showNext() {
  if (canShowNext.value && selectedIndex.value !== null) selectedIndex.value += 1
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
</script>

<template>
  <section class="explorer-section">
    <div class="explorer-section-header">
      <h2>Medien</h2>
      <span>{{ media.length }}</span>
    </div>
    <div
      v-if="media.length"
      ref="mediaGrid"
      class="media-grid"
      :style="{ height: `${layout.height}px` }"
      aria-label="Mediengalerie"
    >
      <template v-if="canRenderGallery">
        <button
          v-for="(tile, index) in galleryTiles"
          :key="tile.id"
          class="media-grid-tile"
          type="button"
          :style="{
            transform: `translate(${tile.left}px, ${tile.top}px)`,
            width: `${tile.renderedWidth}px`,
            height: `${tile.renderedHeight}px`
          }"
          @click="openLightbox(index)"
        >
          <template v-if="shouldRequestMedia">
            <img
              v-if="isImage(tile.media)"
              class="media-grid-tile-media"
              :class="{ 'media-grid-tile-media-loaded': isTileLoaded(tile) }"
              :src="tile.mediaUrl"
              :alt="tile.alt"
              loading="lazy"
              decoding="async"
              @load="markTileLoaded(tile)"
            />
            <video
              v-else-if="isVideo(tile.media)"
              class="media-grid-tile-media"
              :class="{ 'media-grid-tile-media-loaded': isTileLoaded(tile) }"
              :src="tile.mediaUrl"
              preload="metadata"
              muted
              playsinline
              @loadedmetadata="markTileLoaded(tile)"
            />
            <span v-else class="pi pi-image media-grid-icon" />
            <span
              v-if="(isImage(tile.media) || isVideo(tile.media)) && !isTileLoaded(tile)"
              class="media-grid-tile-placeholder"
            />
          </template>
          <span v-else class="media-grid-tile-placeholder" />
        </button>
      </template>
      <div v-else class="media-grid-placeholder">
        <ProgressSpinner class="small-spinner" />
        <span>{{ streaming ? 'Medien werden geladen...' : 'Medien werden vorbereitet...' }}</span>
      </div>
    </div>
    <div v-else-if="streaming" class="explorer-loading">
      <ProgressSpinner class="small-spinner" />
      <span>Medien werden geladen...</span>
    </div>
    <div v-else class="explorer-empty">Keine Medien unter diesem Pfad.</div>

    <ExplorerMediaLightbox
      v-if="selectedMedia"
      :tenant-name="tenantName"
      :media="selectedMedia"
      :can-show-previous="canShowPrevious"
      :can-show-next="canShowNext"
      @close="closeLightbox"
      @previous="showPrevious"
      @next="showNext"
      @updated="updateMedia"
    />
  </section>
</template>
