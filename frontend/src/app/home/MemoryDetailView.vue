<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { session } from '../session'
import { i18n, t } from '../../i18n/i18n'
import { calculateJustifiedLayout } from '../../features/gallery/justifiedLayout'
import { formatDateRange, formatDayLabel, groupByDay } from '../../features/gallery/dateBuckets'
import { loadGalleryTiles, loadOriginal, type GalleryTile } from '../../features/gallery/gallery'
import { fileToNewMedia } from '../../features/uploads/clientMedia'
import { uploadMedia } from '../../features/uploads/media/media'
import { setCover } from '../../features/memory/curate/curate'
import { deleteMedia } from '../../features/uploads/media/media'
import { getJournal, updateJournal } from '../../features/memory/journal/journal'
import { renderMarkdown } from '../../features/memory/journal/markdown'
import { createTrack, deleteTrack, getTracks, type Track } from '../../features/memory/track/track'
import { parseGpx } from '../../features/memory/track/gpx'
import DownloadModal from './DownloadModal.vue'
import MemoryMap from './MemoryMap.vue'
import ShareModal from './ShareModal.vue'
import type { ActiveSpace, MemoryNode } from '../../features/home/home'

const props = defineProps<{ active: ActiveSpace; memory: MemoryNode; name: string }>()
const emit = defineEmits<{ back: [] }>()

const tiles = ref<GalleryTile[]>([])
const loading = ref(true)
const containerWidth = ref(0)
const galleryEl = ref<HTMLElement | null>(null)
const lightbox = ref<string | null>(null)
const lightboxTile = ref<GalleryTile | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const downloadOpen = ref(false)
const shareOpen = ref(false)
const tab = ref<'gallery' | 'journal' | 'map'>('gallery')
const journalMarkdown = ref('')
const journalDraft = ref('')
const journalEditing = ref(false)
const journalSaving = ref(false)
const journalLoaded = ref(false)
const tracks = ref<Track[]>([])
const tracksLoaded = ref(false)
const importing = ref(false)
const gpxInput = ref<HTMLInputElement | null>(null)
const error = ref('')
let ro: ResizeObserver | null = null

// Group the (takenAt-ordered) tiles into day buckets, each with its own justified layout — a dated timeline.
const buckets = computed(() =>
  groupByDay(tiles.value).map((b) => ({
    key: b.key,
    label: formatDayLabel(b.key, i18n.locale),
    layout: calculateJustifiedLayout({
      items: b.items.map((t) => ({ id: t.id, aspectRatio: t.aspectRatio })),
      containerWidth: containerWidth.value,
      targetHeight: 220,
      gap: 6,
    }),
  })),
)
const dateRange = computed(() => formatDateRange(tiles.value, i18n.locale))
const tileById = computed(() => Object.fromEntries(tiles.value.map((t) => [t.id, t])))
const journalHtml = computed(() => renderMarkdown(journalMarkdown.value))
const geoPoints = computed(() =>
  tiles.value.filter((t) => t.lat != null && t.lng != null).map((t) => ({ id: t.id, lat: t.lat!, lng: t.lng! })),
)
function openMapPin(id: string) {
  const tile = tileById.value[id]
  if (tile) openLightbox(tile)
}

async function openMap() {
  tab.value = 'map'
  if (tracksLoaded.value) return
  try {
    tracks.value = await getTracks(session.apiBase, session.unlocked!, props.active, props.memory.id)
  } finally {
    tracksLoaded.value = true
  }
}
async function onGpxPicked(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  importing.value = true
  try {
    const route = parseGpx(await file.text())
    await createTrack(session.apiBase, session.unlocked!, props.active, props.memory.id, route)
    tracks.value = await getTracks(session.apiBase, session.unlocked!, props.active, props.memory.id)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    importing.value = false
    if (gpxInput.value) gpxInput.value.value = ''
  }
}
async function removeTrack(id: string) {
  await deleteTrack(session.apiBase, session.unlocked!, props.active, props.memory.id, id)
  tracks.value = tracks.value.filter((t) => t.id !== id)
}

async function openJournal() {
  tab.value = 'journal'
  if (journalLoaded.value) return
  try {
    journalMarkdown.value = (await getJournal(session.apiBase, session.unlocked!, props.active, props.memory.id)).markdown
  } finally {
    journalLoaded.value = true
  }
}
function startEditJournal() {
  journalDraft.value = journalMarkdown.value
  journalEditing.value = true
}
async function saveJournal() {
  journalSaving.value = true
  try {
    journalMarkdown.value = (await updateJournal(session.apiBase, session.unlocked!, props.active, props.memory.id, journalDraft.value)).markdown
    journalEditing.value = false
  } finally {
    journalSaving.value = false
  }
}

