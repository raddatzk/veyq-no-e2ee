<script setup lang="ts">
// A Memory's detail surface = its **gallery** (cr-0049, uc.memory.gallery). Opens the Memory's media as a
// thumbnail grid, each tile's thumbnail fetched as ciphertext via presigned GET and decrypted on device under
// K_mem (operator-blind); clicking a tile opens the lightbox (preview, prev/next, download original). The host
// view (MemoryHomeView) owns the rename/delete dialogs + the favorites store and unwraps K_mem from the tree.
// Ported presentation from the design source (legacy/frontend-noe2ee); only the data layer is ours. Decrypted
// thumbnails become object URLs that are **revoked** on reload / unmount (no lingering plaintext blob URLs).
// Out of scope (own CRs): journal, chapters + chapter-nested media, map/tracks, playlists, buckets, sharing.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import type { TreeNode } from '../tree'
import { listMedia, openGrid, openBlob, uploadCoverBlob, deleteCoverBlob, hasDerivatives, unsealMeta, effectiveCapturedAt, type MediaItem } from '../media'
import { unsealMemoryDate, computeAutoRange, setMemoryDate, sameMemoryDate, sortNodesByDate, type MemoryDate } from '../memoryDate'
import {
  unsealMemoryLocation,
  setMemoryLocation,
  computeMedoid,
  mediaGpsPoints,
  sameMemoryLocation,
  isLocationPending,
  type MemoryLocation,
} from '../memoryLocation'
import { unsealMemoryCover, setMemoryCover, FULL_FRAME, type MemoryCover, type CoverSaveIntent } from '../memoryCover'
import { reverseGeocode, defaultLevel, nameAtLevel } from '../geocode'
import { formatClosedRange, formatDay } from '../memoryDateRange'
import { galleryDisplayAspectRatio } from '../mediaGridLayout'
import MediaGrid from './MediaGrid.vue'
import OptimisticTile from './OptimisticTile.vue'
import { enqueueUploads, isSupportedMediaFile, inferMediaType, mergeFiles, retryFile } from '../uploadQueue'
import { removedThumbIds } from '../optimisticUpload'
import { useOptimisticTiles, type OptimisticTileVM } from '../useOptimisticTiles'
import MediaLightbox from './MediaLightbox.vue'
import MemoryDateEditor from './MemoryDateEditor.vue'
import MemoryLocationEditor from './MemoryLocationEditor.vue'
import MemoryCoverImage from './MemoryCoverImage.vue'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import MemoryCard from './MemoryCard.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import Dialog from 'primevue/dialog'
import { useViewMode } from '../../../shared/state/useViewMode'
import { useMapConfig, setMapsConsent } from '../../../shared/state/useMapConfig'

const props = defineProps<{
  node: TreeNode
  scopeId: string
  memoryKey: Uint8Array | null
  favorite?: boolean
  chapters?: TreeNode[] // child CHAPTER nodes of this Memory/chapter (cr-0060)
  shareable?: boolean // this is a personal-scope MEMORY → offer the public-share affordance (cr-0100)
}>()
const emit = defineEmits<{
  toggleFavorite: []
  rename: []
  delete: []
  openChapter: [node: TreeNode]
  createChapter: []
  share: []
  metaUpdated: [{ nodeId: string; wrappedMeta: string }]
}>()

const { t, locale } = useI18n()
const { viewMode } = useViewMode()

const items = ref<MediaItem[]>([])
const thumbs = ref<Map<string, string>>(new Map()) // mediaId → thumbnail object URL (derivable items only)
const status = ref<'loading' | 'ready' | 'error'>('loading')
const lightboxIndex = ref<number | null>(null)
const lightboxItems = ref<MediaItem[]>([]) // a snapshot of the sorted order at open time (so an in-lightbox date edit can't reorder underfoot)
// date sort (cr-0066): tiles order by effective capturedAt; newest first by default.
const sortDir = ref<'newest' | 'oldest'>('newest')
const activeTab = ref<'gallery' | 'chapters'>('gallery') // local (the #fragment carries the tree path, cr-0031)
const fileInput = ref<HTMLInputElement | null>(null)

// --- justified grid (uc.memory.gallery, cr-0059). Each tile's aspect ratio comes from the E2EE-sealed
// dimensions unwrapped under K_mem (operator-blind); items without sealed dimensions fall back to a default
// aspect. The container measurement + justified layout live in the shared [MediaGrid] (reused by the public
// share viewer, cr-0100); here we only assemble the tile list + render each tile's content in its slot.

