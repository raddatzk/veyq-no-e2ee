<script setup lang="ts">
// A Memory's detail surface = its **gallery** (cr-0049, uc.memory.gallery). Opens the Memory's media as a
// thumbnail grid, each tile's thumbnail fetched as ciphertext via presigned GET and decrypted on device under
// K_mem (operator-blind); clicking a tile opens the lightbox (preview, prev/next, download original). The host
// view (MemoryHomeView) owns the rename/delete dialogs + the favorites store and unwraps K_mem from the tree.
// Ported presentation from the design source (legacy/frontend-noe2ee); only the data layer is ours. Decrypted
// thumbnails become object URLs that are **revoked** on reload / unmount (no lingering plaintext blob URLs).
// Out of scope (own CRs): journal, chapters + chapter-nested media, map/tracks, playlists, buckets, sharing.
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { TreeNode } from '../tree'
import { listMedia, openGrid, hasDerivatives, unsealDimensions, type MediaItem } from '../media'
import { calculateJustifiedMediaLayout, galleryDisplayAspectRatio } from '../mediaGridLayout'
import { enqueueUploads, isSupportedMediaFile, inferMediaType, mergeFiles } from '../uploadQueue'
import MediaLightbox from './MediaLightbox.vue'

const props = defineProps<{ node: TreeNode; scopeId: string; memoryKey: Uint8Array | null; favorite?: boolean }>()
defineEmits<{ toggleFavorite: []; rename: []; delete: [] }>()

const { t } = useI18n()

const items = ref<MediaItem[]>([])
const thumbs = ref<Map<string, string>>(new Map()) // mediaId → thumbnail object URL (derivable items only)
const status = ref<'loading' | 'ready' | 'error'>('loading')
const lightboxIndex = ref<number | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

// --- justified grid (uc.memory.gallery, cr-0059). Each tile's aspect ratio comes from the E2EE-sealed
// dimensions unwrapped under K_mem (operator-blind); items without sealed dimensions fall back to a default
// aspect. The layout is recomputed whenever the items or the measured container width change.
const GAP = 8
const gridEl = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
let gridObserver: ResizeObserver | null = null
let measureFrame: number | null = null

const targetTileHeight = computed(() => (containerWidth.value <= 720 ? 150 : 200))
const aspectRatios = computed(() => {
  const key = props.memoryKey
  const m = new Map<string, number>()
  for (const item of items.value) {
    const dims = key ? unsealDimensions(key, item) : null
    m.set(item.mediaId, galleryDisplayAspectRatio(dims?.w, dims?.h))
  }
  return m
})
const layout = computed(() =>
  calculateJustifiedMediaLayout({
    items: items.value.map((it) => ({ id: it.mediaId, aspectRatio: aspectRatios.value.get(it.mediaId) })),
    containerWidth: containerWidth.value,
    targetHeight: targetTileHeight.value,
    gap: GAP,
  }),
)
const itemsById = computed(() => new Map(items.value.map((it, index) => [it.mediaId, { item: it, index }])))
const tiles = computed(() =>
  layout.value.tiles.flatMap((tile) => {
    const entry = itemsById.value.get(tile.id)
    return entry ? [{ ...tile, media: entry.item, index: entry.index }] : []
  }),
)

function measureGrid() {
  const el = gridEl.value
  if (!el) return
  const width = el.getBoundingClientRect().width || el.clientWidth || el.parentElement?.clientWidth || 0
  if (width > 0) containerWidth.value = Math.floor(width)
}

function attachGridObserver() {
  const el = gridEl.value
  gridObserver?.disconnect()
  gridObserver = null
  if (!el) return
  measureGrid()
  if (typeof ResizeObserver === 'undefined') return
  // rAF-debounce so a width change doesn't trip the ResizeObserver loop guard
  gridObserver = new ResizeObserver(() => {
    if (measureFrame !== null) return
    measureFrame = requestAnimationFrame(() => {
      measureFrame = null
      measureGrid()
    })
  })
  gridObserver.observe(el)
}

// --- upload trigger (uc.memory.upload). The Safari Photos picker populates input.files LATE / across repeated
// picks with empty MIME types — so we re-read the input over a short window, MERGE (never replace) the picks,
// and only enqueue after a brief quiet period; selection accepts files by extension when the MIME is empty.
const RETRY_DELAYS = [250, 750, 1500, 3000]
const QUIET_MS = 1200
let selection: File[] = []
let quietTimer: ReturnType<typeof setTimeout> | null = null

