<script setup lang="ts">
// Picker for inserting/editing a journal embed token. Loads the candidate entities for the requested kind
// (media thumbnails, tracks, playlists, or sibling chapters — all decrypted client-side) and returns the
// chosen ids + optional label. media/track allow multi-select; chapter/playlist are single. Pure UI over the
// already-ported listing modules; the parent serializes the result with journalEmbedMarkdown.
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import ProgressSpinner from 'primevue/progressspinner'
import type { JournalEmbedKind } from '../../../features/memory/journal/journalEmbed'
import { loadGalleryTiles } from '../../../features/gallery/gallery'
import { getTracks } from '../../../features/memory/track/track'
import { listPlaylists, PROVIDER_LABEL } from '../../../features/memory/playlist/playlist'
import { listChildren, decodeNode } from '../../../features/memory/browse/browse'
import type { ActiveSpace } from '../../../features/home/home'
import { session } from '../../../app/session'
import { t } from '../../../i18n/i18n'

const props = defineProps<{
  kind: JournalEmbedKind
  active: ActiveSpace
  nodeId: string
  initial?: { ids: string[]; label?: string }
}>()
const emit = defineEmits<{ select: [{ ids: string[]; label?: string }]; remove: []; cancel: [] }>()

type Candidate = { id: string; label: string; thumb?: string }

const loading = ref(true)
const error = ref('')
const candidates = ref<Candidate[]>([])
const selected = ref<string[]>(props.initial?.ids ? [...props.initial.ids] : [])
const label = ref(props.initial?.label ?? '')
const isEdit = !!props.initial
const multi = props.kind === 'media' || props.kind === 'track'

const titleKey: Record<JournalEmbedKind, string> = {
  media: 'journal.embedKind.media',
  track: 'journal.embedKind.track',
  playlist: 'journal.embedKind.playlist',
  chapter: 'journal.embedKind.chapter',
}

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    candidates.value = await loadCandidates()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function loadCandidates(): Promise<Candidate[]> {
  const s = session.unlocked!
  if (props.kind === 'media') {
    const tiles = await loadGalleryTiles(session.apiBase, s, props.active, props.nodeId)
    return tiles.map((tile) => ({ id: tile.id, label: '', thumb: tile.url }))
  }
  if (props.kind === 'track') {
    const tracks = await getTracks(session.apiBase, s, props.active, props.nodeId)
    return tracks.map((tr) => ({ id: tr.id, label: tr.name || tr.id }))
  }
  if (props.kind === 'playlist') {
    const playlists = await listPlaylists(session.apiBase, s, props.active, props.nodeId)
    return playlists.map((p) => ({ id: p.id, label: p.title || PROVIDER_LABEL[p.provider] }))
  }
  // chapter — sibling CHAPTER nodes under this memory
  const children = await listChildren(session.apiBase, s, props.active.scope.scopeId, props.nodeId)
  return children
    .filter((n) => n.type === 'CHAPTER')
    .map((n) => ({ id: n.id, label: decodeNode(props.active.contentKey, n).name || n.id }))
}

function toggle(id: string) {
  if (multi) {
    const i = selected.value.indexOf(id)
    if (i >= 0) selected.value.splice(i, 1)
    else selected.value.push(id)
  } else {
    selected.value = [id]
  }
}

function confirm() {
  if (!selected.value.length) return
  emit('select', { ids: [...selected.value], label: label.value.trim() || undefined })
}
</script>

<template>
  <Dialog :visible="true" modal :header="t(titleKey[kind])" :style="{ width: kind === 'media' ? '46rem' : '32rem' }" @update:visible="(v) => { if (!v) emit('cancel') }">
    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /></div>
    <p v-else-if="error" class="picker-error">{{ error }}</p>
    <p v-else-if="!candidates.length" class="picker-empty">{{ t('journal.embedNone') }}</p>

    <div v-else-if="kind === 'media'" class="picker-grid">
      <button
        v-for="c in candidates"
        :key="c.id"
        type="button"
        class="picker-thumb"
        :class="{ selected: selected.includes(c.id) }"
        @click="toggle(c.id)"
      >
        <img :src="c.thumb" alt="" loading="lazy" />
        <span v-if="selected.includes(c.id)" class="picker-check pi pi-check-circle" />
      </button>
    </div>

    <ul v-else class="picker-list">
      <li v-for="c in candidates" :key="c.id">
        <button type="button" class="picker-row" :class="{ selected: selected.includes(c.id) }" @click="toggle(c.id)">
          <span :class="selected.includes(c.id) ? 'pi pi-check-circle' : 'pi pi-circle'" />
          <span class="picker-row-label">{{ c.label }}</span>
        </button>
      </li>
    </ul>

    <label class="picker-label">
      {{ t('journal.embedLabel') }}
      <InputText v-model="label" :placeholder="t('journal.embedLabelHint')" />
    </label>

    <template #footer>
      <Button v-if="isEdit" type="button" :label="t('common.delete')" icon="pi pi-trash" severity="danger" text @click="emit('remove')" />
      <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="emit('cancel')" />
      <Button type="button" :label="t('common.insert')" icon="pi pi-check" :disabled="!selected.length" @click="confirm" />
    </template>
  </Dialog>
</template>

<style scoped>
.picker-error { color: var(--p-red-400, #f87171); }
.picker-empty { opacity: 0.6; }
.picker-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); gap: 0.5rem; max-height: 50vh; overflow: auto; }
.picker-thumb { position: relative; padding: 0; border: 2px solid transparent; border-radius: 8px; overflow: hidden; cursor: pointer; aspect-ratio: 1; background: none; }
.picker-thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.picker-thumb.selected { border-color: var(--p-primary-color, #6366f1); }
.picker-check { position: absolute; top: 4px; right: 4px; color: var(--p-primary-color, #6366f1); background: #fff; border-radius: 50%; }
.picker-list { list-style: none; margin: 0; padding: 0; max-height: 50vh; overflow: auto; display: flex; flex-direction: column; gap: 0.25rem; }
.picker-row { display: flex; align-items: center; gap: 0.6rem; width: 100%; padding: 0.5rem 0.6rem; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.2)); border-radius: 8px; background: none; color: inherit; cursor: pointer; text-align: left; }
.picker-row.selected { border-color: var(--p-primary-color, #6366f1); }
.picker-row-label { flex: 1; }
.picker-label { display: flex; flex-direction: column; gap: 0.3rem; margin-top: 0.85rem; }
</style>