// The effective capture time of an item (from its sealed metadata tier), or undefined when it has none.
function capturedAtOf(item: MediaItem): number | undefined {
  return props.memoryKey ? effectiveCapturedAt(unsealMeta(props.memoryKey, item)) : undefined
}
// Tiles ordered by effective capture time (newest first by default, toggleable); undated media always last.
const sortedItems = computed(() => {
  const dir = sortDir.value === 'newest' ? -1 : 1
  return items.value
    .map((it, i) => ({ it, i, t: capturedAtOf(it) }))
    .sort((a, b) => {
      if (a.t === undefined && b.t === undefined) return a.i - b.i // both undated → stable list order
      if (a.t === undefined) return 1 // undated last, regardless of direction
      if (b.t === undefined) return -1
      return (a.t - b.t) * dir
    })
    .map((x) => x.it)
})

// --- Memory date (cr-0067, uc.memory.date-place). The effective range is sealed on the NODE under K_mem
// (operator-blind) and shown in the header; an editor sets a manual mode or `auto`. `auto` is **materialized**
// from the Memory's own media and self-heals on open + after an upload batch (a media-delete surface doesn't
// exist yet — the open-time self-heal covers it once it lands).
const dateEditorOpen = ref(false)
const storedDate = computed(() => (props.memoryKey ? unsealMemoryDate(props.memoryKey, props.node) : null))
const autoRange = computed<MemoryDate>(() =>
  props.memoryKey ? computeAutoRange(items.value, props.memoryKey) : { mode: 'auto' },
)
// What the header shows: a manual date as sealed, else the live materialized `auto` range (before/without a seal).
const effectiveDate = computed<MemoryDate>(() => {
  const s = storedDate.value
  return s && (s.mode !== 'auto' || s.from) ? s : autoRange.value
})
const dateLabel = computed(() => {
  const d = effectiveDate.value
  if (d.mode === 'ongoing') return d.from ? t('memory.date.since', { day: formatDay(d.from, locale.value) }) : ''
  return formatClosedRange(d.from, d.to, locale.value)
})
// Chapters ordered by their own sealed start date — a chapter's K_mem opens under THIS Memory's K_mem (cr-0067),
// epoch-independent (only the top-level wrap keys off the epoch), so every chapter resolves under the one memoryKey.
const datedChapters = computed(() => sortNodesByDate(() => props.memoryKey, props.chapters ?? []))

/** Save an edited date: re-seal under K_mem, PATCH via updateNode, and let the host update the tree. */
async function onDateSave(date: MemoryDate) {
  const key = props.memoryKey
  if (!key) return
  const nodeId = props.node.id
  try {
    const wrappedMeta = await setMemoryDate(props.scopeId, nodeId, key, date, props.node.wrappedMeta)
    emit('metaUpdated', { nodeId, wrappedMeta })
  } catch {
    // best-effort: keep the header stable; the next open-time reconcile can re-materialize an `auto` range
  }
}

/** Materialize the `auto` range from the full media list and re-seal only when it changed (the self-heal). */
async function reconcileAuto() {
  const key = props.memoryKey
  if (!key) return
  const stored = unsealMemoryDate(key, props.node)
  if (stored && stored.mode !== 'auto') return // a manual date is the user's — never overwritten
  const next = computeAutoRange(items.value, key)
  const current: MemoryDate = stored ?? { mode: 'auto' }
  if (sameMemoryDate(current, next)) return
  if (!stored && next.from === undefined) return // absent meta already == the empty `auto` state; nothing to seal
  const nodeId = props.node.id
  try {
    const wrappedMeta = await setMemoryDate(props.scopeId, nodeId, key, next, props.node.wrappedMeta)
    if (props.node.id === nodeId) emit('metaUpdated', { nodeId, wrappedMeta })
  } catch {
    // leave the stale range; the next Memory open self-heals it
  }
}

