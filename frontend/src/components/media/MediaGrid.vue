<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import ProgressSpinner from 'primevue/progressspinner'
import MediaLightbox from './MediaLightbox.vue'
import { calculateJustifiedMediaLayout, galleryDisplayAspectRatio } from './mediaGridLayout'
import type { MediaGridLayoutResult, MediaGridLayoutTile } from './mediaGridLayout'
import type { MemoryMedia, UpdateMemoryMediaMetadataRequest } from '../../features/memories/api'
import { API_BASE } from '../../api/httpClient'

type GalleryItem = {
  id: string
  aspectRatio: number
  mediaUrl: string
  thumbnailUrl: string | null
  previewUrl: string | null
  alt: string
  media: MemoryMedia
}

type GalleryTile = MediaGridLayoutTile & GalleryItem

const props = defineProps<{
  tenantName: string
  media: MemoryMedia[]
  streaming: boolean
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
const selectedIndex = ref<number | null>(null)
const mediaGrid = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
const layout = ref<MediaGridLayoutResult>({ tiles: [], height: 0 })
const shouldRequestMedia = ref(false)
const loadedPreviewKeys = ref<Set<string>>(new Set())
const loadedOriginalKeys = ref<Set<string>>(new Set())
const mediaOverrides = ref<Record<string, MemoryMedia>>({})
let mediaGridObserver: ResizeObserver | null = null
let layoutSequence = 0

const galleryItems = computed<GalleryItem[]>(() =>
  props.media.map((media) => ({
    id: media.id,
    aspectRatio: mediaAspectRatio(media),
    mediaUrl: `${API_BASE}${media.contentUrl}`,
    thumbnailUrl: media.thumbnailUrl ? `${API_BASE}${media.thumbnailUrl}` : null,
    previewUrl: media.previewUrl ? `${API_BASE}${media.previewUrl}` : null,
    alt: media.title || media.id,
    media
  }))
)
const galleryItemById = computed(() => new Map(galleryItems.value.map((item) => [item.id, item])))
const targetTileHeight = computed(() => (containerWidth.value <= 720 ? 150 : 200))
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
    pruneLoadedMediaKeys()

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

function mediaAspectRatio(media: MemoryMedia): number {
  return galleryDisplayAspectRatio(media.imageWidth, media.imageHeight)
}

function isImage(media: MemoryMedia): boolean {
  return Boolean(media.mimeType?.startsWith('image/'))
}

function isVideo(media: MemoryMedia): boolean {
  return Boolean(media.mimeType?.startsWith('video/'))
}

function hasThumbnail(tile: Pick<GalleryTile, 'thumbnailUrl' | 'previewUrl'>): boolean {
  return Boolean(tile.thumbnailUrl || tile.previewUrl)
}

function usesPreviewImage(tile: GalleryTile): boolean {
  return isImage(tile.media) || (isVideo(tile.media) && hasThumbnail(tile))
}

function mediaLoadKey(tile: Pick<GalleryTile, 'id'>, url: string): string {
  return `${tile.id}:${url}`
}

function previewLoadKey(tile: GalleryTile): string {
  return mediaLoadKey(tile, tileImageUrl(tile))
}

function originalLoadKey(tile: GalleryTile): string {
  return mediaLoadKey(tile, tile.mediaUrl)
}

function isPreviewLoaded(tile: GalleryTile): boolean {
  return loadedPreviewKeys.value.has(previewLoadKey(tile))
}

function isOriginalLoaded(tile: GalleryTile): boolean {
  return loadedOriginalKeys.value.has(originalLoadKey(tile))
}

function markPreviewLoaded(tile: GalleryTile) {
  const key = previewLoadKey(tile)
  if (loadedPreviewKeys.value.has(key)) return
  loadedPreviewKeys.value = new Set([...loadedPreviewKeys.value, key])
}

function markOriginalLoaded(tile: GalleryTile) {
  const key = originalLoadKey(tile)
  if (loadedOriginalKeys.value.has(key)) return
  loadedOriginalKeys.value = new Set([...loadedOriginalKeys.value, key])
}

function markTileImageLoaded(tile: GalleryTile) {
  markPreviewLoaded(tile)
}

function tileImageUrl(tile: GalleryTile): string {
  return tile.thumbnailUrl || tile.previewUrl || tile.mediaUrl
}

function isTileDisplayLoaded(tile: GalleryTile): boolean {
  return usesPreviewImage(tile)
    ? isPreviewLoaded(tile) || isOriginalLoaded(tile)
    : isVideo(tile.media)
      ? isOriginalLoaded(tile)
      : true
}

function pruneLoadedMediaKeys() {
  const previewKeys = new Set(galleryItems.value.map((item) => mediaLoadKey(item, item.thumbnailUrl || item.previewUrl || item.mediaUrl)))
  const originalKeys = new Set(galleryItems.value.map((item) => mediaLoadKey(item, item.mediaUrl)))
  loadedPreviewKeys.value = new Set([...loadedPreviewKeys.value].filter((key) => previewKeys.has(key)))
  loadedOriginalKeys.value = new Set([...loadedOriginalKeys.value].filter((key) => originalKeys.has(key)))
}

function openLightbox(index: number) {
  selectedIndex.value = index
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
  <section class="veyq-section memory-section">
    <div class="veyq-section-header memory-section-header">
      <h2>{{ t('media.title') }}</h2>
      <div class="media-grid-header-actions">
        <span>{{ media.length }}</span>
      </div>
    </div>
    <div
      v-if="media.length"
      ref="mediaGrid"
      class="media-grid"
      :style="{ height: `${layout.height}px` }"
      :aria-label="t('media.gallery')"
    >
      <template v-if="canRenderGallery">
        <button
          v-for="(tile, index) in galleryTiles"
          :key="tile.id"
          class="media-grid-tile"
          type="button"
          tabindex="-1"
          :style="{
            transform: `translate(${tile.left}px, ${tile.top}px)`,
            width: `${tile.renderedWidth}px`,
            height: `${tile.renderedHeight}px`
          }"
          @click="openLightbox(index)"
        >
          <template v-if="shouldRequestMedia">
            <img
              v-if="usesPreviewImage(tile)"
              class="media-grid-tile-media"
              :class="{ 'media-grid-tile-media-loaded': isTileDisplayLoaded(tile) }"
              :src="tileImageUrl(tile)"
              :alt="tile.alt"
              loading="eager"
              decoding="async"
              @load="markTileImageLoaded(tile)"
            />
            <video
              v-else-if="isVideo(tile.media)"
              class="media-grid-tile-media"
              :class="{ 'media-grid-tile-media-loaded': isTileDisplayLoaded(tile) }"
              :src="tile.mediaUrl"
              preload="metadata"
              muted
              playsinline
              @loadedmetadata="markOriginalLoaded(tile)"
            />
            <span v-else class="pi pi-image media-grid-icon" />
            <span
              v-if="(isImage(tile.media) || isVideo(tile.media)) && !isTileDisplayLoaded(tile)"
              class="media-grid-tile-placeholder"
            />
          </template>
          <span v-else class="media-grid-tile-placeholder" />
        </button>
      </template>
      <div v-else class="media-grid-placeholder">
        <ProgressSpinner class="small-spinner" />
        <span>{{ streaming ? t('media.loading') : t('media.preparing') }}</span>
      </div>
    </div>
    <div v-else-if="streaming" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('media.loading') }}</span>
    </div>
    <div v-else class="veyq-empty">{{ t('media.empty') }}</div>

    <MediaLightbox
      v-if="selectedMedia"
      :tenant-name="tenantName"
      :media="selectedMedia"
      :media-items="visibleMedia"
      :selected-index="selectedIndex ?? undefined"
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
