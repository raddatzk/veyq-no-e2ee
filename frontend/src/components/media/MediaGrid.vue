<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import ProgressSpinner from 'primevue/progressspinner'
import SelectButton from 'primevue/selectbutton'
import MediaLightbox from './MediaLightbox.vue'
import { calculateJustifiedMediaLayout, galleryDisplayAspectRatio } from './mediaGridLayout'
import type { MediaGridLayoutResult, MediaGridLayoutTile } from './mediaGridLayout'
import type {
  MemoryMedia,
  MemoryMediaBucket,
  UpdateMemoryMediaCapturedAtRequest,
  UpdateMemoryMediaHighlightRequest
} from '../../features/memories/api'
import {
  mediaContentUrl,
  mediaPreviewOnlyUrl,
  mediaThumbnailUrl
} from '../../shared/utils/mediaUrls'
import { createRafResizeObserver, type RafResizeObserver } from '../../shared/utils/resizeObserver'

type MediaFilter = 'highlighted' | 'all'

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
  mediaBuckets?: MemoryMediaBucket[]
  streaming: boolean
  canEditMetadata?: boolean
  canDeleteMedia?: boolean
  canHighlight?: boolean
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
  canHighlightMedia?: (media: MemoryMedia) => boolean
  deleteMedia?: (mediaId: string, memoryId: string) => Promise<void>
}>()
const emit = defineEmits<{
  deleted: [media: MemoryMedia]
  updated: [media: MemoryMedia]
}>()

const { t } = useI18n()
const selectedMediaId = ref<string | null>(null)
const mediaGrid = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
const layout = ref<MediaGridLayoutResult>({ tiles: [], height: 0 })
const shouldRequestMedia = ref(false)
const loadedPreviewKeys = ref<Set<string>>(new Set())
const loadedOriginalKeys = ref<Set<string>>(new Set())
let mediaGridObserver: RafResizeObserver | null = null
let layoutSequence = 0
let visibilityRetryFrame: number | null = null

const mediaFilter = ref<MediaFilter>('all')
const mediaFilterOptions = computed(() => [
  { label: t('media.filterHighlighted'), value: 'highlighted' as const },
  { label: t('media.filterAll'), value: 'all' as const }
])