// --- Memory location (cr-0069, uc.memory.place). A deliberate per-Memory named place sealed in the SAME node
// `wrappedMeta` blob as the date (read-modify-write, operator-blind). The map + geocoding are gated behind a
// personal two-stage opt-in (ADR 0016/0018); the position is the medoid of the Memory's photo GPS (`auto`,
// self-heals as media arrive) or a hand-dropped pin (`fixed`).
const { mapsAvailable, geocoderUrl, geocodingAvailable } = useMapConfig()
const locationEditorOpen = ref(false)
const consentDialogOpen = ref(false)
const storedLocation = computed(() => (props.memoryKey ? unsealMemoryLocation(props.memoryKey, props.node) : null))
const gpsPoints = computed(() => (props.memoryKey ? mediaGpsPoints(items.value, props.memoryKey) : []))
const medoid = computed(() => computeMedoid(gpsPoints.value))
// A pending auto place (armed, name deferred) shows a "…being determined" label until the first media names it.
const locationLabel = computed(() => {
  const loc = storedLocation.value
  if (!loc) return ''
  return isLocationPending(loc) ? t('memory.place.pending') : loc.name
})

/** Open the location editor — but first gate on the personal maps opt-in (no consent ⇒ ask, no external calls). */
function openLocationEditor() {
  if (!props.memoryKey) return
  if (mapsAvailable.value) locationEditorOpen.value = true
  else consentDialogOpen.value = true
}

/** The user grants the coupled tiles+geocoding consent → enable and proceed into the editor. */
function enableMaps() {
  setMapsConsent(true)
  consentDialogOpen.value = false
  if (mapsAvailable.value) locationEditorOpen.value = true
}

/** Save (or, for null, remove) the location: read-modify-write under K_mem — the date is preserved either way. */
async function onLocationSave(location: MemoryLocation | null) {
  const key = props.memoryKey
  if (!key) return
  const nodeId = props.node.id
  try {
    const wrappedMeta = await setMemoryLocation(props.scopeId, nodeId, key, location, props.node.wrappedMeta)
    emit('metaUpdated', { nodeId, wrappedMeta })
  } catch {
    // best-effort: keep the header stable; a retry re-seals
  }
}

/** Re-medoid an `auto` location from the media, and materialize a **pending** name by reverse-geocoding the
 *  medoid once (opt-in) — the self-heal that turns a create-time `auto` arm into a named place on the first
 *  geotagged upload. Re-seals only when something changed. A `manual` name / a `fixed` place is never touched. */
async function reconcileAutoLocation() {
  const key = props.memoryKey
  if (!key) return
  const stored = unsealMemoryLocation(key, props.node)
  if (!stored || stored.derivedMode !== 'auto') return // no location / a `fixed` place is the user's — never moved
  const point = computeMedoid(mediaGpsPoints(items.value, key))
  if (!point) return // no GPS yet (e.g. an `auto` pre-armed at creation) — nothing to derive from
  let next: MemoryLocation = { ...stored, derived: point }
  // Materialize/refresh a geocoded name from the medoid at the stored granularity `level` (client-direct
  // third-party, ADR 0016 standing consent): a pending arm gets its first name; a moved medoid refreshes it.
  const medoidMoved = stored.derived?.lat !== point.lat || stored.derived?.lng !== point.lng
  if (stored.nameSource === 'geocoded' && geocodingAvailable.value && (isLocationPending(stored) || medoidMoved)) {
    const levels = await reverseGeocode(geocoderUrl.value, point.lat, point.lng, locale.value)
    const level = levels ? (stored.nameAnchor?.level && levels.find((l) => l.key === stored.nameAnchor?.level) ? { key: stored.nameAnchor.level, name: nameAtLevel(levels, stored.nameAnchor.level)! } : defaultLevel(levels)) : null
    if (level) next = { ...next, name: level.name, nameAnchor: { lat: point.lat, lng: point.lng, level: level.key } }
  }
  if (sameMemoryLocation(stored, next)) return
  const nodeId = props.node.id
  try {
    const wrappedMeta = await setMemoryLocation(props.scopeId, nodeId, key, next, props.node.wrappedMeta)
    if (props.node.id === nodeId) emit('metaUpdated', { nodeId, wrappedMeta })
  } catch {
    // leave the stale position; the next Memory open self-heals it
  }
}

