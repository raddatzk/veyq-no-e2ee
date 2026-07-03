<script setup lang="ts">
// A Memory's detail surface = its **gallery** (cr-0049, uc.memory.gallery). Opens the Memory's media as a
// thumbnail grid, each tile's thumbnail fetched as ciphertext via presigned GET and decrypted on device under
// K_mem (operator-blind); clicking a tile opens the lightbox (preview, prev/next, download original). The host
// view (MemoryHomeView) owns the rename/delete dialogs + the favorites store and unwraps K_mem from the tree.
// Ported presentation from the design source (legacy/frontend-noe2ee); only the data layer is ours. Decrypted
// thumbnails become object URLs that are **revoked** on reload / unmount (no lingering plaintext blob URLs).
// Out of scope (own CRs): journal, chapters + chapter-nested media, map/tracks, playlists, buckets, sharing.
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import type { TreeNode } from '../tree'
import { listMedia, openGrid, openBlob, uploadCoverBlob, deleteCoverBlob, hasDerivatives, unsealMeta, effectiveCapturedAt, createBrowserDeriver, type MediaItem } from '../media'
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
import { calculateJustifiedMediaLayout, galleryDisplayAspectRatio, type MediaGridLayoutTile } from '../mediaGridLayout'
import { enqueueUploads, isSupportedMediaFile, inferMediaType, mergeFiles, retryFile, useUploadQueue, type UploadFile } from '../uploadQueue'
import { selectOptimisticUploads, removedThumbIds, optimisticTileId } from '../optimisticUpload'
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
}>()
const emit = defineEmits<{
  toggleFavorite: []
  rename: []
  delete: []
  openChapter: [node: TreeNode]
  createChapter: []
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
// aspect. The layout is recomputed whenever the items or the measured container width change.
const GAP = 8
const gridEl = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
let gridObserver: ResizeObserver | null = null
let measureFrame: number | null = null

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
// Chapters ordered by their own sealed start date — a chapter's K_mem opens under THIS Memory's K_mem (cr-0067).
const datedChapters = computed(() => sortNodesByDate(props.memoryKey, props.chapters ?? []))

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

const targetTileHeight = computed(() => (containerWidth.value <= 720 ? 150 : 200))
const aspectRatios = computed(() => {
  const key = props.memoryKey
  const m = new Map<string, number>()
  for (const item of items.value) {
    const dims = key ? unsealMeta(key, item) : null
    m.set(item.mediaId, galleryDisplayAspectRatio(dims?.w, dims?.h))
  }
  return m
})
// --- optimistic in-grid upload tiles (uc.memory.upload, cr-0074). The global upload queue's still-in-flight
// files for THIS node render immediately as tiles — an instant local preview (a `URL.createObjectURL` of the
// user's own file, never uploaded in the clear) + a per-tile progress / failed-with-retry state — pinned ahead
// of the listed media. Each reconciles into the real decrypted thumbnail once its media lists (matched by the
// `mediaId` the queue records on `done`); the pure selection/merge logic lives in ../optimisticUpload.
const { batches: uploadBatches } = useUploadQueue()
const deriver = createBrowserDeriver()
const RING_CIRCUMFERENCE = 2 * Math.PI * 16 // the progress-ring radius (see the SVG below)
type Preview = { url: string; aspectRatio: number; donated: boolean }
// fileId → { local objectURL, client-probed aspect ratio (4:3 until probed), donated (its url handed to `thumbs`
// on reconcile so the swap has no gray flash — `thumbs` then owns its revoke) }.
const previews = reactive(new Map<string, Preview>())
const donatedThumbs = new Set<string>() // mediaIds in `thumbs` currently backed by a donated preview url

const listedMediaIds = computed(() => new Set(items.value.map((it) => it.mediaId)))
const optimisticFiles = computed(() => selectOptimisticUploads(uploadBatches.value, props.node.id, listedMediaIds.value))
const optimisticTiles = computed(() =>
  optimisticFiles.value.map((f) => {
    const p = previews.get(f.id)
    return {
      layoutId: optimisticTileId(f.id),
      fileId: f.id,
      name: f.name,
      url: p?.url ?? null,
      aspectRatio: p?.aspectRatio ?? galleryDisplayAspectRatio(undefined, undefined),
      status: f.status,
      progress: f.progress,
      isVideo: f.mediaType.startsWith('video/'),
    }
  }),
)

// Own the preview lifecycle: mint a local objectURL + probe the aspect ratio as each file appears; revoke +
// prune when it drops (cancelled / reconciled). A donated url is skipped — `thumbs` owns its revoke by then.
watch(
  optimisticFiles,
  (files) => {
    const present = new Set(files.map((f) => f.id))
    for (const [fileId, p] of previews) {
      if (present.has(fileId)) continue
      if (!p.donated) URL.revokeObjectURL(p.url)
      previews.delete(fileId)
    }
    for (const f of files) {
      if (previews.has(f.id)) continue
      previews.set(f.id, { url: URL.createObjectURL(f.file), aspectRatio: galleryDisplayAspectRatio(undefined, undefined), donated: false })
      void probeAspectRatio(f)
    }
  },
  { immediate: true },
)

/** Probe a picked file's intrinsic dimensions client-side (best-effort) and settle its tile's aspect ratio. */
async function probeAspectRatio(f: UploadFile) {
  try {
    const dims = await deriver.probeDimensions?.(f.file, f.mediaType)
    const p = previews.get(f.id)
    if (p && dims) previews.set(f.id, { ...p, aspectRatio: galleryDisplayAspectRatio(dims.w, dims.h) })
  } catch {
    // best-effort: keep the fallback aspect ratio
  }
}

/** Retry a failed optimistic tile's upload (re-enqueues it in the shared queue). */
function onRetryOptimistic(fileId: string) {
  retryFile(fileId)
}

const layout = computed(() =>
  calculateJustifiedMediaLayout({
    // optimistic tiles pin ahead of the listed media (the freshest action), in enqueue order
    items: [
      ...optimisticTiles.value.map((t) => ({ id: t.layoutId, aspectRatio: t.aspectRatio })),
      ...sortedItems.value.map((it) => ({ id: it.mediaId, aspectRatio: aspectRatios.value.get(it.mediaId) })),
    ],
    containerWidth: containerWidth.value,
    targetHeight: targetTileHeight.value,
    gap: GAP,
  }),
)
const itemsById = computed(() => new Map(sortedItems.value.map((it, index) => [it.mediaId, { item: it, index }])))
const optimisticById = computed(() => new Map(optimisticTiles.value.map((t) => [t.layoutId, t])))
type OptimisticTile = (typeof optimisticTiles.value)[number]
type GridTile = MediaGridLayoutTile &
  ({ kind: 'optimistic'; optimistic: OptimisticTile } | { kind: 'media'; media: MediaItem; index: number })
const tiles = computed<GridTile[]>(() =>
  layout.value.tiles.flatMap((tile): GridTile[] => {
    const opt = optimisticById.value.get(tile.id)
    if (opt) return [{ ...tile, kind: 'optimistic', optimistic: opt }]
    const entry = itemsById.value.get(tile.id)
    return entry ? [{ ...tile, kind: 'media', media: entry.item, index: entry.index }] : []
  }),
)
// Whether the gallery has anything to show — real media or in-flight optimistic uploads. Drives the grid/empty
// gate: it must NOT depend on the measured `layout` (the grid element has to mount first so its width can be
// measured — gating on measured tiles would deadlock: no width → no tiles → grid never renders → no width).
const hasContent = computed(() => items.value.length > 0 || optimisticFiles.value.length > 0)

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

/** Find a done upload's still-live preview for a just-listed media, and mark it donated (so its lifecycle passes
 *  to `thumbs`) — the seamless-handoff source. */
function donorPreviewFor(mediaId: string): Preview | null {
  for (const b of uploadBatches.value) {
    for (const f of b.files) {
      if (f.status !== 'done' || f.mediaId !== mediaId) continue
      const p = previews.get(f.id)
      if (p && !p.donated) {
        previews.set(f.id, { ...p, donated: true })
        return previews.get(f.id)!
      }
    }
  }
  return null
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
    const donor = donorPreviewFor(m.mediaId)
    if (!donor) continue
    thumbs.value.set(m.mediaId, donor.url)
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

// The grid element mounts/unmounts with the ready state; (re)attach the size observer when it appears.
onMounted(attachGridObserver)
watch(gridEl, attachGridObserver, { flush: 'post' })

onBeforeUnmount(() => {
  revokeThumbs()
  revokeUploadCover()
  for (const p of previews.values()) if (!p.donated) URL.revokeObjectURL(p.url) // donated urls are owned by `thumbs`
  previews.clear()
  gridObserver?.disconnect()
  if (measureFrame !== null) cancelAnimationFrame(measureFrame)
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
            <div
              ref="gridEl"
              class="gallery-grid"
            :style="{ height: `${layout.height}px` }"
            :aria-label="t('media.gallery')"
          >
            <template v-for="tile in tiles" :key="tile.id">
              <!-- optimistic in-grid upload tile (cr-0074): the picked file's local preview + progress / retry -->
              <div
                v-if="tile.kind === 'optimistic'"
                class="gallery-tile gallery-tile-optimistic"
                :class="{ 'is-failed': tile.optimistic.status === 'failed' }"
                :data-upload-id="tile.optimistic.fileId"
                :style="{
                  transform: `translate(${tile.left}px, ${tile.top}px)`,
                  width: `${tile.renderedWidth}px`,
                  height: `${tile.renderedHeight}px`,
                }"
                :aria-label="t('media.uploadTilePreview', { name: tile.optimistic.name })"
              >
                <img
                  v-if="tile.optimistic.url && !tile.optimistic.isVideo"
                  class="media-grid-tile-media"
                  :src="tile.optimistic.url"
                  :alt="tile.optimistic.name"
                />
                <span v-else class="media-grid-icon pi pi-video" />
                <div v-if="tile.optimistic.status === 'failed'" class="upload-tile-state upload-tile-failed">
                  <span class="pi pi-exclamation-triangle" />
                  <span class="upload-tile-failed-label">{{ t('media.uploadTileFailed') }}</span>
                  <button type="button" class="upload-tile-retry" @click.stop="onRetryOptimistic(tile.optimistic.fileId)">
                    <span class="pi pi-refresh" />
                    <span>{{ t('media.uploadRetry') }}</span>
                  </button>
                </div>
                <div v-else class="upload-tile-state upload-tile-progress" :aria-label="t('media.uploadStatus.uploading')">
                  <svg class="upload-ring" viewBox="0 0 36 36" role="progressbar">
                    <circle class="upload-ring-track" cx="18" cy="18" r="16" />
                    <circle
                      class="upload-ring-fill"
                      :class="{ 'is-indeterminate': tile.optimistic.status === 'queued' }"
                      cx="18"
                      cy="18"
                      r="16"
                      :stroke-dasharray="`${tile.optimistic.progress * RING_CIRCUMFERENCE} ${RING_CIRCUMFERENCE}`"
                    />
                  </svg>
                </div>
              </div>
              <button
                v-else
                class="gallery-tile"
                type="button"
                :data-media-id="tile.media.mediaId"
                :style="{
                  transform: `translate(${tile.left}px, ${tile.top}px)`,
                  width: `${tile.renderedWidth}px`,
                  height: `${tile.renderedHeight}px`,
                }"
                :aria-label="t('media.openItem', { index: tile.index + 1 })"
                @click="openLightboxAt(tile.index)"
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
            </template>
            </div>
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
/* Optimistic upload tile (cr-0074): the picked file shown instantly as a dimmed local preview with a progress
   overlay; a failed upload dims further and surfaces an inline retry. Not clickable (the lightbox opens the
   real media once it lands) — a plain div, not a button. */
.gallery-tile-optimistic {
  cursor: default;
}
.gallery-tile-optimistic .media-grid-tile-media {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.55; /* dim the preview so the progress overlay reads clearly */
}
.gallery-tile-optimistic.is-failed .media-grid-tile-media {
  opacity: 0.3;
}
.upload-tile-state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  padding: 0.5rem;
  text-align: center;
}
.upload-ring {
  width: 42px;
  height: 42px;
  transform: rotate(-90deg); /* start the fill at 12 o'clock */
}
.upload-ring-track {
  fill: none;
  stroke: color-mix(in srgb, #fff 55%, transparent);
  stroke-width: 3;
}
.upload-ring-fill {
  fill: none;
  stroke: #fff;
  stroke-width: 3;
  stroke-linecap: round;
  transition: stroke-dasharray 0.2s ease;
}
/* A queued file has no real progress yet — spin a short arc until its bytes start moving. */
.upload-ring-fill.is-indeterminate {
  stroke-dasharray: 25 100;
  transform-origin: center;
  animation: upload-ring-spin 0.9s linear infinite;
}
@keyframes upload-ring-spin {
  to {
    transform: rotate(360deg);
  }
}
.upload-tile-failed {
  color: #fff;
  background: color-mix(in srgb, var(--app-backdrop-strong) 55%, transparent);
}
.upload-tile-failed .pi-exclamation-triangle {
  font-size: 1.25rem;
}
.upload-tile-failed-label {
  font-size: 0.78rem;
  font-weight: 600;
}
.upload-tile-retry {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.25rem 0.6rem;
  border: 1px solid color-mix(in srgb, #fff 70%, transparent);
  border-radius: 999px;
  background: transparent;
  color: #fff;
  font-size: 0.78rem;
  cursor: pointer;
}
.upload-tile-retry:hover {
  background: color-mix(in srgb, #fff 18%, transparent);
}
.upload-tile-retry:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}
</style>
