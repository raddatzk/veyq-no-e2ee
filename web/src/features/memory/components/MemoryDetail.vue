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
import { openGrid, hasDerivatives, unsealMeta, effectiveCapturedAt, listMediaPage, newestCursor, deleteMedia, restoreMedia, type MediaItem } from '../media'
import { startMediaRevisionPolling, stopMediaRevisionPolling } from '../mediaRevisionPolling'
import { useMemoryDate } from '../useMemoryDate'
import { useMemoryLocation } from '../useMemoryLocation'
import { useMemoryCover } from '../useMemoryCover'
import { useMediaTrash } from '../useMediaTrash'
import { galleryDisplayAspectRatio } from '../mediaGridLayout'
import MediaGrid from './MediaGrid.vue'
import MediaTile from './MediaTile.vue'
import MemoryBuckets from './MemoryBuckets.vue'
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
import Menu from 'primevue/menu'
import { useViewMode } from '../../../shared/state/useViewMode'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { SHARING_UPLOADER_LINKS } from '../../platform/capabilities'

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
  shareUpload: []
  metaUpdated: [{ nodeId: string; wrappedMeta: string }]
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()
const { hasCapability } = useCapabilities()
// The uploader (drop-box) link is its OWN memory action (a separate dialog), offered only when the instance keeps
// the uploader-links capability (ADR 0032) — the server also 422-rejects createShare(UPLOADER) when it is off.
const canUploadShare = computed(() => !!props.shareable && hasCapability(SHARING_UPLOADER_LINKS))

const items = ref<MediaItem[]>([])
const thumbs = ref<Map<string, string>>(new Map()) // mediaId → thumbnail object URL (derivable items only)
const status = ref<'loading' | 'ready' | 'error'>('loading')
// live media updates (uc.memory.live-updates, cr-0107): the cached per-node counters — mediaRevision (the ETag
// echoed as If-None-Match; null until the first load) + deleteRevision (echoed as sinceDelete to gate the id-set).
const mediaRevision = ref<number | null>(null)
const deleteRevision = ref(0)
const lightboxIndex = ref<number | null>(null)
const lightboxItems = ref<MediaItem[]>([]) // a snapshot of the sorted order at open time (so an in-lightbox date edit can't reorder underfoot)
// --- trash (uc.memory.delete-media, cr-0110). A lightbox-only delete soft-deletes a photo into a per-Memory
// trash. The undo snackbar offers an immediate restore right after a lightbox delete.
const undo = ref<{ mediaId: string } | null>(null)
let undoTimer: ReturnType<typeof setTimeout> | null = null
// Tombstone of mediaIds optimistically removed by a trash but not yet confirmed gone by the server (cr-0111):
// filtered out of the merge/poll paths so a stale live read (a poll firing before the delete commits) can't
// re-add them — the "two-quick-trashes reappear" race.
const pendingDelete = new Set<string>()
// The Trash tab (cr-0110/0111) lives in `useMediaTrash` (cr-0147): its listing, thumbnails, batch select, the
// restore/purge menu + confirm. A restore/purge lifts the gallery tombstone; a restore re-lists the gallery.
const {
  trashedItems,
  trashThumbs,
  trashStatus,
  selectedTrash,
  trashMenu,
  purgeConfirm,
  trashLayoutItems,
  trashMenuModel,
  loadTrash,
  resetTrash,
  toggleTrashSelect,
  clearTrashSelection,
  openTrashMenu,
  restoreSelected,
  purgeSelected,
  confirmPurge,
} = useMediaTrash({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  reloadGallery: () => void load(),
  liftTombstone: (mediaId) => pendingDelete.delete(mediaId),
})
// date sort (cr-0066): tiles order by effective capturedAt; newest first by default.
const sortDir = ref<'newest' | 'oldest'>('newest')
const activeTab = ref<'gallery' | 'chapters' | 'beitraege' | 'trash'>('gallery') // local (the #fragment carries the tree path, cr-0031)
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