// --- Memory cover (cr-0072 + cr-0073, uc.memory.cover). A deliberately chosen image + a 16:9 crop, sealed in the
// SAME node `wrappedMeta` blob as the date/location (read-modify-write, operator-blind). The source is one of the
// Memory's own media OR a brand-new uploaded image (a standalone cover blob, cr-0073); the header shows it via the
// shared crop formula. No auto-cover — unset ⇒ the placeholder tile.
const coverEditorOpen = ref(false)
const storedCover = computed(() => (props.memoryKey ? unsealMemoryCover(props.memoryKey, props.node) : null))
// An **upload**-source cover isn't in `thumbs` (no media row), so its standalone blob is decrypted on demand into
// this object URL (revoked on change/unmount). A **media**-source cover reuses the already-decrypted thumbnail.
const uploadCoverUrl = ref<string | null>(null)
function revokeUploadCover() {
  if (uploadCoverUrl.value) URL.revokeObjectURL(uploadCoverUrl.value)
  uploadCoverUrl.value = null
}
watch(
  storedCover,
  async (cover) => {
    revokeUploadCover()
    const key = props.memoryKey
    if (!key || cover?.source.kind !== 'upload') return
    try {
      const bytes = await openBlob(props.scopeId, key, cover.source.ref)
      uploadCoverUrl.value = URL.createObjectURL(new Blob([bytes as BlobPart]))
    } catch {
      // best-effort: a locked/absent cover blob ⇒ the placeholder tile
    }
  },
  { immediate: true },
)
// The decrypted image of the current cover: the media thumbnail (already in `thumbs`) or the uploaded cover blob.
const coverSrc = computed(() => {
  const cover = storedCover.value
  if (!cover) return null
  return cover.source.kind === 'upload' ? uploadCoverUrl.value : thumbs.value.get(cover.source.mediaId) ?? null
})
// The current cover, decrypted, to re-crop in the dialog (its cropper needs the source image — esp. for uploads).
const coverExistingSrc = computed(() => coverSrc.value)
// The current cover's media id (when the cover is a Memory photo) — the lightbox marks that photo as the cover.
const coverMediaId = computed(() => (storedCover.value?.source.kind === 'media' ? storedCover.value.source.mediaId : null))

/**
 * "Set as cover" from the lightbox (cr-0073 §gallery): set that photo as the cover **in one click**, with a
 * default centre-crop (full frame → `object-fit: cover`), and **keep the lightbox open** so the user can keep
 * browsing. Fine-tuning the crop is a deliberate step later, via the header cover button.
 */
function onSetCoverFromPhoto(payload: { mediaId: string }) {
  void onCoverSave({ kind: 'media', mediaId: payload.mediaId, crop: { ...FULL_FRAME } })
}

/**
 * Apply the cover dialog's save intent (cr-0072 media + cr-0073 upload): read-modify-write the node meta under
 * K_mem (the date + location are preserved). An **upload** uploads + commits a standalone cover blob (whose
 * commit atomically reclaims any prior cover blob); switching **away** from a prior upload cover (to a media
 * cover or none) reclaims its now-orphaned blob via `deleteCoverBlob`. `keep` re-seals the current source with a
 * moved crop only.
 */
async function onCoverSave(intent: CoverSaveIntent) {
  const key = props.memoryKey
  if (!key) return
  const nodeId = props.node.id
  const priorUpload = storedCover.value?.source.kind === 'upload'
  try {
    let cover: MemoryCover | null
    if (intent.kind === 'upload') {
      const ref = await uploadCoverBlob(props.scopeId, nodeId, key, {
        bytes: new Uint8Array(await intent.file.arrayBuffer()),
        mediaType: intent.file.type,
        lastModified: intent.file.lastModified,
      })
      cover = { source: { kind: 'upload', ref }, crop: intent.crop }
    } else if (intent.kind === 'media') {
      if (priorUpload) await deleteCoverBlob(props.scopeId, nodeId) // reclaim the now-orphaned upload blob
      cover = { source: { kind: 'media', mediaId: intent.mediaId }, crop: intent.crop }
    } else if (intent.kind === 'keep') {
      const source = storedCover.value?.source
      if (!source) return
      cover = { source, crop: intent.crop }
    } else {
      if (priorUpload) await deleteCoverBlob(props.scopeId, nodeId)
      cover = null
    }
    const wrappedMeta = await setMemoryCover(props.scopeId, nodeId, key, cover, props.node.wrappedMeta)
    emit('metaUpdated', { nodeId, wrappedMeta })
  } catch {
    // best-effort: keep the header stable; a retry re-seals
  }
}

