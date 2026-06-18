<script setup lang="ts">
// The shared, tab-based memory presentation (Legacy UI) used by BOTH the internal member view and the public
// guest share viewer, so they look identical. It owns the Galerie/Map/Journal tabs, in-memory chapter
// navigation (breadcrumb + child-node cards), the justified gallery + lightbox (via useGallery), and the
// embed-aware journal render (JournalBody). Everything data-related comes in as loaders keyed by nodeId, so the
// member passes scope-authed loaders and the guest passes share-capability loaders. Member-only actions
// (upload, delete, set-cover, edit/live journal, GPX import, download, share) are supplied via named slots; the
// guest simply omits them.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../../i18n/i18n'
import { useGallery } from '../../features/gallery/useGallery'
import type { GalleryTile } from '../../features/gallery/gallery'
import type { Bucket } from '../../features/memory/buckets/buckets'
import type { Playlist } from '../../features/memory/playlist/playlist'
import type { Track } from '../../features/memory/track/track'
import type { NodeType } from '../../features/memory/createTree/memoryTree'
import MemoryChaptersSection from './MemoryChaptersSection.vue'
import MemoryDetailHeader from './MemoryDetailHeader.vue'
import MemoryDetailTabs, { type MemoryDetailTab } from './MemoryDetailTabs.vue'
import MemoryContributionsSection from './MemoryContributionsSection.vue'
import MemoryGalleryGrid from './MemoryGalleryGrid.vue'
import MemoryGalleryStatus from './MemoryGalleryStatus.vue'
import MemoryGalleryToolbar from './MemoryGalleryToolbar.vue'
import MemoryInfoSection from './MemoryInfoSection.vue'
import MemoryJournalSection from './MemoryJournalSection.vue'
import MemoryLightbox from './MemoryLightbox.vue'
import MemoryMapSection from './MemoryMapSection.vue'
import MemoryPlaylistsSection from './MemoryPlaylistsSection.vue'

type ChildNode = { id: string; type: NodeType; name: string }

const props = defineProps<{
  rootNodeId: string
  rootName: string
  showBack?: boolean
  journalEditing?: boolean
  loadTiles: (nodeId: string) => Promise<GalleryTile[]>
  loadJournalMarkdown: (nodeId: string) => Promise<string>
  // Optional live (CRDT) journal read: when provided, the journal tab subscribes instead of static-loading, so
  // a viewer sees owner edits live. Returns a teardown. The guest share viewer wires this; the member doesn't
  // (it has live editing separately).
  liveJournal?: (nodeId: string, onUpdate: (markdown: string) => void) => () => void
  loadTracks: (nodeId: string) => Promise<Track[]>
  loadChildNodes: (nodeId: string) => Promise<ChildNode[]>
  resolveChapterNames: (markdown: string) => Promise<Record<string, { name: string }>>
  loadPreview: (tile: GalleryTile) => Promise<string>
  loadOriginal: (tile: GalleryTile) => Promise<string>
  downloadFileName?: (tile: GalleryTile, ext: string) => string
  /** Optional: a child node's cover thumbnail (object URL) for the chapter cards; null/absent → emoji card. */
  loadChildCover?: (nodeId: string) => Promise<string | null>
  // Contribution buckets (ADR 0023). When loadBuckets is provided the Contributions tab shows; the rest enable
  // managing the caller's OWN buckets. The member wrapper supplies these (crypto/identity stay there).
  loadBuckets?: (nodeId: string) => Promise<Bucket[]>
  bucketLabel?: (bucket: Bucket) => string
  ownerKey?: string
  createBucket?: (nodeId: string, name: string) => Promise<void>
  renameBucket?: (nodeId: string, bucketId: string, name: string) => Promise<void>
  deleteBucket?: (nodeId: string, bucketId: string) => Promise<void>
  moveMedia?: (mediaId: string, bucketId: string) => Promise<void>
  // Playlists (ADR 0010). loadPlaylists enables the tab; the rest enable member editing (guest = read-only).
  loadPlaylists?: (nodeId: string) => Promise<Playlist[]>
  addPlaylist?: (nodeId: string, url: string, title: string) => Promise<void>
  removePlaylist?: (nodeId: string, playlistId: string) => Promise<void>
  featurePlaylist?: (nodeId: string, playlistId: string, featured: boolean) => Promise<void>
}>()
const emit = defineEmits<{ back: []; nodeChanged: [nodeId: string, name: string]; error: [message: string] }>()

