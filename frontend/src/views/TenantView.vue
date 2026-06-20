<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { loadCover, loadLevel, moveDestinations, type ActiveSpace, type LevelEntry } from '../features/home/home'
import { createChild, deleteNode, isNodeNameConflict, moveNode, renameNode } from '../features/memory/curate/curate'
import type { MemoryNode } from '../features/memory/createTree/memoryTree'
import { buildHomePath } from '../features/home/homeRoute'
import { listFavoriteNodeIds, setMemoryFavorite } from '../features/memory/favorite/favorite'
import { downloadMemoryZip } from '../features/download/download'
import { session } from '../app/session'
import { t } from '../i18n/i18n'
import { decodeNode, type NodeType } from '../features/memory/createTree/memoryTree'
import MemoryGallery from '../features/memories/components/MemoryGallery.vue'
import MemoryJournal from '../features/memories/components/MemoryJournal.vue'
import MemoryParticipants from '../features/memories/components/MemoryParticipants.vue'
import MemoryPlaylists from '../features/memories/components/MemoryPlaylists.vue'
import MemoryMap from '../features/memories/components/MemoryMap.vue'
import MemoryShare from '../features/memories/components/MemoryShare.vue'

const props = defineProps<{
  active: ActiveSpace
  folders: LevelEntry[]
  memory: LevelEntry | null
}>()

const router = useRouter()
const entries = ref<LevelEntry[]>([])
const covers = ref<Record<string, string>>({})
const loading = ref(false)
const error = ref('')
const memoryTab = ref<'gallery' | 'journal' | 'map' | 'participants' | 'playlists' | 'share'>('gallery')
const createOpen = ref(false)
const createType = ref<NodeType>('MEMORY')
const createName = ref('')
const createError = ref('')
const creating = ref(false)
const createTitle = computed(() =>
  createType.value === 'FOLDER' ? t('home.createFolder') : createType.value === 'CHAPTER' ? t('detail.chapters') : t('home.createMemory'),
)
const isFavorite = ref(false)
const favBusy = ref(false)
const downloading = ref(false)
let coverUrls: string[] = []

async function loadFavorite() {
  if (!props.memory) return
  try {
    const ids = await listFavoriteNodeIds(session.apiBase, session.unlocked!, props.active.scope.scopeId)
    isFavorite.value = ids.includes(props.memory.node.id)
  } catch {
    isFavorite.value = false
  }
}