const aspectRatios = computed(() => {
  const key = props.memoryKey
  const m = new Map<string, number>()
  for (const item of items.value) {
    const dims = key ? unsealMeta(key, item) : null
    m.set(item.mediaId, galleryDisplayAspectRatio(dims?.w, dims?.h))
  }
  return m
})
// --- optimistic in-grid upload tiles (uc.memory.upload, cr-0074). The shared [useOptimisticTiles] composable
// renders the global upload queue's still-in-flight files for THIS node (grouping key = the node id) as instant
// preview tiles + progress/retry, pinned ahead of the listed media, reconciled into the real thumbnail by
// `mediaId`. Reused by the anonymous contributor viewer (cr-0101).
const listedMediaIds = computed(() => new Set(items.value.map((it) => it.mediaId)))
const donatedThumbs = new Set<string>() // mediaIds in `thumbs` currently backed by a donated preview url
const { optimisticTiles, donorPreviewFor } = useOptimisticTiles(computed(() => props.node.id), listedMediaIds)

// The tile list handed to [MediaGrid] — optimistic upload tiles pinned ahead of the listed media (the freshest
// action, in enqueue order), each carrying its content so the grid slot renders it without a lookup. MediaGrid
// runs the justified layout + positions each tile; here we just declare id + aspect + content.
type MemoryTileItem =
  | { id: string; aspectRatio: number; kind: 'optimistic'; optimistic: OptimisticTileVM }
  | { id: string; aspectRatio: number; kind: 'media'; media: MediaItem; index: number }
const FALLBACK_ASPECT = galleryDisplayAspectRatio(undefined, undefined)
const layoutItems = computed<MemoryTileItem[]>(() => [
  ...optimisticTiles.value.map((t) => ({ id: t.layoutId, aspectRatio: t.aspectRatio, kind: 'optimistic' as const, optimistic: t })),
  ...sortedItems.value.map((it, index) => ({
    id: it.mediaId,
    aspectRatio: aspectRatios.value.get(it.mediaId) ?? FALLBACK_ASPECT,
    kind: 'media' as const,
    media: it,
    index,
  })),
])
// Whether the gallery has anything to show — real media or in-flight optimistic uploads.
const hasContent = computed(() => items.value.length > 0 || optimisticTiles.value.length > 0)

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
  donatedThumbs.clear()
}

/** Set (or replace) a media's decrypted thumbnail, revoking any prior url — incl. a donated preview it supersedes. */
function setThumb(mediaId: string, url: string) {
  const prev = thumbs.value.get(mediaId)
  if (prev) URL.revokeObjectURL(prev)
  thumbs.value.set(mediaId, url)
  donatedThumbs.delete(mediaId)
  thumbs.value = new Map(thumbs.value) // reassign so the template reacts
}

/** Merge a fresh media list into `items` WITHOUT an empty-flash: revoke only the thumbnails of dropped media,
 *  keep the rest in place, and donate a done upload's preview into `thumbs` for each newly-listed media so its
 *  optimistic tile hands off with no gray gap (the real thumbnail replaces the donation once it decrypts). */
function mergeItems(listed: MediaItem[]) {
  for (const id of removedThumbIds(thumbs.value.keys(), listed)) {
    const url = thumbs.value.get(id)
    if (url) URL.revokeObjectURL(url)
    thumbs.value.delete(id)
    donatedThumbs.delete(id)
  }
  const known = listedMediaIds.value
  for (const m of listed) {
    if (known.has(m.mediaId) || thumbs.value.has(m.mediaId) || !hasDerivatives(m)) continue
    const donorUrl = donorPreviewFor(m.mediaId) // the composable marks the preview donated + returns its url
    if (!donorUrl) continue
    thumbs.value.set(m.mediaId, donorUrl)
    donatedThumbs.add(m.mediaId)
  }
  thumbs.value = new Map(thumbs.value)
  items.value = listed
}

/** List the Memory's media and fetch each derivable item's thumbnail tier (parallel, operator-blind). */
/** Open the lightbox on a grid tile, snapshotting the current sorted order so an in-lightbox edit can't reorder it. */
function openLightboxAt(index: number) {
  lightboxItems.value = sortedItems.value.slice()
  lightboxIndex.value = index
}
/** A date correction from the lightbox → patch the item's sealed tier so the grid re-sorts (no refetch). The
 *  item object is shared with the open lightbox's snapshot, so the reopened detail stays consistent too. */