const tab = ref<MemoryDetailTab>('gallery')
const currentNodeId = ref(props.rootNodeId)
const currentName = ref(props.rootName)
const crumbs = ref<{ id: string; name: string }[]>([{ id: props.rootNodeId, name: props.rootName }])
const loading = ref(true)
const childNodes = ref<ChildNode[]>([])
const childCovers = ref<Record<string, string>>({})
// The full loaded tile set; `tiles` (useGallery, drives the justified buckets + lightbox) holds the currently
// VISIBLE subset — all, or only highlights when the filter is on.
const allTiles = ref<GalleryTile[]>([])
const showHighlights = ref(false)
const journalMarkdown = ref('')
const journalLoaded = ref(false)
const chapterById = ref<Record<string, { name: string }>>({})
const tracks = ref<Track[]>([])
const tracksLoaded = ref(false)
// Contribution buckets + the current filter (a selected bucket scopes the gallery + camera filter).
const contribBuckets = ref<Bucket[]>([])
const bucketsLoaded = ref(false)
const selectedBucketId = ref<string | null>(null)
const cameraFilter = ref<string | null>(null)
const playlists = ref<Playlist[]>([])
const playlistsLoaded = ref(false)

const { tiles, galleryEl, lightbox, lightboxTile, downloading, buckets, dateRange, tileById, observe, openLightbox, closeLightbox, downloadThisOriginal } = useGallery({
  loadPreview: (tile) => props.loadPreview(tile),
  loadOriginal: (tile) => props.loadOriginal(tile),
  downloadFileName: props.downloadFileName,
  onError: (e) => emit('error', e.message),
})

// Per-track map visibility (toggled in the track list); hidden tracks aren't drawn.
const hiddenTracks = ref<Set<string>>(new Set())
const visibleTracks = computed(() => tracks.value.filter((tr) => !hiddenTracks.value.has(tr.id)))
function toggleTrack(id: string) {
  const next = new Set(hiddenTracks.value)
  next.has(id) ? next.delete(id) : next.add(id)
  hiddenTracks.value = next
}
const geoPoints = () => tiles.value.filter((tl) => tl.lat != null && tl.lng != null).map((tl) => ({ id: tl.id, lat: tl.lat!, lng: tl.lng! }))
const tracksById = () => Object.fromEntries(tracks.value.map((tr) => [tr.id, tr]))
const playlistById = () => Object.fromEntries(playlists.value.map((p) => [p.id, p]))
function openMapPin(id: string) { const tile = tileById.value[id]; if (tile) openLightbox(tile) }

// Cover hero = the current node's first photo (by takenAt); lightbox prev/next over the takenAt-ordered tiles.
const heroUrl = computed(() => tiles.value[0]?.url ?? null)
const lightboxIndex = computed(() => (lightboxTile.value ? tiles.value.findIndex((tl) => tl.id === lightboxTile.value!.id) : -1))
function lightboxStep(d: number) {
  const i = lightboxIndex.value
  if (i < 0 || tiles.value.length < 2) return
  openLightbox(tiles.value[(i + d + tiles.value.length) % tiles.value.length])
}
const chapterChildCount = () => childNodes.value.filter((n) => n.type === 'CHAPTER').length