const galleryItems = computed<GalleryItem[]>(() =>
  props.media
    .filter((media: MemoryMedia) => (mediaFilter.value === 'highlighted' ? media.highlight : true))
    .map((media) => ({
      id: media.id,
      aspectRatio: mediaAspectRatio(media),
      mediaUrl: mediaContentUrl(media),
      thumbnailUrl: mediaThumbnailUrl(media),
      previewUrl: mediaPreviewOnlyUrl(media),
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
  galleryTiles.value.map((tile) => tile.media)
)
const selectedIndex = computed(() =>
  selectedMediaId.value
    ? visibleMedia.value.findIndex((media) => media.id === selectedMediaId.value)
    : -1
)
const selectedMedia = computed(() =>
  selectedIndex.value >= 0 ? visibleMedia.value[selectedIndex.value] : null
)
const bucketById = computed(() =>
  new Map((props.mediaBuckets ?? []).map((bucket) => [bucket.id, bucket]))
)
const canHighlightSelectedMedia = computed(() =>
  selectedMedia.value
    ? props.canHighlight !== false &&
      (props.canHighlightMedia?.(selectedMedia.value) ?? canHighlightByBucket(selectedMedia.value))
    : true
)
const isLightboxOpen = computed(() => selectedMedia.value !== null)
const canShowPrevious = computed(() => selectedIndex.value > 0)
const canShowNext = computed(
  () => selectedIndex.value >= 0 && selectedIndex.value < visibleMedia.value.length - 1
)
const canRenderGallery = computed(() => galleryTiles.value.length > 0)
const layoutSignature = computed(() =>
  galleryItems.value
    .map((item) => `${item.id}:${item.media.imageWidth ?? ''}x${item.media.imageHeight ?? ''}`)
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
  () => galleryItems.value.map((item) => item.id).join('|'),
  () => {
    if (!galleryItems.value.length) {
      selectedMediaId.value = null
      return
    }

    pruneLoadedMediaKeys()

    if (
      selectedMediaId.value &&
      !galleryItems.value.some((item) => item.id === selectedMediaId.value)
    ) {
      closeLightbox()
    }
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
  cancelVisibilityRetry()
})

function attachMediaGridObserver() {
  const element = mediaGrid.value
  if (!element) return

  mediaGridObserver?.disconnect()
  measureMediaGrid(element)
  void recalculateLayout()

  if (typeof ResizeObserver === 'undefined') return
  mediaGridObserver = createRafResizeObserver((entry) => {
    const width = entry.contentRect.width || 0
    containerWidth.value = Math.floor(width || measuredWidth(element))
  })
  mediaGridObserver?.observe(element)
}

function measureMediaGrid(element: HTMLElement) {
  containerWidth.value = Math.floor(measuredWidth(element))
}

function measuredWidth(element: HTMLElement): number {
  return (
    element.getBoundingClientRect().width ||
    element.clientWidth ||
    element.parentElement?.clientWidth ||
    0
  )
}

async function recalculateLayout() {
  const sequence = ++layoutSequence
  const element = mediaGrid.value
  if (element && containerWidth.value <= 0) measureMediaGrid(element)

  if (galleryItems.value.length > 0 && containerWidth.value <= 0) {
    scheduleVisibilityRetry()
  } else {
    cancelVisibilityRetry()
  }

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

function scheduleVisibilityRetry() {
  if (visibilityRetryFrame !== null || typeof window === 'undefined') return
  visibilityRetryFrame = window.requestAnimationFrame(() => {
    visibilityRetryFrame = null
    const element = mediaGrid.value
    if (!element) return

    const nextWidth = Math.floor(measuredWidth(element))
    if (nextWidth > 0) {
      containerWidth.value = nextWidth
      return
    }

    scheduleVisibilityRetry()
  })
}

function cancelVisibilityRetry() {
  if (visibilityRetryFrame === null || typeof window === 'undefined') return
  window.cancelAnimationFrame(visibilityRetryFrame)
  visibilityRetryFrame = null
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
  const previewKeys = new Set(
    galleryItems.value.map((item) =>
      mediaLoadKey(item, item.thumbnailUrl || item.previewUrl || item.mediaUrl)
    )
  )
  const originalKeys = new Set(galleryItems.value.map((item) => mediaLoadKey(item, item.mediaUrl)))
  loadedPreviewKeys.value = new Set(
    [...loadedPreviewKeys.value].filter((key) => previewKeys.has(key))
  )
  loadedOriginalKeys.value = new Set(
    [...loadedOriginalKeys.value].filter((key) => originalKeys.has(key))
  )
}

function openLightbox(mediaId: string) {
  selectedMediaId.value = mediaId
}

function canHighlightByBucket(media: MemoryMedia): boolean {
  if (!media.bucketId) return true
  if (props.mediaBuckets === undefined) return true
  return bucketById.value.get(media.bucketId)?.canManage ?? false
}

function updateMedia(media: MemoryMedia) {
  emit('updated', media)
}

function removeMedia(media: MemoryMedia) {
  closeLightbox()
  emit('deleted', media)
}

function closeLightbox() {
  selectedMediaId.value = null
}

function showPrevious() {
  if (!canShowPrevious.value) return
  selectedMediaId.value = visibleMedia.value[selectedIndex.value - 1]?.id || null
}

function showNext() {
  if (!canShowNext.value) return
  selectedMediaId.value = visibleMedia.value[selectedIndex.value + 1]?.id || null
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
        <span v-if="galleryItems.length !== media.length">
          {{ t('media.highlightCount', { highlight: galleryItems.length, all: media.length }) }}
        </span>
        <span v-else>
          {{ media.length }}
        </span>
        <div class="media-grid-filter-toggle">
          <SelectButton
            v-model="mediaFilter"
            :options="mediaFilterOptions"
            option-label="label"
            option-value="value"
            :allow-empty="false"
          />
        </div>
      </div>
    </div>
    <div
      v-if="galleryItems.length"
      ref="mediaGrid"
      class="media-grid"
      :style="{ height: `${layout.height}px` }"
      :aria-label="t('media.gallery')"
    >
      <template v-if="canRenderGallery">
        <button
          v-for="tile in galleryTiles"
          :key="tile.id"
          class="media-grid-tile"
          type="button"
          tabindex="-1"
          :style="{
            transform: `translate(${tile.left}px, ${tile.top}px)`,
            width: `${tile.renderedWidth}px`,
            height: `${tile.renderedHeight}px`
          }"
          @click="openLightbox(tile.id)"
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
      :selected-index="selectedIndex >= 0 ? selectedIndex : undefined"
      :can-show-previous="canShowPrevious"
      :can-show-next="canShowNext"
      :can-edit-metadata="canEditMetadata"
      :can-delete="canDeleteMedia"
      :update-captured-at="updateCapturedAt"
      :update-highlight="updateHighlight"
      :can-highlight="canHighlightSelectedMedia"
      :delete-media="deleteMedia"
      @close="closeLightbox"
      @previous="showPrevious"
      @next="showNext"
      @updated="updateMedia"
      @deleted="removeMedia"
    />
  </section>
</template>