function onCapturedAtUpdated(payload: { mediaId: string; wrappedMeta: string | null }) {
  const item = items.value.find((it) => it.mediaId === payload.mediaId)
  if (!item) return
  item.wrappedMeta = payload.wrappedMeta ?? undefined
  items.value = [...items.value] // reassign so sortedItems recomputes and the grid re-sorts
}

// `reset` (a Memory/chapter switch) clears the grid up front; an incremental reload (post-upload batch) merges
// the fresh list into place so existing tiles don't blink empty — jarring next to the optimistic tiles (cr-0074).
async function load({ reset = false }: { reset?: boolean } = {}) {
  const key = props.memoryKey
  const nodeId = props.node.id
  if (!key) {
    revokeThumbs()
    items.value = []
    status.value = 'error' // E2: no K_mem (locked / not openable) → gallery unavailable
    return
  }
  if (reset) {
    revokeThumbs()
    items.value = []
  }
  status.value = items.value.length ? 'ready' : 'loading'
  try {
    const listed = await listMedia(props.scopeId, nodeId)
    if (props.node.id !== nodeId) return // navigated away while listing
    mergeItems(listed)
    status.value = 'ready'
    await Promise.all(
      listed.map(async (m) => {
        if (!hasDerivatives(m)) return // E1: non-image/video → icon tile, no fetch
        if (thumbs.value.has(m.mediaId) && !donatedThumbs.has(m.mediaId)) return // real thumbnail already in place
        try {
          const bytes = await openGrid(props.scopeId, key, m)
          if (props.node.id !== nodeId) return
          setThumb(m.mediaId, URL.createObjectURL(new Blob([bytes as BlobPart])))
        } catch {
          // a single failed tile stays a placeholder (or its donated preview); the rest of the grid is unaffected
        }
      }),
    )
    if (props.node.id === nodeId) {
      await reconcileAuto() // settle point: open-time self-heal + post-upload-batch (date, then location)
      await reconcileAutoLocation() // reads the date-reconciled wrappedMeta (patched in place) → RMW-safe
    }
  } catch {
    if (props.node.id === nodeId) status.value = 'error'
  }
}

watch(
  () => [props.node.id, props.memoryKey] as const,
  () => {
    lightboxIndex.value = null
    activeTab.value = 'gallery' // reset to the gallery when a different Memory/chapter opens
    void load({ reset: true }) // a different Memory/chapter → clear the grid; a post-upload reload merges (below)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  revokeThumbs()
  revokeUploadCover()
  // the optimistic preview urls are owned + revoked by useOptimisticTiles
})
</script>

