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
import { PROVIDER_LABEL, type Playlist } from '../../features/memory/playlist/playlist'
import type { Track } from '../../features/memory/track/track'
import { trackStats, formatDistance, formatDuration, formatElevation } from '../../features/memory/track/trackStats'
import type { NodeType } from '../../features/memory/createTree/memoryTree'
import JournalBody from './JournalBody.vue'
import MemoryMap from './MemoryMap.vue'

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

const tab = ref<'gallery' | 'map' | 'journal' | 'info' | 'contributions' | 'chapters' | 'playlists'>('gallery')
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

const lightboxInfo = ref(false)
const lightboxImg = ref<HTMLImageElement | null>(null)
function toggleFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen()
  else void lightboxImg.value?.requestFullscreen?.()
}
const { tiles, galleryEl, lightbox, lightboxTile, downloading, buckets, dateRange, tileById, observe, openLightbox, closeLightbox, downloadThisOriginal } = useGallery({
  loadPreview: (tile) => props.loadPreview(tile),
  loadOriginal: (tile) => props.loadOriginal(tile),
  downloadFileName: props.downloadFileName,
  onLightboxClose: () => { lightboxInfo.value = false },
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
function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

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

const PLAYLIST_LABEL = PROVIDER_LABEL
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

async function openTab(which: 'gallery' | 'map' | 'journal' | 'info' | 'contributions' | 'chapters' | 'playlists') {
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
    <img v-if="heroUrl" class="detail-hero" :src="heroUrl" alt="" />
    <div class="detail-head">
      <button v-if="showBack" class="crumb" @click="emit('back')">{{ t('common.back') }}</button>
      <nav v-if="crumbs.length > 1" class="crumbs detail-crumbs">
        <template v-for="(c, i) in crumbs" :key="c.id">
          <span v-if="i > 0" class="crumb-sep">/</span>
          <button class="crumb" :class="{ current: i === crumbs.length - 1 }" @click="navigate(c.id)">{{ c.name }}</button>
        </template>
      </nav>
      <h1 class="detail-title">{{ currentName }}</h1>
      <span class="content-count">{{ t('detail.mediaCount', { n: tiles.length }) }}</span>
      <slot name="head-actions" :node-id="currentNodeId" :tab="tab" />
    </div>

    <div class="detail-tabs">
      <button class="detail-tab" :class="{ active: tab === 'gallery' }" @click="openTab('gallery')">{{ t('detail.gallery') }}</button>
      <button v-if="childNodes.length" class="detail-tab" :class="{ active: tab === 'chapters' }" @click="openTab('chapters')">{{ t('detail.chapters') }}</button>
      <button class="detail-tab" :class="{ active: tab === 'map' }" @click="openTab('map')">{{ t('detail.map') }}</button>
      <button class="detail-tab" :class="{ active: tab === 'journal' }" @click="openTab('journal')">{{ t('detail.journal') }}</button>
      <button v-if="loadBuckets" class="detail-tab" :class="{ active: tab === 'contributions' }" @click="openTab('contributions')">{{ t('detail.contributions') }}</button>
      <button v-if="loadPlaylists" class="detail-tab" :class="{ active: tab === 'playlists' }" @click="openTab('playlists')">{{ t('detail.playlists') }}</button>
      <button class="detail-tab" :class="{ active: tab === 'info' }" @click="openTab('info')">{{ t('detail.info') }}</button>
    </div>

    <section v-if="tab === 'playlists'" class="memory-playlists">
      <button v-if="addPlaylist" class="btn btn-primary" type="button" @click="addPlaylist">+ {{ t('playlist.add') }}</button>
      <ul v-if="playlists.length" class="playlist-list">
        <li v-for="p in playlists" :key="p.id" class="playlist-row">
          <span class="playlist-provider">{{ PLAYLIST_LABEL[p.provider] }}</span>
          <a class="playlist-title" :href="p.url" target="_blank" rel="noopener noreferrer">{{ p.title || p.url }}</a>
          <button v-if="featurePlaylist" class="btn btn-ghost" type="button" :title="t('playlist.featured')" @click="toggleFeatured(p)">{{ p.featured ? '★' : '☆' }}</button>
          <button v-if="removePlaylist" class="btn btn-ghost" type="button" style="color: var(--app-danger)" @click="removePlaylist(p)">{{ t('common.delete') }}</button>
        </li>
      </ul>
      <div v-else class="home-empty">{{ t('playlist.empty') }}</div>
    </section>

    <section v-if="tab === 'contributions'" class="memory-contributions">
      <ul class="bucket-list">
        <li v-for="b in contribBuckets" :key="b.id" class="bucket-row" :class="{ sub: !b.primary }">
          <button class="bucket-open" type="button" @click="openBucket(b)">
            <span class="bucket-name">{{ b.primary ? '👤' : '📁' }} {{ bucketLabel?.(b) || (b.primary ? t('contrib.member') : '—') }}</span>
            <span class="bucket-count">{{ bucketCount(b) }}</span>
          </button>
          <span v-if="isOwnBucket(b)" class="bucket-actions">
            <button v-if="!b.primary" class="btn btn-ghost" type="button" @click="renameOwnBucket(b)">{{ t('common.rename') }}</button>
            <button v-if="!b.primary" class="btn btn-ghost" type="button" style="color: var(--app-danger)" @click="deleteOwnBucket(b)">{{ t('common.delete') }}</button>
            <button v-if="b.primary && createBucket" class="btn btn-ghost" type="button" @click="newSubBucket">+ {{ t('contrib.newBucket') }}</button>
          </span>
        </li>
      </ul>
      <div v-if="!contribBuckets.length" class="home-empty">{{ t('contrib.empty') }}</div>
    </section>

    <section v-if="tab === 'info'" class="memory-info">
      <div class="info-row"><span>{{ t('detail.gallery') }}</span><strong>{{ tiles.length }}</strong></div>
      <div v-if="dateRange" class="info-row"><span>{{ t('info.dateRange') }}</span><strong>{{ dateRange }}</strong></div>
      <div class="info-row"><span>{{ t('detail.map') }}</span><strong>{{ tracks.length }}</strong></div>
      <div class="info-row"><span>{{ t('info.chapters') }}</span><strong>{{ chapterChildCount() }}</strong></div>
    </section>

    <!-- child chapters/folders/memories, browsable from their own tab (Legacy parity) -->
    <section v-if="tab === 'chapters' && childNodes.length" class="memory-children">
      <div class="card-grid">
        <button v-for="n in childNodes" :key="n.id" class="memory-card child-card" @click="navigate(n.id, n.name)">
          <img v-if="childCovers[n.id]" class="memory-cover" :src="childCovers[n.id]" alt="" />
          <div v-else class="memory-cover placeholder">{{ n.type === 'FOLDER' ? '📁' : n.type === 'CHAPTER' ? '🗂' : '🖼' }}</div>
          <div class="memory-label"><div class="memory-name">{{ n.name }}</div></div>
        </button>
      </div>
    </section>

    <section v-if="tab === 'map'" class="memory-map-wrap">
      <slot name="map-toolbar" :node-id="currentNodeId" />
      <MemoryMap v-if="geoPoints().length || visibleTracks.length" :points="geoPoints()" :tracks="visibleTracks" @select="openMapPin" />
      <div v-else class="home-empty">{{ t('map.empty') }}</div>
      <ul v-if="tracks.length" class="track-list">
        <li v-for="tr in tracks" :key="tr.id" class="track-row">
          <span class="track-meta">
            <label class="track-vis"><input type="checkbox" :checked="!hiddenTracks.has(tr.id)" @change="toggleTrack(tr.id)" /></label>
            <span class="track-name">🛤 {{ tr.name }}</span>
            <span class="track-stat">{{ formatDistance(trackStats(tr.points).distanceM) }}</span>
            <span v-if="trackStats(tr.points).durationS" class="track-stat">{{ formatDuration(trackStats(tr.points).durationS!) }}</span>
            <span v-if="formatElevation(trackStats(tr.points))" class="track-stat">{{ formatElevation(trackStats(tr.points)) }}</span>
          </span>
          <slot name="track-actions" :track="tr" />
        </li>
      </ul>
    </section>

    <section v-if="tab === 'journal'" class="journal">
      <slot v-if="journalEditing" name="journal-edit" :node-id="currentNodeId" :markdown="journalMarkdown" />
      <template v-else>
        <slot name="journal-bar" :node-id="currentNodeId" :markdown="journalMarkdown" />
        <JournalBody
          v-if="journalMarkdown.trim()"
          :markdown="journalMarkdown"
          :tile-by-id="tileById"
          :tracks-by-id="tracksById()"
          :chapter-by-id="chapterById"
          @open-media="openLightbox"
          @open-chapter="(id) => navigate(id)"
        />
        <div v-else class="home-empty">{{ t('journal.empty') }}</div>
      </template>
    </section>

    <div v-if="tab === 'gallery' && (dateRange || hasHighlights() || selectedBucket)" class="detail-gallery-bar">
      <span v-if="dateRange" class="detail-daterange">{{ dateRange }}</span>
      <button v-if="selectedBucket" class="btn btn-ghost gallery-filter active" type="button" @click="clearBucket">
        {{ bucketLabel?.(selectedBucket) || t('contrib.member') }} ✕
      </button>
      <select v-if="selectedBucket && cameraOptions.length" v-model="cameraFilter" class="bucket-camera-filter" @change="applyTileFilter">
        <option :value="null">{{ t('contrib.allCameras') }}</option>
        <option v-for="cam in cameraOptions" :key="cam" :value="cam">{{ cam }}</option>
      </select>
      <button v-if="hasHighlights()" class="btn btn-ghost gallery-filter" :class="{ active: showHighlights }" type="button" @click="toggleHighlightFilter">
        ★ {{ t('detail.highlights') }}
      </button>
    </div>

    <div v-show="tab === 'gallery'" ref="galleryEl" class="gallery-buckets">
      <section v-for="b in buckets" :key="b.key" class="gallery-bucket">
        <h2 class="bucket-date">{{ b.label }}</h2>
        <div class="gallery" :style="{ height: b.layout.height + 'px' }">
          <button
            v-for="tile in b.layout.tiles"
            :key="tile.id"
            class="gallery-tile"
            :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.renderedWidth + 'px', height: tile.renderedHeight + 'px' }"
            @click="openLightbox(tileById[tile.id])"
          >
            <img :src="tileById[tile.id].url" alt="" />
            <span v-if="tileById[tile.id].highlighted" class="tile-star">★</span>
          </button>
        </div>
      </section>
    </div>

    <template v-if="tab === 'gallery'">
      <div v-if="loading" class="home-empty">{{ t('detail.loadingGallery') }}</div>
      <div v-else-if="!tiles.length && !childNodes.length" class="home-empty">{{ t('detail.noMedia') }}</div>
    </template>

    <div v-if="lightbox" class="lightbox" @click="closeLightbox">
      <button v-if="tiles.length > 1" class="lightbox-nav lightbox-prev" type="button" @click.stop="lightboxStep(-1)">‹</button>
      <img ref="lightboxImg" :src="lightbox" alt="" @click.stop="toggleFullscreen" />
      <button v-if="tiles.length > 1" class="lightbox-nav lightbox-next" type="button" @click.stop="lightboxStep(1)">›</button>
      <div v-if="lightboxInfo && lightboxTile" class="lightbox-info" @click.stop>
        <div><span>{{ t('info.takenAt') }}</span><strong>{{ new Date(lightboxTile.takenAt).toLocaleString() }}</strong></div>
        <div><span>{{ t('info.type') }}</span><strong>{{ lightboxTile.mimeType }}</strong></div>
        <div v-if="lightboxTile.lat != null"><span>{{ t('info.location') }}</span><strong>{{ lightboxTile.lat!.toFixed(5) }}, {{ lightboxTile.lng!.toFixed(5) }}</strong></div>
        <div v-if="lightboxTile.cameraModel"><span>{{ t('info.camera') }}</span><strong>{{ [lightboxTile.cameraMake, lightboxTile.cameraModel].filter(Boolean).join(' ') }}</strong></div>
        <div v-if="lightboxTile.lens"><span>{{ t('info.lens') }}</span><strong>{{ lightboxTile.lens }}</strong></div>
        <div v-if="lightboxTile.width && lightboxTile.height"><span>{{ t('info.dimensions') }}</span><strong>{{ lightboxTile.width }}×{{ lightboxTile.height }}</strong></div>
        <div v-if="lightboxTile.fileSize"><span>{{ t('info.fileSize') }}</span><strong>{{ formatBytes(lightboxTile.fileSize) }}</strong></div>
        <div v-for="(val, key) in (lightboxTile.exif ?? {})" :key="key"><span>{{ key }}</span><strong>{{ val }}</strong></div>
      </div>
      <div class="lightbox-actions" @click.stop>
        <slot name="lightbox-actions" :tile="lightboxTile" />
        <select
          v-if="moveMedia && lightboxTile && lightboxTile.createdBy === ownerKey"
          class="bucket-move" :value="lightboxTile.bucketId ?? `primary:${ownerKey}`"
          @change="moveToBucket(lightboxTile, ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="b in contribBuckets.filter(isOwnBucket)" :key="b.id" :value="b.id">{{ b.primary ? t('contrib.moveToPrimary') : (bucketLabel?.(b) || '—') }}</option>
        </select>
        <button class="btn btn-ghost" type="button" @click="toggleFullscreen">{{ t('detail.fullscreen') }}</button>
        <button class="btn btn-ghost" type="button" @click="lightboxInfo = !lightboxInfo">{{ t('info.info') }}</button>
        <button class="btn btn-ghost" type="button" :disabled="downloading" @click="downloadThisOriginal">{{ t('detail.download') }}</button>
        <button class="btn btn-ghost" type="button" @click="closeLightbox">{{ t('common.close') }}</button>
      </div>
    </div>
  </div>
</template>