const hasHighlights = () => allTiles.value.some((tl) => tl.highlighted)
// A tile belongs to a bucket: a sub-bucket matches bucketId; a primary matches the contributor's un-bucketed media.
function tileInBucket(tl: GalleryTile, b: Bucket): boolean {
  return b.primary ? tl.createdBy === b.ownerKey && (tl.bucketId == null) : tl.bucketId === b.id
}
const selectedBucket = computed(() => contribBuckets.value.find((b) => b.id === selectedBucketId.value) ?? null)
function cameraOf(tl: GalleryTile): string { return [tl.cameraMake, tl.cameraModel].filter(Boolean).join(' ').trim() }
// Distinct cameras among the currently-selected bucket's tiles (for the in-bucket camera filter).
const cameraOptions = computed(() => {
  const b = selectedBucket.value
  if (!b) return [] as string[]
  return [...new Set(allTiles.value.filter((tl) => tileInBucket(tl, b)).map(cameraOf).filter(Boolean))].sort()
})
function applyTileFilter() {
  let v = allTiles.value
  if (showHighlights.value) v = v.filter((tl) => tl.highlighted)
  const b = selectedBucket.value
  if (b) v = v.filter((tl) => tileInBucket(tl, b))
  if (cameraFilter.value) v = v.filter((tl) => cameraOf(tl) === cameraFilter.value)
  tiles.value = v
}
function toggleHighlightFilter() { showHighlights.value = !showHighlights.value; applyTileFilter() }
function setCameraFilter(value: string | null) { cameraFilter.value = value; applyTileFilter() }
function bucketCount(b: Bucket): number { return allTiles.value.filter((tl) => tileInBucket(tl, b)).length }
function isOwnBucket(b: Bucket): boolean { return !!props.ownerKey && b.ownerKey === props.ownerKey }
async function loadContribBuckets() {
  if (!props.loadBuckets) return
  try { contribBuckets.value = await props.loadBuckets(currentNodeId.value); bucketsLoaded.value = true } catch (e) { emit('error', (e as Error).message) }
}
function openBucket(b: Bucket) { selectedBucketId.value = b.id; cameraFilter.value = null; tab.value = 'gallery'; applyTileFilter() }
function clearBucket() { selectedBucketId.value = null; cameraFilter.value = null; applyTileFilter() }
async function newSubBucket() {
  if (!props.createBucket) return
  const name = window.prompt(t('contrib.newBucketPrompt'))?.trim()
  if (!name) return
  try { await props.createBucket(currentNodeId.value, name); await loadContribBuckets() } catch (e) { emit('error', (e as Error).message) }
}
async function renameOwnBucket(b: Bucket) {
  if (!props.renameBucket) return
  const name = window.prompt(t('contrib.renamePrompt'), props.bucketLabel?.(b) ?? '')?.trim()
  if (!name) return
  try { await props.renameBucket(currentNodeId.value, b.id, name); await loadContribBuckets() } catch (e) { emit('error', (e as Error).message) }
}
async function deleteOwnBucket(b: Bucket) {
  if (!props.deleteBucket || !window.confirm(t('contrib.deleteConfirm'))) return
  try { await props.deleteBucket(currentNodeId.value, b.id); if (selectedBucketId.value === b.id) clearBucket(); await loadContribBuckets(); await reloadTiles() } catch (e) { emit('error', (e as Error).message) }
}
async function moveToBucket(tile: GalleryTile | null, bucketId: string) {
  if (!tile || !props.moveMedia) return
  try { await props.moveMedia(tile.id, bucketId); await loadContribBuckets(); await reloadTiles() } catch (e) { emit('error', (e as Error).message) }
}
async function moveLightboxTile(bucketId: string) { await moveToBucket(lightboxTile.value, bucketId) }

async function loadPlaylistsTab() {
  if (!props.loadPlaylists) return
  try { playlists.value = await props.loadPlaylists(currentNodeId.value); playlistsLoaded.value = true } catch (e) { emit('error', (e as Error).message) }
}
async function addPlaylist() {
  if (!props.addPlaylist) return
  const url = window.prompt(t('playlist.urlPrompt'))?.trim()
  if (!url) return
  const title = window.prompt(t('playlist.titlePrompt'))?.trim() ?? ''
  try { await props.addPlaylist(currentNodeId.value, url, title); await loadPlaylistsTab() } catch (e) { emit('error', (e as Error).message) }
}
async function removePlaylist(p: Playlist) {
  if (!props.removePlaylist || !window.confirm(t('playlist.deleteConfirm'))) return
  try { await props.removePlaylist(currentNodeId.value, p.id); await loadPlaylistsTab() } catch (e) { emit('error', (e as Error).message) }
}
async function toggleFeatured(p: Playlist) {
  if (!props.featurePlaylist) return
  try { await props.featurePlaylist(currentNodeId.value, p.id, !p.featured); await loadPlaylistsTab() } catch (e) { emit('error', (e as Error).message) }
}
function revokeAllTiles() { allTiles.value.forEach((tl) => URL.revokeObjectURL(tl.url)); allTiles.value = [] }

function revokeChildCovers() { for (const u of Object.values(childCovers.value)) URL.revokeObjectURL(u); childCovers.value = {} }
function loadChildCovers() {
  if (!props.loadChildCover) return
  for (const n of childNodes.value) {
    props.loadChildCover(n.id).then((url) => { if (url) childCovers.value[n.id] = url }).catch(() => {})
  }
}