<template>
  <div class="memory-route-surface memory-detail">
    <header class="gallery-header">
      <div class="gallery-header-lead">
        <button
          type="button"
          class="memory-cover-preview"
          :class="{ 'is-empty': !coverSrc }"
          :disabled="!memoryKey"
          :aria-label="storedCover ? t('memory.cover.edit') : t('memory.cover.set')"
          :title="storedCover ? t('memory.cover.edit') : t('memory.cover.set')"
          @click="coverEditorOpen = true"
        >
          <MemoryCoverImage :src="coverSrc" :crop="storedCover?.crop ?? null" :alt="node.name" />
          <span class="memory-cover-preview-hint">
            <span class="pi pi-image" />
            <span>{{ storedCover ? t('memory.cover.edit') : t('memory.cover.set') }}</span>
          </span>
        </button>
      <div class="gallery-header-copy">
        <h1 class="gallery-title">{{ node.name }}</h1>
        <span class="gallery-count">{{ t('media.itemCount', { count: items.length }, items.length) }}</span>
        <button
          type="button"
          class="memory-date-chip"
          :class="{ 'is-empty': !dateLabel }"
          :disabled="!memoryKey"
          :aria-label="t('memory.date.edit')"
          :title="t('memory.date.edit')"
          @click="dateEditorOpen = true"
        >
          <span class="pi pi-calendar" />
          <span>{{ dateLabel || t('memory.date.noDate') }}</span>
        </button>
        <button
          type="button"
          class="memory-date-chip memory-location-chip"
          :class="{ 'is-empty': !locationLabel }"
          :disabled="!memoryKey"
          :aria-label="locationLabel ? t('memory.place.edit') : t('memory.place.set')"
          :title="locationLabel ? t('memory.place.edit') : t('memory.place.set')"
          @click="openLocationEditor"
        >
          <span class="pi pi-map-marker" />
          <span>{{ locationLabel || t('memory.place.set') }}</span>
        </button>
      </div>
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
          v-if="shareable"
          v-tooltip.bottom="t('share.share')"
          type="button"
          :aria-label="t('share.share')"
          icon="pi pi-share-alt"
          severity="secondary"
          text
          :disabled="!memoryKey"
          @click="$emit('share')"
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

    <!-- Tabbed detail (cr-0061, ported from the design source): Gallery (the media grid) + Chapters (the
         nested Memories). Other design tabs — journal / map / playlists — arrive with their features. -->
    <Tabs v-model:value="activeTab" class="memory-tabs">
      <TabList>
        <Tab value="gallery">{{ t('memory.gallery') }}</Tab>
        <Tab value="chapters">
          {{ t('memory.chapters') }}<span v-if="chapters && chapters.length" class="tab-count">{{ chapters.length }}</span>
        </Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="gallery">
          <div v-if="status === 'loading' && !hasContent" class="media-grid-placeholder">
            <span class="pi pi-spin pi-spinner" />
            <span>{{ t('media.loading') }}</span>
          </div>
          <p v-else-if="status === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
          <p v-else-if="!hasContent" class="veyq-empty">{{ t('media.empty') }}</p>
          <template v-else>
            <div v-if="items.length" class="gallery-toolbar">
              <button
                class="gallery-sort-toggle"
                type="button"
                :aria-label="sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest')"
                :title="sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest')"
                @click="sortDir = sortDir === 'newest' ? 'oldest' : 'newest'"
              >
                <span class="pi" :class="sortDir === 'newest' ? 'pi-sort-amount-down' : 'pi-sort-amount-up-alt'" />
                <span>{{ sortDir === 'newest' ? t('media.sortNewest') : t('media.sortOldest') }}</span>
              </button>
            </div>
            <MediaGrid :items="layoutItems" :aria-label="t('media.gallery')">
              <template #tile="{ tile, item }">
                <!-- optimistic in-grid upload tile (cr-0074): the shared OptimisticTile (preview + progress / retry) -->
                <OptimisticTile
                  v-if="item.kind === 'optimistic'"
                  :tile="item.optimistic"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  @retry="retryFile"
                />
                <button
                  v-else
                  class="gallery-tile"
                  type="button"
                  :data-media-id="item.media.mediaId"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  :aria-label="t('media.openItem', { index: item.index + 1 })"
                  @click="openLightboxAt(item.index)"
                >
                  <img
                    v-if="thumbs.get(item.media.mediaId)"
                    class="media-grid-tile-media media-grid-tile-media-loaded"
                    :src="thumbs.get(item.media.mediaId)"
                    :alt="node.name"
                  />
                  <span v-else-if="!hasDerivatives(item.media)" class="media-grid-icon pi pi-file" />
                  <span v-else class="media-grid-tile-placeholder" />
                </button>
              </template>
            </MediaGrid>
          </template>
        </TabPanel>

        <TabPanel value="chapters">
          <!-- Chapters as a MemoryCard grid + a create card, like the browse Memories section (design source).
               Each chapter opens to its own gallery; its media is sealed under the chapter's own K_mem. -->
          <div class="chapters-toolbar">
            <ViewModeToggle />
          </div>
          <div
            class="memory-card-grid memory-card-grid-memories"
            :class="{ 'is-list': viewMode === 'list' }"
            :aria-label="t('memory.chapters')"
          >
            <MemoryCard
              v-for="{ node: chapter, date } in datedChapters"
              :key="chapter.id"
              :memory="chapter"
              :date="date"
              :favoritable="false"
              @open="$emit('openChapter', $event)"
            />
            <button
              class="veyq-card veyq-card-interactive memory-create-card memory-create-card-memory"
              type="button"
              :disabled="!memoryKey"
              @click="$emit('createChapter')"
            >
              <span class="pi pi-plus" />
              <strong>{{ t('memory.addChapter') }}</strong>
            </button>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <MediaLightbox
      v-if="lightboxIndex !== null && memoryKey"
      :scope-id="scopeId"
      :memory-key="memoryKey"
      :items="lightboxItems"
      :start-index="lightboxIndex"
      :memory-name="node.name"
      :cover-media-id="coverMediaId"
      @close="lightboxIndex = null"
      @updated="onCapturedAtUpdated"
      @set-cover="onSetCoverFromPhoto"
    />

    <MemoryDateEditor
      v-if="dateEditorOpen && memoryKey"
      :date="storedDate"
      :auto-range="autoRange"
      @save="onDateSave"
      @close="dateEditorOpen = false"
    />

    <MemoryLocationEditor
      v-if="locationEditorOpen && memoryKey"
      :location="storedLocation"
      :medoid="medoid"
      :points="gpsPoints"
      @save="onLocationSave"
      @close="locationEditorOpen = false"
    />

    <MemoryCoverDialog
      v-if="coverEditorOpen && memoryKey"
      :cover="storedCover"
      :existing-src="coverExistingSrc"
      :has-media="items.length > 0"
      @save="onCoverSave"
      @close="coverEditorOpen = false"
    />

    <Dialog
      :visible="consentDialogOpen"
      :header="t('memory.place.consentTitle')"
      modal
      class="memory-location-consent"
      @update:visible="consentDialogOpen = false"
    >
      <p>{{ t('memory.place.consentBody') }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="consentDialogOpen = false" />
        <Button type="button" :label="t('memory.place.consentEnable')" icon="pi pi-map" @click="enableMaps" />
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.gallery-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.6rem;
}
/* Icon + label pill (NOT the icon-only .veyq-icon-button utility, which forces a square grid-centered box and
   stacks the icon over the label). Matches the header date/place chips for a consistent toolbar affordance. */
