<script setup lang="ts">
// A memory's journal. Two paths over the SAME operator-blind CRDT store (sealed snapshot + sealed update log):
//  - read/single-writer Markdown: getJournal (read projection) + saveJournalMarkdown (compaction).
//  - LIVE multi-cursor co-editing: MemoryJournalLive (Yjs over the encrypted relay + CodeMirror cursors).
// Both converge; the server never sees plaintext.
import { ref, watch } from 'vue'
import Button from 'primevue/button'
import ProgressSpinner from 'primevue/progressspinner'
import { getJournal } from '../../../features/memory/journal/journal'
import { parseJournalEmbeds } from '../../../features/memory/journal/journalEmbed'
import { saveJournalMarkdown } from '../../../features/memory/journalLive/journalPersistence'
import { loadGalleryTiles, type GalleryTile } from '../../../features/gallery/gallery'
import { getTracks, type Track } from '../../../features/memory/track/track'
import { listPlaylists, type Playlist } from '../../../features/memory/playlist/playlist'
import { listChildren, decodeNode } from '../../../features/memory/browse/browse'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'
import MemoryJournalLive from './MemoryJournalLive.vue'
import JournalBody from './JournalBody.vue'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
// The parent (TenantView) owns the slug chain, so chapter navigation from a ::chapter embed is delegated up
// with the target chapter's slug; the journal itself stays slug-unaware.
const emit = defineEmits<{ openChapter: [slug: string] }>()

const markdown = ref('')
const draft = ref('')
const editing = ref(false)
const live = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')

// Embed resolution maps: media/track/chapter/playlist ids → the decrypted entity the journal body links to.
// Loaded lazily by kind (only the kinds the body actually references), matching the iOS/Android read path.
const tileById = ref<Record<string, GalleryTile>>({})
const tracksById = ref<Record<string, Track>>({})
const chapterById = ref<Record<string, { name: string }>>({})
const playlistById = ref<Record<string, Playlist>>({})
// node id → slug of the resolved chapter, so openChapter can hand the parent a path segment to navigate to.
const chapterSlugById = ref<Record<string, string>>({})

async function loadEmbedData(md: string) {
  const kinds = new Set(parseJournalEmbeds(md).map((e) => e.kind))
  const s = session.unlocked
  if (!s) return
  if (kinds.has('media')) {
    const tiles = await loadGalleryTiles(session.apiBase, s, props.active, props.nodeId)
    tileById.value = Object.fromEntries(tiles.map((tile) => [tile.id, tile]))
  }
  if (kinds.has('track')) {
    const tracks = await getTracks(session.apiBase, s, props.active, props.nodeId)
    tracksById.value = Object.fromEntries(tracks.map((tr) => [tr.id, tr]))
  }
  if (kinds.has('playlist')) {
    const playlists = await listPlaylists(session.apiBase, s, props.active, props.nodeId)
    playlistById.value = Object.fromEntries(playlists.map((p) => [p.id, p]))
  }
  if (kinds.has('chapter')) {
    const children = await listChildren(session.apiBase, s, props.active.scope.scopeId, props.nodeId)
    const chapters = children.filter((n) => n.type === 'CHAPTER').map((n) => ({ node: n, decoded: decodeNode(props.active.contentKey, n) }))
    chapterById.value = Object.fromEntries(chapters.map(({ node, decoded }) => [node.id, { name: decoded.name }]))
    chapterSlugById.value = Object.fromEntries(chapters.map(({ node, decoded }) => [node.id, decoded.slug]))
  }
}

function openChapter(chapterNodeId: string) {
  const slug = chapterSlugById.value[chapterNodeId]
  if (slug) emit('openChapter', slug)
}

function onLiveClose(finalMarkdown: string) {
  // Show the just-edited text immediately — the read projection is compacted asynchronously on close, so a
  // bare reload would briefly race the compaction and show stale content. Then re-sync in the background.
  markdown.value = finalMarkdown
  live.value = false
  void load()
}

function resetEmbedData() {
  tileById.value = {}
  tracksById.value = {}
  chapterById.value = {}
  playlistById.value = {}
  chapterSlugById.value = {}
}

async function load() {
  loading.value = true
  error.value = ''
  resetEmbedData()
  try {
    markdown.value = (await getJournal(session.apiBase, session.unlocked!, props.active, props.nodeId)).markdown
    await loadEmbedData(markdown.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function startEdit() {
  draft.value = markdown.value
  editing.value = true
}

async function save() {
  saving.value = true
  error.value = ''
  try {
    await saveJournalMarkdown(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.nodeId, props.active.contentKey, draft.value)
    markdown.value = draft.value
    editing.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

watch(() => [props.active.scope.scopeId, props.nodeId], () => { editing.value = false; live.value = false; void load() }, { immediate: true })
</script>

<template>
  <div class="memory-journal">
    <p v-if="error" class="journal-error">{{ error }}</p>
    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /></div>

    <MemoryJournalLive
      v-else-if="live"
      :active="props.active"
      :node-id="props.nodeId"
      :initial-markdown="markdown"
      @close="onLiveClose"
    />

    <template v-else-if="editing">
      <textarea v-model="draft" class="journal-editor" :placeholder="t('journal.placeholder')" rows="16"></textarea>
      <div class="journal-actions">
        <Button type="button" :label="saving ? t('journal.saving') : t('journal.save')" icon="pi pi-check" :loading="saving" @click="save" />
        <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="saving" @click="editing = false" />
      </div>
    </template>

    <template v-else>
      <div class="journal-toolbar">
        <Button type="button" :label="t('journal.edit')" icon="pi pi-pencil" severity="secondary" outlined @click="startEdit" />
        <Button type="button" :label="t('journal.live')" icon="pi pi-users" severity="secondary" outlined @click="live = true" />
      </div>
      <JournalBody
        v-if="markdown"
        :markdown="markdown"
        :tile-by-id="tileById"
        :tracks-by-id="tracksById"
        :chapter-by-id="chapterById"
        :playlist-by-id="playlistById"
        @open-chapter="openChapter"
      />
      <p v-else class="empty-hint">{{ t('journal.empty') }}</p>
    </template>
  </div>
</template>

<style scoped>
.memory-journal { margin: 0.5rem 0 1rem; }
.journal-error { color: var(--p-red-400, #f87171); }
.journal-toolbar { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; }
.journal-actions { display: flex; gap: 0.5rem; margin-top: 0.5rem; }
.journal-editor { width: 100%; box-sizing: border-box; font-family: var(--font-mono, monospace); font-size: 0.95rem; padding: 0.75rem; border-radius: 8px; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); background: var(--p-content-background, transparent); color: inherit; resize: vertical; }
.empty-hint { opacity: 0.6; }
</style>