/** Load the current node's gallery + children; journal/tracks load lazily when their tab opens. */
async function loadCurrent() {
  loading.value = true
  stopLiveJournal()
  journalLoaded.value = false
  tracksLoaded.value = false
  bucketsLoaded.value = false
  selectedBucketId.value = null
  cameraFilter.value = null
  contribBuckets.value = []
  playlistsLoaded.value = false
  playlists.value = []
  journalMarkdown.value = ''
  tracks.value = []
  revokeAllTiles()
  revokeChildCovers()
  tiles.value = []
  try {
    const [t1, kids] = await Promise.all([props.loadTiles(currentNodeId.value), props.loadChildNodes(currentNodeId.value)])
    allTiles.value = t1
    applyTileFilter()
    childNodes.value = kids
    loadChildCovers()
    observe()
    if (props.loadBuckets) await loadContribBuckets() // needed for the move-to-bucket control + counts
    if (props.loadPlaylists) await loadPlaylistsTab() // for the tab + ::playlist embeds
    if (tab.value === 'journal') await ensureJournal()
    if (tab.value === 'map' || tab.value === 'info') await ensureTracks()
  } catch (e) {
    emit('error', (e as Error).message)
  } finally {
    loading.value = false
  }
}

let liveTeardown: (() => void) | null = null
function stopLiveJournal() { liveTeardown?.(); liveTeardown = null }

async function ensureJournal() {
  if (journalLoaded.value) return
  journalLoaded.value = true
  if (props.liveJournal) {
    // Live read: subscribe (the subscriber reconstructs + streams updates). Chapter names resolve from the
    // subtree independently of the (initially empty) markdown.
    chapterById.value = await props.resolveChapterNames('')
    stopLiveJournal()
    liveTeardown = props.liveJournal(currentNodeId.value, (md) => { journalMarkdown.value = md })
  } else {
    journalMarkdown.value = await props.loadJournalMarkdown(currentNodeId.value)
    chapterById.value = await props.resolveChapterNames(journalMarkdown.value)
  }
}
async function ensureTracks() {
  if (tracksLoaded.value) return
  tracks.value = await props.loadTracks(currentNodeId.value)
  tracksLoaded.value = true
}

async function openTab(which: MemoryDetailTab) {
  tab.value = which
  try {
    if (which === 'journal') await ensureJournal()
    if (which === 'map' || which === 'info') await ensureTracks() // info shows the track count
    if (which === 'contributions' && !bucketsLoaded.value) await loadContribBuckets()
    if (which === 'playlists' && !playlistsLoaded.value) await loadPlaylistsTab()
  } catch (e) {
    emit('error', (e as Error).message)
  }
}

/** Navigate to a node within the subtree: a child card or a ::chapter embed. Updates the breadcrumb. */
async function navigate(nodeId: string, name?: string) {
  const at = crumbs.value.findIndex((c) => c.id === nodeId)
  const label = name ?? childNodes.value.find((c) => c.id === nodeId)?.name ?? chapterById.value[nodeId]?.name ?? ''
  crumbs.value = at >= 0 ? crumbs.value.slice(0, at + 1) : [...crumbs.value, { id: nodeId, name: label }]
  currentNodeId.value = nodeId
  currentName.value = crumbs.value[crumbs.value.length - 1].name
  tab.value = 'gallery'
  emit('nodeChanged', nodeId, currentName.value)
  await loadCurrent()
}

// Parent-facing refresh hooks (after upload/delete/journal-save/track-import) — re-fetch the current node's data.
async function reloadTiles() { revokeAllTiles(); allTiles.value = await props.loadTiles(currentNodeId.value); applyTileFilter() }
async function reloadJournal() { journalLoaded.value = false; await ensureJournal() }
async function reloadTracks() { tracksLoaded.value = false; await ensureTracks() }
/** Reflect a highlight toggle without re-downloading thumbnails (the parent persists it via updateMedia). */
function setTileHighlighted(id: string, value: boolean) {
  const tl = allTiles.value.find((x) => x.id === id)
  if (tl) tl.highlighted = value
  applyTileFilter()
}
defineExpose({ reloadTiles, reloadJournal, reloadTracks, closeLightbox, setTileHighlighted, currentNodeId })

onMounted(() => { emit('nodeChanged', currentNodeId.value, currentName.value); loadCurrent() })
onBeforeUnmount(() => { stopLiveJournal(); revokeChildCovers(); revokeAllTiles() })
</script>