.gallery-sort-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.35rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-surface);
  color: var(--app-text);
  font-size: 0.86rem;
  cursor: pointer;
}
.gallery-sort-toggle:hover {
  background: var(--app-hover);
}
.gallery-sort-toggle:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.gallery-sort-toggle .pi {
  font-size: 0.9rem;
}
.gallery-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 1.15rem;
  border-bottom: 1px solid var(--app-line);
}
/* The leading group: the cover preview beside the title + chips. */
.gallery-header-lead {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
  min-width: 0;
}
.gallery-header-copy {
  display: grid;
  gap: 0.3rem;
  min-width: 0;
}
/* The Memory cover (cr-0072): a 16:9 tile showing the chosen, cropped cover (or the placeholder). It IS the
   "set/edit cover" button — clicking it opens the crop dialog. A hint label sits over it (always when empty,
   on hover once a cover is set). */
.memory-cover-preview {
  position: relative;
  flex: 0 0 auto;
  width: 180px;
  aspect-ratio: 16 / 9;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  cursor: pointer;
}
.memory-cover-preview:disabled {
  opacity: 0.6;
  cursor: default;
}
.memory-cover-preview-hint {
  position: absolute;
  inset-inline: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.3rem;
  background: color-mix(in srgb, var(--app-backdrop-strong) 62%, transparent);
  color: #fff;
  font-size: 0.8rem;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.memory-cover-preview.is-empty .memory-cover-preview-hint,
.memory-cover-preview:hover .memory-cover-preview-hint,
.memory-cover-preview:focus-visible .memory-cover-preview-hint {
  opacity: 1;
}
.memory-cover-preview-hint .pi {
  font-size: 0.85rem;
}
@media (max-width: 640px) {
  .gallery-header-lead {
    flex-direction: column;
  }
  .memory-cover-preview {
    width: 100%;
    max-width: 260px;
  }
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
/* The sealed Memory date, shown under the title as a clickable chip that opens the date editor (cr-0067). */
.memory-date-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  width: auto;
  justify-self: start;
  margin-top: 0.15rem;
  padding: 0.28rem 0.6rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-surface);
  color: var(--app-text);
  font-size: 0.86rem;
  cursor: pointer;
}
.memory-date-chip:disabled {
  opacity: 0.6;
  cursor: default;
}
.memory-date-chip.is-empty {
  color: var(--app-muted);
}
.memory-date-chip .pi {
  font-size: 0.9rem;
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

/* The Chapters tab's view toggle, right-aligned above the card grid. */
.chapters-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 0.75rem;
}
/* A small count next to the Chapters tab label. */
.tab-count {
  margin-left: 0.4rem;
  padding: 0 0.4rem;
  border-radius: 999px;
  background: var(--app-control-bg);
  color: var(--app-muted);
  font-size: 0.75rem;
  font-weight: 700;
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
/* The optimistic upload tile's own styles now live in the shared OptimisticTile component (cr-0074). */
</style>