// --- Memory date (cr-0067, uc.memory.date-place) → extracted into `useMemoryDate` (cr-0145). The effective range
// is sealed on the NODE under K_mem (operator-blind) and shown in the header; `auto` materializes from the
// Memory's own media and self-heals on open + after an upload batch (via `reconcileAuto`, called at the settle
// points below). Props are passed as getters so the composable stays reactive to them.
const { dateEditorOpen, storedDate, autoRange, dateLabel, datedChapters, onDateSave, reconcileAuto } = useMemoryDate({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  chapters: () => props.chapters,
  items,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})

// --- Memory location (cr-0069, uc.memory.place) → extracted into `useMemoryLocation` (cr-0146). A deliberate
// per-Memory named place sealed in the SAME node `wrappedMeta` blob as the date (read-modify-write); the map +
// geocoding gate behind a personal two-stage opt-in (ADR 0016/0018). `reconcileAutoLocation` is called at the
// settle points below. Props are passed as getters so the composable stays reactive to them.
const {
  locationEditorOpen,
  consentDialogOpen,
  storedLocation,
  gpsPoints,
  medoid,
  locationLabel,
  locationResolved,
  openLocationEditor,
  enableMaps,
  onLocationSave,
  reconcileAutoLocation,
} = useMemoryLocation({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  items,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})

// --- Memory cover (cr-0072 + cr-0073, uc.memory.cover) → extracted into `useMemoryCover` (cr-0146). A chosen
// image + a 16:9 crop, sealed in the SAME node `wrappedMeta` blob as the date/location (read-modify-write). The
// source is a Memory photo (reusing its decrypted `thumbs` entry) or an uploaded standalone blob (decrypted on
// demand; the composable owns that object-URL's lifecycle incl. unmount cleanup). Props passed as getters.
const {
  coverEditorOpen,
  coverEditorMedia,
  storedCover,
  coverSrc,
  coverExistingSrc,
  coverMediaId,
  onSetCoverFromPhoto,
  openCoverEditor,
  closeCoverEditor,
  onCoverSave,
} = useMemoryCover({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  thumbs,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})

// --- delete / trash (uc.memory.delete-media, cr-0110) ---

/**
 * Trash the current lightbox photo: optimistic removal from the grid + lightbox snapshot, the server soft-delete,
 * and a transient undo snackbar (restore). The live poll would also drop it; the optimistic removal is instant.
 */
async function onDeleteFromLightbox(payload: { mediaId: string }) {
  if (!props.memoryKey) return
  removeFromGrid(payload.mediaId)
  showUndo(payload.mediaId)
  try {
    await deleteMedia(props.scopeId, payload.mediaId)
  } catch {
    clearUndo() // the trash failed → re-list to restore the true state
    void load()
  }
}

/** Drop a media from the open grid + lightbox snapshot (optimistic), revoking its thumbnail object URL. */
function removeFromGrid(mediaId: string) {
  pendingDelete.add(mediaId) // cr-0111: tombstone so a racing live read can't re-add it before the delete commits
  const url = thumbs.value.get(mediaId)
  if (url) URL.revokeObjectURL(url)
  thumbs.value.delete(mediaId)
  donatedThumbs.delete(mediaId)
  thumbs.value = new Map(thumbs.value)
  items.value = items.value.filter((it) => it.mediaId !== mediaId)
  lightboxItems.value = lightboxItems.value.filter((it) => it.mediaId !== mediaId)
}

function showUndo(mediaId: string) {
  if (undoTimer) clearTimeout(undoTimer)
  undo.value = { mediaId }
  undoTimer = setTimeout(() => { undo.value = null; undoTimer = null }, 6000)
}
function clearUndo() {
  if (undoTimer) clearTimeout(undoTimer)
  undoTimer = null
  undo.value = null
}