function triggerUpload() {
  fileInput.value?.click()
}

function onFilesPicked() {
  const input = fileInput.value
  if (!input) return
  collect(input)
  RETRY_DELAYS.forEach((d) => setTimeout(() => collect(input), d))
}

function collect(input: HTMLInputElement) {
  const before = selection.length
  const incoming = Array.from(input.files ?? []).filter(isSupportedMediaFile)
  selection = mergeFiles(selection, incoming)
  if (selection.length !== before || (selection.length > 0 && quietTimer === null)) {
    if (quietTimer) clearTimeout(quietTimer)
    quietTimer = setTimeout(flushSelection, QUIET_MS)
  }
}

function flushSelection() {
  quietTimer = null
  const key = props.memoryKey
  const picked = selection
  selection = []
  if (fileInput.value) fileInput.value.value = '' // allow re-picking the same file
  if (picked.length === 0 || !key) return
  const targetNodeId = props.node.id
  enqueueUploads(
    { scopeId: props.scopeId, nodeId: targetNodeId, memoryKey: key },
    picked.map((file) => ({ file, mediaType: inferMediaType(file) })),
    { label: props.node.name, onComplete: () => { if (props.node.id === targetNodeId) void load() } },
  )
}

function revokeThumbs() {
  for (const url of thumbs.value.values()) URL.revokeObjectURL(url)
  thumbs.value = new Map()
}

/** List the Memory's media and fetch each derivable item's thumbnail tier (parallel, operator-blind). */
async function load() {
  revokeThumbs()
  items.value = []
  status.value = 'loading'
  const key = props.memoryKey
  if (!key) {
    status.value = 'error' // E2: no K_mem (locked / not openable) → gallery unavailable
    return
  }
  const nodeId = props.node.id
  try {
    const listed = await listMedia(props.scopeId, nodeId)
    if (props.node.id !== nodeId) return // navigated away while listing
    items.value = listed
    status.value = 'ready'
    await Promise.all(
      listed.map(async (m) => {
        if (!hasDerivatives(m)) return // E1: non-image/video → icon tile, no fetch
        try {
          const bytes = await openGrid(props.scopeId, key, m)
          if (props.node.id !== nodeId) return
          thumbs.value.set(m.mediaId, URL.createObjectURL(new Blob([bytes as BlobPart])))
          thumbs.value = new Map(thumbs.value) // reassign so the template reacts
        } catch {
          // a single failed tile stays a placeholder; the rest of the grid is unaffected
        }
      }),
    )
  } catch {
    if (props.node.id === nodeId) status.value = 'error'
  }
}

watch(
  () => [props.node.id, props.memoryKey] as const,
  () => {
    lightboxIndex.value = null
    void load()
  },
  { immediate: true },
)

// The grid element mounts/unmounts with the ready state; (re)attach the size observer when it appears.
onMounted(attachGridObserver)
watch(gridEl, attachGridObserver, { flush: 'post' })

onBeforeUnmount(() => {
  revokeThumbs()
  gridObserver?.disconnect()
  if (measureFrame !== null) cancelAnimationFrame(measureFrame)
})
</script>

