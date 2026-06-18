<script setup lang="ts">
// Member (internal) memory view — a thin wrapper around the shared, tab-based <MemoryContent> (Legacy UI, same
// as the guest share viewer). It supplies scope-authed loaders keyed by nodeId and the member-only actions
// (upload, delete, set-cover, edit/live journal, GPX import, download, share) via named slots.
import { defineAsyncComponent, onMounted, ref } from 'vue'
import { session } from '../session'
const LiveJournalEditor = defineAsyncComponent(() => import('./LiveJournalEditor.vue'))
import { t } from '../../i18n/i18n'
import { loadGalleryTiles, loadOriginal, loadPreview, type GalleryTile } from '../../features/gallery/gallery'
import { fileToNewMedia } from '../../features/uploads/clientMedia'
import { deleteMedia, sealMediaMeta, updateMedia, uploadMedia } from '../../features/uploads/media/media'
import { setCover } from '../../features/memory/curate/curate'
import { listBuckets, createBucket, renameBucket, deleteBucket, bucketName, type Bucket } from '../../features/memory/buckets/buckets'
import { listGroupMembers } from '../../features/spaces/groups/groupSpaces'
import { listPlaylists, createPlaylist, deletePlaylist, setPlaylistFeatured } from '../../features/memory/playlist/playlist'
import { getJournal } from '../../features/memory/journal/journal'
import { saveJournalMarkdown } from '../../features/memory/journalLive/journalPersistence'
import { parseJournalEmbeds } from '../../features/memory/journal/journalEmbed'
import { createTrack, deleteTrack, getTracks, updateTrack, type Track } from '../../features/memory/track/track'
import { parseGpx } from '../../features/memory/track/gpx'
import { loadCover, loadLevel, loadMemoryById, type ActiveSpace, type MemoryNode } from '../../features/home/home'
import DownloadModal from './DownloadModal.vue'
import ShareModal from './ShareModal.vue'
import MemoryContent from './MemoryContent.vue'

const props = defineProps<{ active: ActiveSpace; memory: MemoryNode; name: string }>()
const emit = defineEmits<{ back: [] }>()

const content = ref<InstanceType<typeof MemoryContent> | null>(null)
const currentNodeId = ref(props.memory.id)
const error = ref('')
const uploading = ref(false)
const downloadOpen = ref(false)
const shareOpen = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const gpxInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)
const mediaDelArm = ref(false)
// Journal editing (member only): a plain textarea draft, or the heavy live CodeMirror editor.
const journalEditing = ref(false)
const journalLive = ref(false)
const journalDraft = ref('')
const journalSaving = ref(false)

const s = () => session.unlocked!

// ---- loaders (scope-authed), keyed by node ----
const loadTiles = (nodeId: string) => loadGalleryTiles(session.apiBase, s(), props.active, nodeId)
const loadJournalMarkdown = async (nodeId: string) => (await getJournal(session.apiBase, s(), props.active, nodeId)).markdown
const loadTracks = (nodeId: string) => getTracks(session.apiBase, s(), props.active, nodeId)
const loadChildNodes = async (nodeId: string) =>
  (await loadLevel(session.apiBase, s(), props.active, nodeId)).map((e) => ({ id: e.node.id, type: e.node.type, name: e.name }))
const loadChildCover = async (nodeId: string): Promise<string | null> => {
  const entry = await loadMemoryById(session.apiBase, s(), props.active, nodeId)
  return entry ? loadCover(session.apiBase, s(), props.active, entry.node) : null
}
async function resolveChapterNames(markdown: string): Promise<Record<string, { name: string }>> {
  const ids = parseJournalEmbeds(markdown).filter((e) => e.kind === 'chapter').map((e) => e.ids[0])
  const out: Record<string, { name: string }> = {}
  for (const id of new Set(ids)) {
    const entry = await loadMemoryById(session.apiBase, s(), props.active, id)
    if (entry) out[id] = { name: entry.name }
  }
  return out
}

function onNodeChanged(nodeId: string) {
  currentNodeId.value = nodeId
  journalEditing.value = false
  journalLive.value = false
  mediaDelArm.value = false
}