/** Undo the just-trashed media: restore it server-side, then re-list so it re-appears in the grid. */
async function onUndoDelete() {
  const u = undo.value
  clearUndo()
  if (!u) return
  pendingDelete.delete(u.mediaId) // cr-0111: a genuine restore lifts the tombstone so the media can re-list
  try {
    await restoreMedia(props.scopeId, u.mediaId)
  } finally {
    void load()
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

// The Beiträge tab (the media organizer, MemoryBuckets.vue) is ALWAYS available — it's where you create sub-buckets
// and file your own uploads (cr-0113). Its own state/loading live in that component; here we only host it + wire the
// lightbox/upload/reload it emits.
const showBeitraege = computed(() => true)

/** Open the lightbox on a bucket-scoped list the organizer hands up (so prev/next stays within the picked bucket). */
function openBucketLightbox(payload: { items: MediaItem[]; index: number }) {
  lightboxItems.value = payload.items.slice()
  lightboxIndex.value = payload.index
}
/** The organizer changed buckets/media (create/rename/delete/move) — re-list so each item's ownerKey/bucketId refresh. */
function onBucketChanged() { void load() }
/** Upload from the organizer — reuses the Memory upload; the caller's primary materializes on complete (moving into a
 *  specific sub-bucket after upload is a follow-up; for now uploads land in the primary, then move them in). */
function onBucketUpload() { triggerUpload() }

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
    {
      label: props.node.name,
      // cr-0111: reconcile each file's optimistic tile the moment IT finishes — a targeted live poll lists just
      // the newly-attached media so its tile flips to the real thumbnail without waiting for the whole batch.
      onFileDone: () => { if (props.node.id === targetNodeId) void pollMedia() },
      onComplete: () => { if (props.node.id === targetNodeId) void load() }, // final settle (date/location self-heal)
    },
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
  for (const id of [...pendingDelete]) if (!listed.some((m) => m.mediaId === id)) pendingDelete.delete(id) // cr-0111: confirmed gone
  const visible = listed.filter((m) => !pendingDelete.has(m.mediaId)) // cr-0111: keep optimistically-trashed media out until the delete commits
  for (const id of removedThumbIds(thumbs.value.keys(), visible)) {
    const url = thumbs.value.get(id)
    if (url) URL.revokeObjectURL(url)
    thumbs.value.delete(id)
    donatedThumbs.delete(id)
  }
  const known = listedMediaIds.value
  for (const m of visible) {
    if (known.has(m.mediaId) || thumbs.value.has(m.mediaId) || !hasDerivatives(m)) continue
    const donorUrl = donorPreviewFor(m.mediaId) // the composable marks the preview donated + returns its url
    if (!donorUrl) continue
    thumbs.value.set(m.mediaId, donorUrl)
    donatedThumbs.add(m.mediaId)
  }
  thumbs.value = new Map(thumbs.value)
  items.value = visible
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

/** Fetch + decrypt the thumbnail tier for each derivable item lacking a real thumbnail (parallel, operator-blind).
 *  Used by both the full [load] and the live delta [pollMedia] — the poll passes only the delta, so it decrypts
 *  just the new/changed tiles, never the whole grid. */
async function fetchThumbs(list: MediaItem[], key: Uint8Array, nodeId: string) {
  await Promise.all(
    list.map(async (m) => {
      if (!hasDerivatives(m)) return // E1: non-image/video → icon tile, no fetch
      if (thumbs.value.has(m.mediaId) && !donatedThumbs.has(m.mediaId)) return // real thumbnail already in place
      try {
        const bytes = await openGrid(props.scopeId, key, m)
        if (props.node.id !== nodeId) return
        setThumb(m.mediaId, URL.createObjectURL(new Blob([bytes as BlobPart])))
      } catch {
        // E5: a single failed tile stays a placeholder (or its donated preview); the rest of the grid is unaffected
      }
    }),
  )
}

// `reset` (a Memory/chapter switch) clears the grid up front; an incremental reload (post-upload batch) merges
// the fresh list into place so existing tiles don't blink empty — jarring next to the optimistic tiles (cr-0074).
async function load({ reset = false }: { reset?: boolean } = {}) {
  const key = props.memoryKey
  const nodeId = props.node.id
  if (!key) {
    revokeThumbs()
    items.value = []
    mediaRevision.value = null // E4: no K_mem (locked / not openable) → gallery unavailable, no live poll
    deleteRevision.value = 0
    status.value = 'error'
    return
  }
  if (reset) {
    revokeThumbs()
    items.value = []
    mediaRevision.value = null
    deleteRevision.value = 0
  }
  status.value = items.value.length ? 'ready' : 'loading'
  try {
    const page = await listMediaPage(props.scopeId, nodeId) // full list — no ETag/cursor, so never a 304
    if (props.node.id !== nodeId || page.notModified) return // navigated away while listing / (unreachable) 304
    mergeItems(page.items)
    mediaRevision.value = page.mediaRevision // cache the ETag + delete gate for the live poll
    deleteRevision.value = page.deleteRevision
    status.value = 'ready'
    await fetchThumbs(page.items, key, nodeId)
    if (props.node.id === nodeId) {
      await reconcileAuto() // settle point: open-time self-heal + post-upload-batch (date, then location)
      await reconcileAutoLocation() // reads the date-reconciled wrappedMeta (patched in place) → RMW-safe
    }
  } catch {
    if (props.node.id === nodeId) status.value = 'error'
  }
}

/** One live poll (uc.memory.live-updates, cr-0107): a conditional GET keyed on the cached `mediaRevision`. A 304
 *  is a no-op; a change upserts the keyset delta (new tiles auto-insert), reconciles removals via the id-set, and
 *  decrypts just the delta's tiles. Errors are swallowed — the last-rendered grid stays and the next poll re-syncs
 *  in one round-trip (E6). Node-race-guarded so a mid-poll navigation can't write into the wrong gallery. */
async function pollMedia() {
  const key = props.memoryKey
  const nodeId = props.node.id
  if (!key || mediaRevision.value === null) return // not an open, loaded gallery
  try {
    const page = await listMediaPage(props.scopeId, nodeId, {
      ifNoneMatch: String(mediaRevision.value),
      since: newestCursor(items.value),
      sinceDelete: deleteRevision.value,
    })
    if (props.node.id !== nodeId || page.notModified) return // navigated away / nothing changed (304)
    upsertItems(page.items, page.mediaIds)
    mediaRevision.value = page.mediaRevision
    deleteRevision.value = page.deleteRevision
    await fetchThumbs(page.items, key, nodeId) // decrypt only the delta's new/changed tiles
    if (props.node.id === nodeId) {
      await reconcileAuto() // others' new media surfacing tightens the cr-0067 auto-date self-heal (ADR 0033)
      await reconcileAutoLocation()
    }
  } catch {
    // offline/transient: keep the current grid; the revision compare makes the next successful poll a single round-trip
  }
}

/** Merge a live delta into `items` (cr-0107): upsert each delta media by `mediaId` — a new media auto-inserts, an
 *  in-place meta edit patches the existing tile (keeping object identity, so an open lightbox snapshot stays
 *  consistent) — and, **only when the id-set is present** (a delete advanced `delete_revision`), drop any cached
 *  media absent from it, revoking its thumbnail. Reassigns `items` so the justified grid re-sorts. */
function upsertItems(delta: MediaItem[], mediaIds: string[] | null) {
  const byId = new Map(items.value.map((it) => [it.mediaId, it] as const))
  for (const m of delta) {
    if (pendingDelete.has(m.mediaId)) continue // cr-0111: don't re-add an optimistically-trashed media (a stale pre-commit read)
    const existing = byId.get(m.mediaId)
    if (existing) {
      existing.wrappedMeta = m.wrappedMeta // a meta edit re-sealed the tier; patch in place (shared with the lightbox snapshot)
      existing.updatedAt = m.updatedAt
    } else {
      byId.set(m.mediaId, m)
    }
  }
  if (mediaIds) {
    const keep = new Set(mediaIds)
    for (const id of [...pendingDelete]) if (!keep.has(id)) pendingDelete.delete(id) // cr-0111: server confirmed it's gone → lift the tombstone
    for (const id of [...byId.keys()]) {
      if (keep.has(id)) continue
      const url = thumbs.value.get(id)
      if (url) URL.revokeObjectURL(url)
      thumbs.value.delete(id)
      donatedThumbs.delete(id)
      byId.delete(id)
    }
    thumbs.value = new Map(thumbs.value) // reassign so the template drops removed tiles
  }
  items.value = [...byId.values()]
}

// The Trash tab loads lazily on open — no extra list on every Memory open.
watch(activeTab, (tab) => { if (tab === 'trash') void loadTrash() })

watch(
  () => [props.node.id, props.memoryKey] as const,
  () => {
    lightboxIndex.value = null
    activeTab.value = 'gallery' // reset to the gallery when a different Memory/chapter opens
    resetTrash() // cr-0110/0111: drop the previous Memory's trash listing/thumbs/selection
    pendingDelete.clear()
    clearUndo()
    stopMediaRevisionPolling() // tear down the previous Memory's live poll before switching
    // a different Memory/chapter → clear the grid; a post-upload reload merges (below). Once the full list has
    // loaded, start the live poll — but only for an open, unlocked gallery (E4: no K_mem ⇒ no polling).
    void load({ reset: true }).then(() => {
      if (props.memoryKey) startMediaRevisionPolling(() => void pollMedia())
    })
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  stopMediaRevisionPolling() // stop the live poll on close/navigate away
  revokeThumbs()
  clearUndo()
  // trash thumbs are owned + revoked by useMediaTrash; the optimistic preview urls by useOptimisticTiles
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
          @click="openCoverEditor"
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
        <div class="memory-meta-row">
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
            :class="{ 'is-empty': !locationResolved }"
            :disabled="!memoryKey"
            :aria-label="locationResolved ? t('memory.place.edit') : t('memory.place.set')"
            :title="locationResolved ? t('memory.place.edit') : t('memory.place.set')"
            @click="openLocationEditor"
          >
            <span class="pi pi-map-marker" />
            <span>{{ locationLabel || t('memory.place.set') }}</span>
          </button>
        </div>
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
        <!-- The uploader (drop-box) link is its own action + own dialog (no role switcher) — capability-gated. -->
        <Button
          v-if="canUploadShare"
          v-tooltip.bottom="t('share.uploadRequest')"
          type="button"
          :aria-label="t('share.uploadRequest')"
          icon="pi pi-user-plus"
          severity="secondary"
          text
          :disabled="!memoryKey"
          @click="$emit('shareUpload')"
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
        <Tab v-if="showBeitraege" value="beitraege">{{ t('memory.buckets.title') }}</Tab>
        <Tab value="trash">{{ t('memory.trash.title') }}</Tab>
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
                <MediaTile
                  v-else
                  :data-media-id="item.media.mediaId"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  :src="thumbs.get(item.media.mediaId)"
                  :has-derivatives="hasDerivatives(item.media)"
                  :open-label="t('media.openItem', { index: item.index + 1 })"
                  @open="openLightboxAt(item.index)"
                />
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

        <!-- Beiträge tab (uc.memory.buckets, cr-0113): the media ORGANIZER — a hierarchy picker (your primary +
             sub-buckets), create/rename/delete sub-buckets, move media in, upload into a bucket. Ported from the
             legacy MemoryAssetBuckets UX. Always present; the primary is virtual until a sub-bucket/upload materializes it. -->
        <TabPanel v-if="showBeitraege" value="beitraege">
          <MemoryBuckets
            :scope-id="scopeId"
            :node-id="node.id"
            :memory-key="memoryKey"
            :media="items"
            :thumbs="thumbs"
            @open-lightbox="openBucketLightbox"
            @changed="onBucketChanged"
            @upload-bucket="onBucketUpload"
          />
        </TabPanel>

        <!-- Trash tab (uc.memory.delete-media, cr-0110/0111): the Memory's soft-deleted media in the SAME justified
             MediaGrid, with a per-tile checkbox (batch select) + a 3-dot menu (restore / purge). Lazy on open. -->
        <TabPanel value="trash">
          <div v-if="trashStatus === 'loading'" class="media-grid-placeholder">
            <span class="pi pi-spin pi-spinner" />
            <span>{{ t('media.loading') }}</span>
          </div>
          <p v-else-if="trashStatus === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>
          <p v-else-if="trashedItems.length === 0" class="veyq-empty">{{ t('memory.trash.empty') }}</p>
          <template v-else>
            <div class="memory-trash-toolbar">
              <span class="memory-trash-hint">{{ t('memory.trash.hint') }}</span>
              <div v-if="selectedTrash.size" class="memory-trash-batch">
                <span class="memory-trash-batch-count">{{ t('memory.trash.selected', { count: selectedTrash.size }) }}</span>
                <button type="button" class="veyq-text-button" @click="restoreSelected">
                  <span class="pi pi-replay" /><span>{{ t('memory.trash.restore') }}</span>
                </button>
                <button type="button" class="veyq-text-button memory-trash-purge-action" @click="purgeSelected">
                  <span class="pi pi-trash" /><span>{{ t('memory.trash.purge') }}</span>
                </button>
                <button type="button" class="veyq-text-button" @click="clearTrashSelection">
                  <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
                </button>
              </div>
            </div>
            <MediaGrid :items="trashLayoutItems" :aria-label="t('memory.trash.title')">
              <template #tile="{ tile, item }">
                <MediaTile
                  class="memory-trash-tile"
                  :style="{
                    transform: `translate(${tile.left}px, ${tile.top}px)`,
                    width: `${tile.renderedWidth}px`,
                    height: `${tile.renderedHeight}px`,
                  }"
                  :src="trashThumbs.get(item.media.mediaId)"
                  :has-derivatives="hasDerivatives(item.media)"
                  :open-label="t('media.openItem', { index: item.index + 1 })"
                  :selected="selectedTrash.has(item.media.mediaId)"
                  @open="toggleTrashSelect(item.media.mediaId)"
                >
                  <template #overlay>
                    <span class="media-tile-check" :class="{ 'is-on': selectedTrash.has(item.media.mediaId) }" aria-hidden="true">
                      <span class="pi pi-check" />
                    </span>
                    <button
                      type="button"
                      class="media-tile-menu"
                      :aria-label="t('common.more')"
                      @click.stop="openTrashMenu($event, item.media.mediaId)"
                    >
                      <span class="pi pi-ellipsis-v" />
                    </button>
                  </template>
                </MediaTile>
              </template>
            </MediaGrid>
            <Menu ref="trashMenu" :model="trashMenuModel" popup />
          </template>
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
      :suspend-keys="coverEditorOpen"
      @close="lightboxIndex = null"
      @updated="onCapturedAtUpdated"
      @set-cover="onSetCoverFromPhoto"
      @delete="onDeleteFromLightbox"
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
      :media-source="coverEditorMedia"
      :has-media="items.length > 0"
      @save="onCoverSave"
      @close="closeCoverEditor"
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

    <!-- Confirm the irreversible purge (cr-0111): permanently deletes the trashed media NOW (bypasses the 30-day retention). -->
    <Dialog
      :visible="purgeConfirm !== null"
      :header="t('memory.trash.purgeConfirmTitle')"
      modal
      class="memory-trash-purge-dialog"
      @update:visible="purgeConfirm = null"
    >
      <p>{{ t('memory.trash.purgeConfirmBody', { count: purgeConfirm?.ids.length ?? 0 }, purgeConfirm?.ids.length ?? 0) }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="purgeConfirm = null" />
        <Button type="button" :label="t('memory.trash.purge')" icon="pi pi-trash" severity="danger" @click="confirmPurge" />
      </div>
    </Dialog>

    <!-- Undo snackbar after a lightbox delete (cr-0110): a transient restore, above the lightbox so it's visible mid-culling. -->
    <div v-if="undo" class="memory-undo-snackbar" role="status">
      <span>{{ t('memory.trash.movedToTrash') }}</span>
      <button type="button" class="veyq-text-button memory-undo-action" @click="onUndoDelete">{{ t('common.undo') }}</button>
    </div>
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
/* Date + location under the title, side by side (cr-0067/0069). A light metadata row rather than bordered pills:
   each is a borderless button (accent icon + label) that opens its editor, with a subtle hover so it still reads
   as clickable. The "set / pending / no-location" placeholder states render muted via `.is-empty`. */
.memory-meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.2rem 0.4rem;
  margin-top: 0.4rem;
}
.memory-date-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.25rem 0.45rem;
  border: none;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-text);
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.memory-date-chip:hover:not(:disabled) {
  background: var(--app-hover);
}
.memory-date-chip:disabled {
  opacity: 0.6;
  cursor: default;
}
.memory-date-chip.is-empty {
  color: var(--app-muted);
  font-weight: 500;
}
.memory-date-chip .pi {
  font-size: 0.95rem;
  color: var(--app-accent);
}
.memory-date-chip.is-empty .pi {
  color: var(--app-muted);
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
/* Beiträge tab (cr-0113): the uploader picker — a row of chips (label + count), the active one accented. */
.beitraege-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.beitraege-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.3rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.beitraege-chip:hover {
  background: var(--app-control-bg);
}
.beitraege-chip.is-active {
  border-color: color-mix(in srgb, var(--app-accent) 55%, var(--app-line));
  background: color-mix(in srgb, var(--app-accent) 12%, transparent);
}
.beitraege-chip-count {
  padding: 0 0.35rem;
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
/* Gallery tiles are the shared MediaTile (cr-0125) — its wrapper/fit/icon styles live in the component + global
   memory.css. The optimistic upload tile's own styles live in the shared OptimisticTile component (cr-0074). */

/* --- Trash tab (cr-0110/0111): the same gallery grid, dimmed tiles + select-check + 3-dot menu --- */
.memory-trash-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}
.memory-trash-hint {
  color: var(--app-muted);
  font-size: 0.85rem;
}
.memory-trash-batch {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.4rem;
}
.memory-trash-batch-count {
  margin-right: 0.2rem;
  color: var(--app-muted);
  font-size: 0.85rem;
  font-weight: 700;
}
.memory-trash-batch .veyq-text-button {
  min-height: 2rem;
  padding: 0 0.6rem;
  gap: 0.3rem;
  font-size: 0.85rem;
}
.memory-trash-purge-action {
  color: var(--app-danger);
  border-color: color-mix(in srgb, var(--app-danger) 40%, var(--app-line));
}
/* A trashed tile IS the shared MediaTile (cr-0125), just with the trashed media dimmed (the check/menu/outline are
   the standard `.media-tile*`). `:deep` reaches the image inside the child component. */
.memory-trash-tile :deep(.media-tile-img) {
  opacity: 0.72; /* trashed media reads as dimmed */
  transition: opacity 0.15s ease;
}
.memory-trash-tile.is-selected :deep(.media-tile-img) {
  opacity: 0.92;
}
/* The undo snackbar sits above the lightbox (z-index 1000) so it's visible mid-culling. */
.memory-undo-snackbar {
  position: fixed;
  left: 50%;
  bottom: 1.5rem;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.6rem 0.6rem 0.6rem 1.1rem;
  border-radius: 999px;
  background: var(--app-backdrop-strong);
  color: #fff;
  box-shadow: 0 12px 32px rgb(0 0 0 / 28%);
  z-index: 1200;
}
/* The undo button on the DARK snackbar: transparent (not the light .veyq-text-button surface) + white text. */
.memory-undo-action {
  min-height: auto;
  padding: 0.2rem 0.5rem;
  border: none;
  background: transparent;
  color: #fff;
  font-weight: 700;
  text-decoration: underline;
}
.memory-undo-action:hover:not(:disabled) {
  background: rgb(255 255 255 / 14%);
}
</style>