<template>
  <div class="memory-route-surface memory-detail">
    <header class="gallery-header">
      <div class="gallery-header-copy">
        <h1 class="gallery-title">{{ node.name }}</h1>
        <span class="gallery-count">{{ t('media.itemCount', { count: items.length }, items.length) }}</span>
      </div>
      <div class="gallery-header-actions">
        <Button
          v-tooltip.bottom="t('media.upload')"
          type="button"
          :aria-label="t('media.upload')"
          icon="pi pi-cloud-upload"
          :disabled="!memoryKey"
          @click="triggerUpload"
        />
        <input
          ref="fileInput"
          type="file"
          multiple
          accept="image/*,video/*,.heic,.heif,.mov,.m4v,.mkv,.avi,.tif,.tiff"
          class="visually-hidden"
          @change="onFilesPicked"
        />
        <Button
          v-tooltip.bottom="favorite ? t('memory.unfavorite') : t('memory.favorite')"
          type="button"
          class="memory-detail-fav"
          :class="{ 'is-favorite': favorite }"
          :aria-label="favorite ? t('memory.unfavorite') : t('memory.favorite')"
          :icon="favorite ? 'pi pi-heart-fill' : 'pi pi-heart'"
          severity="secondary"
          text
          @click="$emit('toggleFavorite')"
        />
        <Button
          v-tooltip.bottom="t('memory.rename')"
          type="button"
          :aria-label="t('memory.rename')"
          icon="pi pi-pencil"
          severity="secondary"
          text
          @click="$emit('rename')"
        />
        <Button
          v-tooltip.bottom="t('memory.delete')"
          type="button"
          :aria-label="t('memory.delete')"
          icon="pi pi-trash"
          severity="danger"
          text
          @click="$emit('delete')"
        />
      </div>
    </header>

    <div v-if="status === 'loading'" class="media-grid-placeholder">
      <span class="pi pi-spin pi-spinner" />
      <span>{{ t('media.loading') }}</span>
    </div>
    <p v-else-if="status === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
    <p v-else-if="!items.length" class="veyq-empty">{{ t('media.empty') }}</p>
    <div
      v-else
      ref="gridEl"
      class="gallery-grid"
      :style="{ height: `${layout.height}px` }"
      :aria-label="t('media.gallery')"
    >
      <button
        v-for="tile in tiles"
        :key="tile.media.mediaId"
        class="gallery-tile"
        type="button"
        :style="{
          transform: `translate(${tile.left}px, ${tile.top}px)`,
          width: `${tile.renderedWidth}px`,
          height: `${tile.renderedHeight}px`,
        }"
        :aria-label="t('media.openItem', { index: tile.index + 1 })"
        @click="lightboxIndex = tile.index"
      >
        <img
          v-if="thumbs.get(tile.media.mediaId)"
          class="media-grid-tile-media media-grid-tile-media-loaded"
          :src="thumbs.get(tile.media.mediaId)"
          :alt="node.name"
        />
        <span v-else-if="!hasDerivatives(tile.media)" class="media-grid-icon pi pi-file" />
        <span v-else class="media-grid-tile-placeholder" />
      </button>
    </div>

    <MediaLightbox
      v-if="lightboxIndex !== null && memoryKey"
      :scope-id="scopeId"
      :memory-key="memoryKey"
      :items="items"
      :start-index="lightboxIndex"
      :memory-name="node.name"
      @close="lightboxIndex = null"
    />
  </div>
</template>

<style scoped>
.gallery-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 1.15rem;
  border-bottom: 1px solid var(--app-line);
}
.gallery-header-copy {
  display: grid;
  gap: 0.3rem;
  min-width: 0;
}
.gallery-title {
  margin: 0;
  font-size: 2rem;
  line-height: 1.1;
  overflow-wrap: anywhere;
}
.gallery-count {
  color: var(--app-muted);
  font-size: 0.9rem;
  font-weight: 700;
}
.gallery-header-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.5rem;
}
/* Narrow viewports: stack the actions under the title so the title keeps the full row width (otherwise the
   actions squeeze it into a sliver and it breaks mid-word). */
@media (max-width: 640px) {
  .gallery-header {
    flex-direction: column;
  }
  .gallery-header-actions {
    justify-content: flex-start;
  }
  .gallery-title {
    font-size: 1.5rem;
  }
}
/* The active heart is a heart-red blended with the brand accent, matching the card pin. */
.memory-detail-fav.is-favorite :deep(.p-button-icon) {
  color: color-mix(in srgb, var(--app-accent) 45%, #e11d48);
}

/* The justified-rows gallery (cr-0059): aspect-preserving tiles laid out by calculateJustifiedMediaLayout into
   rows that fill the container width (the per-tile aspect ratio comes from the E2EE-sealed dimensions). The
   container is position:relative with an explicit height; each tile is absolutely positioned via a transform +
   its rendered width/height. The tile-inner classes (.media-grid-tile-media / -placeholder / -icon) are the
   design source's (media.css). */
.gallery-grid {
  position: relative;
  width: 100%;
}
.gallery-tile {
  position: absolute;
  top: 0;
  left: 0;
  display: block;
  overflow: hidden;
  padding: 0;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
  color: var(--app-muted);
  cursor: pointer;
}
.gallery-tile img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.gallery-tile .media-grid-icon {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  font-size: 1.5rem;
}
</style>