async function toggleFavorite() {
  if (!props.memory) return
  favBusy.value = true
  try {
    isFavorite.value = await setMemoryFavorite(session.apiBase, session.unlocked!, props.active.scope.scopeId, props.memory.node.id, !isFavorite.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    favBusy.value = false
  }
}

async function downloadMemory() {
  if (!props.memory) return
  downloading.value = true
  error.value = ''
  try {
    await downloadMemoryZip(session.apiBase, session.unlocked!, props.active, props.memory.node, props.memory.name, true)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    downloading.value = false
  }
}

// The slug segments of the current location (folder chain + optional trailing memory).
const currentSegments = computed(() => [
  ...props.folders.map((f) => f.slug),
  ...(props.memory ? [props.memory.slug] : []),
])
// The level we list children of: inside a memory → its chapters; in a folder → that folder; else root.
const parentId = computed(() => props.memory?.node.id ?? props.folders.at(-1)?.node.id)
const folderEntries = computed(() => entries.value.filter((e) => e.node.type === 'FOLDER'))
const memoryEntries = computed(() => entries.value.filter((e) => e.node.type !== 'FOLDER'))

function releaseCovers() {
  coverUrls.forEach((u) => URL.revokeObjectURL(u))
  coverUrls = []
  covers.value = {}
}

async function load() {
  loading.value = true
  error.value = ''
  releaseCovers()
  try {
    entries.value = await loadLevel(session.apiBase, session.unlocked!, props.active, parentId.value)
    // Decrypt covers lazily, in the background, so the grid renders immediately.
    for (const entry of entries.value) {
      if (entry.node.type === 'FOLDER') continue
      void loadCover(session.apiBase, session.unlocked!, props.active, entry.node).then((url) => {
        if (url) {
          coverUrls.push(url)
          covers.value = { ...covers.value, [entry.node.id]: url }
        }
      })
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function open(entry: LevelEntry) {
  void router.push(buildHomePath(props.active.entry, [...currentSegments.value, entry.slug]))
}

// A ::chapter embed in the journal resolves to a child chapter slug; navigate into it under the current memory.
function openChapterFromJournal(slug: string) {
  void router.push(buildHomePath(props.active.entry, [...currentSegments.value, slug]))
}

function openCrumb(index: number) {
  // index -1 = space root; otherwise the folder at that depth.
  const slugs = index < 0 ? [] : props.folders.slice(0, index + 1).map((f) => f.slug)
  void router.push(buildHomePath(props.active.entry, slugs))
}

function openCreate(type: NodeType) {
  createType.value = type
  createName.value = ''
  createError.value = ''
  createOpen.value = true
}

async function confirmCreate() {
  const name = createName.value.trim()
  if (!name) return
  creating.value = true
  createError.value = ''
  try {
    const created = await createChild(session.apiBase, session.unlocked!, props.active, createType.value, name, entries.value.length, parentId.value)
    createOpen.value = false
    if (createType.value === 'FOLDER') {
      await load()
    } else {
      // Jump straight into the new memory/chapter (decode its slug for the URL).
      const slug = decodeNode(props.active.contentKey, created).slug
      void router.push(buildHomePath(props.active.entry, [...currentSegments.value, slug]))
    }
  } catch (e) {
    createError.value = isNodeNameConflict(e) ? t('home.nameConflict') : (e instanceof Error ? e.message : String(e))
  } finally {
    creating.value = false
  }
}

// --- node actions: rename / move / delete (current memory via toolbar, or any node via card hover) ---
const actionNode = ref<MemoryNode | null>(null)
const actionName = ref('')
const actionError = ref('')
const actionBusy = ref(false)
const renameOpen = ref(false)
const moveOpen = ref(false)
const deleteOpen = ref(false)
const moveTargets = ref<{ id: string; name: string }[]>([])
const moveDest = ref('root')

function nodeName(node: MemoryNode): string {
  if (props.memory?.node.id === node.id) return props.memory.name
  return entries.value.find((e) => e.node.id === node.id)?.name ?? ''
}

function openRename(node: MemoryNode) {
  actionNode.value = node
  actionName.value = nodeName(node)
  actionError.value = ''
  renameOpen.value = true
}

async function openMove(node: MemoryNode) {
  actionNode.value = node
  actionError.value = ''
  moveDest.value = 'root'
  moveTargets.value = []
  moveOpen.value = true
  try {
    moveTargets.value = await moveDestinations(session.apiBase, session.unlocked!, props.active, node)
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : String(e)
  }
}

function openDelete(node: MemoryNode) {
  actionNode.value = node
  actionError.value = ''
  deleteOpen.value = true
}

async function confirmRename() {
  const node = actionNode.value
  const name = actionName.value.trim()
  if (!node || !name) return
  actionBusy.value = true
  actionError.value = ''
  try {
    const updated = await renameNode(session.apiBase, session.unlocked!, props.active, node, name)
    renameOpen.value = false
    if (props.memory?.node.id === node.id) {
      const slug = decodeNode(props.active.contentKey, updated).slug
      void router.push(buildHomePath(props.active.entry, [...props.folders.map((f) => f.slug), slug]))
    } else {
      await load()
    }
  } catch (e) {
    actionError.value = isNodeNameConflict(e) ? t('home.nameConflict') : e instanceof Error ? e.message : String(e)
  } finally {
    actionBusy.value = false
  }
}

async function confirmMove() {
  const node = actionNode.value
  if (!node) return
  actionBusy.value = true
  actionError.value = ''
  try {
    await moveNode(session.apiBase, session.unlocked!, props.active, node, moveDest.value)
    moveOpen.value = false
    // Moved away from the current level → go to the space root if it was the open memory, else reload.
    if (props.memory?.node.id === node.id) void router.push(buildHomePath(props.active.entry, []))
    else await load()
  } catch (e) {
    actionError.value = isNodeNameConflict(e) ? t('home.nameConflict') : e instanceof Error ? e.message : String(e)
  } finally {
    actionBusy.value = false
  }
}

async function confirmDelete() {
  const node = actionNode.value
  if (!node) return
  actionBusy.value = true
  actionError.value = ''
  try {
    await deleteNode(session.apiBase, session.unlocked!, props.active, node.id)
    deleteOpen.value = false
    if (props.memory?.node.id === node.id) void router.push(buildHomePath(props.active.entry, props.folders.map((f) => f.slug)))
    else await load()
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : String(e)
  } finally {
    actionBusy.value = false
  }
}

watch(() => [props.active.scope.scopeId, parentId.value], load, { immediate: true })
watch(() => props.memory?.node.id, loadFavorite, { immediate: true })
onBeforeUnmount(releaseCovers)
</script>

<template>
  <div class="memory-browser">
    <nav class="memory-breadcrumb">
      <button type="button" class="crumb" @click="openCrumb(-1)">{{ props.active.entry.displayName }}</button>
      <template v-for="(folder, index) in props.folders" :key="folder.node.id">
        <span class="crumb-sep">/</span>
        <button type="button" class="crumb" @click="openCrumb(index)">{{ folder.name }}</button>
      </template>
      <template v-if="props.memory">
        <span class="crumb-sep">/</span>
        <span class="crumb crumb-current">{{ props.memory.name }}</span>
      </template>
    </nav>

    <div class="memory-toolbar">
      <Button type="button" :label="t('home.createFolder')" icon="pi pi-folder" severity="secondary" outlined @click="openCreate('FOLDER')" />
      <Button type="button" :label="props.memory ? t('detail.chapters') : t('home.createMemory')" icon="pi pi-plus" @click="openCreate(props.memory ? 'CHAPTER' : 'MEMORY')" />
      <template v-if="props.memory">
        <Button type="button" :icon="isFavorite ? 'pi pi-star-fill' : 'pi pi-star'" :label="isFavorite ? t('memory.unfavorite') : t('memory.favorite')" severity="secondary" text :loading="favBusy" @click="toggleFavorite" />
        <Button type="button" icon="pi pi-download" :label="t('detail.download')" severity="secondary" text :loading="downloading" @click="downloadMemory" />
        <Button type="button" icon="pi pi-pencil" :label="t('common.rename')" severity="secondary" text @click="openRename(props.memory.node)" />
        <Button type="button" icon="pi pi-arrows-alt" :label="t('common.move')" severity="secondary" text @click="openMove(props.memory.node)" />
        <Button type="button" icon="pi pi-trash" :label="t('common.delete')" severity="danger" text @click="openDelete(props.memory.node)" />
      </template>
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <!-- Inside a memory: gallery (encrypted media) / journal, plus any chapters. -->
    <template v-if="props.memory">
      <div class="memory-tabs">
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'gallery' }" @click="memoryTab = 'gallery'">{{ t('detail.gallery') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'journal' }" @click="memoryTab = 'journal'">{{ t('detail.journal') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'map' }" @click="memoryTab = 'map'">{{ t('detail.map') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'participants' }" @click="memoryTab = 'participants'">{{ t('people.participants') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'playlists' }" @click="memoryTab = 'playlists'">{{ t('detail.playlists') }}</button>
        <button type="button" class="memory-tab" :class="{ active: memoryTab === 'share' }" @click="memoryTab = 'share'">{{ t('share.share') }}</button>
      </div>
      <MemoryGallery v-if="memoryTab === 'gallery'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryJournal v-else-if="memoryTab === 'journal'" :active="props.active" :node-id="props.memory.node.id" @open-chapter="openChapterFromJournal" />
      <MemoryMap v-else-if="memoryTab === 'map'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryParticipants v-else-if="memoryTab === 'participants'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryPlaylists v-else-if="memoryTab === 'playlists'" :active="props.active" :node-id="props.memory.node.id" />
      <MemoryShare v-else :active="props.active" :node-id="props.memory.node.id" />
      <h3 v-if="memoryEntries.length" class="section-title">{{ t('info.chapters') }}</h3>
    </template>

    <div v-if="loading" class="veyq-loading"><ProgressSpinner class="small-spinner" /></div>

    <section v-if="folderEntries.length" class="node-grid">
      <div v-for="entry in folderEntries" :key="entry.node.id" class="node-cell">
        <div class="node-card node-folder" role="button" tabindex="0" @click="open(entry)" @keydown.enter="open(entry)">
          <span class="pi pi-folder node-folder-icon" />
          <span class="node-name">{{ entry.name }}</span>
        </div>
        <div class="node-actions">
          <button type="button" class="pi pi-pencil" :title="t('common.rename')" @click.stop="openRename(entry.node)" />
          <button type="button" class="pi pi-arrows-alt" :title="t('common.move')" @click.stop="openMove(entry.node)" />
          <button type="button" class="pi pi-trash" :title="t('common.delete')" @click.stop="openDelete(entry.node)" />
        </div>
      </div>
    </section>

    <section v-if="memoryEntries.length" class="node-grid">
      <div v-for="entry in memoryEntries" :key="entry.node.id" class="node-cell">
        <div class="node-card" role="button" tabindex="0" @click="open(entry)" @keydown.enter="open(entry)">
          <span class="node-cover">
            <img v-if="covers[entry.node.id]" :src="covers[entry.node.id]" :alt="entry.name" />
            <span v-else class="pi pi-images node-cover-empty" />
          </span>
          <span class="node-name">{{ entry.name }}</span>
        </div>
        <div class="node-actions">
          <button type="button" class="pi pi-pencil" :title="t('common.rename')" @click.stop="openRename(entry.node)" />
          <button type="button" class="pi pi-arrows-alt" :title="t('common.move')" @click.stop="openMove(entry.node)" />
          <button type="button" class="pi pi-trash" :title="t('common.delete')" @click.stop="openDelete(entry.node)" />
        </div>
      </div>
    </section>

    <p v-if="!loading && !entries.length && !props.memory" class="empty-hint">{{ t('home.noMemories') }}</p>

    <Dialog v-model:visible="createOpen" :header="createTitle" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <form class="veyq-form" @submit.prevent="confirmCreate">
        <label>
          {{ t('common.name') }}
          <InputText v-model="createName" autofocus :disabled="creating" />
        </label>
        <Message v-if="createError" severity="error" :closable="false">{{ createError }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="creating" @click="createOpen = false" />
          <Button type="submit" :label="t('common.create')" icon="pi pi-check" :loading="creating" :disabled="!createName.trim()" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="renameOpen" :header="t('common.rename')" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <form class="veyq-form" @submit.prevent="confirmRename">
        <label>{{ t('common.name') }}<InputText v-model="actionName" autofocus :disabled="actionBusy" /></label>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="actionBusy" @click="renameOpen = false" />
          <Button type="submit" :label="t('common.save')" icon="pi pi-check" :loading="actionBusy" :disabled="!actionName.trim()" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="moveOpen" :header="t('move.title')" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <form class="veyq-form" @submit.prevent="confirmMove">
        <label>
          {{ t('common.move') }}
          <select v-model="moveDest" class="move-select" :disabled="actionBusy">
            <option v-for="dest in moveTargets" :key="dest.id" :value="dest.id">{{ dest.name }}</option>
          </select>
        </label>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="actionBusy" @click="moveOpen = false" />
          <Button type="submit" :label="t('common.move')" icon="pi pi-arrows-alt" :loading="actionBusy" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="deleteOpen" :header="t('delete.title')" modal class="tenant-dialog" :style="{ width: '24rem' }">
      <p class="veyq-form-copy">{{ t('delete.warning') }}</p>
      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" severity="secondary" text :disabled="actionBusy" @click="deleteOpen = false" />
        <Button type="button" :label="t('delete.confirm')" icon="pi pi-trash" severity="danger" :loading="actionBusy" @click="confirmDelete" />
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.memory-browser { padding: 1rem; }
.memory-breadcrumb { display: flex; flex-wrap: wrap; align-items: center; gap: 0.35rem; margin-bottom: 0.75rem; }
.crumb { background: none; border: none; color: var(--p-primary-color, #6366f1); cursor: pointer; padding: 0; font: inherit; }
.crumb-current { color: inherit; cursor: default; font-weight: 600; }
.crumb-sep { opacity: 0.5; }
.memory-toolbar { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.section-title { margin: 1.25rem 0 0.5rem; }
.node-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 1rem; margin-bottom: 1rem; }
.node-card { display: flex; flex-direction: column; gap: 0.5rem; background: none; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.2)); border-radius: 12px; padding: 0.75rem; cursor: pointer; text-align: left; color: inherit; }
.node-card:hover { border-color: var(--p-primary-color, #6366f1); }
.node-cell { position: relative; }
.node-actions { position: absolute; top: 0.45rem; right: 0.45rem; display: flex; gap: 0.2rem; opacity: 0; transition: opacity 0.15s; }
.node-cell:hover .node-actions, .node-cell:focus-within .node-actions { opacity: 1; }
.node-actions button { width: 1.8rem; height: 1.8rem; border: none; border-radius: 6px; cursor: pointer; color: var(--p-text-color, inherit); background: var(--p-content-background, rgba(20,20,20,0.85)); box-shadow: 0 1px 3px rgba(0,0,0,0.3); }
.node-actions button:hover { color: var(--p-primary-color, #6366f1); }
.move-select { width: 100%; padding: 0.5rem; border-radius: 8px; border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.3)); background: var(--p-content-background, transparent); color: inherit; }
.node-folder { flex-direction: row; align-items: center; }
.node-folder-icon { font-size: 1.4rem; opacity: 0.7; }
.node-cover { display: block; aspect-ratio: 4 / 3; border-radius: 8px; overflow: hidden; background: var(--p-content-border-color, rgba(127,127,127,0.12)); display: grid; place-items: center; }
.node-cover img { width: 100%; height: 100%; object-fit: cover; }
.node-cover-empty { font-size: 1.6rem; opacity: 0.4; }
.node-name { font-weight: 600; }
.empty-hint { opacity: 0.6; }
.memory-tabs { display: flex; gap: 0.25rem; border-bottom: 1px solid var(--p-content-border-color, rgba(127,127,127,0.2)); margin-bottom: 1rem; }
.memory-tab { background: none; border: none; border-bottom: 2px solid transparent; padding: 0.5rem 0.85rem; cursor: pointer; color: inherit; opacity: 0.7; }
.memory-tab.active { opacity: 1; border-bottom-color: var(--p-primary-color, #6366f1); font-weight: 600; }
</style>