onMounted(async () => {
  ro = new ResizeObserver((entries) => { containerWidth.value = entries[0].contentRect.width })
  if (galleryEl.value) ro.observe(galleryEl.value)
  try {
    tiles.value = await loadGalleryTiles(session.apiBase, session.unlocked!, props.active, props.memory.id)
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  ro?.disconnect()
  tiles.value.forEach((t) => URL.revokeObjectURL(t.url))
  if (lightbox.value) URL.revokeObjectURL(lightbox.value)
})

async function refresh() {
  tiles.value.forEach((t) => URL.revokeObjectURL(t.url))
  tiles.value = await loadGalleryTiles(session.apiBase, session.unlocked!, props.active, props.memory.id)
}

async function onFilesPicked(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (!files.length) return
  uploading.value = true
  try {
    for (const file of files) {
      const media = await fileToNewMedia(file)
      await uploadMedia(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.active.contentKey, props.memory.id, media)
    }
    await refresh()
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function openLightbox(tile: GalleryTile) {
  lightboxTile.value = tile
  lightbox.value = await loadOriginal(session.apiBase, session.unlocked!, props.active, tile)
}
function closeLightbox() {
  if (lightbox.value) URL.revokeObjectURL(lightbox.value)
  lightbox.value = null
  lightboxTile.value = null
  mediaDelArm.value = false
}
const mediaDelArm = ref(false)
async function deleteThisMedia() {
  if (!lightboxTile.value) return
  const id = lightboxTile.value.id
  try {
    await deleteMedia(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.memory.id, id)
    closeLightbox()
    tiles.value.forEach((t) => t.id === id && URL.revokeObjectURL(t.url))
    tiles.value = await loadGalleryTiles(session.apiBase, session.unlocked!, props.active, props.memory.id)
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function setAsCover() {
  if (!lightboxTile.value) return
  await setCover(session.apiBase, session.unlocked!, props.active, props.memory.id, lightboxTile.value.id)
  closeLightbox()
}
</script>

<template>
  <div class="memory-detail">
    <div class="detail-head">
      <button class="crumb" @click="emit('back')">{{ t('common.back') }}</button>
      <h1 class="detail-title">{{ name }}</h1>
      <span class="content-count">{{ t('detail.mediaCount', { n: tiles.length }) }}</span>
      <button class="btn btn-ghost detail-share" type="button" @click="shareOpen = true">{{ t('share.share') }}</button>
      <template v-if="tab === 'gallery'">
        <button class="btn btn-ghost detail-download" type="button" :disabled="!tiles.length" @click="downloadOpen = true">{{ t('detail.download') }}</button>
        <button class="btn btn-primary detail-upload" type="button" :disabled="uploading" @click="fileInput?.click()">
          {{ uploading ? t('detail.uploading') : t('detail.upload') }}
        </button>
      </template>
      <input ref="fileInput" type="file" accept="image/*,video/*" multiple hidden @change="onFilesPicked" />
    </div>

    <div class="detail-tabs">
      <button class="detail-tab" :class="{ active: tab === 'gallery' }" @click="tab = 'gallery'">{{ t('detail.gallery') }}</button>
      <button class="detail-tab" :class="{ active: tab === 'journal' }" @click="openJournal">{{ t('detail.journal') }}</button>
      <button class="detail-tab" :class="{ active: tab === 'map' }" @click="openMap">{{ t('detail.map') }}</button>
    </div>

    <DownloadModal v-if="downloadOpen" :active="active" :memory="memory" :name="name" @close="downloadOpen = false" />
    <ShareModal v-if="shareOpen" :active="active" :memory="memory" :name="name" @close="shareOpen = false" />

    <section v-if="tab === 'journal'" class="journal">
      <template v-if="journalEditing">
        <textarea v-model="journalDraft" class="journal-editor" :placeholder="t('journal.placeholder')"></textarea>
        <div class="journal-actions">
          <button class="btn btn-ghost" type="button" :disabled="journalSaving" @click="journalEditing = false">{{ t('common.cancel') }}</button>
          <button class="btn btn-primary" type="button" :disabled="journalSaving" @click="saveJournal">{{ journalSaving ? t('journal.saving') : t('journal.save') }}</button>
        </div>
      </template>
      <template v-else>
        <div class="journal-bar">
          <button class="btn btn-ghost" type="button" @click="startEditJournal">{{ t('journal.edit') }}</button>
        </div>
        <div v-if="journalMarkdown.trim()" class="journal-body" v-html="journalHtml"></div>
        <div v-else class="home-empty">{{ t('journal.empty') }}</div>
      </template>
    </section>

    <section v-if="tab === 'map'" class="memory-map-wrap">
      <div class="map-bar">
        <button class="btn btn-ghost" type="button" :disabled="importing" @click="gpxInput?.click()">
          {{ importing ? t('track.importing') : t('track.import') }}
        </button>
        <input ref="gpxInput" type="file" accept=".gpx,application/gpx+xml,application/xml" hidden @change="onGpxPicked" />
      </div>
      <div v-if="error" class="home-empty">{{ error }}</div>
      <MemoryMap v-if="geoPoints.length || tracks.length" :points="geoPoints" :tracks="tracks" @select="openMapPin" />
      <div v-else class="home-empty">{{ t('map.empty') }}</div>
      <ul v-if="tracks.length" class="track-list">
        <li v-for="tr in tracks" :key="tr.id" class="track-row">
          <span class="track-name">🛤 {{ tr.name }}</span>
          <button class="btn btn-ghost track-del" type="button" @click="removeTrack(tr.id)">{{ t('track.delete') }}</button>
        </li>
      </ul>
    </section>

    <div v-if="tab === 'gallery' && dateRange" class="detail-daterange">{{ dateRange }}</div>

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
          </button>
        </div>
      </section>
    </div>

    <template v-if="tab === 'gallery'">
      <div v-if="loading" class="home-empty">{{ t('detail.loadingGallery') }}</div>
      <div v-else-if="!tiles.length" class="home-empty">{{ t('detail.noMedia') }}</div>
    </template>

    <div v-if="lightbox" class="lightbox" @click="closeLightbox">
      <img :src="lightbox" alt="" @click.stop />
      <div class="lightbox-actions" @click.stop>
        <button class="btn btn-primary" type="button" @click="setAsCover">{{ t('cover.setAsCover') }}</button>
        <button v-if="!mediaDelArm" class="btn btn-ghost" type="button" style="color: var(--app-danger)" @click="mediaDelArm = true">{{ t('common.delete') }}</button>
        <button v-else class="btn btn-danger" type="button" @click="deleteThisMedia">{{ t('delete.confirm') }}</button>
        <button class="btn btn-ghost" type="button" @click="closeLightbox">{{ t('common.close') }}</button>
      </div>
    </div>
  </div>
</template>