async function onFilesPicked(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (!files.length) return
  uploading.value = true
  try {
    for (const file of files) await uploadMedia(session.apiBase, s(), props.active.scope.scopeId, props.active.contentKey, currentNodeId.value, await fileToNewMedia(file))
    await content.value?.reloadTiles()
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function deleteThisMedia(tile: GalleryTile | null) {
  if (!tile) return
  try {
    await deleteMedia(session.apiBase, s(), props.active.scope.scopeId, currentNodeId.value, tile.id)
    content.value?.closeLightbox()
    mediaDelArm.value = false
    await content.value?.reloadTiles()
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function setAsCover(tile: GalleryTile | null) {
  if (!tile) return
  await setCover(session.apiBase, s(), props.active, currentNodeId.value, tile.id)
  content.value?.closeLightbox()
}
async function toggleFavorite(tile: GalleryTile | null) {
  if (!tile) return
  const next = !tile.highlighted
  try {
    await updateMedia(session.apiBase, s(), props.active.scope.scopeId, currentNodeId.value, tile.id, { highlighted: next })
    content.value?.setTileHighlighted(tile.id, next)
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function onDateChange(tile: GalleryTile | null, value: string) {
  if (!tile || !value) return
  try {
    const sealedMeta = sealMediaMeta(props.active.contentKey, { takenAt: new Date(value).toISOString(), lat: tile.lat, lng: tile.lng })
    await updateMedia(session.apiBase, s(), props.active.scope.scopeId, currentNodeId.value, tile.id, { sealedMeta })
    await content.value?.reloadTiles()
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function onGpxPicked(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  importing.value = true
  try {
    await createTrack(session.apiBase, s(), props.active, currentNodeId.value, parseGpx(await file.text()))
    await content.value?.reloadTracks()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    importing.value = false
    if (gpxInput.value) gpxInput.value.value = ''
  }
}
async function removeTrack(id: string) {
  await deleteTrack(session.apiBase, s(), props.active, currentNodeId.value, id)
  await content.value?.reloadTracks()
}
async function renameTrack(track: Track) {
  const name = window.prompt(t('track.renamePrompt'), track.name)?.trim()
  if (!name || name === track.name) return
  try {
    await updateTrack(session.apiBase, s(), props.active, currentNodeId.value, track.id, { name, points: track.points })
    await content.value?.reloadTracks()
  } catch (e) {
    error.value = (e as Error).message
  }
}

function startEditJournal(markdown: string) {
  journalDraft.value = markdown
  journalEditing.value = true
}
async function saveJournal() {
  journalSaving.value = true
  try {
    await saveJournalMarkdown(session.apiBase, s(), props.active.scope.scopeId, currentNodeId.value, props.active.contentKey, journalDraft.value)
    journalEditing.value = false
    await content.value?.reloadJournal()
  } finally {
    journalSaving.value = false
  }
}
async function onLiveClosed() {
  journalLive.value = false
  await content.value?.reloadJournal()
}

// ---- contribution buckets (ADR 0023) ----
const ownerKey = `user:${session.unlocked!.user.id}`
const loadBuckets = (nodeId: string) => listBuckets(session.apiBase, s(), props.active, nodeId)
// Group member directory (userId → display name). Display names are account-level + server-visible (like the
// member-admin list), so resolving OTHER members' synthesized primary buckets client-side stays operator-blind
// re: content — the server still never learns the (sealed) bucket names. Mirrors what Legacy got server-side.
const memberNames = ref<Record<string, string>>({})
onMounted(async () => {
  const e = props.active.entry
  if ((e.kind === 'GROUP' || e.kind === 'TENANT') && e.tenantId) {
    try {
      const members = await listGroupMembers(session.apiBase, s(), e.tenantId, props.active.scope.scopeId)
      memberNames.value = Object.fromEntries(members.map((m) => [m.userId, m.displayName]))
    } catch { /* directory optional — fall back to the generic label */ }
  }
})
// Label a bucket: own primary → my name; persisted (sub / guest primary) → its sealed name; other member
// primary → resolved from the directory; else generic (handled by MemoryContent's fallback).
function bucketLabel(b: Bucket): string {
  if (b.primary && b.ownerKey === ownerKey) return session.unlocked!.user.displayName
  const sealed = bucketName(props.active.contentKey, b)
  if (sealed) return sealed
  if (b.ownerKey.startsWith('user:')) return memberNames.value[b.ownerKey.slice(5)] ?? ''
  return ''
}
const createBucketFn = (nodeId: string, name: string) => createBucket(session.apiBase, s(), props.active, nodeId, name).then(() => undefined)
const renameBucketFn = (nodeId: string, bucketId: string, name: string) => renameBucket(session.apiBase, s(), props.active, nodeId, bucketId, name).then(() => undefined)
const deleteBucketFn = (nodeId: string, bucketId: string) => deleteBucket(session.apiBase, s(), props.active, nodeId, bucketId)
const moveMedia = async (mediaId: string, bucketId: string) => {
  await updateMedia(session.apiBase, s(), props.active.scope.scopeId, currentNodeId.value, mediaId, { bucketId })
}

// ---- playlists (ADR 0010) ----
const loadPlaylists = (nodeId: string) => listPlaylists(session.apiBase, s(), props.active, nodeId)
const addPlaylist = (nodeId: string, url: string, title: string) => createPlaylist(session.apiBase, s(), props.active, nodeId, url, title, '', false)
const removePlaylist = (nodeId: string, playlistId: string) => deletePlaylist(session.apiBase, s(), props.active, nodeId, playlistId)
const featurePlaylist = (nodeId: string, playlistId: string, featured: boolean) => setPlaylistFeatured(session.apiBase, s(), props.active, nodeId, playlistId, featured)
</script>

<template>
  <div>
    <DownloadModal v-if="downloadOpen" :active="active" :memory="memory" :name="name" @close="downloadOpen = false" />
    <ShareModal v-if="shareOpen" :active="active" :memory="memory" :name="name" @close="shareOpen = false" />

    <MemoryContent
      ref="content"
      :root-node-id="memory.id"
      :root-name="name"
      show-back
      :journal-editing="journalEditing || journalLive"
      :load-tiles="loadTiles"
      :load-journal-markdown="loadJournalMarkdown"
      :load-tracks="loadTracks"
      :load-child-nodes="loadChildNodes"
      :resolve-chapter-names="resolveChapterNames"
      :load-child-cover="loadChildCover"
      :load-buckets="loadBuckets"
      :bucket-label="bucketLabel"
      :owner-key="ownerKey"
      :create-bucket="createBucketFn"
      :rename-bucket="renameBucketFn"
      :delete-bucket="deleteBucketFn"
      :move-media="moveMedia"
      :load-playlists="loadPlaylists"
      :add-playlist="addPlaylist"
      :remove-playlist="removePlaylist"
      :feature-playlist="featurePlaylist"
      :load-preview="(tile) => loadPreview(session.apiBase, s(), active, tile)"
      :load-original="(tile) => loadOriginal(session.apiBase, s(), active, tile)"
      :download-file-name="(tile, ext) => `${name}-${tile.id.slice(0, 8)}.${ext}`"
      @back="emit('back')"
      @node-changed="onNodeChanged"
      @error="error = $event"
    >
      <template #head-actions="{ tab }">
        <button class="btn btn-ghost detail-share" type="button" @click="shareOpen = true">{{ t('share.share') }}</button>
        <template v-if="tab === 'gallery'">
          <button class="btn btn-ghost detail-download" type="button" @click="downloadOpen = true">{{ t('detail.download') }}</button>
          <button class="btn btn-primary detail-upload" type="button" :disabled="uploading" @click="fileInput?.click()">
            {{ uploading ? t('detail.uploading') : t('detail.upload') }}
          </button>
        </template>
        <input ref="fileInput" type="file" accept="image/*,video/*" multiple hidden @change="onFilesPicked" />
      </template>

      <template #map-toolbar>
        <div class="map-bar">
          <button class="btn btn-ghost" type="button" :disabled="importing" @click="gpxInput?.click()">
            {{ importing ? t('track.importing') : t('track.import') }}
          </button>
          <input ref="gpxInput" type="file" accept=".gpx,application/gpx+xml,application/xml" hidden @change="onGpxPicked" />
        </div>
      </template>
      <template #track-actions="{ track }">
        <button class="btn btn-ghost" type="button" @click="renameTrack(track)">{{ t('common.rename') }}</button>
        <button class="btn btn-ghost track-del" type="button" @click="removeTrack(track.id)">{{ t('track.delete') }}</button>
      </template>

      <template #journal-bar="{ markdown }">
        <div class="journal-bar">
          <button class="btn btn-ghost" type="button" @click="startEditJournal(markdown)">{{ t('journal.edit') }}</button>
          <button class="btn btn-ghost" type="button" @click="journalLive = true">{{ t('journal.live') }}</button>
        </div>
      </template>
      <template #journal-edit="{ nodeId, markdown }">
        <LiveJournalEditor v-if="journalLive" :active="active" :node-id="nodeId" :initial-markdown="markdown" @close="onLiveClosed" />
        <template v-else>
          <textarea v-model="journalDraft" class="journal-editor" :placeholder="t('journal.placeholder')"></textarea>
          <div class="journal-actions">
            <button class="btn btn-ghost" type="button" :disabled="journalSaving" @click="journalEditing = false">{{ t('common.cancel') }}</button>
            <button class="btn btn-primary" type="button" :disabled="journalSaving" @click="saveJournal">{{ journalSaving ? t('journal.saving') : t('journal.save') }}</button>
          </div>
        </template>
      </template>

      <template #lightbox-actions="{ tile }">
        <button class="btn btn-ghost lightbox-fav" type="button" :title="t('media.favorite')" @click="toggleFavorite(tile)">{{ tile?.highlighted ? '★' : '☆' }}</button>
        <label class="lightbox-date" :title="t('media.editDate')">
          <input
            type="datetime-local"
            :value="tile?.takenAt?.slice(0, 16)"
            @change="onDateChange(tile, ($event.target as HTMLInputElement).value)"
          />
        </label>
        <button class="btn btn-primary" type="button" @click="setAsCover(tile)">{{ t('cover.setAsCover') }}</button>
        <button v-if="!mediaDelArm" class="btn btn-ghost" type="button" style="color: var(--app-danger)" @click="mediaDelArm = true">{{ t('common.delete') }}</button>
        <button v-else class="btn btn-danger" type="button" @click="deleteThisMedia(tile)">{{ t('delete.confirm') }}</button>
      </template>
    </MemoryContent>

    <div v-if="error" class="home-empty">{{ error }}</div>
  </div>
</template>