<template>
  <div class="memory-detail">
    <MemoryDetailHeader
      :hero-url="heroUrl"
      :show-back="showBack"
      :crumbs="crumbs"
      :current-name="currentName"
      :media-count="tiles.length"
      @back="emit('back')"
      @navigate="navigate"
    >
      <slot name="head-actions" :node-id="currentNodeId" :tab="tab" />
    </MemoryDetailHeader>

    <MemoryDetailTabs
      :tab="tab"
      :has-children="!!childNodes.length"
      :has-buckets="!!loadBuckets"
      :has-playlists="!!loadPlaylists"
      @open="openTab"
    />

    <MemoryPlaylistsSection
      v-if="tab === 'playlists'"
      :playlists="playlists"
      :can-add="!!addPlaylist"
      :can-feature="!!featurePlaylist"
      :can-remove="!!removePlaylist"
      @add="addPlaylist"
      @feature="toggleFeatured"
      @remove="removePlaylist"
    />

    <MemoryContributionsSection
      v-if="tab === 'contributions'"
      :buckets="contribBuckets"
      :can-create-bucket="!!createBucket"
      :bucket-label="bucketLabel"
      :bucket-count="bucketCount"
      :is-own-bucket="isOwnBucket"
      @open-bucket="openBucket"
      @rename-bucket="renameOwnBucket"
      @delete-bucket="deleteOwnBucket"
      @create-bucket="newSubBucket"
    />

    <MemoryInfoSection
      v-if="tab === 'info'"
      :media-count="tiles.length"
      :date-range="dateRange"
      :track-count="tracks.length"
      :chapter-count="chapterChildCount()"
    />

    <MemoryChaptersSection
      v-if="tab === 'chapters' && childNodes.length"
      :nodes="childNodes"
      :covers="childCovers"
      @navigate="navigate"
    />

    <MemoryMapSection
      v-if="tab === 'map'"
      :points="geoPoints()"
      :tracks="tracks"
      :visible-tracks="visibleTracks"
      :hidden-track-ids="hiddenTracks"
      @select-pin="openMapPin"
      @toggle-track="toggleTrack"
    >
      <template #map-toolbar>
        <slot name="map-toolbar" :node-id="currentNodeId" />
      </template>
      <template #track-actions="{ track }">
        <slot name="track-actions" :track="track" />
      </template>
    </MemoryMapSection>

    <MemoryJournalSection
      v-if="tab === 'journal'"
      :node-id="currentNodeId"
      :markdown="journalMarkdown"
      :editing="journalEditing"
      :tile-by-id="tileById"
      :tracks-by-id="tracksById()"
      :chapter-by-id="chapterById"
      :playlist-by-id="playlistById()"
      @open-media="openLightbox"
      @open-chapter="(id) => navigate(id)"
    >
      <template #journal-edit="{ nodeId, markdown }">
        <slot name="journal-edit" :node-id="nodeId" :markdown="markdown" />
      </template>
      <template #journal-bar="{ nodeId, markdown }">
        <slot name="journal-bar" :node-id="nodeId" :markdown="markdown" />
      </template>
    </MemoryJournalSection>

    <MemoryGalleryToolbar
      v-if="tab === 'gallery' && (dateRange || hasHighlights() || selectedBucket)"
      :date-range="dateRange"
      :selected-bucket="selectedBucket"
      :bucket-label="bucketLabel"
      :camera-filter="cameraFilter"
      :camera-options="cameraOptions"
      :has-highlights="hasHighlights()"
      :show-highlights="showHighlights"
      @clear-bucket="clearBucket"
      @camera-filter-change="setCameraFilter"
      @toggle-highlights="toggleHighlightFilter"
    />

    <div v-show="tab === 'gallery'" ref="galleryEl" class="gallery-buckets">
      <MemoryGalleryGrid :buckets="buckets" :tile-by-id="tileById" @open-tile="openLightbox" />
    </div>

    <MemoryGalleryStatus
      v-if="tab === 'gallery'"
      :loading="loading"
      :has-tiles="!!tiles.length"
      :has-children="!!childNodes.length"
    />

    <MemoryLightbox
      v-if="lightbox"
      :image-url="lightbox"
      :tile="lightboxTile"
      :tile-count="tiles.length"
      :downloading="downloading"
      :can-move-tile="!!moveMedia && !!lightboxTile && lightboxTile.createdBy === ownerKey"
      :move-buckets="contribBuckets.filter(isOwnBucket)"
      :owner-key="ownerKey"
      :bucket-label="bucketLabel"
      @close="closeLightbox"
      @step="lightboxStep"
      @move-tile="moveLightboxTile"
      @download="downloadThisOriginal"
    >
      <template #actions="{ tile }">
        <slot name="lightbox-actions" :tile="tile" />
      </template>
    </MemoryLightbox>
  </div>
</template>
